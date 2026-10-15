package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a call task's audio for demos: the downlink as the agent hears it (after removing listen-in
 * playback) and the uplink EVA injects, as PCM16 mono files under `cache/call-audio/<leg>/`, each with
 * the wall-clock time of its first sample. Only debuggable builds record, and only while
 * `files/debug-call-audio` exists; the phone tool's demo recorder creates that file and removes it
 * and the recordings afterwards.
 */
internal class CallAudioCapture private constructor(
    private val directory: File,
) {
    private val downlink = Stream("downlink")
    private val uplink = Stream("uplink")

    fun downlink(pcm: ByteArray) = downlink.write(pcm)

    fun uplink(pcm: ByteArray) = uplink.write(pcm)

    fun close() {
        downlink.close()
        uplink.close()
    }

    private inner class Stream(
        private val name: String,
    ) {
        private var output: FileOutputStream? = null
        private var failed = false

        @Synchronized
        fun write(pcm: ByteArray) {
            if (failed) return
            try {
                val stream =
                    output ?: FileOutputStream(File(directory, "$name.pcm")).also {
                        File(directory, "$name.start").writeText(System.currentTimeMillis().toString())
                        output = it
                    }
                stream.write(pcm)
            } catch (_: Exception) {
                failed = true
            }
        }

        @Synchronized
        fun close() {
            runCatching { output?.close() }
            output = null
        }
    }

    companion object {
        fun open(
            context: Context,
            legId: String,
            sampleRateHz: Int,
        ): CallAudioCapture? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            if (!File(context.filesDir, "debug-call-audio").exists()) return null
            val directory = File(context.cacheDir, "call-audio/${legId.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            if (!directory.mkdirs() && !directory.isDirectory) return null
            File(directory, "format").writeText("s16le $sampleRateHz 1")
            return CallAudioCapture(directory)
        }
    }
}
