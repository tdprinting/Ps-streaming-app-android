// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.Process
import android.util.Log
import com.metallic.chiaki.lib.ControllerState
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hayai "direct input" (experimental, needs root): reads the built-in gamepad straight from the
 * kernel's /dev/input/eventN instead of through Android's input system.
 *
 * Android's path is kernel → InputReader → InputDispatcher → a socket to the app → the app's UI
 * thread, which may be busy drawing or running other callbacks. Here a root `cat` streams the raw
 * evdev events into a pipe, and a dedicated high-priority thread turns each complete report into a
 * [ControllerState] and sends it on at once. That skips two system services and the UI thread.
 *
 * The device is found with `getevent -p` (part of every Android build), which also gives each
 * axis's raw range. Buttons follow Android's Generic.kl layout, axes the SDL convention: right
 * stick on RX/RY when the device has them, otherwise on Z/RZ.
 *
 * Android still sees the same events, so [StreamInput] ignores the gamepad events of the device
 * named [Device.name] while this runs.
 */
class RootInput private constructor(
	val device: Device,
	private val swapFaceButtons: Boolean,
	private val stickDeadZone: Float,
	private val onState: (ControllerState) -> Unit
)
{
	data class AbsInfo(val min: Int, val max: Int)

	data class Device(val path: String, val name: String, val keys: Set<Int>, val abs: Map<Int, AbsInfo>)

	companion object
	{
		private const val TAG = "RootInput"

		// Event types and codes from linux/input-event-codes.h
		private const val EV_SYN = 0
		private const val EV_KEY = 1
		private const val EV_ABS = 3
		private const val SYN_REPORT = 0

		private const val BTN_SOUTH = 0x130 // A
		private const val BTN_EAST = 0x131 // B
		private const val BTN_NORTH = 0x133 // BTN_X: Android maps it to BUTTON_X
		private const val BTN_WEST = 0x134 // BTN_Y: Android maps it to BUTTON_Y
		private const val BTN_TL = 0x136
		private const val BTN_TR = 0x137
		private const val BTN_TL2 = 0x138
		private const val BTN_TR2 = 0x139
		private const val BTN_SELECT = 0x13a
		private const val BTN_START = 0x13b
		private const val BTN_MODE = 0x13c
		private const val BTN_THUMBL = 0x13d
		private const val BTN_THUMBR = 0x13e
		private const val BTN_DPAD_UP = 0x220
		private const val BTN_DPAD_DOWN = 0x221
		private const val BTN_DPAD_LEFT = 0x222
		private const val BTN_DPAD_RIGHT = 0x223

		private const val ABS_X = 0x00
		private const val ABS_Y = 0x01
		private const val ABS_Z = 0x02
		private const val ABS_RX = 0x03
		private const val ABS_RY = 0x04
		private const val ABS_RZ = 0x05
		private const val ABS_GAS = 0x09
		private const val ABS_BRAKE = 0x0a
		private const val ABS_HAT0X = 0x10
		private const val ABS_HAT0Y = 0x11

		/** struct input_event for 64-bit userspace: timeval (2 × 8 bytes), type, code (2 bytes each), value (4) */
		private const val EVENT_SIZE = 24

		private fun su(command: String): java.lang.Process =
			Runtime.getRuntime().exec(arrayOf("su", "-c", command))

		/**
		 * Lists the input devices through root and returns the first that looks like a gamepad
		 * (has the A button and a left stick). Null without root or a gamepad. Blocks: call it off
		 * the main thread.
		 */
		fun findGamepad(): Device?
		{
			val output = try
			{
				val process = su("getevent -p")
				val text = process.inputStream.bufferedReader().readText()
				process.waitFor()
				text
			}
			catch(e: Exception)
			{
				Log.i(TAG, "No root access for direct input: $e")
				return null
			}
			return parseGetevent(output).firstOrNull { BTN_SOUTH in it.keys && ABS_X in it.abs && ABS_Y in it.abs }
		}

		/** Parses the output of `getevent -p` */
		internal fun parseGetevent(text: String): List<Device>
		{
			val devices = mutableListOf<Device>()
			var path: String? = null
			var name = ""
			var keys = mutableSetOf<Int>()
			var abs = mutableMapOf<Int, AbsInfo>()
			var section = ""
			fun finish()
			{
				path?.let { devices.add(Device(it, name, keys, abs)) }
			}
			val absLine = Regex("""([0-9a-fA-F]{4})\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
			for(raw in text.lineSequence())
			{
				val line = raw.trim()
				when
				{
					line.startsWith("add device") ->
					{
						finish()
						path = line.substringAfter(": ").trim()
						name = ""
						keys = mutableSetOf()
						abs = mutableMapOf()
						section = ""
					}
					line.startsWith("name:") -> name = line.substringAfter("name:").trim().trim('"')
					line.startsWith("KEY (0001):") ->
					{
						section = "KEY"
						line.substringAfter(":").split(Regex("\\s+")).mapNotNullTo(keys) { it.toIntOrNull(16) }
					}
					line.startsWith("ABS (0003):") ->
					{
						section = "ABS"
						absLine.find(line)?.let { m -> abs[m.groupValues[1].toInt(16)] = AbsInfo(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
					}
					Regex("^[A-Z]+ \\([0-9a-fA-F]{4}\\):").containsMatchIn(line) -> section = ""
					section == "KEY" -> line.split(Regex("\\s+")).mapNotNullTo(keys) { it.toIntOrNull(16) }
					section == "ABS" ->
						absLine.find(line)?.let { m -> abs[m.groupValues[1].toInt(16)] = AbsInfo(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
				}
			}
			finish()
			return devices
		}

		/** Starts reading [device]. Null if `su` refuses. */
		fun start(device: Device, swapFaceButtons: Boolean, stickDeadZone: Float, onState: (ControllerState) -> Unit): RootInput?
		{
			val input = RootInput(device, swapFaceButtons, stickDeadZone, onState)
			return if(input.open()) input else null
		}
	}

	@Volatile private var process: java.lang.Process? = null
	private var thread: Thread? = null

	// Input delay: kernel timestamp of each report to the moment it is sent on, in microseconds
	@Volatile private var ageSumUs = 0L
	@Volatile private var ageCount = 0
	@Volatile private var ageMaxUs = 0L

	/** Average and maximum delay from the kernel to sending, in ms, since the last call */
	fun takeInputAge(): Pair<Float, Float>?
	{
		val count = ageCount
		if(count == 0)
			return null
		val result = Pair(ageSumUs / 1000f / count, ageMaxUs / 1000f)
		ageSumUs = 0
		ageCount = 0
		ageMaxUs = 0
		return result
	}

	// Which axes carry what, decided once from the device's capabilities
	private val rightStickOnRx = ABS_RX in device.abs && ABS_RY in device.abs
	private val rightXAxis = if(rightStickOnRx) ABS_RX else ABS_Z
	private val rightYAxis = if(rightStickOnRx) ABS_RY else ABS_RZ
	private val l2Axis = when
	{
		rightStickOnRx && ABS_Z in device.abs -> ABS_Z
		ABS_BRAKE in device.abs -> ABS_BRAKE
		else -> -1
	}
	private val r2Axis = when
	{
		rightStickOnRx && ABS_RZ in device.abs -> ABS_RZ
		ABS_GAS in device.abs -> ABS_GAS
		else -> -1
	}

	private val buttonMap: Map<Int, UInt> = mapOf(
		BTN_SOUTH to if(swapFaceButtons) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS,
		BTN_EAST to if(swapFaceButtons) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON,
		BTN_NORTH to if(swapFaceButtons) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX,
		BTN_WEST to if(swapFaceButtons) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID,
		BTN_TL to ControllerState.BUTTON_L1,
		BTN_TR to ControllerState.BUTTON_R1,
		BTN_SELECT to ControllerState.BUTTON_SHARE,
		BTN_START to ControllerState.BUTTON_OPTIONS,
		BTN_MODE to ControllerState.BUTTON_PS,
		BTN_THUMBL to ControllerState.BUTTON_L3,
		BTN_THUMBR to ControllerState.BUTTON_R3,
		BTN_DPAD_UP to ControllerState.BUTTON_DPAD_UP,
		BTN_DPAD_DOWN to ControllerState.BUTTON_DPAD_DOWN,
		BTN_DPAD_LEFT to ControllerState.BUTTON_DPAD_LEFT,
		BTN_DPAD_RIGHT to ControllerState.BUTTON_DPAD_RIGHT
	)

	// The device's current state, only touched by the reader thread
	private var buttons = 0U
	private var hatButtons = 0U
	private var digitalL2 = false
	private var digitalR2 = false
	private val axes = HashMap<Int, Int>()

	private fun open(): Boolean
	{
		val p = try
		{
			su("exec cat ${device.path}")
		}
		catch(e: IOException)
		{
			Log.e(TAG, "Couldn't start reading ${device.path}", e)
			return false
		}
		process = p
		thread = Thread({ readLoop(p) }, "Hayai direct input").also { it.start() }
		Log.i(TAG, "Direct input from ${device.path} \"${device.name}\", right stick on " +
				(if(rightStickOnRx) "RX/RY" else "Z/RZ"))
		return true
	}

	private fun readLoop(p: java.lang.Process)
	{
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
		val stream = DataInputStream(p.inputStream)
		val bytes = ByteArray(EVENT_SIZE)
		val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		try
		{
			while(true)
			{
				stream.readFully(bytes)
				val sec = buffer.getLong(0)
				val usec = buffer.getLong(8)
				val type = buffer.getShort(16).toInt() and 0xffff
				val code = buffer.getShort(18).toInt() and 0xffff
				val value = buffer.getInt(20)
				when(type)
				{
					EV_KEY -> key(code, value != 0)
					EV_ABS -> axes[code] = value
					EV_SYN -> if(code == SYN_REPORT)
					{
						onState(buildState())
						// evdev timestamps on a client's own fd are CLOCK_REALTIME by default
						val ageUs = System.currentTimeMillis() * 1000 - (sec * 1_000_000 + usec)
						if(ageUs in 0..1_000_000)
						{
							ageSumUs += ageUs
							ageCount++
							if(ageUs > ageMaxUs)
								ageMaxUs = ageUs
						}
					}
				}
			}
		}
		catch(e: IOException)
		{
			Log.i(TAG, "Direct input ended: $e")
		}
	}

	private fun key(code: Int, down: Boolean)
	{
		when(code)
		{
			BTN_TL2 -> digitalL2 = down
			BTN_TR2 -> digitalR2 = down
			else ->
			{
				val mask = buttonMap[code] ?: return
				buttons = if(down) buttons or mask else buttons and mask.inv()
			}
		}
	}

	private fun stickAxis(code: Int): Float
	{
		val info = device.abs[code] ?: return 0f
		val value = axes[code] ?: return 0f
		val center = (info.min + info.max) / 2f
		val half = (info.max - info.min) / 2f
		if(half <= 0f)
			return 0f
		return ((value - center) / half).coerceIn(-1f, 1f)
	}

	private fun triggerAxis(code: Int): UByte
	{
		if(code < 0)
			return 0U
		val info = device.abs[code] ?: return 0U
		val value = axes[code] ?: return 0U
		val range = info.max - info.min
		if(range <= 0)
			return 0U
		return ((value - info.min) * 255f / range).roundToInt().coerceIn(0, 255).toUByte()
	}

	/** Radial dead zone, rescaled so the stick still reaches full deflection */
	private fun stick(x: Float, y: Float): Pair<Short, Short>
	{
		val length = sqrt(x * x + y * y)
		if(length <= stickDeadZone || length == 0f)
			return Pair(0.toShort(), 0.toShort())
		val scale = ((length - stickDeadZone) / (1f - stickDeadZone)).coerceAtMost(1f) / length
		fun Float.toShortAxis() = (this * scale * Short.MAX_VALUE).roundToInt()
			.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
		return Pair(x.toShortAxis(), y.toShortAxis())
	}

	private fun buildState(): ControllerState
	{
		val hatX = axes[ABS_HAT0X] ?: 0
		val hatY = axes[ABS_HAT0Y] ?: 0
		hatButtons = (if(hatX > 0) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
				(if(hatX < 0) ControllerState.BUTTON_DPAD_LEFT else 0U) or
				(if(hatY > 0) ControllerState.BUTTON_DPAD_DOWN else 0U) or
				(if(hatY < 0) ControllerState.BUTTON_DPAD_UP else 0U)
		val (lx, ly) = stick(stickAxis(ABS_X), stickAxis(ABS_Y))
		val (rx, ry) = stick(stickAxis(rightXAxis), stickAxis(rightYAxis))
		val l2 = triggerAxis(l2Axis).let { if(digitalL2) UByte.MAX_VALUE else it }
		val r2 = triggerAxis(r2Axis).let { if(digitalR2) UByte.MAX_VALUE else it }
		return ControllerState(
			buttons = buttons or hatButtons,
			l2State = l2,
			r2State = r2,
			leftX = lx,
			leftY = ly,
			rightX = rx,
			rightY = ry
		)
	}

	fun stop()
	{
		process?.destroy()
		process = null
		thread?.interrupt()
		thread = null
	}
}
