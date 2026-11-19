// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.metallic.chiaki.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * TS5 home screen background: a deep blue gradient with slow light waves and hundreds of small
 * glowing particles drifting through a flow field, in the spirit of a console's home screen.
 *
 * Each particle is a pre-rendered soft glow bitmap, so a frame is a few hundred bitmap draws that
 * the GPU handles easily at 120 Hz. It only animates while visible and attached, and holds still
 * when the system's animations are turned off.
 */
class ParticleFlowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
): View(context, attrs)
{
	private class Particle(
		var x: Float,
		var y: Float,
		/** 0 far .. 1 near: near particles are bigger, brighter and faster */
		val depth: Float,
		val size: Float,
		val twinklePhase: Float,
		val twinkleSpeed: Float
	)

	private val density = resources.displayMetrics.density
	private val random = Random(5)
	private var particles = emptyArray<Particle>()

	private val glow: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
		val c = Canvas(it)
		val p = Paint(Paint.ANTI_ALIAS_FLAG)
		p.shader = RadialGradient(32f, 32f, 32f,
			intArrayOf(0xFFFFFFFF.toInt(), 0xCCCFE3FF.toInt(), 0x335AA2FF, 0x00000000),
			floatArrayOf(0f, 0.18f, 0.5f, 1f), Shader.TileMode.CLAMP)
		c.drawCircle(32f, 32f, 32f, p)
	}
	private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val dst = RectF()

	private val backgroundPaint = Paint()
	private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val wavePath = Path()

	private val animationsEnabled = Settings.Global.getFloat(context.contentResolver,
		Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

	private var startNanos = System.nanoTime()
	private var lastNanos = startNanos
	private var running = false

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		backgroundPaint.shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(),
			intArrayOf(
				ContextCompat.getColor(context, R.color.home_gradient_top),
				ContextCompat.getColor(context, R.color.home_gradient_middle),
				ContextCompat.getColor(context, R.color.home_gradient_bottom)),
			floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
		// About one particle per 6000 dp², so the density looks the same on every screen
		val count = ((w / density) * (h / density) / 6000f).toInt().coerceIn(60, 260)
		particles = Array(count) {
			val depth = random.nextFloat()
			Particle(
				x = random.nextFloat() * w,
				y = random.nextFloat() * h,
				depth = depth,
				size = (1.2f + depth * 4.5f + random.nextFloat() * 1.5f) * density,
				twinklePhase = random.nextFloat() * 2f * PI.toFloat(),
				twinkleSpeed = 0.6f + random.nextFloat() * 1.4f)
		}
	}

	override fun onDraw(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		val now = System.nanoTime()
		val t = (now - startNanos) / 1e9f
		val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
		lastNanos = now

		canvas.drawRect(0f, 0f, w, h, backgroundPaint)
		drawWaves(canvas, w, h, if(animationsEnabled) t else 0f)

		for(p in particles)
		{
			if(animationsEnabled)
			{
				// A slowly changing flow field: particles drift up and to the right in swirls
				val angle = (sin(p.x * 0.0021f + t * 0.11f) + cos(p.y * 0.0027f - t * 0.07f)) * 0.9f - 0.6f
				val speed = (10f + p.depth * 34f) * density
				p.x += cos(angle) * speed * dt
				p.y += sin(angle) * speed * dt - 6f * density * dt
				val margin = p.size * 2f
				if(p.x > w + margin) p.x = -margin
				if(p.x < -margin) p.x = w + margin
				if(p.y < -margin) p.y = h + margin
				if(p.y > h + margin) p.y = -margin
			}
			val twinkle = 0.55f + 0.45f * sin(t * p.twinkleSpeed + p.twinklePhase)
			glowPaint.alpha = ((0.25f + p.depth * 0.6f) * twinkle * 255f).toInt().coerceIn(0, 255)
			val r = p.size * 2.2f
			dst.set(p.x - r, p.y - r, p.x + r, p.y + r)
			canvas.drawBitmap(glow, null, dst, glowPaint)
		}

		if(running && animationsEnabled)
			postInvalidateOnAnimation()
	}

	/** Three translucent bands of light that sway slowly across the lower half */
	private fun drawWaves(canvas: Canvas, w: Float, h: Float, t: Float)
	{
		val bands = arrayOf(
			Triple(0.62f, 0x1A5AA2FF, 0.21f),
			Triple(0.72f, 0x145AA2FF, 0.15f),
			Triple(0.80f, 0x108FC8FF, 0.27f))
		for((i, band) in bands.withIndex())
		{
			val (baseY, color, speed) = band
			wavePaint.color = color
			wavePath.reset()
			val thickness = h * (0.05f + i * 0.015f)
			val steps = 24
			for(s in 0..steps)
			{
				val x = w * s / steps
				val y = h * baseY + sin(x / w * 2f * PI.toFloat() * 1.2f + t * speed + i) * h * 0.05f
				if(s == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
			}
			for(s in steps downTo 0)
			{
				val x = w * s / steps
				val y = h * baseY + thickness + sin(x / w * 2f * PI.toFloat() * 1.2f + t * speed * 1.3f + i + 0.8f) * h * 0.05f
				wavePath.lineTo(x, y)
			}
			wavePath.close()
			canvas.drawPath(wavePath, wavePaint)
		}
	}

	private fun updateRunning()
	{
		val shouldRun = isAttachedToWindow && windowVisibility == VISIBLE && isShown
		if(shouldRun == running)
			return
		running = shouldRun
		if(running)
		{
			lastNanos = System.nanoTime()
			postInvalidateOnAnimation()
		}
	}

	override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
	override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
	override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateRunning() }
	override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateRunning() }
}
