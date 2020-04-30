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

  static const MethodChannel _channel = const MethodChannel('flutter_twilio_voice/messages');

  static const EventChannel _eventChannel = EventChannel('flutter_twilio_voice/events');

  static Stream<CallState> _onCallStateChanged;
  static String callFrom = "SafeNSound";
  static String callTo = "SafeNSound";
  static String sid;
  static bool   muted;
  static bool   onHold; 

  static int callStartedOn;
  static CallDirection callDirection = CallDirection.incoming;
  static var callActionsDelegates = List<FlutterTwilioVoice>(); 

  static void setDelegate({FlutterTwilioVoice delegate}) {
    callActionsDelegates.add(delegate);
  }

  static void removeDelegate({FlutterTwilioVoice delegate}) {
    callActionsDelegates.remove(delegate);
  }

  static Stream<CallState> get onCallStateChanged {
    if (_onCallStateChanged == null) {
      _onCallStateChanged = _eventChannel.receiveBroadcastStream().map((dynamic event) => _parseCallState(event));
    }
    return _onCallStateChanged;
  }

  static Future<bool> tokens({@required String accessToken, @required String fcmToken}) {
    assert(accessToken != null);
    return _channel.invokeMethod('tokens', <String, dynamic>{"accessToken": accessToken, "fcmToken": fcmToken});
  }

  static Future<bool> unregister() {
    return _channel.invokeMethod('unregister', <String, dynamic>{});
  }

  static Future<bool> makeCall(
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

  static Future<bool> hangUp() {
    return _channel.invokeMethod('hangUp', <String, dynamic>{});
  }

  static Future<bool> answer() {
    return _channel.invokeMethod('answer', <String, dynamic>{});
  }

  static Future<bool> holdCall() {
    return _channel.invokeMethod('holdCall', <String, dynamic>{});
  }

  static Future<bool> muteCall() {
    return _channel.invokeMethod('muteCall', <String, dynamic>{});
  }

  static Future<bool> toggleSpeaker(bool speakerIsOn) {
    assert(speakerIsOn != null);
    return _channel.invokeMethod('toggleSpeaker', <String, dynamic>{"speakerIsOn": speakerIsOn});
  }

  static Future<bool> sendDigits(String digits) {
    assert(digits != null);
    return _channel.invokeMethod('sendDigits', <String, dynamic>{"digits": digits});
  }

  static Future<bool> isOnCall() {
    return _channel.invokeMethod('isOnCall', <String, dynamic>{});
  }

  static String getFrom() {
    return callFrom;
  }

  static String getTo() {
    return callTo;
  }

  static DateTime get callStartDate {
    return DateTime.fromMillisecondsSinceEpoch(callStartedOn);
  }

  // same as getFrom in getter form
  static String get fromNumber {
    return callFrom;
  }

  // same as getTo in getter form
  static String get toNumber {
    return callTo;
  }

  static String get callSid {
    return sid;
  }

  static bool get isMuted {
    return muted;
  }

  static bool get isOnHold {
    return onHold;
  }

  static int getCallStartedOn() {
    return callStartedOn;
  }

  static CallDirection getCallDirection() {
    return callDirection;
  }

  static CallState _parseCallState(Map<dynamic, dynamic> json) {
    var state = json['event'];

    switch (state) {
      case "call_invite":
        _setCallInfoFromJson(json: json);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callActionsDelegates.forEach((delegate) { 
          delegate.callInvite(customParameters: json["customParameters"]); 
        });
        return CallState.call_invite;
      case "call_invite_canceled":
        _setCallInfoFromJson(json: json);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callActionsDelegates.forEach((delegate) { 
          delegate.callInviteCancel(customParameters: json["customParameters"]); 
        });
        return CallState.call_invite_canceled;
      case "connected":
      _setCallInfoFromJson(json: json);
        if (callStartedOn == null) {
          callStartedOn = DateTime.now().millisecondsSinceEpoch;
        }
        callActionsDelegates.forEach((delegate) { delegate.callDidConnect(); });
        return CallState.connected;
      case "ringing":
        _setCallInfoFromJson(json: json);
        callStartedOn = DateTime.now().millisecondsSinceEpoch;
        callActionsDelegates.forEach((delegate) { delegate.callDidStartRinging(); });
        return CallState.ringing;
      case "reconnecting":
        _setCallInfoFromJson(json: json);
        callActionsDelegates.forEach((delegate) { 
          delegate.callReconnecting(errorMsg: json["error"]); 
        }); 
        return CallState.reconnecting;
      case "reconnected":
        _setCallInfoFromJson(json: json);
        callActionsDelegates.forEach((delegate) { delegate.callReconnected(); });
        return CallState.reconnected;
      case "connect_failed":
        _setCallInfoFromJson(json: json);
        callActionsDelegates.forEach((delegate) { 
          delegate.callConnectFailed(errorMsg: json["error"]); 
        }); 
        return CallState.connect_failed;
      case "call_ended":
        callStartedOn = null;
        callFrom = "SafeNSound";
        callTo = "SafeNSound";
        callDirection = CallDirection.incoming;
        callActionsDelegates.forEach((delegate) { 
          delegate.callEnded(errorMsg: json["error"]); 
        }); 
        return CallState.call_ended;
      case "unhold":
        onHold = false;
        callActionsDelegates.forEach((delegate) { 
          delegate.callHoldChanged(isOnHold: onHold); 
        }); 
        return CallState.unhold;
      case "hold":
        onHold = true;
        callActionsDelegates.forEach((delegate) { 
          delegate.callHoldChanged(isOnHold: onHold); 
        }); 
        return CallState.hold;
      case "unmute":
        muted = false;
        callActionsDelegates.forEach((delegate) { 
          delegate.callMuteChanged(isMuted: muted); 
        }); 
        return CallState.unmute;
      case "mute":
        muted = true;
        callActionsDelegates.forEach((delegate) { 
          delegate.callMuteChanged(isMuted: muted); 
        }); 
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

  static _setCallInfoFromJson({Map<dynamic, dynamic> json}){
    callFrom = _prettyPrintNumber(json["from"]);
    callTo = _prettyPrintNumber(json["to"]);
    sid = json['sid'];
    muted = json['muted'];
    onHold = json['onhold'];
    callDirection = "incoming" == json["direction"]
        ? CallDirection.incoming
        : CallDirection.outgoing;
  }

  static String _prettyPrintNumber(String phoneNumber) {
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
