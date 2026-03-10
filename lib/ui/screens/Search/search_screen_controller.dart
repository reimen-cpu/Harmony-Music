import 'dart:async';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:hive/hive.dart';

import '/utils/app_link_controller.dart' show ProcessLink;
import '/services/music_service.dart';
import '/services/voice_search_service.dart';

class SearchScreenController extends GetxController with ProcessLink {
  final textInputController = TextEditingController();
  final musicServices = Get.find<MusicServices>();
  final suggestionList = [].obs;
  final historyQuerylist = [].obs;
  late Box<dynamic> queryBox;
  final urlPasted = false.obs;

  // Voice search state
  final isListening = false.obs;
  final isDownloadingModel = false.obs;
  final downloadProgress = 0.obs;
  VoiceSearchService? _voiceService;
  final List<StreamSubscription> _voiceSubs = [];

  // Desktop search bar related
  final focusNode = FocusNode();
  final isSearchBarInFocus = false.obs;

  @override
  onInit() {
    _init();
    super.onInit();
  }

  _init() async {
    if(GetPlatform.isDesktop){
      focusNode.addListener((){
        isSearchBarInFocus.value = focusNode.hasFocus;
      });
    }
    queryBox = await Hive.openBox("searchQuery");
    historyQuerylist.value = queryBox.values.toList().reversed.toList();

    // Initialize voice search on Android
    if (GetPlatform.isAndroid) {
      _initVoiceSearch();
    }
  }

  void _initVoiceSearch() {
    try {
      _voiceService = Get.find<VoiceSearchService>();
      _voiceSubs.add(
        _voiceService!.isListening.listen((val) => isListening.value = val),
      );
      _voiceSubs.add(
        _voiceService!.isDownloadingModel.listen((val) => isDownloadingModel.value = val),
      );
      _voiceSubs.add(
        _voiceService!.downloadProgress.listen((val) => downloadProgress.value = val),
      );
      _voiceSubs.add(
        _voiceService!.currentPartialText.listen((text) {
          if (text.isNotEmpty && isListening.value) {
            textInputController.text = text;
            textInputController.selection =
                TextSelection.collapsed(offset: textInputController.text.length);
            onChanged(text);
          }
        }),
      );
      _voiceSubs.add(
        _voiceService!.errorMessage.listen((msg) {
          if (msg.isNotEmpty) {
            Get.snackbar(
              'Búsqueda por voz',
              msg,
              snackPosition: SnackPosition.BOTTOM,
              duration: const Duration(seconds: 3),
            );
          }
        }),
      );
    } catch (_) {
      // VoiceSearchService not yet available, ignore
    }
  }

  void toggleVoiceSearch() {
    _voiceService?.toggleListening();
  }

  /// Auto-stop voice recognition if currently listening.
  void stopVoiceIfListening() {
    if (isListening.value) {
      _voiceService?.stopListening();
    }
  }

  Future<void> onChanged(String text) async {
    if(text.contains("https://")){
      urlPasted.value = true; 
      return;
    }
    urlPasted.value = false;
    suggestionList.value = await musicServices.getSearchSuggestion(text);
  }

  Future<void> suggestionInput(String txt) async {
    textInputController.text = txt;
    textInputController.selection =
        TextSelection.collapsed(offset: textInputController.text.length);
    await onChanged(txt);
  }

  Future<void> addToHistryQueryList(String txt) async {
    stopVoiceIfListening();
    if (historyQuerylist.length > 9) {
      final queryForRemoval = queryBox.getAt(0);
      await queryBox.deleteAt(0);
      historyQuerylist.removeWhere((element) => element == queryForRemoval);
    }
    if (!historyQuerylist.contains(txt)) {
      await queryBox.add(txt);
      historyQuerylist.insert(0, txt);
    }

    //reset current query and suggestionlist
    reset();
  }

  void reset() {
    urlPasted.value = false;
    textInputController.text = "";
    suggestionList.clear();
  }

  Future<void> removeQueryFromHistory(String txt) async {
    final index = queryBox.values.toList().indexOf(txt);
    await queryBox.deleteAt(index);
    historyQuerylist.remove(txt);
  }

  @override
  void onClose() {
    stopVoiceIfListening();
    for (final sub in _voiceSubs) {
      sub.cancel();
    }
    _voiceSubs.clear();
    focusNode.dispose();
    textInputController.dispose();
    queryBox.close();
    super.onClose();
  }
}
