# flutter_twilio_voice

Provides an interface to Twilio's Programmable Voice SDK to allow voice-over-IP (VoIP) calling into your Flutter applications.


## Configure Server to Generate Access Token

View Twilio Documentation on Access Token Generation: https://www.twilio.com/docs/iam/access-tokens

## Make a Call

```
 await FlutterTwilioVoice.makeCall(to: "$client_identifier_or_number_to_call",
                   accessTokenUrl: "https://${YOUR-SERVER-URL}/accesstoken");

```


## Mute a Call

```
 await FlutterTwilioVoice.muteCall(isMuted: true);

```

## Toggle Speaker

```
 await FlutterTwilioVoice.toggleSpeaker(speakerIsOn: true);

```

## Hang Up

```
 await FlutterTwilioVoice.hangUp();

```

## Client Setup to Receive Calls

```
 await FlutterTwilioVoice.receiveCalls(clientIdentifier: 'alice');

```

## Listen for Call Events
```
FlutterTwilioVoice.phoneCallEventSubscription.listen((data) 
    {
      setState(() {
        _callStatus = data.toString();
      });
    }, onError: (error) {
      setState(() {
        print(error);
      });
    });
    
```

## Android specific requirements for receiving incoming calls.

Receiving incoming calls for Andorid requires special handling because 
incoming calls are triggered by FCM push notifications as Call Invites. Of course,
other parts of the apps also need access to push notifications, for example messaging.
In the Flutter world (at this time) most apps that need push notifiations are using a
plugin called FirebaseMessaging (firebase_messaging). FirebaseMessaging listens to 
FCM push notifications which conlficts with FlutterTwilioVoice attempting to 
natively captiure these messages.

My solution at this point, in my opinion, is very undesirable, but does accomplish 
getting the messages into FlutterTwilioVoicePlugin.

I have created a Fork of FirebaseMessaging which allows requesting a
LocalBroadcast off all push notifications via an Intent (on Android). These are then
picked up by the FlutterTwilioVoice plugin and incoming calls are processed.

(BTW, if anyone knows of a way both plugins can capture these push messages please
let me know!)

My branch for FirebaseMessaging is:
  https://github.com/tkeithblack/flutterfire


In order to register for forwarded push notifications, in your Flutter app make the following call:

```
firebaseMessaging.registerAndroidMessageIntentListener(FlutterTwilioVoice.ANDROID_CALLINVITE_INTENT_ACTION);
```

It is also necessary to add the following items to your Flutter app's AndroidManifest.xml file:

```
    <application
        android:name=".Application"        
        ...
        <activity
            android:name=".MainActivity"
        ...
            <intent-filter>
                <action android:name="ACTION_INCOMING_CALL" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <service
            android:enabled="true"
            android:name="com.dormmom.flutter_twilio_voice.IncomingCallNotificationService">
            <intent-filter>
                <action android:name="ACTION_ACCEPT" />
                <action android:name="ACTION_REJECT" />
            </intent-filter>
        </service>

        <service
            android:name="com.dormmom.flutter_twilio_voice.fcm.VoiceFirebaseMessagingService"
            android:stopWithTask="false">
            <intent-filter>
                <action android:name="com.flutter.android.twilio.callinvite_message" />
            </intent-filter>
        </service>
      ...

    </application>
```

## To Do

1. Android Support
2. Propagate Events and Call Status Notifications to Flutter



