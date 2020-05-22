import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

enum CallState { ringing, connected, reconnecting, reconnected, connect_failed, call_invite, call_invite_canceled, call_ended, unhold, hold, unmute, mute, speaker_on, speaker_off }
enum CallDirection { incoming, outgoing }

class FlutterTwilioVoice {
  static final String ACTION_ACCEPT = "ACTION_ACCEPT";
  static final String ACTION_REJECT = "ACTION_REJECT";
  static final String ACTION_INCOMING_CALL_NOTIFICATION = "ACTION_INCOMING_CALL_NOTIFICATION";
  static final String ACTION_INCOMING_CALL = "ACTION_INCOMING_CALL";
  static final String ACTION_CANCEL_CALL = "ACTION_CANCEL_CALL";
  static final String ACTION_FCM_TOKEN = "ACTION_FCM_TOKEN";

  static final String ANDROID_MESSAGE_INTENT_STRING = "com.flutter.twilio.message.notificaiton";

  final MethodChannel _channel = const MethodChannel('flutter_twilio_voice/messages');

  final EventChannel _eventChannel = EventChannel('flutter_twilio_voice/events');

  Stream<CallState> _onCallStateChanged;
  String callFrom;
  String callTo;
  String sid;
  bool   muted;
  bool   onHold; 

  int callStartedOn;
  CallDirection callDirection = CallDirection.incoming;

  Stream<CallState> get onCallStateChanged {
    if (_onCallStateChanged == null) {
      _onCallStateChanged = _eventChannel.receiveBroadcastStream().map((dynamic event) => _parseCallState(event));
    }
    return _onCallStateChanged;
  }

  Future<bool> tokens({@required String accessToken, @required String fcmToken}) {
    assert(accessToken != null);
    return _channel.invokeMethod('tokens', <String, dynamic>{"accessToken": accessToken, "fcmToken": fcmToken});
  }

  Future<bool> unregister() {
    return _channel.invokeMethod('unregister', <String, dynamic>{});
  }

  Future<bool> makeCall(
      {@required String from, @required String to, String toDisplayName, Map<String, dynamic> extraOptions}) {
    assert(to != null);
    assert(from != null);
    var options = extraOptions != null ? extraOptions : Map<String, dynamic>();
    options['from'] = from;
    options['to'] = to;
    options['toDisplayName'] = toDisplayName;
    callFrom = from;
    callTo = to;
    callDirection = CallDirection.outgoing;
    return _channel.invokeMethod('makeCall', options);
  }

    Future<bool> processIncomingInviteMessage(
      {@required dynamic message}) {
    return _channel.invokeMethod('incomingVoipMessage',  <String, dynamic>{"message": message});
  }

  Future<bool> hangUp() {
    return _channel.invokeMethod('hangUp', <String, dynamic>{});
  }

  Future<bool> answer() {
    return _channel.invokeMethod('answer', <String, dynamic>{});
  }

  Future<bool> holdCall() {
    return _channel.invokeMethod('holdCall', <String, dynamic>{});
  }

  Future<bool> toggleMute() {
    return _channel.invokeMethod('muteCall', <String, dynamic>{});
  }

  Future<bool> toggleSpeaker(bool speakerIsOn) {
    assert(speakerIsOn != null);
    return _channel.invokeMethod('toggleSpeaker', <String, dynamic>{"speakerIsOn": speakerIsOn});
  }

  Future<bool> sendDigits(String digits) {
    assert(digits != null);
    return _channel.invokeMethod('sendDigits', <String, dynamic>{"digits": digits});
  }

  Future<bool> isOnCall() {
    return _channel.invokeMethod('isOnCall', <String, dynamic>{});
  }

  // Legacy Methods replaced by new version ---------
  String getFrom() {    // replaced by getter fromNumber
    return fromNumber;
  }
  String getTo() {       // replaced by getter toNumber
    return toNumber;
  }
  // replaced by toggleMute same functionality, more intuatuve name.
  Future<bool> muteCall() {    
    return toggleMute();
  }
  // End legacy calls --------------------------------


  DateTime get callStartDate {
    if (callStartedOn != null)
      return DateTime.fromMillisecondsSinceEpoch(callStartedOn);
  
    return null;
  }

  // same as getFrom in getter form
  String get fromNumber {
    return callFrom ?? "";;
  }

  // same as getTo in getter form
  String get toNumber {
    return callTo ?? "";;
  }

  String get externalNumber {
    return callDirection == CallDirection.incoming ? fromNumber : toNumber;  
  }

  String get internalNumber {
    return callDirection == CallDirection.outgoing ? fromNumber : toNumber;  
  }

  String get callSid {
    return sid;
  }

  bool get isMuted {
    return muted ?? false;
  }

  bool get isOnHold {
    return onHold ?? false;
  }

  int getCallStartedOn() {
    return callStartedOn;
  }

  CallDirection getCallDirection() {
    return callDirection;
  }

  CallState _parseCallState(dynamic params) {

    print("_parseCallState - params: $params");
    var state = params['event'];

    switch (state) {
      case "call_invite":
        _setCallInfoFromParams(params: params);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callInvite(customParameters: params["customParameters"]); 
        return CallState.call_invite;
      case "call_invite_canceled":
        _setCallInfoFromParams(params: params);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callInviteCancel(customParameters: params["customParameters"]); 
        return CallState.call_invite_canceled;
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
        callDirection = CallDirection.incoming;
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
        // TODO: Need to study audio routes further to handle bluetooh etc.
        return CallState.speaker_on;
      case "speaker_of":
        return CallState.speaker_off;
      default:
        print('$state is not a valid CallState.');
        throw ArgumentError('$state is not a valid CallState.');
    }
  }

  void _setCallInfoFromParams({Map<dynamic, dynamic> params}){

    var value;
    
    value = params["from"];
    callFrom = value != null ? _prettyPrintNumber(value) : null;

    value = params["to"];
    callTo = value != null ? _prettyPrintNumber(value) : null;
    
    sid = params['sid'];
    muted = params['muted'];
    onHold = params['onhold'];
    callDirection = "incoming" == params["direction"]
        ? CallDirection.incoming
        : CallDirection.outgoing;
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
  void callInvite({ Map<dynamic, dynamic> customParameters }) {}
  void callInviteCancel({ Map<dynamic, dynamic> customParameters }) {}
  void callDidStartRinging() {}
  void callDidConnect() {}
  void callReconnected() {}
  void callReconnecting({ String errorMsg }) {}
  void callConnectFailed({ String errorMsg }) {}
  void callEnded({ String errorMsg }) {}
  void callHoldChanged({ bool isOnHold }) {}
  void callMuteChanged({ bool isMuted }) {}

}
