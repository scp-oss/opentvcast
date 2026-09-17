/*
 * playfair - reverse-engineered implementation of Apple's FairPlay
 * session-key exchange, used by AirPlay 2 mirroring.
 *
 * Origin : https://github.com/EstebanKubata/playfair
 *          vendored by RPiPlay (https://github.com/FD-/RPiPlay) as lib/playfair
 * License: GNU GPL (version not specified by upstream).
 *          Under GPLv2 section 9 ("If the Program does not specify a version
 *          number of this License, you may choose any version ever published
 *          by the Free Software Foundation") opentvcast elects GPLv3,
 *          which is the version compatible with the Apache-2.0 code it is
 *          combined with. Distributed as part of opentvcast under GPLv3.
 *
 * Upstream disclaimer (RPiPlay README), reproduced because it is a condition
 * of redistribution we want every reader to see:
 *
 *   "This project makes use of a third-party GPL library for handling
 *    FairPlay. The legal status of that library is unclear. Should you be a
 *    representative of Apple and have any objections against the legality of
 *    the library and its use in this project, please contact me and I'll take
 *    the appropriate steps."
 *
 * This file implements only the session-key exchange negotiated over the
 * AirPlay /fp-setup endpoint. It does NOT implement FairPlay Streaming (FPS)
 * content DRM, and opentvcast cannot play FairPlay-protected media.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Contributors to the playfair project
 */

#include <stdint.h>

#include "playfair.h"

void generate_key_schedule(unsigned char* key_material, uint32_t key_schedule[11][4]);
void generate_session_key(unsigned char* oldSap, unsigned char* messageIn, unsigned char* sessionKey);
void cycle(unsigned char* block, uint32_t key_schedule[11][4]);
void z_xor(unsigned char* in, unsigned char* out, int blocks);
void x_xor(unsigned char* in, unsigned char* out, int blocks);

extern unsigned char default_sap[];

void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut)
{
	unsigned char* chunk1 = &cipherText[16];
	unsigned char* chunk2 = &cipherText[56];
	int i;
	unsigned char blockIn[16];
	unsigned char sapKey[16];
	uint32_t key_schedule[11][4];
	generate_session_key(default_sap, message3, sapKey);	
	generate_key_schedule(sapKey, key_schedule);
	z_xor(chunk2, blockIn, 1);
	cycle(blockIn, key_schedule);
	for (i = 0; i < 16; i++) {
		keyOut[i] = blockIn[i] ^ chunk1[i];
	}
	x_xor(keyOut, keyOut, 1);
	z_xor(keyOut, keyOut, 1);
}

