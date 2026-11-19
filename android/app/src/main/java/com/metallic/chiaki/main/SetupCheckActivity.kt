// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.fitSystemBars
import com.metallic.chiaki.databinding.ActivitySetupCheckBinding
import com.metallic.chiaki.databinding.ItemSetupCheckBinding
import java.io.File

/**
 * TS5 setup check: everything on this device and its network that decides how much delay the
 * stream has, each with a plain verdict and what to do about it, plus one tap to apply the best
 * stream settings.
 */
class SetupCheckActivity: AppCompatActivity()
{
	enum class Status { GOOD, WARN, BAD, INFO }

	data class Check(val status: Status, val title: String, val detail: String)

	private lateinit var binding: ActivitySetupCheckBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivitySetupCheckBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.rootLayout.fitSystemBars()
		binding.backButton.setOnClickListener { finish() }
		binding.recheckButton.setOnClickListener { runChecks() }
		binding.applyButton.setOnClickListener {
			applyBestSettings(Preferences(this))
			Toast.makeText(this, R.string.setup_check_applied, Toast.LENGTH_LONG).show()
			runChecks()
		}
		runChecks()
		binding.applyButton.requestFocus()
	}

	override fun onResume()
	{
		super.onResume()
		runChecks()
	}

	private fun runChecks()
	{
		val checks = listOfNotNull(
			networkCheck(),
			wifiBandCheck(),
			wifiSignalCheck(),
			wifiLowLatencyCheck(),
			refreshCheck(),
			hdrCheck(),
			audioCheck(),
			batteryCheck(),
			thermalCheck(),
			settingsCheck(),
			rootCheck()
		)
		val layout = binding.checksLayout
		layout.removeAllViews()
		val inflater = LayoutInflater.from(this)
		for(check in checks)
		{
			val item = ItemSetupCheckBinding.inflate(inflater, layout, false)
			item.titleTextView.text = check.title
			item.detailTextView.text = check.detail
			item.statusTextView.text = when(check.status)
			{
				Status.GOOD -> "✓"
				Status.WARN -> "!"
				Status.BAD -> "✕"
				Status.INFO -> "i"
			}
			item.statusTextView.background.mutate().setTint(ContextCompat.getColor(this, when(check.status)
			{
				Status.GOOD -> R.color.state_ready
				Status.WARN -> R.color.state_standby
				Status.BAD -> R.color.md_error
				Status.INFO -> R.color.state_unknown
			}))
			layout.addView(item.root)
		}
		val bad = checks.count { it.status == Status.BAD }
		val warn = checks.count { it.status == Status.WARN }
		binding.summaryTextView.text = when
		{
			bad > 0 -> getString(R.string.setup_check_summary_bad, bad)
			warn > 0 -> getString(R.string.setup_check_summary_warn, warn)
			else -> getString(R.string.setup_check_summary_good)
		}
	}

	// ── Network ──────────────────────────────────────────────────────────────────────────────

	private val connectivity get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

	@Suppress("DEPRECATION")
	private val wifiInfo get() = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo

	private fun transport(): Int?
	{
		val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
		return listOf(NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI,
			NetworkCapabilities.TRANSPORT_CELLULAR).firstOrNull { caps.hasTransport(it) }
	}

	private fun networkCheck(): Check = when(transport())
	{
		NetworkCapabilities.TRANSPORT_ETHERNET -> Check(Status.GOOD, "Wired network",
			"A USB Ethernet adapter: the steadiest, lowest delay connection there is.")
		NetworkCapabilities.TRANSPORT_WIFI -> Check(Status.GOOD, "Wi-Fi connected",
			"For the PS5 itself, an Ethernet cable to the router is best: then only one hop is wireless.")
		NetworkCapabilities.TRANSPORT_CELLULAR -> Check(Status.WARN, "Mobile data",
			"Works for play away from home, but expect much more delay than at home on Wi-Fi.")
		else -> Check(Status.BAD, "No network", "Connect to the same network as your console.")
	}

	private fun onWifi() = transport() == NetworkCapabilities.TRANSPORT_WIFI

	private fun wifiBandCheck(): Check?
	{
		if(!onWifi())
			return null
		val info = wifiInfo ?: return null
		val mhz = info.frequency
		val standard = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) when(info.wifiStandard)
		{
			ScanResult.WIFI_STANDARD_11BE -> " · Wi-Fi 7"
			ScanResult.WIFI_STANDARD_11AX -> if(mhz >= 5925) " · Wi-Fi 6E" else " · Wi-Fi 6"
			ScanResult.WIFI_STANDARD_11AC -> " · Wi-Fi 5"
			ScanResult.WIFI_STANDARD_11N -> " · Wi-Fi 4"
			else -> ""
		} else ""
		return when
		{
			mhz >= 5925 -> Check(Status.GOOD, "6 GHz$standard",
				"The cleanest band: wide channels and almost no interference from neighbors.")
			mhz >= 5000 -> Check(Status.WARN, "5 GHz$standard",
				"Good. 6 GHz is better if your router has it (an eero 6E does): forget the network and reconnect close to the router.")
			mhz > 0 -> Check(Status.BAD, "2.4 GHz$standard",
				"Slow and crowded, and the main cause of stutter and delay. Connect on 5 or 6 GHz.")
			else -> null
		}
	}

	private fun wifiSignalCheck(): Check?
	{
		if(!onWifi())
			return null
		val info = wifiInfo ?: return null
		val rssi = info.rssi
		val rx = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps else info.linkSpeed
		val tx = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else info.linkSpeed
		val speeds = "Signal $rssi dBm · link ${rx.coerceAtLeast(0)} Mbit/s down, ${tx.coerceAtLeast(0)} Mbit/s up"
		return when
		{
			rssi >= -60 && rx >= 300 -> Check(Status.GOOD, "Strong Wi-Fi link", speeds)
			rssi >= -70 && rx >= 100 -> Check(Status.WARN, "Fair Wi-Fi link",
				"$speeds. Moving closer to the router or removing walls in between helps most.")
			else -> Check(Status.BAD, "Weak Wi-Fi link", "$speeds. Expect lost packets and stutter: move closer to the router.")
		}
	}

	private fun wifiLowLatencyCheck(): Check?
	{
		if(!onWifi() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
			return null
		val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
		return if(wifi.isLowLatencyModeSupported)
			Check(Status.GOOD, "Wi-Fi low latency mode", "Supported: power saving on Wi-Fi is turned off while you stream.")
		else
			Check(Status.INFO, "Wi-Fi low latency mode",
				"Not supported by this device, so TS5 uses the high performance Wi-Fi mode and keeps Wi-Fi awake itself.")
	}

	// ── Display ──────────────────────────────────────────────────────────────────────────────

	@Suppress("DEPRECATION")
	private fun currentDisplay(): Display? =
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

	private fun refreshCheck(): Check?
	{
		val display = currentDisplay() ?: return null
		val max = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
		val mode = Preferences(this).refreshMode
		return when
		{
			max >= 110f && mode == Preferences.RefreshMode.MAX -> Check(Status.GOOD, "${max.toInt()} Hz display",
				"TS5 runs the screen at ${max.toInt()} Hz while streaming, so a frame waits at most ${"%.1f".format(1000f / max)} ms for the screen.")
			max >= 110f -> Check(Status.WARN, "${max.toInt()} Hz display, not used",
				"Settings → Latency → Display refresh rate is set to ${mode.value}. Fastest saves about 4 ms per frame.")
			else -> Check(Status.INFO, "${max.toInt()} Hz display", "This screen tops out at ${max.toInt()} Hz.")
		}
	}

	private fun hdrCheck(): Check?
	{
		val display = currentDisplay() ?: return null
		val hdr10 = display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
		val usingHdr = Preferences(this).codec == Preferences.Codec.CODEC_H265_HDR
		return when
		{
			hdr10 && usingHdr -> Check(Status.GOOD, "HDR10 on", "The display reports HDR10 and the stream asks the PS5 for HDR.")
			hdr10 -> Check(Status.INFO, "HDR10 available",
				"For the best picture in slower games, choose Codec → H265 HDR. It costs a few ms of decoding.")
			usingHdr -> Check(Status.WARN, "HDR selected, display doesn't report HDR10",
				"Android will tone map the HDR picture to SDR. Plain H265 decodes a little faster.")
			else -> Check(Status.INFO, "No HDR10", "The display doesn't report HDR10 to Android.")
		}
	}

	// ── Audio, power ─────────────────────────────────────────────────────────────────────────

	private fun audioCheck(): Check?
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
			return null
		val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
		val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
		val bluetooth = outputs.any {
			it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
				(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
		}
		return if(bluetooth)
			Check(Status.WARN, "Bluetooth audio",
				"Bluetooth headphones usually add 100–200 ms of sound delay. Use the speakers, the headphone jack or USB-C audio.")
		else
			Check(Status.GOOD, "Audio output", "Speakers or wired audio: low latency audio can bypass Android's mixer.")
	}

	private fun batteryCheck(): Check
	{
		val power = getSystemService(Context.POWER_SERVICE) as PowerManager
		return if(power.isPowerSaveMode)
			Check(Status.BAD, "Battery saver is on", "It lowers CPU and GPU clocks and adds delay. Turn it off while you play.")
		else
			Check(Status.GOOD, "Battery saver off", "Clocks can run at full speed.")
	}

	private fun thermalCheck(): Check?
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
			return null
		val status = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
		return when
		{
			status >= PowerManager.THERMAL_STATUS_SEVERE -> Check(Status.BAD, "Device is hot",
				"It's throttling. On the Odin, set the fan to Sport and let it cool down.")
			status >= PowerManager.THERMAL_STATUS_MODERATE -> Check(Status.WARN, "Device is warm",
				"Clocks may be held back. A faster fan setting helps.")
			else -> Check(Status.GOOD, "Temperature normal", "No throttling.")
		}
	}

	// ── TS5 settings ─────────────────────────────────────────────────────────────────────────

	private fun settingsCheck(): Check
	{
		val p = Preferences(this)
		val off = mutableListOf<String>()
		if(!p.unbufferedInput) off.add("instant controller input")
		if(!p.inputRedundancy) off.add("redundant input")
		if(!p.skipStaleFrames) off.add("newest frame")
		if(!p.snapdragonTuning) off.add("Snapdragon decoder tuning")
		if(!p.lowLatencyAudio && !p.isTv) off.add("low latency audio")
		if(!p.bigCores) off.add("fast cores")
		if(!p.keepWifiAwake) off.add("keep Wi-Fi awake")
		if(p.controllerOnly) off.add("controller only is on, so no picture is streamed")
		val quality = if(p.quality == Preferences.Quality.MAX) "" else " Quality is ${p.quality.value}; Max gives the sharpest picture."
		return when
		{
			off.isEmpty() && quality.isEmpty() -> Check(Status.GOOD, "TS5 settings", "Every latency option is on, at Max quality.")
			off.isEmpty() -> Check(Status.INFO, "TS5 settings", "Every latency option is on.$quality")
			else -> Check(Status.WARN, "TS5 settings", "Off: ${off.joinToString()}.$quality Apply best settings turns them on.")
		}
	}

	private fun rootCheck(): Check
	{
		val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/magisk")
			.any { File(it).exists() }
		val direct = Preferences(this).directInput
		return when
		{
			su && direct -> Check(Status.GOOD, "Direct controller input", "Root found and direct input is on.")
			su -> Check(Status.INFO, "Root found",
				"Settings → Latency → Direct controller input reads the controller without Android's input system (experimental).")
			else -> Check(Status.INFO, "No root", "Not needed. Only the experimental direct controller input uses it.")
		}
	}

	private fun applyBestSettings(p: Preferences)
	{
		p.quality = Preferences.Quality.MAX
		if(p.codec != Preferences.Codec.CODEC_H265_HDR)
			p.codec = Preferences.Codec.CODEC_H265
		p.framePacing = Preferences.FramePacing.LOWEST_LATENCY
		p.unbufferedInput = true
		p.inputRedundancy = true
		p.snapdragonTuning = true
		p.snapdragonEarlyOutput = true
		p.lowLatencyAudio = !p.isTv
		p.bigCores = true
		p.keepWifiAwake = true
		p.connectionWarnings = true
		p.controllerOnly = false
		if(p.isAynHandheld)
			p.sustainedPerformance = false
	}
}
