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
#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <assert.h>
#include <string.h>
#define printf(...) (void)0;

int shift[] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
               5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20,
               4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
               6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

uint32_t F(uint32_t B, uint32_t C, uint32_t D)
{
   return (B & C) | (~B & D);
}

uint32_t G(uint32_t B, uint32_t C, uint32_t D)
{
   return (B & D) | (C & ~D);
}

uint32_t H(uint32_t B, uint32_t C, uint32_t D)
{
   return B ^ C ^ D;
}

uint32_t I(uint32_t B, uint32_t C, uint32_t D)
{
   return C ^ (B | ~D);
}


uint32_t rol(uint32_t input, int count)
{
   return ((input << count) & 0xffffffff) | (input & 0xffffffff) >> (32-count);
}

void swap(uint32_t* a, uint32_t* b)
{
   printf("%08x <-> %08x\n", *a, *b);
   uint32_t c = *a;
   *a = *b;
   *b = c;
}

void modified_md5(unsigned char* originalblockIn, unsigned char* keyIn, unsigned char* keyOut)
{
   // Type-pun the 64-byte block via a union (well-defined; avoids the strict-aliasing UB and
   // unaligned access of casting unsigned char* to uint32_t*). The algorithm reads the block
   // byte-wise for `input` and word-wise for the i==31 swaps, so both views must share memory.
   union { unsigned char bytes[64]; uint32_t words[16]; } block;
   uint32_t key_words[4];
   uint32_t out_words[4];
   uint32_t A, B, C, D, Z, tmp;
   int i;

   memcpy(block.bytes, originalblockIn, 64);
   memcpy(key_words, keyIn, sizeof(key_words));   // native-endian read, identical to the old cast

   // Each cycle does something like this:
   A = key_words[0];
   B = key_words[1];
   C = key_words[2];
   D = key_words[3];
   for (i = 0; i < 64; i++)
   {
      uint32_t input;
      int j;
      if (i < 16)
         j = i;
      else if (i < 32)
         j = (5*i + 1) % 16;
      else if (i < 48)
         j = (3*i + 5) % 16;
      else if (i < 64)
         j = 7*i % 16;

      input = block.bytes[4*j] << 24 | block.bytes[4*j+1] << 16 | block.bytes[4*j+2] << 8 | block.bytes[4*j+3];
      printf("Key = %08x\n", A);
      Z = A + input + (int)(long long)((1LL << 32) * fabs(sin(i + 1)));
      if (i < 16)
         Z = rol(Z + F(B,C,D), shift[i]);
      else if (i < 32)
         Z = rol(Z + G(B,C,D), shift[i]);
      else if (i < 48)
         Z = rol(Z + H(B,C,D), shift[i]);
      else if (i < 64)
         Z = rol(Z + I(B,C,D), shift[i]);
      if (i == 63)
         printf("Ror is %08x\n", Z);
      printf("Output of round %d: %08X + %08X = %08X (shift %d, constant %08X)\n", i, Z, B, Z+B, shift[i], (int)(long long)((1LL << 32) * fabs(sin(i + 1))));
      Z = Z + B;
      tmp = D;
      D = C;
      C = B;
      B = Z;
      A = tmp;
      if (i == 31)
      {
         // swapsies
         swap(&block.words[A & 15], &block.words[B & 15]);
         swap(&block.words[C & 15], &block.words[D & 15]);
         swap(&block.words[(A & (15<<4))>>4], &block.words[(B & (15<<4))>>4]);
         swap(&block.words[(A & (15<<8))>>8], &block.words[(B & (15<<8))>>8]);
         swap(&block.words[(A & (15<<12))>>12], &block.words[(B & (15<<12))>>12]);
      }
   }
   printf("%08X %08X %08X %08X\n", A, B, C, D);
   // Now we can actually compute the output
   printf("Out:\n");
   printf("%08x + %08x = %08x\n", key_words[0], A, key_words[0] + A);
   printf("%08x + %08x = %08x\n", key_words[1], B, key_words[1] + B);
   printf("%08x + %08x = %08x\n", key_words[2], C, key_words[2] + C);
   printf("%08x + %08x = %08x\n", key_words[3], D, key_words[3] + D);
   out_words[0] = key_words[0] + A;
   out_words[1] = key_words[1] + B;
   out_words[2] = key_words[2] + C;
   out_words[3] = key_words[3] + D;
   memcpy(keyOut, out_words, sizeof(out_words));   // native-endian write, identical to the old cast
}
