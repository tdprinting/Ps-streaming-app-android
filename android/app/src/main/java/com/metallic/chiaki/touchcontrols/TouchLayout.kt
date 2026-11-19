// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import org.json.JSONObject

/**
 * TS5: where the user put each on-screen control, relative to its place in the default layout.
 * x and y are offsets as fractions of the screen's width and height, so a layout fits any screen.
 */
data class ControlPlacement(
	val x: Float = 0f,
	val y: Float = 0f,
	val scale: Float = 1f,
	val hidden: Boolean = false
)

class TouchLayout(context: Context)
{
	companion object
	{
		private const val PREFS = "touch_layout"
		private const val KEY_LAYOUT = "layout"
		private const val KEY_OPACITY = "opacity"
		const val MIN_SCALE = 0.5f
		const val MAX_SCALE = 2.2f
		val OPACITY_STEPS = floatArrayOf(1f, 0.75f, 0.5f, 0.3f)
	}

	private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	val placements: MutableMap<String, ControlPlacement> = load()

	var opacity: Float = prefs.getFloat(KEY_OPACITY, 1f)
		set(value)
		{
			field = value
			prefs.edit().putFloat(KEY_OPACITY, value).apply()
		}

	fun placement(key: String) = placements[key] ?: ControlPlacement()

	private fun load(): MutableMap<String, ControlPlacement>
	{
		val result = mutableMapOf<String, ControlPlacement>()
		val text = prefs.getString(KEY_LAYOUT, null) ?: return result
		try
		{
			val json = JSONObject(text)
			for(key in json.keys())
			{
				val o = json.getJSONObject(key)
				result[key] = ControlPlacement(
					o.optDouble("x", 0.0).toFloat(),
					o.optDouble("y", 0.0).toFloat(),
					o.optDouble("s", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
					o.optBoolean("h", false))
			}
		}
		catch(e: Exception)
		{
			// A broken layout falls back to the default one
		}
		return result
	}

	fun save()
	{
		val json = JSONObject()
		for((key, p) in placements)
		{
			if(p == ControlPlacement())
				continue
			json.put(key, JSONObject()
				.put("x", p.x.toDouble())
				.put("y", p.y.toDouble())
				.put("s", p.scale.toDouble())
				.put("h", p.hidden))
		}
		prefs.edit().putString(KEY_LAYOUT, json.toString()).apply()
	}

	fun reset()
	{
		placements.clear()
		opacity = 1f
		save()
	}
}
