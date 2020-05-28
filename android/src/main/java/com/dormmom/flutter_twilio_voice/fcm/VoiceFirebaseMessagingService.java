package com.dormmom.flutter_twilio_voice.fcm;

import android.app.Service;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.IBinder;
import android.util.Log;

import com.google.firebase.messaging.RemoteMessage;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import com.dormmom.flutter_twilio_voice.Constants;
import com.dormmom.flutter_twilio_voice.IncomingCallNotificationService;

import java.util.Map;

// This class processes messages that originally come through FCM. However, these messages
// arrive here after the message is caught by FlutterTwilioVoicePlugin.onReceive() which in
// response launches this service.
//
// FlutterTwilioVoicePlugin.onReceive() receives the FCM message via a broadcast from the
// FlutterMessaging plugin. In order to receive this broadcast
// the flutter app using this FlutterTwilioVoice plugin must register for FCM
// broadcasts by calling:
//
//   firebaseMessaging.registerAndroidMessageIntentListener("com.flutter.android.twilio.callinvite_message")
//
// This call request that the FirebaseMessaging plugin send a LocalBroadcast of the Intent
// along with the push message payload each time it receives an FCM push message.
//
// For this service to launch the main app's AndroidManifest.xml file must include.
//
//         <service
//            android:name="com.dormmom.flutter_twilio_voice.fcm.VoiceFirebaseMessagingService"
//            android:stopWithTask="false">
//            <intent-filter>
//                <action android:name="com.flutter.android.twilio.callinvite_message" />
//            </intent-filter>
//        </service>
//
// This implementation differs from the prescribed approach of extending FirebaseMessagingService
// because when this plugin implements FirebaseMessagingService it conflicts with
// the popular FirebaseMessaging plugin and hence both can't receive FCM push notifications.
//
public class VoiceFirebaseMessagingService extends Service {

    private static final String TAG = "VoiceFCMService";

    @Override
    public void onCreate() {
        Log.d(TAG, "VoiceFirebaseMessagingService.onCreate");
        super.onCreate();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "VoiceFirebaseMessagingService.onBind");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VoiceFirebaseMessagingService.onStartCommand");
        RemoteMessage remoteMessage = intent.getParcelableExtra(Constants.EXTRA_CALLINVITE_MESSAGE);

        if (remoteMessage != null)
            processMessageReceived(remoteMessage);

        return START_NOT_STICKY;
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
//    @Override
//    public void onMessageReceived(RemoteMessage remoteMessage) {
//        processMessageReceived(remoteMessage);
//    }

    public void processMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "Received onMessageReceived()");
        Log.d(TAG, "Bundle data: " + remoteMessage.getData());
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        // Check if message contains a data payload.
        Map<String, String> data = remoteMessage.getData();

        if (data != null && data.size() > 0 && isTwilioMessage(data)) {
            boolean valid = Voice.handleMessage(this, data, new MessageListener() {
                @Override
                public void onCallInvite(@NonNull CallInvite callInvite) {
                    final int notificationId = (int) System.currentTimeMillis();
                    handleInvite(callInvite, notificationId);
                }

                @Override
                public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite, @Nullable CallException callException) {
                    handleCanceledCallInvite(cancelledCallInvite, callException);
                }
            });

            if (!valid) {
                Log.e(TAG, "The message was not a valid Twilio Voice SDK payload: " +
                        remoteMessage.getData());
            }
        }
    }

    private boolean isTwilioMessage(Map<String, String> data) {
        // Verify this a twilio voice call.
        if (data == null)
            return false;

        String twiMsgType = data.get("twi_message_type");
        boolean result =  (twiMsgType != null && twiMsgType.equals("twilio.voice.call"));
        return result;
    }

    private void handleInvite(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "Inside handleInvite(CallInvite callInvite, int notificationId) {\n");

        Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);

        startService(intent);
    }

    private void handleCanceledCallInvite(CancelledCallInvite cancelledCallInvite, @Nullable CallException callException) {
        Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Constants.ACTION_CANCEL_CALL);
        intent.putExtra(Constants.CANCELLED_CALL_INVITE, cancelledCallInvite);

        if (callException != null && callException.getErrorCode() != CallException.EXCEPTION_CALL_CANCELLED)
            intent.putExtra(Constants.CANCELLED_CALL_INVITE_ERROR, callException.getLocalizedMessage());

        startService(intent);
    }
}
