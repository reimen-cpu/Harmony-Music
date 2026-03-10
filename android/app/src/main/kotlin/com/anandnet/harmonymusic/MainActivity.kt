package com.anandnet.harmonymusic

import com.ryanheise.audioservice.AudioServiceActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : AudioServiceActivity() {

    private var voiceSearchManager: VoiceSearchManager? = null
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        voiceSearchManager = VoiceSearchManager(this, this)

        // MethodChannel for commands from Flutter
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "voice_search")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "checkModelStatus" -> {
                        result.success(voiceSearchManager?.isModelReady() == true)
                    }
                    "startListening" -> {
                        setupVoiceSearchListener()
                        voiceSearchManager?.startListening()
                        result.success(null)
                    }
                    "stopListening" -> {
                        voiceSearchManager?.stopListening()
                        result.success(null)
                    }
                    "downloadModel" -> {
                        setupVoiceSearchListener()
                        voiceSearchManager?.let { manager ->
                            if (!manager.isModelReady()) {
                                manager.startListening() // This will trigger download flow
                            }
                        }
                        result.success(null)
                    }
                    "cancelDownload" -> {
                        voiceSearchManager?.cancelDownload()
                        result.success(null)
                    }
                    "isListening" -> {
                        result.success(voiceSearchManager?.isListening() == true)
                    }
                    else -> result.notImplemented()
                }
            }

        // EventChannel for streaming results to Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "voice_search_events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    setupVoiceSearchListener()
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    private fun setupVoiceSearchListener() {
        voiceSearchManager?.setListener(object : VoiceSearchManager.StateListener {
            override fun onStateChanged(state: VoiceSearchManager.State, message: String) {
                val event = mapOf(
                    "type" to "state",
                    "state" to state.name.lowercase(),
                    "message" to message
                )
                eventSink?.success(event)
            }

            override fun onPartialResult(text: String) {
                val event = mapOf(
                    "type" to "partial",
                    "text" to text
                )
                eventSink?.success(event)
            }

            override fun onFinalResult(text: String) {
                val event = mapOf(
                    "type" to "final",
                    "text" to text
                )
                eventSink?.success(event)
            }

            override fun onDownloadProgress(progress: Int) {
                val event = mapOf(
                    "type" to "downloadProgress",
                    "progress" to progress
                )
                eventSink?.success(event)
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        voiceSearchManager?.onPermissionResult(requestCode, grantResults)
    }

    override fun onPause() {
        super.onPause()
        if (voiceSearchManager?.isListening() == true) {
            voiceSearchManager?.stopListening()
        }
    }

    override fun onDestroy() {
        voiceSearchManager?.release()
        voiceSearchManager = null
        eventSink = null
        super.onDestroy()
    }
}
