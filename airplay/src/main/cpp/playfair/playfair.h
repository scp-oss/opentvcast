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

#ifndef PLAYFAIR_H
#define PLAYFAIR_H

void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut);

#endif
