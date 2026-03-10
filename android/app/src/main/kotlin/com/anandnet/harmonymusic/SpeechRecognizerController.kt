package com.anandnet.harmonymusic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject

/**
 * Controls Vosk speech recognizer lifecycle: init model, record audio, stream to recognizer.
 */
class SpeechRecognizerController {

    companion object {
        private const val TAG = "SpeechRecController"
        private const val SAMPLE_RATE = 16000
    }

    interface RecognitionListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    @Volatile
    private var isRecognizing = false

    fun initModel(modelPath: String): Boolean {
        return try {
            if (model == null) {
                model = Model(modelPath)
                Log.d(TAG, "Vosk model initialized from: $modelPath")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init model", e)
            false
        }
    }

    fun isModelInitialized(): Boolean = model != null

    fun startRecognition(listener: RecognitionListener) {
        if (isRecognizing) {
            Log.w(TAG, "Already recognizing")
            return
        }

        if (model == null) {
            listener.onError("Modelo no inicializado")
            return
        }

        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("No se pudo inicializar el micrófono")
                release()
                return
            }

            audioRecord?.startRecording()
            isRecognizing = true

            recognitionThread = Thread {
                val buffer = ShortArray(bufferSize)
                while (isRecognizing) {
                    val nread = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (nread < 0) {
                        break
                    }

                    // Convert short array to byte array for Vosk
                    val byteBuffer = ByteArray(nread * 2)
                    for (i in 0 until nread) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    if (recognizer?.acceptWaveForm(byteBuffer, byteBuffer.size) == true) {
                        val result = recognizer?.result ?: continue
                        val text = parseResult(result)
                        if (text.isNotEmpty()) {
                            listener.onFinalResult(text)
                        }
                    } else {
                        val partial = recognizer?.partialResult ?: continue
                        val text = parsePartialResult(partial)
                        if (text.isNotEmpty()) {
                            listener.onPartialResult(text)
                        }
                    }
                }

                // Get final result when stopping
                recognizer?.let { rec ->
                    val finalResult = rec.finalResult
                    val text = parseResult(finalResult)
                    if (text.isNotEmpty()) {
                        listener.onFinalResult(text)
                    }
                }
            }
            recognitionThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Recognition start failed", e)
            listener.onError("Error al iniciar reconocimiento: ${e.localizedMessage}")
            release()
        }
    }

    fun stopRecognition() {
        isRecognizing = false
        try {
            recognitionThread?.join(2000)
        } catch (_: InterruptedException) {}
        recognitionThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null
    }

    fun release() {
        stopRecognition()
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
    }

    private fun parseResult(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun parsePartialResult(json: String): String {
        return try {
            JSONObject(json).optString("partial", "")
        } catch (_: Exception) {
            ""
        }
    }
}
