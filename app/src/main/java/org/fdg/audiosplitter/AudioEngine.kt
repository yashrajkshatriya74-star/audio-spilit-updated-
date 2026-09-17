package org.fdg.audiosplitter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(
    val pcm: ByteArray,      // 16-bit PCM, interleaved channels
    val sampleRate: Int,
    val channelCount: Int
) {
    val durationSec: Double
        get() {
            val bytesPerFrame = channelCount * 2 // 16-bit = 2 bytes
            val totalFrames = pcm.size / bytesPerFrame
            return if (sampleRate > 0) totalFrames.toDouble() / sampleRate else 0.0
        }
}

object AudioEngine {

    /**
     * Decode any audio file Android's MediaCodec can handle (mp3, m4a,
     * aac, ogg, wav, flac on most devices) into raw 16-bit PCM, using the
     * device's built-in hardware/software decoder. No FFmpeg needed.
     */
    fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw RuntimeException("No audio track found in this file.")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOut = ByteArrayOutputStream()
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10000L

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inBuffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIndex >= 0 -> {
                    val outBuffer: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outBuffer.get(chunk)
                    outBuffer.clear()
                    pcmOut.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return DecodedAudio(pcmOut.toByteArray(), sampleRate, channelCount)
    }

    /** Build a simple amplitude envelope for waveform display. */
    fun buildEnvelope(audio: DecodedAudio, nBars: Int = 120): FloatArray {
        val bytesPerFrame = audio.channelCount * 2
        val totalFrames = audio.pcm.size / bytesPerFrame
        if (totalFrames == 0) return FloatArray(0)

        val buf = ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN)
        val framesPerBar = (totalFrames / nBars).coerceAtLeast(1)
        val envelope = FloatArray(nBars)
        var peak = 1

        val frameSums = IntArray(nBars)
        for (bar in 0 until nBars) {
            val startFrame = bar * framesPerBar
            if (startFrame >= totalFrames) break
            val endFrame = (startFrame + framesPerBar).coerceAtMost(totalFrames)
            var sum = 0L
            var count = 0
            for (f in startFrame until endFrame) {
                val byteOffset = f * bytesPerFrame
                if (byteOffset + 1 >= audio.pcm.size) break
                val sample = buf.getShort(byteOffset).toInt()
                sum += kotlin.math.abs(sample)
                count++
            }
            val avg = if (count > 0) (sum / count).toInt() else 0
            frameSums[bar] = avg
            if (avg > peak) peak = avg
        }
        for (bar in 0 until nBars) {
            envelope[bar] = (frameSums[bar].toFloat() / peak).coerceIn(0f, 1f)
        }
        return envelope
    }

    /** Split decoded PCM into WAV files of `intervalSec` each, written to individual local temp files. */
    fun splitToWavFiles(
        audio: DecodedAudio,
        intervalSec: Int,
        tempDir: File,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): List<File> {
        val bytesPerFrame = audio.channelCount * 2
        val totalFrames = audio.pcm.size / bytesPerFrame
        val framesPerChunk = intervalSec * audio.sampleRate
        val nClips = Math.ceil(totalFrames.toDouble() / framesPerChunk).toInt().coerceAtLeast(1)

        val files = mutableListOf<File>()
        for (i in 0 until nClips) {
            val startFrame = i * framesPerChunk
            val endFrame = ((i + 1) * framesPerChunk).coerceAtMost(totalFrames)
            val startByte = startFrame * bytesPerFrame
            val endByte = endFrame * bytesPerFrame

            val outFile = File(tempDir, "${baseName}_part${(i + 1).toString().padStart(3, '0')}.wav")
            writeWav(outFile, audio.pcm, startByte, endByte, audio.sampleRate, audio.channelCount)
            files.add(outFile)
            onProgress(i + 1, nClips)
        }
        return files
    }

    private fun writeWav(
        outFile: File,
        pcm: ByteArray,
        startByte: Int,
        endByte: Int,
        sampleRate: Int,
        channelCount: Int
    ) {
        val dataSize = endByte - startByte
        val byteRate = sampleRate * channelCount * 2
        val blockAlign = channelCount * 2

        FileOutputStream(outFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channelCount.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())
            fos.write(pcm, startByte, dataSize)
        }
    }
}
