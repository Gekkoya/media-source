package org.symera.mediasource.lib.m3u8server

object AutoDetector {
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    private val GIF_HEADER = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
    private const val MPEG_TS_SYNC = 0x47.toByte()
    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte())
    private val AVI_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte())
    private const val MPEG_TS_PACKET_SIZE = 188

    fun detectSkipBytes(data: ByteArray): Int = when {
        data.isEmpty() -> 0
        isMpegTsValid(data) -> 0
        isJpegHeader(data) || isPngHeader(data) || isGifHeader(data) -> detectDisguise(data)
        isVideoFormat(data) -> 0
        else -> 0
    }

    private fun isMpegTsValid(data: ByteArray): Boolean {
        if (data.size < MPEG_TS_PACKET_SIZE || data[0] != MPEG_TS_SYNC) return false
        var validPackets = 0
        for (i in 0 until minOf(data.size, 1024) step MPEG_TS_PACKET_SIZE) {
            if (i + MPEG_TS_PACKET_SIZE <= data.size && data[i] == MPEG_TS_SYNC) validPackets++
        }
        return validPackets >= 3
    }

    private fun isJpegHeader(data: ByteArray): Boolean = data.size >= 3 && data[0] == JPEG_HEADER[0] && data[1] == JPEG_HEADER[1] && data[2] == JPEG_HEADER[2]

    private fun isPngHeader(data: ByteArray): Boolean = data.size >= 4 && data[0] == PNG_HEADER[0] && data[1] == PNG_HEADER[1] && data[2] == PNG_HEADER[2] && data[3] == PNG_HEADER[3]

    private fun isGifHeader(data: ByteArray): Boolean = data.size >= 3 && data[0] == GIF_HEADER[0] && data[1] == GIF_HEADER[1] && data[2] == GIF_HEADER[2]

    private fun detectDisguise(data: ByteArray): Int {
        val ftypOffset = findPattern(data, MP4_FTYP)
        if (ftypOffset >= 4) return ftypOffset - 4
        val riffOffset = findPattern(data, AVI_RIFF)
        if (riffOffset > 0) return riffOffset
        val mpegTsOffset = findMpegTsSync(data)
        if (mpegTsOffset > 0) return mpegTsOffset
        return 0
    }

    private fun isVideoFormat(data: ByteArray): Boolean = isMpegTsValid(data) || findPattern(data, MP4_FTYP) >= 0 || findPattern(data, AVI_RIFF) >= 0

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun findMpegTsSync(data: ByteArray): Int {
        for (i in data.indices) {
            if (data[i] == MPEG_TS_SYNC) {
                var validCount = 0
                for (j in i until minOf(data.size, i + 1024) step MPEG_TS_PACKET_SIZE) {
                    if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) validCount++
                }
                if (validCount >= 2) return i
            }
        }
        return -1
    }
}
