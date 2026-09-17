/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast.airplay.media

import tv.opentvcast.util.Logger

/**
 * Parses the H.264 Sequence Parameter Set to recover the real video resolution.
 *
 * WHY THIS IS ITS OWN FILE: the resolution logic used to live inside
 * `VideoDecoder`, which imports `android.media.MediaCodec` and
 * `android.view.Surface`. That made it unloadable on a desktop JVM, so the test
 * runner worked around it with a *stub copy* of the very same algorithm — which
 * meant `VideoDecoderSpsTest` was asserting against the stub, not production
 * code. Editing the real parser could not fail the suite. Extracting the pure
 * bit-twiddling here removes the duplicate and makes those tests real.
 *
 * Nothing in this file may reference an Android API.
 */
object SpsParser {

    /**
     * Extracts `(width, height)` from an SPS NAL unit.
     *
     * AirPlay SDP carries no explicit geometry, so the dimensions have to come
     * out of the SPS as `pic_width_in_mbs_minus1` and
     * `pic_height_in_map_units_minus1`, each counting 16x16 macroblocks, minus
     * whatever `frame_cropping` trims.
     *
     * Handles Baseline (66), Main (77) and the High-profile family, whose SPS
     * carries chroma-format and scaling-list fields that must be skipped to
     * reach the common fields.
     *
     * @param sps raw SPS NAL bytes; `sps[0]` is the NAL header (0x67).
     * @return the resolution, or `null` when the unit is too short, malformed,
     *         or uses a feature this parser does not implement. Callers fall
     *         back to a hint resolution — never to a crash.
     */
    fun parseResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            if (sps.size < 4) return null
            val reader = SpsBitReader(sps, startOffset = 1) // skip the NAL type byte

            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags + 2 reserved zero bits
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            // Baseline and Main imply 4:2:0; the High family states it explicitly.
            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0

            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) {
                    separateColorPlaneFlag = reader.readBits(1) // separate_colour_plane_flag
                }
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                if (reader.readBits(1) == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBits(1) == 1) { // seq_scaling_list_present_flag[i]
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            when (reader.readUe()) { // pic_order_cnt_type
                0 -> reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.readBits(1) // delta_pic_order_always_zero_flag
                    reader.readSe() // offset_for_non_ref_pic
                    reader.readSe() // offset_for_top_to_bottom_field
                    repeat(reader.readUe()) { reader.readSe() }
                }
            }

            reader.readUe() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1) // mb_adaptive_frame_field_flag
            }
            reader.readBits(1) // direct_8x8_inference_flag

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) { // frame_cropping_flag
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val codedWidth = (picWidthInMbsMinus1 + 1) * 16
            val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

            val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

            val subWidthC = when (chromaArrayType) {
                0 -> 1
                1, 2 -> 2
                else -> 1
            }
            val subHeightC = when (chromaArrayType) {
                1 -> 2
                else -> 1
            }

            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = if (chromaArrayType == 0) {
                2 - frameMbsOnlyFlag
            } else {
                subHeightC * (2 - frameMbsOnlyFlag)
            }

            val width = codedWidth - (cropLeft + cropRight) * cropUnitX
            val height = codedHeight - (cropTop + cropBottom) * cropUnitY
            if (width <= 0 || height <= 0) return null

            Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
            return Pair(width, height)
        } catch (e: Exception) {
            Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
            return null
        }
    }
}

/**
 * Bit reader for H.264 RBSP payloads.
 *
 * Reads MSB-first and implements the unsigned/signed Exp-Golomb codes the SPS
 * syntax is built from.
 *
 * @param data raw SPS bytes, NAL header included.
 * @param startOffset where to begin; normally 1, to skip the NAL header byte.
 */
class SpsBitReader(private val data: ByteArray, startOffset: Int) {

    private var bytePos = startOffset
    private var bitPos = 7 // MSB first

    fun readBit(): Int {
        if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
        val bit = (data[bytePos].toInt() ushr bitPos) and 1
        if (--bitPos < 0) {
            bitPos = 7
            bytePos++
        }
        return bit
    }

    fun readBits(n: Int): Int {
        var result = 0
        repeat(n) { result = (result shl 1) or readBit() }
        return result
    }

    /** Reads an unsigned Exp-Golomb code `ue(v)`. */
    fun readUe(): Int {
        var leadingZeros = 0
        while (readBit() == 0) {
            if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
        }
        return if (leadingZeros == 0) 0 else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
    }

    /** Reads a signed Exp-Golomb code `se(v)`. */
    fun readSe(): Int {
        val k = readUe()
        return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
    }

    /** Skips a scaling list (High Profile SPS, §7.3.2.1.1.1). */
    fun skipScalingList(size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                val deltaScale = readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }
}
