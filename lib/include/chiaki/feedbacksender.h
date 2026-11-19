// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_FEEDBACKSENDER_H
#define CHIAKI_FEEDBACKSENDER_H

#include "controller.h"
#include "takion.h"
#include "thread.h"
#include "common.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_feedback_sender_t
{
	ChiakiLog *log;
	ChiakiTakion *takion;
	ChiakiThread thread;

	ChiakiSeqNum16 state_seq_num;

	ChiakiSeqNum16 history_seq_num;
	ChiakiFeedbackHistoryBuffer history_buf;

	bool should_stop;
	ChiakiControllerState controller_state_prev;
	ChiakiControllerState controller_state;
	bool controller_state_changed;
	ChiakiMutex state_mutex;
	ChiakiCond state_cond;

	// TS5 input redundancy: the newest history packet, sent again a few times with the same
	// sequence number (see feedbacksender.c)
	uint8_t last_history_buf[0x300];
	size_t last_history_size;
	ChiakiSeqNum16 last_history_seq;
	uint64_t last_history_sent_ms;
	unsigned last_history_resends;
} ChiakiFeedbackSender;

/**
 * TS5: with redundancy on, every input event is sent again 2 and 6 ms after it, so a packet lost
 * on Wi-Fi costs ~2 ms instead of waiting for the next input. Either way it is also repeated
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
