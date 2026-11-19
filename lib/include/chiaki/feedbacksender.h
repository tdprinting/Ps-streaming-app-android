// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_FEEDBACKSENDER_H
#define CHIAKI_FEEDBACKSENDER_H

#include "controller.h"
#include "takion.h"
#include "thread.h"
#include "common.h"

#define CHIAKI_FEEDBACK_HISTORY_PACKET_BUF_SIZE 0x300
#define CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE 0x40

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_feedback_sender_t
{
	ChiakiLog *log;
	ChiakiTakion *takion;
	ChiakiThread thread;

	ChiakiSeqNum16 state_seq_num;

	// Sequence number the next history event takes; a packet carries the number of its newest event
	ChiakiSeqNum16 history_seq_num;
	ChiakiFeedbackHistoryBuffer history_buf;
	uint8_t history_packets[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE][CHIAKI_FEEDBACK_HISTORY_PACKET_BUF_SIZE];
	size_t history_packet_sizes[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE];
	uint8_t history_packet_event_counts[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE];
	ChiakiSeqNum16 history_packet_seq_nums[CHIAKI_FEEDBACK_HISTORY_PACKET_QUEUE_SIZE];
	size_t history_packet_begin;
	size_t history_packet_len;
	// Last history packet sent, repeated a few times while nothing new happens (see feedbacksender.c)
	uint8_t history_last_packet[CHIAKI_FEEDBACK_HISTORY_PACKET_BUF_SIZE];
	size_t history_last_size;
	uint8_t history_last_event_count;
	ChiakiSeqNum16 history_last_seq_num;
	uint64_t history_last_sent_ms;
	unsigned int history_tail_resends;

	bool should_stop;
	ChiakiControllerState controller_state_prev;
	ChiakiControllerState controller_state_history_prev;
	ChiakiControllerState controller_state;
	bool controller_state_changed;
	bool history_dirty;
	ChiakiMutex state_mutex;
	ChiakiCond state_cond;
} ChiakiFeedbackSender;

/**
 * TS5: with redundancy on, the newest input packet is sent again 2 and 6 ms after it, so a packet
 * lost on Wi-Fi costs ~2 ms instead of waiting for the next copy. Either way it is also repeated
 * 20, 50, 100 and 200 ms later, so a lost release can't leave a button held. On by default.
 */
CHIAKI_EXPORT void chiaki_feedback_sender_set_input_redundancy(bool enabled);

/**
 * TS5: repeat the controller state every ms milliseconds while it doesn't change, 0 for the
 * default (200 ms). Applies to all senders, from their next wait.
 */
CHIAKI_EXPORT void chiaki_feedback_sender_set_keepalive_ms(uint64_t ms);

CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_init(ChiakiFeedbackSender *feedback_sender, ChiakiTakion *takion);
CHIAKI_EXPORT void chiaki_feedback_sender_fini(ChiakiFeedbackSender *feedback_sender);
CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_set_controller_state(ChiakiFeedbackSender *feedback_sender, ChiakiControllerState *state);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_FEEDBACKSENDER_H
