// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265),
		/** TS5: 10-bit HEVC with HDR10 (BT.2020, PQ), PS5 only */
		CODEC_H265_HDR("h265_hdr", R.string.preferences_codec_title_h265_hdr, com.metallic.chiaki.lib.Codec.CODEC_H265_HDR)
	}

	/**
	 * Resolution, frame rate and bitrate together. Custom uses the separate settings for these.
	 */
	enum class Quality(val value: String, @StringRes val title: Int, @StringRes val summary: Int)
	{
		SMOOTH("smooth", R.string.quality_smooth, R.string.quality_smooth_summary),
		BALANCED("balanced", R.string.quality_balanced, R.string.quality_balanced_summary),
		SHARP("sharp", R.string.quality_sharp, R.string.quality_sharp_summary),
		/** TS5: the most a PS5 encodes, for a strong local network such as Wi-Fi 6E */
		MAX("max", R.string.quality_max, R.string.quality_max_summary),
		CUSTOM("custom", R.string.quality_custom, R.string.quality_custom_summary);

		companion object
		{
			fun fromValue(value: String?) = values().firstOrNull { it.value == value }
		}
	}

	companion object
	{
		val resolutionAll = Resolution.values()
		/** Little data and delay, for a weak network and away from home */
		private const val SMOOTH_BITRATE = 5000
		/** A PS4 Pro can't stream much more */
		private const val SHARP_BITRATE_PS4 = 15000
		private const val SHARP_BITRATE_PS5 = 25000
		/** TS5: 1080p60 at 40 Mbit/s, comfortably inside a 6 GHz Wi-Fi link 20 feet from the router */
		private const val MAX_BITRATE_PS5 = 40000
		val qualityAwayDefault = Quality.SMOOTH
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	internal val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources
	private val packageManager = context.packageManager
	private val homeActivity = ComponentName(context, "com.metallic.chiaki.main.HomeActivity")

	/**
	 * TV boxes and other devices without a touchscreen, played with a controller on a big screen
	 */
	val isTv = !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
			|| packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

	/**
	 * TS5: AYN's Android handhelds (Odin 2, Odin 2 Portal, Odin 2 Mini, Thor), which have a
	 * fan, a 1080p screen and, on the Portal, a 120 Hz OLED. Only used to pick defaults.
	 */
	val isAynHandheld = android.os.Build.MANUFACTURER.equals("AYN", ignoreCase = true)
			|| android.os.Build.BRAND.equals("AYN", ignoreCase = true)

	private val resolutionDefault = if(isTv || isAynHandheld) Resolution.RES_1080P else Resolution.RES_720P


	/** TS5: how the display's refresh rate is chosen while streaming */
	enum class RefreshMode(val value: String)
	{
		/** The highest rate the display has, e.g. 120 Hz: a new frame waits at most one short refresh */
		MAX("max"),
		/** The stream's own rate (60 Hz), every frame shown for the same time, as Momiji does */
		MATCH("match"),
		/** Leave it to the system */
		SYSTEM("system");

		companion object
		{
			fun fromValue(value: String?) = values().firstOrNull { it.value == value }
		}
	}

	/**
	 * TS5: Moonlight-style frame pacing presets over [refreshMode] and [skipStaleFrames].
	 * Lowest latency: fastest refresh, late frames skipped. Balanced: 60 Hz, late frames skipped.
	 * Smoothest: 60 Hz, every frame shown once in order, even late ones.
	 */
	enum class FramePacing(val value: String)
	{
		LOWEST_LATENCY("latency"), BALANCED("balanced"), SMOOTHEST("smooth"), CUSTOM("custom");
		companion object { fun fromValue(value: String?) = values().firstOrNull { it.value == value } }
	}

	val framePacingKey get() = "latency_frame_pacing"
	var framePacing: FramePacing
		get() = when
		{
			refreshMode == RefreshMode.MAX && skipStaleFrames -> FramePacing.LOWEST_LATENCY
			refreshMode == RefreshMode.MATCH && skipStaleFrames -> FramePacing.BALANCED
			refreshMode == RefreshMode.MATCH && !skipStaleFrames -> FramePacing.SMOOTHEST
			else -> FramePacing.CUSTOM
		}
		set(value)
		{
			when(value)
			{
				FramePacing.LOWEST_LATENCY -> { refreshMode = RefreshMode.MAX; skipStaleFrames = true }
				FramePacing.BALANCED -> { refreshMode = RefreshMode.MATCH; skipStaleFrames = true }
				FramePacing.SMOOTHEST -> { refreshMode = RefreshMode.MATCH; skipStaleFrames = false }
				FramePacing.CUSTOM -> {}
			}
		}

	val refreshModeKey get() = "latency_refresh_mode"
	var refreshMode: RefreshMode
		get() = RefreshMode.fromValue(sharedPreferences.getString(refreshModeKey, null)) ?: RefreshMode.MAX
		set(value) { sharedPreferences.edit().putString(refreshModeKey, value.value).apply() }

	/** TS5: View.requestUnbufferedDispatch, so stick and trigger moves don't wait for the next vsync */
	val unbufferedInputKey get() = "latency_unbuffered_input"
	var unbufferedInput
		get() = sharedPreferences.getBoolean(unbufferedInputKey, true)
		set(value) { sharedPreferences.edit().putBoolean(unbufferedInputKey, value).apply() }

	/** TS5: show only the newest decoded frame after a burst from the network */
	val skipStaleFramesKey get() = "latency_skip_stale_frames"
	var skipStaleFrames
		get() = sharedPreferences.getBoolean(skipStaleFramesKey, true)
		set(value) { sharedPreferences.edit().putBoolean(skipStaleFramesKey, value).apply() }

	/** TS5: Qualcomm's dedicated low latency decoder and top video clock */
	val snapdragonTuningKey get() = "latency_snapdragon_tuning"
	var snapdragonTuning
		get() = sharedPreferences.getBoolean(snapdragonTuningKey, true)
		set(value) { sharedPreferences.edit().putBoolean(snapdragonTuningKey, value).apply() }

	/** TS5: Snapdragon decoder hands frames on with a software fence (vendor.qti-ext-output-sw-fence-enable) */
	val snapdragonEarlyOutputKey get() = "latency_snapdragon_early_output"
	var snapdragonEarlyOutput
		get() = sharedPreferences.getBoolean(snapdragonEarlyOutputKey, true)
		set(value) { sharedPreferences.edit().putBoolean(snapdragonEarlyOutputKey, value).apply() }

	/** TS5: exclusive (MMAP) audio with the smallest device buffer that doesn't crackle. Off on TVs, whose HDMI outputs drop exclusive streams. */
	val lowLatencyAudioKey get() = "latency_low_latency_audio"
	var lowLatencyAudio
		get() = sharedPreferences.getBoolean(lowLatencyAudioKey, !isTv)
		set(value) { sharedPreferences.edit().putBoolean(lowLatencyAudioKey, value).apply() }

	/** TS5: keep the network, decoder and audio threads off the little CPU cores */
	val bigCoresKey get() = "latency_big_cores"
	var bigCores
		get() = sharedPreferences.getBoolean(bigCoresKey, true)
		set(value) { sharedPreferences.edit().putBoolean(bigCoresKey, value).apply() }

	/** TS5: controller-only mode, like PXPlay's virtual controller: no video or audio is streamed */
	val controllerOnlyKey get() = "stream_controller_only"
	var controllerOnly
		get() = sharedPreferences.getBoolean(controllerOnlyKey, false)
		set(value) { sharedPreferences.edit().putBoolean(controllerOnlyKey, value).apply() }

	/** TS5: repeat the controller state every 20 ms so Wi-Fi never dozes between inputs */
	val keepWifiAwakeKey get() = "latency_keep_wifi_awake"
	var keepWifiAwake
		get() = sharedPreferences.getBoolean(keepWifiAwakeKey, true)
		set(value) { sharedPreferences.edit().putBoolean(keepWifiAwakeKey, value).apply() }

	/** TS5: Moonlight-style warning when packets are lost or frames arrive late */
	val connectionWarningsKey get() = "stream_connection_warnings"
	var connectionWarnings
		get() = sharedPreferences.getBoolean(connectionWarningsKey, true)
		set(value) { sharedPreferences.edit().putBoolean(connectionWarningsKey, value).apply() }

	/** TS5, experimental, root: read the built-in gamepad from /dev/input directly, see RootInput */
	val directInputKey get() = "latency_direct_input"
	var directInput
		get() = sharedPreferences.getBoolean(directInputKey, false)
		set(value) { sharedPreferences.edit().putBoolean(directInputKey, value).apply() }

	/**
	 * TS5, experimental: draw each frame straight into the buffer the display is scanning out
	 * (front buffer rendering), skipping the wait for the next refresh and the compositor. Tears.
	 */
	val frontBufferKey get() = "latency_front_buffer"
	var frontBuffer
		get() = sharedPreferences.getBoolean(frontBufferKey, false)
		set(value) { sharedPreferences.edit().putBoolean(frontBufferKey, value).apply() }

	/**
	 * TS5: Android's sustained performance mode holds clocks below their peak so a fanless phone
	 * doesn't throttle. The Odin's fan keeps it cool, so there it is off by default for full clocks.
	 */
	val sustainedPerformanceKey get() = "latency_sustained_performance"
	var sustainedPerformance
		get() = sharedPreferences.getBoolean(sustainedPerformanceKey, !isAynHandheld)
		set(value) { sharedPreferences.edit().putBoolean(sustainedPerformanceKey, value).apply() }

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, !isTv)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }


	/**
	 * Whether Chiaki is offered as the home screen, which makes a TV box start into it
	 */
	val homeScreenKey get() = resources.getString(R.string.preferences_home_screen_key)
	var homeScreen
		get() = packageManager.getComponentEnabledSetting(homeActivity) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		set(value) = packageManager.setComponentEnabledSetting(homeActivity,
			if(value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
			PackageManager.DONT_KILL_APP)

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val dualSenseEnabledKey get() = resources.getString(R.string.preferences_dualsense_enabled_key)
	var dualSenseEnabled
		get() = sharedPreferences.getBoolean(dualSenseEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(dualSenseEnabledKey, value).apply() }

	val controllerHeadphonesKey get() = resources.getString(R.string.preferences_controller_headphones_key)
	var controllerHeadphones
		get() = sharedPreferences.getBoolean(controllerHeadphonesKey, false)
		set(value) { sharedPreferences.edit().putBoolean(controllerHeadphonesKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }


	val debandingEnabledKey get() = resources.getString(R.string.preferences_debanding_key)
	var debandingEnabled
		// Off by default, like the switch in the settings: the shader's extra rendering pass adds delay
		get() = sharedPreferences.getBoolean(debandingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(debandingEnabledKey, value).apply() }

	val upscalingEnabledKey get() = resources.getString(R.string.preferences_upscaling_key)
	var upscalingEnabled
		// Off by default, like the switch in the settings: drawing through shaders adds a little delay,
		// and it only helps on screens with more pixels than the stream
		get() = sharedPreferences.getBoolean(upscalingEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(upscalingEnabledKey, value).apply() }

	val touchscreenTouchpadEnabledKey get() = "preferences_touchscreen_touchpad_enabled"
	var touchscreenTouchpadEnabled
		get() = sharedPreferences.getBoolean(touchscreenTouchpadEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchscreenTouchpadEnabledKey, value).apply() }

	/** Whether leaving the app keeps the stream in a small window. TV launchers handle it poorly. */
	val pictureInPictureKey get() = "stream_picture_in_picture"
	var pictureInPicture
		get() = sharedPreferences.getBoolean(pictureInPictureKey, !isTv)
		set(value) { sharedPreferences.edit().putBoolean(pictureInPictureKey, value).apply() }

	/** Dual-screen handhelds: the touchpad and the PS, Create and Options buttons on the second screen */
	val secondScreenKey get() = "second_screen"
	var secondScreen: Boolean
		get() = sharedPreferences.getBoolean(secondScreenKey, true)
		set(value) { sharedPreferences.edit().putBoolean(secondScreenKey, value).apply() }

	/** Time spent streaming, for the one-time thank-you on the main screen */
	var playTimeMs: Long
		get() = sharedPreferences.getLong("play_time_ms", 0)
		set(value) { sharedPreferences.edit().putLong("play_time_ms", value).apply() }

	/** Whether the one-time thank-you was answered, either way */
	var supportPromptDone: Boolean
		get() = sharedPreferences.getBoolean("support_prompt_done", false)
		set(value) { sharedPreferences.edit().putBoolean("support_prompt_done", value).apply() }

	val streamStatsEnabledKey get() = "stream_stats_enabled"
	var streamStatsEnabled
		get() = sharedPreferences.getBoolean(streamStatsEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamStatsEnabledKey, value).apply() }

	// Last used Fit/Zoom/Stretch mode of the stream, stored as the TransformMode name
	val streamDisplayModeKey get() = "stream_display_mode"
	var streamDisplayMode: String?
		get() = sharedPreferences.getString(streamDisplayModeKey, null)
		set(value) { sharedPreferences.edit().putString(streamDisplayModeKey, value).apply() }

	val sharpnessIntensityKey get() = "preferences_sharpness_intensity"
	var sharpnessIntensity: Float
		get() = sharedPreferences.getInt(sharpnessIntensityKey, 0).toFloat() / 100f
		set(value) { sharedPreferences.edit().putInt(sharpnessIntensityKey, (value * 100f).toInt()).apply() }


	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfile(quality, true)

	val qualityKey get() = "stream_quality"
	var quality: Quality
		get() = Quality.fromValue(sharedPreferences.getString(qualityKey, null))
			// The details someone set before there were presets stay as they are
			?: if(listOf(resolutionKey, fpsKey, bitrateKey).any { sharedPreferences.contains(it) }) Quality.CUSTOM
				else if(isAynHandheld) Quality.MAX else Quality.BALANCED
		set(value) { sharedPreferences.edit().putString(qualityKey, value.value).apply() }

	/** For playing away from home, where the network is usually slower. Custom has the same values as at home. */
	val qualityAwayKey get() = "stream_quality_away"
	var qualityAway: Quality
		get() = Quality.fromValue(sharedPreferences.getString(qualityAwayKey, null)) ?: qualityAwayDefault
		set(value) { sharedPreferences.edit().putString(qualityAwayKey, value.value).apply() }

	private fun consoleNameKey(mac: MacAddress) = "console/${mac.value}/name"

	/** A name given to the console here, for example to tell two of them apart, instead of its own */
	fun consoleName(mac: MacAddress): String? = sharedPreferences.getString(consoleNameKey(mac), null)?.takeIf { it.isNotBlank() }

	fun setConsoleName(mac: MacAddress, name: String?) = sharedPreferences.edit().also {
		if(name.isNullOrBlank())
			it.remove(consoleNameKey(mac))
		else
			it.putString(consoleNameKey(mac), name.trim())
	}.apply()

	private fun consoleQualityKey(mac: MacAddress) = "console/${mac.value}/quality"

	/** The quality of a console, or null for the one in the settings */
	fun consoleQuality(mac: MacAddress) = Quality.fromValue(sharedPreferences.getString(consoleQualityKey(mac), null))

	fun setConsoleQuality(mac: MacAddress, quality: Quality?) = sharedPreferences.edit().also {
		if(quality == null)
			it.remove(consoleQualityKey(mac))
		else
			it.putString(consoleQualityKey(mac), quality.value)
	}.apply()

	/** @param away whether connecting from away from home, through PSN */
	fun videoProfile(mac: MacAddress?, ps5: Boolean, away: Boolean) =
		videoProfile(if(away) qualityAway else mac?.let { consoleQuality(it) } ?: quality, ps5)

	fun videoProfile(quality: Quality, ps5: Boolean): ConnectVideoProfile = when(quality)
	{
		Quality.SMOOTH -> ConnectVideoProfile.preset(Resolution.RES_720P.preset, FPS.FPS_60.preset, codec.codec)
			.copy(bitrate = SMOOTH_BITRATE)
		Quality.BALANCED -> ConnectVideoProfile.preset(resolutionDefault.preset, FPS.FPS_60.preset, codec.codec)
		Quality.SHARP -> ConnectVideoProfile.preset(Resolution.RES_1080P.preset, FPS.FPS_60.preset, codec.codec)
			.copy(bitrate = if(ps5) SHARP_BITRATE_PS5 else SHARP_BITRATE_PS4)
		Quality.MAX -> ConnectVideoProfile.preset(Resolution.RES_1080P.preset, FPS.FPS_60.preset, codec.codec)
			.copy(bitrate = if(ps5) MAX_BITRATE_PS5 else SHARP_BITRATE_PS4)
		Quality.CUSTOM -> videoProfileDefaultBitrate.let {
			val bitrate = bitrate
			if(bitrate == null)
				it
			else
				it.copy(bitrate = bitrate)
		}
	}
}