package com.dormmom.flutter_twilio_voice;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;

import static android.app.Notification.*;

public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    public static boolean pluginDisplayedAnswerScreen = false;

    TwilioSingleton twSingleton() {
        return TwilioSingleton.getInstance(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "inside OnStartCommand(), intent: " + intent);
        Log.d(TAG, "IncomingCallNotificationService.this: " + this.toString());
        String action = intent.getAction();

        if (action != null) {
            CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            switch (action) {
            case Constants.ACTION_INCOMING_CALL:
                handleIncomingCall(callInvite, notificationId);
                break;
            case Constants.ACTION_ACCEPT:
                accept(callInvite, notificationId);
                break;
            case Constants.ACTION_REJECT:
                reject(callInvite, notificationId);
                break;
            case Constants.ACTION_CANCEL_CALL:
                handleCancelledCall(intent);
                break;
            default:
                break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(CallInvite callInvite, int notificationId, int channelImportance) {
        Log.d(TAG, "Inside createNotification()");

        Intent intent = new Intent(this, AnswerJavaActivity.class);
//        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putString(Constants.CALL_SID_KEY, callInvite.getCallSid());

        Context context = getApplicationContext();
        String appName = getApplicationName(context) + " Incoming Call";
        String notificationText = getCallNotificationText(callInvite);

        return buildNotification(context, appName, notificationText,
          pendingIntent,
          extras,
          callInvite,
          notificationId,
          createChannel(channelImportance));
    }

    /**
     * Build a notification.
     *
     * @param text          the text of the notification
     * @param pendingIntent the body, pending intent for the notification
     * @param extras        extras passed with the notification
     * @return the builder
     */
    private Notification buildNotification(Context context, String title, String text, PendingIntent pendingIntent, Bundle extras,
      final CallInvite callInvite,
      int notificationId,
      String channelId) {
        Log.d(TAG, "Inside buildNotification(...)");

        Intent rejectIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        rejectIntent.setAction(Constants.ACTION_REJECT);
        rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        rejectIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);

        PendingIntent piRejectIntent = PendingIntent.getService(getApplicationContext(), 0, rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent acceptIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);

        Icon answerIcon = (Icon) Icon.createWithResource(context, R.drawable.ic_answer);
        Icon rejectIcon = (Icon) Icon.createWithResource(context, R.drawable.decline_button);

        PendingIntent piAcceptIntent = PendingIntent.getService(getApplicationContext(), 0, acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action rejectAction = new Notification.Action.Builder(rejectIcon, getString(R.string.decline),piRejectIntent).build();
        Notification.Action answerAction = new Notification.Action.Builder(answerIcon, getString(R.string.answer), piAcceptIntent).build();

        Notification.Builder builder =
          new Notification.Builder(context, channelId)
                  .setSmallIcon(R.mipmap.app_icon_white)
                  .setColorized(true)
                  .setColor(ContextCompat.getColor(context, R.color.design_default_color_primary))
                  .setContentTitle(title)
                  .setContentText(text)
                  .setCategory(Notification.CATEGORY_CALL)
                  .setFullScreenIntent(pendingIntent, true)
                  .setContentIntent(pendingIntent)
                  .setExtras(extras)
                  .setAutoCancel(true)
                  .setVisibility(Notification.VISIBILITY_PUBLIC);

        // If Android 12 (API level 31) or above we no longer launch app from
        // the Answer/Decline buttons. Therefore, just display the notification
        // and when the user click it we will launch the answer screen.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            builder.addAction(rejectAction)
                    .addAction(answerAction);
        }
        return builder.build();
    }

    private String getCallNotificationText(CallInvite callInvite) {
        // Twilio allows sending custom parameters. The code below checks to see if there is a
        // parameter called callerId, if so we'll use that number. This allows us to show the
        // actual from callerId rather than the 'client:ID' notation that may be present when
        // calling from a server/mobile device.
        // If this is not provided we'll use the standard callInvite.from.
        // We will also pull the name of the PhoneNumber if provided.

        String callerId = twSingleton().getCallerId(callInvite);
        String name = TwilioSingleton.getLineName(callInvite);

        if (name != null && !name.isEmpty()) {
            callerId += " calling " + name;
        }
        return callerId;
    }

    private String createChannel(int channelImportance) {
        NotificationChannel callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_HIGH_IMPORTANCE,
          "Primary Voice Channel", NotificationManager.IMPORTANCE_HIGH);
        String channelId = Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;

        if (channelImportance == NotificationManager.IMPORTANCE_LOW) {
            callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_LOW_IMPORTANCE,
              "Primary Voice Channel", NotificationManager.IMPORTANCE_LOW);
            channelId = Constants.VOICE_CHANNEL_LOW_IMPORTANCE;
        }
        callInviteChannel.setLightColor(Color.GREEN);
//        callInviteChannel.setLockscreenVisibility(VISIBILITY_PRIVATE);
        callInviteChannel.setLockscreenVisibility(VISIBILITY_PUBLIC);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(callInviteChannel);

        return channelId;
    }

    private void accept(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "Inside accept(CallInvite callInvite, int notificationId)");

        boolean running = twSingleton().isAppRunning("com.dormmom.flutter_twilio_voice");
        Log.d(TAG, "*** IS APP RUNNING = " + running);

        answer(callInvite);

        endForeground();
        twSingleton().bringAppToForeground(this);

        Intent intent = new Intent(this, BackgroundCallPageActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Constants.CALL_FROM, twSingleton().getCallerId(callInvite));
        intent.putExtra(Constants.LINE_NAME, twSingleton().getLineName(callInvite));
        this.startActivity(intent);
    }

    private void answer(CallInvite callInvite) {
        endForeground();
        Log.d(TAG, "Answering call");
        SoundManager.getInstance(getApplicationContext()).stopRinging();
        if (callInvite != null) {
            callInvite.accept(getApplicationContext(), twSingleton().getCallListener());
        }
    }

    private void reject(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "Inside reject(CallInvite callInvite)");
        SoundManager.getInstance(getApplicationContext()).stopRinging();
        endForeground();
        twSingleton().decrementActiveInviteCount();

        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_DECLINED);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
//        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        callInvite.reject(getApplicationContext());
    }

    /*
     * Send the CallInvite to the VoiceActivity. Start the activity if it is not running already.
     */
    private void sendCallInviteToActivity(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "inside sendCallInviteToActivity(CallInvite callInvite, int notificationId= " + notificationId + ")");
        Log.d(TAG, "Build.VERSION.SDK_INT = " + Build.VERSION.SDK_INT);
        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleCancelledCall(Intent intent) {
        Log.d(TAG, "handleCancelledCall: " + intent);
        twSingleton().decrementActiveInviteCount();
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        SoundManager.getInstance(getApplicationContext()).stopRinging();
    }

    private void handleIncomingCall(CallInvite callInvite, int notificationId) {

        Log.i(TAG, "handleIncomingCall -  CallInvite = " + callInvite);

        if (twSingleton().initiateIncomingCall(callInvite,notificationId,false)) {
            if (shouldDisplayAnswerNotification()) {
                Log.i(TAG, "Android Version " + Build.VERSION.SDK_INT + ". Displaying Popup Notificaiton for incoming call.");
                pluginDisplayedAnswerScreen = true;
                setCallInProgressNotification(callInvite, notificationId);
            } else {
                Log.i(TAG, "Android Version " + Build.VERSION.SDK_INT + ". Launching App to foreground for incoming call.");
                pluginDisplayedAnswerScreen = false;
                twSingleton().bringAppToForeground(this);
            }
            sendCallInviteToActivity(callInvite, notificationId);
        }
    }

    private void endForeground() {
        stopForeground(true);
    }

    private void setCallInProgressNotification(CallInvite callInvite, int notificationId) {
        final int importance = isAppVisible() ? NotificationManager.IMPORTANCE_LOW :
                                                NotificationManager.IMPORTANCE_HIGH;

        Log.i(TAG, isAppVisible() ? "setCallInProgressNotification - app is visible." : "setCallInProgressNotification - app is NOT visible.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, createNotification(callInvite, notificationId, importance), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(notificationId, createNotification(callInvite, notificationId, importance));
        }
    }

    private static boolean isAppVisible() {
        return ProcessLifecycleOwner
          .get()
          .getLifecycle()
          .getCurrentState()
          .isAtLeast(Lifecycle.State.STARTED);
    }

    private static boolean shouldDisplayAnswerNotification() {
        // If the device is Android 9.0 (Version 29 - VERSION_CODES.O) or greater then
        // we will NOT be able to pop the app to the foreground due to new security settings.
        // Therefore, for these versions we will popup a Notification window that allows
        // the user to select answer or decline.

        boolean result =  Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isAppVisible();
        Log.d(TAG, "pluginWillDisplayAnswerScreen = " + result);
        return result;
    }

    public static String getApplicationName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    public static int getApplicationIcon(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int iconId = applicationInfo.icon;
        return iconId;
    }
}
