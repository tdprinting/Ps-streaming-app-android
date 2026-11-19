// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * TS5 "tearing mode": shows each decoded frame by drawing it straight into a front buffer, the
 * buffer the display is reading from at that moment.
 *
 * The normal path queues a frame to SurfaceFlinger, which latches it at the next vsync and has the
 * display show it at the vsync after (or later, if the GPU has to compose). Here the picture
 * changes on screen as soon as the GPU has drawn it, about 0.5 ms for a 1080p quad on an Adreno
 * 740, so the whole wait for the display pipeline goes away. The cost: the display may be halfway
 * through scanning out when the new frame lands, which shows as a tear line for one refresh.
 *
 * Built on androidx.graphics' GLFrontBufferedRenderer, made for stylus ink. The decoder writes into
 * a [SurfaceTexture]; each new frame triggers one draw into the front buffered layer, which is
 * never committed to a multi buffered layer.
 *
 * Only worth it where the device's graphics stack supports HardwareBuffer.USAGE_FRONT_BUFFER (see
 * [isSupported]); elsewhere the library falls back to a fenced single buffer with no gain.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class FrontBufferVideo(
	private val surfaceView: SurfaceView,
	videoWidth: Int,
	videoHeight: Int,
	onSurface: (Surface) -> Unit
)
{
	companion object
	{
		private const val TAG = "FrontBufferVideo"
		// Same value as AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY, public in Java only from API 34
		private const val USAGE_COMPOSER_OVERLAY = 1L shl 11

		/** Whether this device can scan out a buffer that is being drawn into */
		fun isSupported(): Boolean
		{
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
				return false
			return try
			{
				HardwareBuffer.isSupported(1, 1, HardwareBuffer.RGBA_8888, 1,
					HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
							USAGE_COMPOSER_OVERLAY or HardwareBuffer.USAGE_FRONT_BUFFER)
			}
			catch(e: Exception)
			{
				false
			}
		}

		private const val VERTEX_SHADER = """
			uniform mat4 uMvp;
			uniform mat4 uTex;
			attribute vec2 aPos;
			attribute vec2 aUv;
			varying vec2 vUv;
			void main() {
				gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
				vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy;
			}
		"""

		private const val FRAGMENT_SHADER = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			uniform samplerExternalOES uSampler;
			varying vec2 vUv;
			void main() {
				gl_FragColor = texture2D(uSampler, vUv);
			}
		"""
	}

	val frontBufferSupported = isSupported()

	/** Frames the decoder delivered, for the statistics */
	@Volatile var framesDrawn = 0L
		private set

	// Created detached, attached to the renderer's GL context on its first draw
	private val surfaceTexture = SurfaceTexture(false).also { it.setDefaultBufferSize(videoWidth, videoHeight) }
	private val surface = Surface(surfaceTexture)

	// The decoder's frame-ready callbacks, on a thread of their own so a busy UI thread can't delay them
	private val frameThread = HandlerThread("TS5 front buffer", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }

	private var texture = 0
	private var setupFailed = false
	private val pendingFrames = java.util.concurrent.atomic.AtomicInteger(0)
	private var program = 0
	private var posLocation = 0
	private var uvLocation = 0
	private var mvpLocation = 0
	private var texLocation = 0
	private val texMatrix = FloatArray(16)
	private val projection = FloatArray(16)
	private val mvp = FloatArray(16)
	private val positions: FloatBuffer = floatBuffer(8)
	private val uvs: FloatBuffer = floatBuffer(8).also {
		// Bottom left, bottom right, top left, top right: GL's texture convention
		it.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
	}

	private val callback = object: GLFrontBufferedRenderer.Callback<Unit>
	{
		override fun onDrawFrontBufferedLayer(eglManager: EGLManager, width: Int, height: Int,
			bufferInfo: BufferInfo, transform: FloatArray, param: Unit)
		{
			if(program == 0)
			{
				// One attempt: a failed one would otherwise repeat every frame, leaking a texture each time
				if(setupFailed || !setUpGl())
				{
					setupFailed = true
					return
				}
			}
			// Every frame queued since the last draw, so the decoder never runs out of buffers
			val pending = pendingFrames.getAndSet(0)
			if(pending == 0)
				return // the front buffer still shows the last frame
			try
			{
				repeat(pending) { surfaceTexture.updateTexImage() }
			}
			catch(e: RuntimeException)
			{
				Log.e(TAG, "updateTexImage failed", e)
				return
			}
			surfaceTexture.getTransformMatrix(texMatrix)

			GLES20.glViewport(0, 0, bufferInfo.width, bufferInfo.height)
			// Content in the SurfaceView's own coordinates, then pre-rotated into the buffer's
			Matrix.orthoM(projection, 0, 0f, bufferInfo.width.toFloat(), 0f, bufferInfo.height.toFloat(), -1f, 1f)
			Matrix.multiplyMM(mvp, 0, projection, 0, transform, 0)

			val w = width.toFloat()
			val h = height.toFloat()
			positions.put(floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)).position(0)

			GLES20.glUseProgram(program)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
			GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
			GLES20.glUniformMatrix4fv(texLocation, 1, false, texMatrix, 0)
			GLES20.glEnableVertexAttribArray(posLocation)
			GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0, positions)
			GLES20.glEnableVertexAttribArray(uvLocation)
			GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uvs)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
			framesDrawn++
		}

		override fun onDrawMultiBufferedLayer(eglManager: EGLManager, width: Int, height: Int,
			bufferInfo: BufferInfo, transform: FloatArray, params: Collection<Unit>)
		{
			// Never committed: everything goes to the front buffered layer
		}
	}

	private val renderer = GLFrontBufferedRenderer(surfaceView, callback)

	init
	{
		surfaceTexture.setOnFrameAvailableListener({
			pendingFrames.incrementAndGet()
			renderer.renderFrontBufferedLayer(Unit)
		},
			Handler(frameThread.looper))
		onSurface(surface)
	}

	/** On the renderer's GL thread, with its context current */
	private fun setUpGl(): Boolean
	{
		val textures = IntArray(1)
		GLES20.glGenTextures(1, textures, 0)
		texture = textures[0]
		GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
		GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
		try
		{
			surfaceTexture.attachToGLContext(texture)
		}
		catch(e: RuntimeException)
		{
			Log.e(TAG, "attachToGLContext failed", e)
			return false
		}

		val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
		val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false
		val p = GLES20.glCreateProgram()
		GLES20.glAttachShader(p, vertex)
		GLES20.glAttachShader(p, fragment)
		GLES20.glLinkProgram(p)
		val linked = IntArray(1)
		GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
		if(linked[0] == 0)
		{
			Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(p))
			GLES20.glDeleteProgram(p)
			return false
		}
		posLocation = GLES20.glGetAttribLocation(p, "aPos")
		uvLocation = GLES20.glGetAttribLocation(p, "aUv")
		mvpLocation = GLES20.glGetUniformLocation(p, "uMvp")
		texLocation = GLES20.glGetUniformLocation(p, "uTex")
		program = p
		Log.i(TAG, "Front buffer video ready, front buffer usage supported: $frontBufferSupported")
		return true
	}

	private fun compile(type: Int, source: String): Int?
	{
		val shader = GLES20.glCreateShader(type)
		GLES20.glShaderSource(shader, source.trimIndent())
		GLES20.glCompileShader(shader)
		val compiled = IntArray(1)
		GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
		if(compiled[0] == 0)
		{
			Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
			GLES20.glDeleteShader(shader)
			return null
		}
		return shader
	}

	fun release()
	{
		surfaceTexture.setOnFrameAvailableListener(null)
		renderer.release(true) {
			surfaceTexture.release()
			surface.release()
		}
		frameThread.quitSafely()
	}
}

private fun floatBuffer(count: Int): FloatBuffer =
	ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
