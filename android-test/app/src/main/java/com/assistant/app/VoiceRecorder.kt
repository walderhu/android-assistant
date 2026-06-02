package com.assistant.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class VoiceRecorder(private val context: Context) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2

    private var record: AudioRecord? = null
    private var outputFile: File? = null
    private var recordingThread: Thread? = null
    private var startedAt: Long = 0
    private var currentMax: Int = 0

    val isRecording: Boolean get() = record != null
    val durationMs: Long get() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    fun start(): File {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = if (minBuf > 0) minBuf else sampleRate * bytesPerSample

        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufSize)
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(44L)
        raf.seek(44L)

        rec.startRecording()
        record = rec
        outputFile = file
        currentMax = 0
        startedAt = System.currentTimeMillis()

        recordingThread = thread(name = "VoiceRecorder", isDaemon = true) {
            val buf = ByteArray(bufSize)
            var total = 0L
            while (record != null) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    raf.write(buf, 0, n)
                    total += n
                    var m = 0
                    for (i in 0 until n step 2) {
                        val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        val a = if (s < 0) -s else s
                        if (a > m) m = a
                    }
                    synchronized(this@VoiceRecorder) {
                        if (m > currentMax) currentMax = m
                    }
                }
            }
            raf.seek(0)
            val dataSize = total.toInt()
            val fileSize = 36 + dataSize
            val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray(Charsets.US_ASCII))
            bb.putInt(fileSize)
            bb.put("WAVE".toByteArray(Charsets.US_ASCII))
            bb.put("fmt ".toByteArray(Charsets.US_ASCII))
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(1)
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * bytesPerSample)
            bb.putShort((bytesPerSample).toShort())
            bb.putShort((bytesPerSample * 8).toShort())
            bb.put("data".toByteArray(Charsets.US_ASCII))
            bb.putInt(dataSize)
            raf.write(bb.array())
            raf.close()
        }
        return file
    }

    @Synchronized
    fun maxAmplitude(): Int {
        val m = currentMax
        currentMax = 0
        return m
    }

    fun stop(): File? {
        val rec = record ?: return null
        val file = outputFile
        record = null
        outputFile = null
        startedAt = 0L
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recordingThread?.join(500)
        return file
    }

    fun cancel() {
        val rec = record ?: return
        record = null
        outputFile = null
        startedAt = 0L
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recordingThread?.join(500)
        outputFile?.delete()
    }
}
