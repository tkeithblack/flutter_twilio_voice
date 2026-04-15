package com.dormmom.flutter_twilio_voice.fcm;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.firebase.messaging.FirebaseMessagingService;

public class VoiceFirebaseMessagingService extends FirebaseMessagingService {

    public static final String ACTION_TOKEN = "io.flutter.plugins.firebase.messaging.TOKEN";
    public static final String EXTRA_TOKEN = "token";

    private static final String TAG = "FlutterFcmService";
    @Override
    public void onNewToken(@NonNull String token) {
        Intent onMessageIntent = new Intent(ACTION_TOKEN);
        onMessageIntent.putExtra(EXTRA_TOKEN, token);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(onMessageIntent);
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @Override
    public void onMessageReceived(final RemoteMessage remoteMessage) {
        Log.d(TAG, "Received onMessageReceived()");
        Log.d(TAG, "Bundle data: " + remoteMessage.getData());
        Log.d(TAG, "From: " + remoteMessage.getFrom());
        // If application is running in the foreground use local broadcast to handle message.
        // Otherwise use the background isolate to handle message.
        // Check if message contains a data payload.
        Map<String, String> data = remoteMessage.getData();

        if (data != null && data.size() > 0 && isTwilioMessage(data)) {
            boolean valid = Voice.handleMessage(this, remoteMessage.getData(), new MessageListener() {
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
        Log.d(TAG, "VoiceFirebaseMessagingService.handleCanceledCallInvite:");
        Log.d(TAG, "cancelledCallInvite = " + cancelledCallInvite );
        Log.d(TAG, "callException = " + callException );

        Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Constants.ACTION_CANCEL_CALL);
        intent.putExtra(Constants.CANCELLED_CALL_INVITE, cancelledCallInvite);

        if (callException != null && callException.getErrorCode() != CallException.EXCEPTION_CALL_CANCELLED)
            intent.putExtra(Constants.CANCELLED_CALL_INVITE_ERROR, callException.getLocalizedMessage());

        Log.d(TAG, "handleCanceledCallInvite intent = ");
        Log.d(TAG, String.valueOf(intent));
        startService(intent);
    }
}
