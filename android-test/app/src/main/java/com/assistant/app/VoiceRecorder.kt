package com.assistant.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    val isRecording: Boolean get() = recorder != null
    val durationMs: Long get() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    fun start(): File {
        val outDir = context.cacheDir
        val file = File(outDir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(96000)
        rec.setAudioSamplingRate(44100)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
        outputFile = file
        startedAt = System.currentTimeMillis()
        return file
    }

    fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    fun stop(): File? {
        val rec = recorder ?: return null
        val file = outputFile
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recorder = null
        outputFile = null
        startedAt = 0L
        return file
    }

    fun cancel() {
        val rec = recorder ?: return
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
        startedAt = 0L
    }
}
