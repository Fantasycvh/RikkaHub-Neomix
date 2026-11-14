package app.androidrik.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pcm16ToWav 的 WAV 头契约守卫。
 *
 * ## 为什么这些断言值得存在
 *
 * MiMo / DeepSeek 的 HTTP 识别接口都要求整段 WAV 文件；WAV 头字段错（字节序、
 * chunk size、byteRate）服务端会直接 400 或识别出垃圾——这类错误在真机上表现为
 * 「录音完成但识别失败」，极难排查。逐字节锚定头部是最便宜的防回归。
 */
class PcmWavTest {

    @Test
    fun wav_header_matches_riff_layout() {
        val pcm = byteArrayOf(1, 0, 2, 0) // 2 个 16-bit 采样
        val wav = pcm16ToWav(pcm, sampleRate = 16000, channels = 1, bitsPerSample = 16)

        assertEquals(44 + 4, wav.size)
        // "RIFF" + chunkSize(36+data) + "WAVE"
        assertEquals('R'.code, wav[0].toInt())
        assertEquals('I'.code, wav[1].toInt())
        assertEquals('F'.code, wav[2].toInt())
        assertEquals('F'.code, wav[3].toInt())
        // chunk size little-endian = 36 + 4 = 40
        assertEquals(40, wav[4].toUByte().toInt() or (wav[5].toUByte().toInt() shl 8) or
            (wav[6].toUByte().toInt() shl 16) or (wav[7].toUByte().toInt() shl 24))
        assertEquals('W'.code, wav[8].toInt())
        assertEquals('A'.code, wav[9].toInt())
        assertEquals('V'.code, wav[10].toInt())
        assertEquals('E'.code, wav[11].toInt())
    }

    @Test
    fun fmt_chunk_carries_sample_rate_and_format() {
        val wav = pcm16ToWav(byteArrayOf(1, 0), sampleRate = 24000, channels = 1, bitsPerSample = 16)

        // "fmt " 在 offset 12
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        fun le16(offset: Int) = wav[offset].toUByte().toInt() or (wav[offset + 1].toUByte().toInt() shl 8)
        fun le32(offset: Int) = le16(offset) or (le16(offset + 2) shl 16)
        assertEquals(16, le32(16))          // fmt chunk size
        assertEquals(1, le16(20))           // audio format = PCM
        assertEquals(1, le16(22))           // mono
        assertEquals(24000, le32(24))       // sample rate
        assertEquals(24000 * 2, le32(28))   // byte rate = rate × channels × bits/8
        assertEquals(2, le16(32))           // block align
        assertEquals(16, le16(34))          // bits per sample
        // data chunk
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(2, le32(40))           // data size
    }

    @Test
    fun empty_pcm_still_produces_valid_header() {
        val wav = pcm16ToWav(byteArrayOf(), 16000, 1, 16)
        assertEquals(44, wav.size)
        assertTrue(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
    }
}
