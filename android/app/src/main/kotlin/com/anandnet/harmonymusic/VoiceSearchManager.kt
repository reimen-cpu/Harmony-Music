package com.anandnet.harmonymusic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Facade orchestrating the voice search flow:
 * permission check → model download → speech recognition.
 */
class VoiceSearchManager(
    context: Context,
    activity: Activity
) {
    companion object {
        private const val TAG = "VoiceSearchManager"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    enum class State {
        IDLE,
        CHECKING_PERMISSION,
        DOWNLOADING,
        INITIALIZING,
        LISTENING,
        ERROR
    }

    interface StateListener {
        fun onStateChanged(state: State, message: String = "")
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onDownloadProgress(progress: Int)
    }

    private val contextRef = WeakReference(context.applicationContext)
    private val activityRef = WeakReference(activity)

    private val downloader = VoskModelDownloader(context.applicationContext)
    private val recognizer = SpeechRecognizerController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: StateListener? = null
    private var currentState = State.IDLE

    private var currentLang: String = "es"

    fun setListener(listener: StateListener?) {
        this.listener = listener
    }

    fun getCurrentState(): State = currentState

    fun isModelReady(lang: String): Boolean = downloader.isModelReady(lang)

    fun isListening(): Boolean = currentState == State.LISTENING

    /**
     * Start the voice search flow.
     * Checks permission → checks model → downloads if needed → starts recognition.
     */
    fun startListening(lang: String) {
        if (currentLang != lang) {
            recognizer.release() // Force re-init next time
        }
        currentLang = lang

        if (currentState == State.LISTENING) {
            stopListening()
            return
        }

        if (currentState == State.DOWNLOADING) {
            return // Already downloading
        }

        val appContext = contextRef.get() ?: return
        val currentActivity = activityRef.get() ?: return

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            updateState(State.CHECKING_PERMISSION)
            ActivityCompat.requestPermissions(
                currentActivity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        proceedAfterPermission()
    }

    /**
     * Called after permission is granted (either already had it or just granted).
     */
    fun proceedAfterPermission() {
        if (downloader.isModelReady(currentLang)) {
            initAndListen()
        } else {
            downloadAndListen()
        }
    }

    /**
     * Handle permission result from the activity.
     */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                proceedAfterPermission()
            } else {
                updateState(State.ERROR, "Permiso de micrófono denegado")
            }
        }
    }

    fun stopListening() {
        recognizer.stopRecognition()
        updateState(State.IDLE)
    }

    fun cancelDownload() {
        downloader.cancelDownload()
        updateState(State.IDLE)
    }

    fun release() {
        recognizer.release()
        listener = null
    }

    private fun downloadAndListen() {
        updateState(State.DOWNLOADING)
        downloader.downloadModel(currentLang, object : VoskModelDownloader.DownloadListener {
            override fun onProgress(progress: Int) {
                mainHandler.post {
                    listener?.onDownloadProgress(progress)
                }
            }

            override fun onComplete(modelPath: String) {
                mainHandler.post {
                    initAndListen()
                }
            }

            override fun onError(error: String) {
                mainHandler.post {
                    updateState(State.ERROR, error)
                }
            }
        })
    }

    private fun initAndListen() {
        updateState(State.INITIALIZING)

        val modelPath = downloader.getModelPath(currentLang)
        if (!recognizer.isModelInitialized()) {
            if (!recognizer.initModel(modelPath)) {
                updateState(State.ERROR, "Error al inicializar el modelo de voz")
                return
            }
        }

        updateState(State.LISTENING)
        recognizer.startRecognition(object : SpeechRecognizerController.RecognitionListener {
            override fun onPartialResult(text: String) {
                mainHandler.post {
                    listener?.onPartialResult(text)
                }
            }

            override fun onFinalResult(text: String) {
                mainHandler.post {
                    listener?.onFinalResult(text)
                }
            }

            override fun onError(error: String) {
                mainHandler.post {
                    updateState(State.ERROR, error)
                }
            }
        })
    }

    private fun updateState(state: State, message: String = "") {
        currentState = state
        Log.d(TAG, "State: $state ${if (message.isNotEmpty()) "($message)" else ""}")
        listener?.onStateChanged(state, message)
    }
}
