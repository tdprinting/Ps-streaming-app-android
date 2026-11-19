// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_AUDIO_OUTPUT_H
#define CHIAKI_JNI_AUDIO_OUTPUT_H

#include <chiaki/log.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *android_chiaki_audio_output_new(ChiakiLog *log);
void android_chiaki_audio_output_free(void *audio_output);
void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output);
/**
 * @param device_id AudioDeviceInfo id to play on, 0 for the system's choice
 */
void android_chiaki_audio_output_set_device(int32_t device_id, void *audio_output);
void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output);
/** How much audio the buffer held at its lowest over the last few seconds, in ms */
int32_t android_chiaki_audio_output_buffered_ms(void *audio_output);
/**
 * Hayai: exclusive (MMAP) stream with a two-burst device buffer that grows only after underruns,
 * and a tighter catch-up on the app's own buffer. For streams opened from now on.
 */
void android_chiaki_audio_output_set_low_latency(bool enabled);
/** Hayai: the device buffer's size in microseconds, for the statistics */
int32_t android_chiaki_audio_output_device_buffer_us(void *audio_output);
/** Hayai: whether the stream got exclusive access to the output */
bool android_chiaki_audio_output_exclusive(void *audio_output);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_OUTPUT_H
