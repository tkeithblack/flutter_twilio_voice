import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:collection/collection.dart';

enum CallState {
  ringing,
  connected,
  reconnecting,
  reconnected,
  connect_failed,
  call_invite,
  call_invite_canceled,
  call_reject,
  call_ended,
  unhold,
  hold,
  unmute,
  mute,
  speaker_on,
  speaker_off,
  audio_route_change
}
enum CallDirection { incoming, outgoing }

enum AudioDeviceType { bluetooth, wired_headset, earpiece, speaker, unknown }

class AudioDevice {
  AudioDevice();
  String? id;
  String? name;
  AudioDeviceType type = AudioDeviceType.unknown;
  bool? selected;

  factory AudioDevice.fromJson(Map<dynamic, dynamic> json) {
    var audioDevice = AudioDevice()
      ..name = json['name'] as String
      ..selected = json['selected'] as bool
      ..id = json['id'];

    switch (json['type']) {
      case 'bluetooth':
        audioDevice.type = AudioDeviceType.bluetooth;
        break;
      case 'wired_headset':
        audioDevice.type = AudioDeviceType.wired_headset;
        break;
      case 'earpiece':
        audioDevice.type = AudioDeviceType.earpiece;
        break;
      case 'speaker':
        audioDevice.type = AudioDeviceType.speaker;
        break;
      default:
        break;
    }
    return audioDevice;
  }

  IconData getDeviceIcon() {
    var icon = Icons.volume_up;
    switch (type) {
      case AudioDeviceType.bluetooth:
        icon = Icons.bluetooth_audio;
        break;
      case AudioDeviceType.wired_headset:
        icon = Icons.headset;
        break;
      case AudioDeviceType.earpiece:
        icon = Icons.phone_in_talk;
        break;
      case AudioDeviceType.speaker:
        icon = Icons.volume_up;
        break;
      case AudioDeviceType.unknown:
        icon = Icons.phone_in_talk;
        break;
    }
    return icon;
  }

  void printProperties() {
    print('Name: $name, id: $id, selected: $selected, type: $type');
  }
}

class FlutterTwilioVoice {
  static final String ACTION_ACCEPT = "ACTION_ACCEPT";
  static final String ACTION_REJECT = "ACTION_REJECT";
  static final String ACTION_INCOMING_CALL_NOTIFICATION =
      "ACTION_INCOMING_CALL_NOTIFICATION";
  static final String ACTION_INCOMING_CALL = "ACTION_INCOMING_CALL";
  static final String ACTION_CANCEL_CALL = "ACTION_CANCEL_CALL";
  static final String ACTION_FCM_TOKEN = "ACTION_FCM_TOKEN";

  static final String ANDROID_CALLINVITE_INTENT_ACTION =
      "com.flutter.android.twilio.callinvite_message";

  final MethodChannel _channel =
      const MethodChannel('flutter_twilio_voice/messages');

  final EventChannel _eventChannel =
      EventChannel('flutter_twilio_voice/events');

  Stream<CallState>? _onCallStateChanged;
  String? callFrom;
  String? callTo;
  String? sid;
  bool muted = false;
  bool onHold = false;
  bool speakerOn = false;
  bool bluetoothAvailable = false;
  var _audioDevices = <AudioDevice>[];

  int? callStartedOn;
  CallDirection _callDirection = CallDirection.incoming;

  Stream<CallState>? get onCallStateChanged {
    if (_onCallStateChanged == null) {
      _onCallStateChanged = _eventChannel
          .receiveBroadcastStream()
          .map((dynamic event) => _parseCallState(event));
    }
    return _onCallStateChanged;
  }

  Future<bool> tokens(
      {required String accessToken, required String fcmToken}) async {
    return await _channel.invokeMethod('tokens', <String, dynamic>{
      "accessToken": accessToken,
      "fcmToken": fcmToken
    });
  }

  Future<bool> unregister() async {
    return await _channel.invokeMethod('unregister', <String, dynamic>{});
  }

  Future<bool> makeCall(
      {required String from,
      required String to,
      String? toDisplayName,
      Map<String, dynamic>? extraOptions}) async {
    var options = extraOptions != null ? extraOptions : Map<String, dynamic>();
    options['from'] = from;
    options['to'] = to;
    options['toDisplayName'] = toDisplayName;
    callFrom = from;
    callTo = to;
    _callDirection = CallDirection.outgoing;
    return await _channel.invokeMethod('makeCall', options);
  }

  Future<bool> hangUp() async {
    return await _channel.invokeMethod('hangUp', <String, dynamic>{});
  }

  Future<bool> answer() async {
    return await _channel.invokeMethod('answer', <String, dynamic>{});
  }

  Future<bool> reject() async {
    return await _channel.invokeMethod('reject', <String, dynamic>{});
  }

  Future<bool> holdCall() async {
    return await _channel.invokeMethod('holdCall', <String, dynamic>{});
  }

  Future<bool> toggleMute() async {
    return await _channel.invokeMethod('muteCall', <String, dynamic>{});
  }

  // This method toggles between the speaker and earpiece (or external selcted device)
  Future<bool> toggleSpeaker(bool speakerIsOn) async {
    return await _channel.invokeMethod(
        'toggleSpeaker', <String, dynamic>{"speakerIsOn": speakerIsOn});
  }

  // This method selects a specific audio device based on a device ID.
  Future<bool> selectAudioDevice(String deviceID) async {
    return await _channel.invokeMethod(
        'selectAudioDevice', <String, dynamic>{"deviceID": deviceID});
  }

  Future<bool> sendDigits(String digits) async {
    return await _channel.invokeMethod(
        'sendDigits', <String, dynamic>{"digits": digits});
  }

  Future<bool> isOnCall() async {
    return await _channel.invokeMethod('isOnCall', <String, dynamic>{});
  }

  Future<void> replayCallConnection() async {
    return await _channel.invokeMethod('replayCallConnection', <String, dynamic>{});
  }

  Future<void>? refreshAudioRoute() async {
    if (Platform.isAndroid) {
      return await _channel.invokeMethod('refreshAudioRoute', <String, dynamic>{});
    }
  }

  // Legacy Methods replaced by new version ---------
  String getFrom() {
    // replaced by getter fromNumber
    return fromNumber;
  }

  String getTo() {
    // replaced by getter toNumber
    return toNumber;
  }

  // replaced by toggleMute same functionality, more intuatuve name.
  Future<bool> muteCall() {
    return toggleMute();
  }
  // End legacy calls --------------------------------

  DateTime? get callStartDate {
    if (callStartedOn != null)
      return DateTime.fromMillisecondsSinceEpoch(callStartedOn!);

    return null;
  }

  // same as getFrom in getter form
  String get fromNumber {
    return callFrom ?? "";
    ;
  }

  // same as getTo in getter form
  String get toNumber {
    return callTo ?? "";
    ;
  }

  String get externalNumber {
    return _callDirection == CallDirection.incoming ? fromNumber : toNumber;
  }

  String get internalNumber {
    return _callDirection == CallDirection.outgoing ? fromNumber : toNumber;
  }

  String? get callSid {
    return sid;
  }

  bool get isMuted {
    return muted;
  }

  bool get isOnHold {
    return onHold;
  }

  bool get isSpeakerOn {
    return speakerOn;
  }

  bool get isBluetoothAvailable {
    return bluetoothAvailable;
  }

  bool get isExterenalAudioRouteAvailable {
    for (var device in _audioDevices) {
      if (device.type == AudioDeviceType.bluetooth ||
          device.type == AudioDeviceType.wired_headset) {
        return true;
      }
    }
    return false;
  }

  AudioDevice? get selectedAudioDevice {
    return audioDevices.firstWhereOrNull((element) => element.selected == true);
  }

  List<AudioDevice> get audioDevices {
    return _audioDevices;
  }

  int? getCallStartedOn() {
    return callStartedOn;
  }

  CallDirection get callDirection {
    return _callDirection;
  }

  CallState _parseCallState(dynamic params) {
    print("_parseCallState - params: $params");
    var state = params['event'];

    switch (state) {
      case "call_invite":
        _setCallInfoFromParams(params: params);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callInvite(
            customParameters: params["customParameters"],
            replay: params['replay'] ?? false,
            pluginDisplayedAnswerScreen:
                params['pluginDisplayedAnswerScreen'] ?? false);
        return CallState.call_invite;
      case "call_invite_canceled":
        _setCallInfoFromParams(params: params);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callInviteCancel(errorMessage: params["error"]);
        return CallState.call_invite_canceled;
      case "call_reject":
        _setCallInfoFromParams(params: params);
        callReject(customParameters: params["customParameters"]);
        return CallState.call_reject;
      case "ringing":
        _setCallInfoFromParams(params: params);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callDidStartRinging();
        return CallState.ringing;
      case "connected":
        _setCallInfoFromParams(params: params);
        if (callStartedOn == null) {
          callStartedOn = DateTime.now().millisecondsSinceEpoch;
        }
        callDidConnect();
        return CallState.connected;
      case "reconnecting":
        _setCallInfoFromParams(params: params);
        callReconnecting(errorMsg: params["error"]);
        return CallState.reconnecting;
      case "reconnected":
        _setCallInfoFromParams(params: params);
        callReconnected();
        return CallState.reconnected;
      case "connect_failed":
        _setCallInfoFromParams(params: params);
        callConnectFailed(errorMsg: params["error"]);
        return CallState.connect_failed;
      case "call_ended":
        callStartedOn = null;
        callFrom = null;
        callTo = null;
        _callDirection = CallDirection.incoming;
        callEnded(errorMsg: params["error"]);
        return CallState.call_ended;
      case "unhold":
        onHold = false;
        callHoldChanged(isOnHold: onHold);
        return CallState.unhold;
      case "hold":
        onHold = true;
        callHoldChanged(isOnHold: onHold);
        return CallState.hold;
      case "unmute":
        muted = false;
        callMuteChanged(isMuted: muted);
        return CallState.unmute;
      case "mute":
        muted = true;
        callMuteChanged(isMuted: muted);
        return CallState.mute;
      case "speaker_on":
        return CallState.speaker_on;
      case "speaker_off":
        return CallState.speaker_off;
      case "audio_route_change":
        _updateAudioRoute(params: params);
        return CallState.audio_route_change;
      default:
        print('$state is not a valid CallState.');
        throw ArgumentError('$state is not a valid CallState.');
    }
  }

  void _setCallInfoFromParams({required Map<dynamic, dynamic> params}) {
    if (params['from'] != null) callFrom = _prettyPrintNumber(params['from']);
    if (params['to'] != null) callTo = _prettyPrintNumber(params['to']);
    if (params['sid'] != null) sid = params['sid'];
    if (params['muted'] != null) muted = params['muted'];
    if (params['onhold'] != null) onHold = params['onhold'];

    if (params["direction"] != null) {
      _callDirection = "incoming" == params["direction"]
          ? CallDirection.incoming
          : CallDirection.outgoing;
    }
  }

  void _updateAudioRoute({required Map<dynamic, dynamic> params}) {
    // Update audio devices list.
    _audioDevices.clear();
    var devices = params['devices'] as List<dynamic>?;
    if (devices != null) {
      for (var element in devices) {
        var device = AudioDevice.fromJson(element);
        _audioDevices.add(device);
      }
    }

    bluetoothAvailable = params["bluetooth_available"] ?? false;
    speakerOn = params["speaker_on"] ?? false;
    callAudioRouteChanged(
        isBluetoothAvailable: bluetoothAvailable, isSpeaker: speakerOn);
  }

  String _prettyPrintNumber(String phoneNumber) {
    if (phoneNumber.indexOf('client:') > -1) {
      return phoneNumber.split(':')[1];
    }
    if (phoneNumber.substring(0, 1) == '+') {
      phoneNumber = phoneNumber.substring(1);
    }
    if (phoneNumber.length == 7) {
      return phoneNumber.substring(0, 3) + "-" + phoneNumber.substring(3);
    }
    if (phoneNumber.length < 10) {
      return phoneNumber;
    }
    int start = 0;
    if (phoneNumber.length == 11) {
      start = 1;
    }
    return "(" +
        phoneNumber.substring(start, start + 3) +
        ") " +
        phoneNumber.substring(start + 3, start + 6) +
        "-" +
        phoneNumber.substring(start + 6);
  }

  // Notification methods that can be overridden
  void callInvite(
      {required Map<dynamic, dynamic> customParameters,
      required bool replay,
      required bool pluginDisplayedAnswerScreen}) {}
  void callInviteCancel({String? errorMessage}) {}
  void callReject({Map<dynamic, dynamic>? customParameters}) {}
  void callDidStartRinging() {}
  void callDidConnect() {}
  void callReconnected() {}
  void callReconnecting({String? errorMsg}) {}
  void callConnectFailed({String? errorMsg}) {}
  void callEnded({String? errorMsg}) {}
  void callHoldChanged({required bool isOnHold}) {}
  void callMuteChanged({required bool isMuted}) {}
  void callAudioRouteChanged(
      {required bool isBluetoothAvailable, required bool isSpeaker}) {}
}
