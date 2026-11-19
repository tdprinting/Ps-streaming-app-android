// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

// The audio comes at the console's pace but plays at the device's, which can be a little slower,
// e.g. over HDMI. Then the buffer slowly fills up during a session, and the sound falls behind the
// picture, up to the buffer's whole size (about 170 ms), where the newest audio gets dropped.
// What is still queued when the buffer is at its lowest over CATCH_UP_WINDOW_MS is never needed:
// once that is more than CATCH_UP_KEEP_MS + CATCH_UP_SLACK_MS, it's skipped down to CATCH_UP_KEEP_MS.
#define CATCH_UP_WINDOW_MS 4000
#define CATCH_UP_KEEP_MS 20
#define CATCH_UP_SLACK_MS 20
// TS5 low latency audio: a shorter window and less kept back, so sound that piles up behind
// (Wi-Fi bursts) is trimmed faster and the steady state sits closer to the picture
#define LL_CATCH_UP_WINDOW_MS 2000
#define LL_CATCH_UP_KEEP_MS 10
#define LL_CATCH_UP_SLACK_MS 10
// TS5 low latency audio: the device buffer starts at two bursts ("double buffering", Android's
// recommendation) and grows by a burst after each underrun, up to this many
#define LL_MAX_BURSTS 6

// TS5: see android_chiaki_audio_output_set_low_latency()
static std::atomic<bool> low_latency_audio{true};

extern "C" void android_chiaki_audio_output_set_low_latency(bool enabled)
{
	low_latency_audio.store(enabled, std::memory_order_relaxed);
}
using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;
	uint32_t channels = 0;
	uint32_t rate = 0;
	// AAudio streams get disconnected over and over on some devices (e.g. Rockchip TV boxes
	// that play on HDMI and the speaker at once), OpenSL ES goes through AudioTrack, which copes
	bool use_opensl = false;
	// Picked by the app, e.g. HDMI instead of a controller's headphone jack, see android_chiaki_audio_output_set_device()
	int32_t device_id = oboe::kUnspecified;
	std::mutex stream_mutex;
	// Oboe's error thread might still use a replaced stream, so they are only deleted with the AudioOutput
	std::vector<oboe::ManagedStream> old_streams;

	// Bytes in buf. Signed, as the stream's callback can count a pop before the frame's push is counted.
	std::atomic<int64_t> queued{0};
	// The lowest the buffer got in the current window, and how long the window is so far.
	// Only the stream's callback uses these.
	int64_t window_lowest = INT64_MAX;
	int64_t window_frames = 0;
	// The lowest over the last window, for the statistics
	std::atomic<int32_t> lowest_ms{0};
	// Skipped by the callback, logged from the decoder's thread, as the callback shouldn't write files
	std::atomic<int32_t> skipped_ms{0};
	std::atomic<int32_t> skipped_from_ms{0};
	// TS5: whether this stream runs the low latency setup, decided when it is opened
	bool low_latency = false;
	// TS5: underruns seen so far, and frames played since they were last checked.
	// Only the stream's callback uses these.
	int32_t xruns_seen = 0;
	int64_t frames_since_xrun_check = 0;
	// TS5: for the statistics, written when the stream opens or the buffer grows
	std::atomic<int32_t> device_buffer_frames{0};
	std::atomic<int32_t> device_rate{0};
	std::atomic<bool> exclusive{false};

	AudioOutput() : stream_callback(this) {}
};

// ao->stream_mutex must be locked
static void open_stream(AudioOutput *ao)
{
	if(ao->stream)
	{
		ao->stream->close();
		ao->old_streams.push_back(std::move(ao->stream));
	}

	oboe::AudioStreamBuilder builder;
	// Shared: exclusive streams are refused or dropped by some HDMI outputs, e.g. on TV boxes.
	// TS5: exclusive in low latency mode, so the sound skips Android's mixer and goes straight
	// into the buffer the DSP reads (AAudio MMAP), which saves several ms on the built-in speakers
	// and wired headphones. Android opens a shared stream instead when it can't give exclusive.
	ao->low_latency = low_latency_audio.load(std::memory_order_relaxed) && !ao->use_opensl;
	ao->xruns_seen = 0;
	ao->frames_since_xrun_check = 0;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(ao->low_latency ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
		->setUsage(oboe::Usage::Game)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(ao->channels)
		->setSampleRate(ao->rate)
		->setCallback(&ao->stream_callback);
	if(ao->device_id != oboe::kUnspecified)
		builder.setDeviceId(ao->device_id); // OpenSL ES can't pick the device
	else if(ao->use_opensl)
		builder.setAudioApi(oboe::AudioApi::OpenSLES);

	auto result = builder.openManagedStream(ao->stream);
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream on device %d", (int)ao->stream->getDeviceId());
	else
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));
		return;
	}

	if(ao->low_latency)
	{
		int32_t burst = ao->stream->getFramesPerBurst();
		if(burst > 0)
			ao->stream->setBufferSizeInFrames(burst * 2);
	}
	ao->device_buffer_frames.store(ao->stream->getBufferSizeInFrames(), std::memory_order_relaxed);
	ao->device_rate.store(ao->stream->getSampleRate(), std::memory_order_relaxed);
	ao->exclusive.store(ao->stream->getSharingMode() == oboe::SharingMode::Exclusive, std::memory_order_relaxed);
	CHIAKI_LOGI(ao->log, "Audio Output: %s, %d Hz, burst %d frames, buffer %d frames, API %s",
			ao->stream->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
			(int)ao->stream->getSampleRate(), (int)ao->stream->getFramesPerBurst(),
			(int)ao->stream->getBufferSizeInFrames(),
			ao->stream->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES");

	result = ao->stream->start();
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	ao->old_streams.clear();
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	ao->channels = channels;
	ao->rate = rate;
	open_stream(ao);
}

extern "C" void android_chiaki_audio_output_set_device(int32_t device_id, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	std::lock_guard<std::mutex> lock(ao->stream_mutex);
	if(device_id == ao->device_id)
		return;
	ao->device_id = device_id;
	if(!ao->channels)
		return; // the stream isn't open yet
	CHIAKI_LOGI(ao->log, "Audio Output switching to device %d", (int)device_id);
	open_stream(ao);
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	ao->queued.fetch_add(static_cast<int64_t>(pushed), std::memory_order_relaxed);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");

	int32_t skipped_ms = ao->skipped_ms.exchange(0, std::memory_order_relaxed);
	if(skipped_ms)
		CHIAKI_LOGI(ao->log, "Audio Output was %d ms behind, skipped %d ms to catch up",
				(int)ao->skipped_from_ms.load(std::memory_order_relaxed), (int)skipped_ms);
}

extern "C" int32_t android_chiaki_audio_output_buffered_ms(void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	return ao->lowest_ms.load(std::memory_order_relaxed);
}

extern "C" int32_t android_chiaki_audio_output_device_buffer_us(void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	int64_t rate = ao->device_rate.load(std::memory_order_relaxed);
	if(rate <= 0)
		return 0;
	return (int32_t)(ao->device_buffer_frames.load(std::memory_order_relaxed) * 1000000LL / rate);
}

extern "C" bool android_chiaki_audio_output_exclusive(void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	return ao->exclusive.load(std::memory_order_relaxed);
}

/**
 * TS5: grows the device buffer by a burst after an underrun, like Oboe's LatencyTuner, so it
 * stays as small as this device allows without crackling. Runs in the stream's callback.
 */
static void tune_buffer(AudioOutput *ao, oboe::AudioStream *stream, int32_t num_frames)
{
	if(!ao->low_latency)
		return;
	ao->frames_since_xrun_check += num_frames;
	// About every 100 ms
	if(ao->frames_since_xrun_check * 10 < stream->getSampleRate())
		return;
	ao->frames_since_xrun_check = 0;
	auto xruns = stream->getXRunCount();
	if(!xruns)
		return;
	int32_t count = xruns.value();
	if(count <= ao->xruns_seen)
		return;
	ao->xruns_seen = count;
	int32_t burst = stream->getFramesPerBurst();
	int32_t size = stream->getBufferSizeInFrames();
	if(burst <= 0 || size >= burst * LL_MAX_BURSTS)
		return;
	auto r = stream->setBufferSizeInFrames(size + burst);
	if(r)
		ao->device_buffer_frames.store(r.value(), std::memory_order_relaxed);
}

// Called by the stream's callback after it took its audio
static void catch_up(AudioOutput *ao, oboe::AudioStream *stream, int32_t num_frames)
{
	int64_t bytes_per_frame = stream->getBytesPerFrame();
	int64_t bytes_per_ms = static_cast<int64_t>(stream->getSampleRate()) * bytes_per_frame / 1000;
	if(bytes_per_frame <= 0 || bytes_per_ms <= 0)
		return;
	int64_t queued = std::max<int64_t>(0, ao->queued.load(std::memory_order_relaxed));
	ao->window_lowest = std::min(ao->window_lowest, queued);
	ao->window_frames += num_frames;
	const int64_t window_ms = ao->low_latency ? LL_CATCH_UP_WINDOW_MS : CATCH_UP_WINDOW_MS;
	const int64_t keep_ms = ao->low_latency ? LL_CATCH_UP_KEEP_MS : CATCH_UP_KEEP_MS;
	const int64_t slack_ms = ao->low_latency ? LL_CATCH_UP_SLACK_MS : CATCH_UP_SLACK_MS;
	if(ao->window_frames * 1000 < static_cast<int64_t>(stream->getSampleRate()) * window_ms)
		return;

	int64_t lowest = ao->window_lowest;
	ao->window_lowest = INT64_MAX;
	ao->window_frames = 0;
	ao->lowest_ms.store(static_cast<int32_t>(lowest / bytes_per_ms), std::memory_order_relaxed);
	if(lowest <= bytes_per_ms * (keep_ms + slack_ms))
		return;
	int64_t skip = (lowest - bytes_per_ms * keep_ms) / bytes_per_frame * bytes_per_frame;
	uint8_t scratch[BUFFER_CHUNK_SIZE];
	int64_t skipped = 0;
	while(skipped < skip)
	{
		size_t popped = ao->buf.Pop(scratch, static_cast<size_t>(std::min<int64_t>(sizeof(scratch), skip - skipped)));
		if(!popped)
			break;
		skipped += static_cast<int64_t>(popped);
	}
	ao->queued.fetch_sub(skipped, std::memory_order_relaxed);
	ao->skipped_from_ms.store(static_cast<int32_t>(lowest / bytes_per_ms), std::memory_order_relaxed);
	ao->skipped_ms.fetch_add(static_cast<int32_t>(skipped / bytes_per_ms), std::memory_order_relaxed);
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	audio_output->queued.fetch_sub(static_cast<int64_t>(buf_size_delivered), std::memory_order_relaxed);
	catch_up(audio_output, stream, num_frames);
	tune_buffer(audio_output, stream, num_frames);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
	std::lock_guard<std::mutex> lock(audio_output->stream_mutex);
	if(stream != audio_output->stream.get())
		return; // already replaced by android_chiaki_audio_output_set_device()
	// The output device changed (e.g. HDMI audio was rerouted), which closes the stream.
	// Oboe calls this on its own thread, where opening a new stream is allowed.
	if(error == oboe::Result::ErrorDisconnected)
	{
		CHIAKI_LOGI(audio_output->log, "Audio Output reopening Oboe stream with OpenSL ES after disconnect");
		audio_output->use_opensl = true;
		open_stream(audio_output);
	}
}
