package app.androidrik.asr

import java.io.ByteArrayOutputStream

/**
 * 把 raw PCM16 little-endian 数据封装成最小 WAV（RIFF/WAVE/fmt/data）。
 *
 * 下沉自 `MiMoASRController.pcm16ToWav`（DeepSeek ASR 也要走同一封装，避免第三份拷贝）：
 * AudioRecord 输出的是裸 PCM，只接受整段文件的 HTTP 识别接口必须自己包 WAV 头。
 */
fun pcm16ToWav(
    pcm: ByteArray,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val out = ByteArrayOutputStream(44 + dataSize)

    // RIFF header
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    writeIntLE(out, 36 + dataSize) // chunk size = file size - 8
    out.write("WAVE".toByteArray(Charsets.US_ASCII))
    // fmt chunk
    out.write("fmt ".toByteArray(Charsets.US_ASCII))
    writeIntLE(out, 16)            // PCM fmt chunk size
    writeShortLE(out, 1)           // audio format = PCM
    writeShortLE(out, channels)
    writeIntLE(out, sampleRate)
    writeIntLE(out, byteRate)
    writeShortLE(out, blockAlign)
    writeShortLE(out, bitsPerSample)
    // data chunk
    out.write("data".toByteArray(Charsets.US_ASCII))
    writeIntLE(out, dataSize)
    out.write(pcm)
    return out.toByteArray()
}

private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
    out.write((value shr 16) and 0xFF)
    out.write((value shr 24) and 0xFF)
}

private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
}
