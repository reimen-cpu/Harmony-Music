package com.anandnet.harmonymusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Handles downloading and extracting the Vosk speech recognition model.
 * Shows a persistent notification with download progress.
 */
class VoskModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelDownloader"
        private const val NOTIFICATION_CHANNEL_ID = "vosk_model_download"
        private const val NOTIFICATION_ID = 9001

        fun getModelUrl(lang: String): String {
            return if (lang == "en") {
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            } else {
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
            }
        }

        fun getModelDirName(lang: String): String {
            return "vosk-model-$lang"
        }
    }

    interface DownloadListener {
        fun onProgress(progress: Int)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    private var isCancelled = false
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun getModelPath(lang: String): String {
        return File(context.filesDir, getModelDirName(lang)).absolutePath
    }

    fun isModelReady(lang: String): Boolean {
        val modelDir = File(context.filesDir, getModelDirName(lang))
        // Check for key Vosk model files
        return modelDir.exists() &&
                modelDir.isDirectory &&
                (File(modelDir, "conf/mfcc.conf").exists() ||
                 File(modelDir, "am/final.mdl").exists() ||
                 File(modelDir, "graph/Gr.fst").exists() ||
                 modelDir.listFiles()?.isNotEmpty() == true)
    }

    fun cancelDownload() {
        isCancelled = true
    }

    fun downloadModel(lang: String, listener: DownloadListener) {
        isCancelled = false
        createNotificationChannel()

        Thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            val tempFile = File(context.cacheDir, "vosk-model-temp-$lang.zip")

            try {
                val url = URL(getModelUrl(lang))
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    listener.onError("Error de servidor: ${connection.responseCode}")
                    return@Thread
                }

                val totalBytes = connection.contentLength.toLong()
                inputStream = BufferedInputStream(connection.inputStream)
                outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0

                showProgressNotification(0)

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        cleanup(tempFile)
                        listener.onError("Descarga cancelada")
                        return@Thread
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalBytes > 0) {
                        val progress = ((totalRead * 100) / totalBytes).toInt()
                        listener.onProgress(progress)
                        showProgressNotification(progress)
                    }
                }

                outputStream.close()
                outputStream = null

                // Extract ZIP
                showExtractingNotification()
                extractZip(tempFile, lang, listener)

                // Cleanup temp file
                tempFile.delete()

                cancelNotification()
                listener.onComplete(getModelPath(lang))

            } catch (e: IOException) {
                Log.e(TAG, "Download failed", e)
                cleanup(tempFile)
                cancelNotification()
                listener.onError("Error de descarga: ${e.localizedMessage}")
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                connection?.disconnect()
            }
        }.start()
    }

    private fun extractZip(zipFile: File, lang: String, listener: DownloadListener) {
        val destDir = File(context.filesDir, getModelDirName(lang))

        // Clean previous model if exists
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                // Find common prefix to strip (model zip usually has a root folder)
                val prefix = entry?.name?.split("/")?.firstOrNull() ?: ""

                // Re-open to process all entries
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis2 ->
                    var zipEntry = zis2.nextEntry
                    while (zipEntry != null) {
                        if (isCancelled) {
                            listener.onError("Extracción cancelada")
                            return
                        }

                        // Strip the root directory from the zip entry path
                        val entryName = if (prefix.isNotEmpty() && zipEntry.name.startsWith("$prefix/")) {
                            zipEntry.name.removePrefix("$prefix/")
                        } else {
                            zipEntry.name
                        }

                        if (entryName.isEmpty()) {
                            zipEntry = zis2.nextEntry
                            continue
                        }

                        val outFile = File(destDir, entryName)

                        if (zipEntry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (zis2.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                        zipEntry = zis2.nextEntry
                    }
                }
            }
            Log.d(TAG, "Model extracted to: ${destDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            destDir.deleteRecursively()
            throw e
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Descarga de modelo de voz",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descarga del modelo de reconocimiento de voz"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showProgressNotification(progress: Int) {
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando modelo de voz")
            .setContentText("$progress%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showExtractingNotification() {
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Preparando modelo de voz")
            .setContentText("Extrayendo archivos...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun cleanup(tempFile: File) {
        try { tempFile.delete() } catch (_: Exception) {}
        cancelNotification()
    }
}
