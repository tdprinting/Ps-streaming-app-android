// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * TS5: covers the on-screen controls while their layout is edited. Drag a control to move it,
 * pinch to resize it. Every control gets a dashed outline, the selected one a solid one.
 */
@SuppressLint("ViewConstructor")
class LayoutEditorOverlay(
	context: Context,
	private val controls: () -> List<Pair<String, View>>,
	private val onSelect: (String?) -> Unit,
	private val onMove: (String, View, Float, Float) -> Unit,
	private val onScale: (String, View, Float) -> Unit,
	private val onGestureEnd: () -> Unit
): View(context)
{
	var selectedKey: String? = null
		set(value)
		{
			field = value
			invalidate()
		}

	private val density = resources.displayMetrics.density
	private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
		color = 0x99FFFFFF.toInt()
		pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
	}
	private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
		color = 0xFF7CB4FF.toInt()
	}
	private val shadePaint = Paint().apply { color = 0x66000000 }
	private val hitRect = Rect()
	private val drawRect = RectF()

	private var dragKey: String? = null
	private var lastX = 0f
	private var lastY = 0f

	private val scaleDetector = ScaleGestureDetector(context, object: ScaleGestureDetector.SimpleOnScaleGestureListener()
	{
		override fun onScale(detector: ScaleGestureDetector): Boolean
		{
			val key = selectedKey ?: return false
			val view = controls().firstOrNull { it.first == key }?.second ?: return false
			onScale(key, view, detector.scaleFactor)
			invalidate()
			return true
		}
	})

	private fun controlAt(x: Float, y: Float): Pair<String, View>?
	{
		// The last one is drawn on top, so look from the end
		for(control in controls().asReversed())
		{
			control.second.getHitRect(hitRect)
			if(hitRect.contains(x.toInt(), y.toInt()))
				return control
		}
		return null
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		scaleDetector.onTouchEvent(event)
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				val hit = controlAt(event.x, event.y)
				selectedKey = hit?.first
				onSelect(selectedKey)
				dragKey = hit?.first
				lastX = event.x
				lastY = event.y
			}
			MotionEvent.ACTION_POINTER_DOWN -> dragKey = null // two fingers: resize, don't move
			MotionEvent.ACTION_MOVE ->
			{
				val key = dragKey
				if(key != null && event.pointerCount == 1)
				{
					val view = controls().firstOrNull { it.first == key }?.second
					if(view != null)
						onMove(key, view, event.x - lastX, event.y - lastY)
					lastX = event.x
					lastY = event.y
					invalidate()
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				dragKey = null
				onGestureEnd()
			}
		}
		return true
	}

	override fun onDraw(canvas: Canvas)
	{
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
		for((key, view) in controls())
		{
			view.getHitRect(hitRect)
			drawRect.set(hitRect)
			canvas.drawRoundRect(drawRect, 10f * density, 10f * density,
				if(key == selectedKey) selectedPaint else outlinePaint)
		}
	}
}
