// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables.combineLatest
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject

abstract class TouchControlsFragment : Fragment()
{
	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				ownControllerStateSubject.onNext(ownControllerState)
		}

	protected val ownControllerStateSubject: Subject<ControllerState>
			= BehaviorSubject.create<ControllerState>().also { it.onNext(ownControllerState) }

	// to delay attaching to the touchpadView until it's available
	protected val controllerStateProxy: Subject<Observable<ControllerState>>
			= BehaviorSubject.create<Observable<ControllerState>>().also { it.onNext(ownControllerStateSubject) }
	val controllerState: Observable<ControllerState> get() =
		controllerStateProxy.flatMap { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null

	/** A double tap beside the controls */
	var onBackgroundDoubleTap: (() -> Unit)? = null

	/** TS5: lets the user move, resize and hide the controls, until Done */
	open fun startEditing() {}
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			controllerStateProxy.onNext(
				combineLatest(ownControllerStateSubject, binding.touchpadView.controllerState) { a, b -> a or b }
			)
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		binding.controlsBackgroundView.onDoubleTap = { onBackgroundDoubleTap?.invoke() }
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			rightX = quantizeStick(it.x)
			rightY = quantizeStick(it.y)
		}}

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			view.visibility = if(it) View.VISIBLE else View.GONE
		})

		// TS5: the user's own layout, applied whenever the screen's size changes
		view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			if(right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
				applyLayout()
		}
	}

	// ── TS5 layout editor ────────────────────────────────────────────────────────────────────

	private val touchLayout by lazy { TouchLayout(requireContext()) }
	private var editor: LayoutEditorOverlay? = null
	private var editorBar: android.widget.LinearLayout? = null

	/** The controls that can be moved, by the key their placement is saved under */
	private fun editableControls(): List<Pair<String, View>> = listOf(
		"touchpad" to binding.touchpadView,
		"left_stick" to binding.leftAnalogStickView,
		"right_stick" to binding.rightAnalogStickView,
		"dpad" to binding.dpadView,
		"face_buttons" to binding.faceButtonsLayout,
		"l1" to binding.l1ButtonView,
		"l2" to binding.l2ButtonView,
		"r1" to binding.r1ButtonView,
		"r2" to binding.r2ButtonView,
		"l3" to binding.l3ButtonView,
		"r3" to binding.r3ButtonView,
		"share" to binding.shareButtonView,
		"options" to binding.optionsButtonView,
		"ps" to binding.psButtonView)

	private fun applyLayout()
	{
		val root = _binding?.root ?: return
		val w = root.width.toFloat()
		val h = root.height.toFloat()
		val editing = editor != null
		for((key, view) in editableControls())
		{
			val p = touchLayout.placement(key)
			view.translationX = p.x * w
			view.translationY = p.y * h
			view.scaleX = p.scale
			view.scaleY = p.scale
			view.visibility = if(p.hidden && !editing) View.INVISIBLE else View.VISIBLE
			view.alpha = if(p.hidden) 0.25f else touchLayout.opacity
		}
	}

	override fun startEditing()
	{
		if(editor != null)
			return
		val root = _binding?.root as? ViewGroup ?: return
		val context = requireContext()
		val overlay = LayoutEditorOverlay(context,
			controls = ::editableControls,
			onSelect = { updateBar() },
			onMove = { key, _, dx, dy ->
				val p = touchLayout.placement(key)
				touchLayout.placements[key] = p.copy(x = p.x + dx / root.width, y = p.y + dy / root.height)
				applyLayout()
			},
			onScale = { key, _, factor -> scaleSelected(key, factor) },
			onGestureEnd = { touchLayout.save() })
		root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		editor = overlay

		val bar = android.widget.LinearLayout(context).apply {
			orientation = android.widget.LinearLayout.HORIZONTAL
			setBackgroundResource(com.metallic.chiaki.R.drawable.stream_stats_background)
			val pad = (8 * resources.displayMetrics.density).toInt()
			setPadding(pad, pad / 2, pad, pad / 2)
		}
		fun button(text: Int, action: () -> Unit) = com.google.android.material.button.MaterialButton(context,
			null, com.google.android.material.R.attr.materialButtonOutlinedStyle).also {
			it.setText(text)
			it.setTextColor(0xFFFFFFFF.toInt())
			it.setOnClickListener { action() }
			bar.addView(it)
		}
		button(com.metallic.chiaki.R.string.touch_edit_hide) { toggleHidden() }
		button(com.metallic.chiaki.R.string.touch_edit_smaller) { overlay.selectedKey?.let { scaleSelected(it, 0.9f) } }
		button(com.metallic.chiaki.R.string.touch_edit_bigger) { overlay.selectedKey?.let { scaleSelected(it, 1.1f) } }
		button(com.metallic.chiaki.R.string.touch_edit_opacity) { cycleOpacity() }
		button(com.metallic.chiaki.R.string.touch_edit_reset) {
			touchLayout.reset()
			overlay.selectedKey = null
			applyLayout()
			updateBar()
			overlay.invalidate()
		}
		button(com.metallic.chiaki.R.string.touch_edit_done) { stopEditing() }
		root.addView(bar, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
			startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
			endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
			topMargin = (12 * resources.displayMetrics.density).toInt()
		})
		editorBar = bar
		view?.visibility = View.VISIBLE
		applyLayout()
		updateBar()
		android.widget.Toast.makeText(context, com.metallic.chiaki.R.string.touch_edit_hint, android.widget.Toast.LENGTH_LONG).show()
	}

	private fun stopEditing()
	{
		val root = _binding?.root as? ViewGroup ?: return
		editor?.let { root.removeView(it) }
		editorBar?.let { root.removeView(it) }
		editor = null
		editorBar = null
		touchLayout.save()
		applyLayout()
		onScreenControlsEnabled?.value?.let { view?.visibility = if(it) View.VISIBLE else View.GONE }
	}

	private fun scaleSelected(key: String, factor: Float)
	{
		val p = touchLayout.placement(key)
		touchLayout.placements[key] = p.copy(scale = (p.scale * factor).coerceIn(TouchLayout.MIN_SCALE, TouchLayout.MAX_SCALE))
		applyLayout()
		editor?.invalidate()
		touchLayout.save()
	}

	private fun toggleHidden()
	{
		val key = editor?.selectedKey ?: return
		val p = touchLayout.placement(key)
		touchLayout.placements[key] = p.copy(hidden = !p.hidden)
		touchLayout.save()
		applyLayout()
		updateBar()
	}

	private fun cycleOpacity()
	{
		val steps = TouchLayout.OPACITY_STEPS
		val i = steps.indexOfFirst { kotlin.math.abs(it - touchLayout.opacity) < 0.01f }
		touchLayout.opacity = steps[(i + 1) % steps.size]
		applyLayout()
		updateBar()
	}

	/** The Hide button says Show for a hidden control, the Opacity button shows the level */
	private fun updateBar()
	{
		val bar = editorBar ?: return
		val hide = bar.getChildAt(0) as? com.google.android.material.button.MaterialButton
		val key = editor?.selectedKey
		hide?.isEnabled = key != null
		hide?.setText(if(key != null && touchLayout.placement(key).hidden)
			com.metallic.chiaki.R.string.touch_edit_show else com.metallic.chiaki.R.string.touch_edit_hide)
		bar.getChildAt(1)?.isEnabled = key != null
		bar.getChildAt(2)?.isEnabled = key != null
		(bar.getChildAt(3) as? com.google.android.material.button.MaterialButton)?.text =
			getString(com.metallic.chiaki.R.string.touch_edit_opacity_value, (touchLayout.opacity * 100).toInt())
	}

	override fun onDestroyView()
	{
		editor = null
		editorBar = null
		super.onDestroyView()
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}