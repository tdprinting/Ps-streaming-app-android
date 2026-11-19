// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
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
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * TS5 home screen background: ribbons of glowing particles that flow across the screen as waves
 * and twist through themselves, over a deep blue gradient, in the spirit of a console home screen.
 *
 * Each ribbon is a grid of particles: columns along the wave, rows across its width. The wave's
 * shape is two sines that drift at different speeds, so it slowly morphs instead of repeating.
 * The rows are spread around the centerline by a twist angle that turns along the ribbon and over
 * time, like a band of light seen in 3D. Where the band turns towards the viewer the particles
 * grow and brighten, where it turns away they shrink and fade, and where it is edge-on the rows
 * pinch together into a single bright line. Particles also slide slowly along the ribbon.
 *
 * A few loose particles float in the background for depth. Every particle is the same
 * pre-rendered soft glow bitmap, so a frame is around 1,700 bitmap draws of one bitmap, light for the GPU
 * at 120 Hz. It only animates while visible, and holds still when system animations are off.
 */
class ParticleFlowView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
): View(context, attrs)
{
	private class Ribbon(
		/** Center line, as a fraction of the height */
		val baseY: Float,
		/** Main wave height, as a fraction of the height */
		val amplitude: Float,
		/** Waves across the screen */
		val waves: Float,
		/** How fast the wave travels, radians per second */
		val speed: Float,
		/** Half the band's width, as a fraction of the height */
		val thickness: Float,
		/** Twists along the screen */
		val twists: Float,
		/** How fast the twist turns, radians per second */
		val twistSpeed: Float,
		val seed: Float,
		val columns: Int,
		val rows: Int,
		/** Fraction of the width the particles slide along the ribbon per second */
		val flow: Float,
		/** 0 to 1, for a faint ribbon in the background */
		val brightness: Float,
		/** Small fixed jitter per particle so the sheet doesn't look mechanical */
		val jitter: FloatArray
	)

	private class Mote(var x: Float, var y: Float, val depth: Float, val phase: Float)

	private val density = resources.displayMetrics.density
	private val random = Random(5)
	private var ribbons = emptyArray<Ribbon>()
	private var motes = emptyArray<Mote>()

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

	private val animationsEnabled = Settings.Global.getFloat(context.contentResolver,
		Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

	private val startNanos = System.nanoTime()
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

		// About one column per 9 dp of width, so the ribbons look as dense on every screen
		val columns = (w / density / 9f).toInt().coerceIn(60, 150)
		fun ribbon(baseY: Float, amplitude: Float, waves: Float, speed: Float, thickness: Float,
			twists: Float, twistSpeed: Float, seed: Float, rows: Int, flow: Float, brightness: Float) =
			Ribbon(baseY, amplitude, waves, speed, thickness, twists, twistSpeed, seed, columns, rows, flow,
				brightness, FloatArray(columns * rows) { random.nextFloat() - 0.5f })
		ribbons = arrayOf(
			// A faint, slow band near the top for depth
			ribbon(0.30f, 0.05f, 0.8f, 0.15f, 0.035f, 0.9f, 0.25f, 4.1f, 4, 0.006f, 0.45f),
			// A thinner band that crosses the main one, moving the other way and twisting faster
			ribbon(0.56f, 0.065f, 1.6f, -0.24f, 0.045f, 2.1f, -0.6f, 2.3f, 6, -0.014f, 0.8f),
			// The main wide band, low on the screen behind the tiles
			ribbon(0.66f, 0.085f, 1.05f, 0.32f, 0.075f, 1.35f, 0.42f, 0.0f, 10, 0.010f, 1f))

		val moteCount = ((w / density) * (h / density) / 9000f).toInt().coerceIn(30, 90)
		motes = Array(moteCount) {
			Mote(random.nextFloat() * w, random.nextFloat() * h, random.nextFloat(), random.nextFloat() * 6.28f)
		}
	}

	override fun onDraw(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		val now = System.nanoTime()
		val t = if(animationsEnabled) (now - startNanos) / 1e9f else 0f
		val dt = if(animationsEnabled) ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f) else 0f
		lastNanos = now

		canvas.drawRect(0f, 0f, w, h, backgroundPaint)
		drawMotes(canvas, w, h, t, dt)
		for(ribbon in ribbons)
			drawRibbon(canvas, ribbon, w, h, t)

		if(running && animationsEnabled)
			postInvalidateOnAnimation()
	}

	private fun drawRibbon(canvas: Canvas, r: Ribbon, w: Float, h: Float, t: Float)
	{
		val twoPi = 2f * PI.toFloat()
		// The whole ribbon sways up and down, very slowly
		val sway = sin(t * 0.07f + r.seed) * h * 0.025f
		val slide = t * r.flow
		var i = 0
		for(c in 0 until r.columns * 2)
		{
			// Two half-columns per column: odd rows sit between the even ones, so the ribbon reads
			// as a woven sheet instead of a ladder
			val odd = c and 1
			// Position along the ribbon, sliding over time and wrapping around
			var u = (c / 2).toFloat() / r.columns + odd * 0.5f / r.columns + slide
			u -= floor(u)
			val x = u * (w * 1.1f) - w * 0.05f
			val ux = u * twoPi

			// Two waves drifting at different speeds, so the shape keeps changing
			val centerY = h * r.baseY + sway +
					sin(ux * r.waves + t * r.speed + r.seed) * h * r.amplitude +
					sin(ux * r.waves * 2.3f - t * r.speed * 0.6f + r.seed * 1.7f) * h * r.amplitude * 0.35f

			// The twist turns along the ribbon and over time, and wanders a little on its own
			val twist = ux * r.twists + t * r.twistSpeed + sin(t * 0.13f + r.seed + ux) * 0.8f
			val spread = cos(twist)
			val facing = sin(twist)
			// The band breathes in width
			val halfWidth = h * r.thickness * (0.75f + 0.25f * sin(t * 0.2f + ux * 0.5f + r.seed))

			// Ribbons fade in and out at the screen's edges, where they wrap around
			val edgeFade = (min(u, 1f - u) * 8f).coerceIn(0f, 1f)

			for(row in odd until r.rows step 2)
			{
				val v = if(r.rows > 1) row.toFloat() / (r.rows - 1) * 2f - 1f else 0f
				val jitter = r.jitter[i++ % r.jitter.size]
				val y = centerY + v * halfWidth * spread + jitter * 2.5f * density
				val px = x + v * halfWidth * 0.25f * facing + jitter * 3f * density
				// How close this particle is: the side of the band turned towards the viewer
				val depth = 0.5f + 0.5f * v * facing
				val size = (1.1f + depth * 2.6f) * density
				val alpha = (0.18f + depth * 0.62f) * (0.75f + 0.25f * sin(t * 1.3f + jitter * 9f)) *
						edgeFade * r.brightness
				glowPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
				val radius = size * 2.2f
				dst.set(px - radius, y - radius, px + radius, y + radius)
				canvas.drawBitmap(glow, null, dst, glowPaint)
			}
		}
	}

	/** Loose particles drifting slowly up and to the right in the background */
	private fun drawMotes(canvas: Canvas, w: Float, h: Float, t: Float, dt: Float)
	{
		for(m in motes)
		{
			m.x += (4f + m.depth * 8f) * density * dt
			m.y -= (3f + m.depth * 6f) * density * dt
			if(m.x > w + 10f) m.x = -10f
			if(m.y < -10f) m.y = h + 10f
			val size = (0.8f + m.depth * 1.8f) * density
			glowPaint.alpha = ((0.12f + m.depth * 0.35f) * (0.6f + 0.4f * sin(t * 0.9f + m.phase)) * 255f)
				.toInt().coerceIn(0, 255)
			val radius = size * 2.2f
			dst.set(m.x - radius, m.y - radius, m.x + radius, m.y + radius)
			canvas.drawBitmap(glow, null, dst, glowPaint)
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
