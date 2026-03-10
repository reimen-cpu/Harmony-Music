import 'dart:async';
import 'package:flutter/services.dart';
import 'package:get/get.dart';

/// Service bridging Flutter ↔ Android native voice search via MethodChannel/EventChannel.
/// Only works on Android. All calls are no-ops on other platforms.
class VoiceSearchService extends GetxService {
  static const _methodChannel = MethodChannel('voice_search');
  static const _eventChannel = EventChannel('voice_search_events');

  // Observable state
  final isListening = false.obs;
  final isDownloadingModel = false.obs;
  final downloadProgress = 0.obs;
  final currentPartialText = ''.obs;
  final errorMessage = ''.obs;
  final isModelReady = false.obs;

  StreamSubscription? _eventSubscription;

  /// Initialize the service and start listening to events from native side.
  Future<VoiceSearchService> init() async {
    if (!GetPlatform.isAndroid) return this;

    // Check initial model status
    try {
      final ready = await _methodChannel.invokeMethod<bool>('checkModelStatus');
      isModelReady.value = ready ?? false;
    } catch (_) {
      isModelReady.value = false;
    }

    // Listen to event stream
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _handleEvent,
      onError: (error) {
        errorMessage.value = error.toString();
        isListening.value = false;
      },
    );

    return this;
  }

  void _handleEvent(dynamic event) {
    if (event is! Map) return;

    final type = event['type'] as String?;
    switch (type) {
      case 'state':
        _handleStateChange(event['state'] as String?, event['message'] as String?);
        break;
      case 'partial':
        currentPartialText.value = event['text'] as String? ?? '';
        break;
      case 'final':
        final text = event['text'] as String? ?? '';
        if (text.isNotEmpty) {
          currentPartialText.value = text;
        }
        break;
      case 'downloadProgress':
        downloadProgress.value = event['progress'] as int? ?? 0;
        break;
    }
  }

  void _handleStateChange(String? state, String? message) {
    switch (state) {
      case 'idle':
        isListening.value = false;
        isDownloadingModel.value = false;
        break;
      case 'checking_permission':
        break;
      case 'downloading':
        isDownloadingModel.value = true;
        isListening.value = false;
        break;
      case 'initializing':
        isDownloadingModel.value = false;
        break;
      case 'listening':
        isListening.value = true;
        isDownloadingModel.value = false;
        isModelReady.value = true;
        errorMessage.value = '';
        break;
      case 'error':
        isListening.value = false;
        isDownloadingModel.value = false;
        errorMessage.value = message ?? 'Error desconocido';
        break;
    }
  }

  /// Start voice recognition. Handles permission + model download automatically.
  Future<void> startListening() async {
    if (!GetPlatform.isAndroid) return;
    errorMessage.value = '';
    currentPartialText.value = '';
    try {
      await _methodChannel.invokeMethod('startListening');
    } on PlatformException catch (e) {
      errorMessage.value = e.message ?? 'Error al iniciar reconocimiento';
    }
  }

  /// Stop voice recognition.
  Future<void> stopListening() async {
    if (!GetPlatform.isAndroid) return;
    try {
      await _methodChannel.invokeMethod('stopListening');
      isListening.value = false;
    } on PlatformException catch (e) {
      errorMessage.value = e.message ?? 'Error al detener reconocimiento';
    }
  }

  /// Toggle between start/stop.
  Future<void> toggleListening() async {
    if (isListening.value) {
      await stopListening();
    } else {
      await startListening();
    }
  }

  /// Cancel an ongoing model download.
  Future<void> cancelDownload() async {
    if (!GetPlatform.isAndroid) return;
    try {
      await _methodChannel.invokeMethod('cancelDownload');
      isDownloadingModel.value = false;
    } catch (_) {}
  }

  /// Check if the model is downloaded and ready.
  Future<bool> checkModelStatus() async {
    if (!GetPlatform.isAndroid) return false;
    try {
      final ready = await _methodChannel.invokeMethod<bool>('checkModelStatus');
      isModelReady.value = ready ?? false;
      return isModelReady.value;
    } catch (_) {
      return false;
    }
  }

  @override
  void onClose() {
    _eventSubscription?.cancel();
    super.onClose();
  }
}
