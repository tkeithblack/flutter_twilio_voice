package com.dormmom.flutter_twilio_voice;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.Call;
import com.twilio.voice.CallInvite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.app.Notification.*;

public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    public static boolean pluginDisplayedAnswerScreen = false;
    Call.Listener callListener = new CallListener().callListener();

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

        Intent intent = new Intent();
//        Intent intent = new Intent(this, FlutterTwilioVoicePlugin.class);

        intent.setAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//        PendingIntent pendingIntent =
//          PendingIntent.getBroadcast(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putString(Constants.CALL_SID_KEY, callInvite.getCallSid());

        Context context = getApplicationContext();
        String appName = getApplicationName(context) + " Incoming Call";
        String notificationText = getCallNotificationText(callInvite);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "calling buildNotification() - Build.VERSION.SDK_INT (" + Build.VERSION.SDK_INT +  ") >= Build.VERSION_CODES.O ("  + Build.VERSION_CODES.O +  ")");

            return buildNotification(context, appName, notificationText,
              pendingIntent,
              extras,
              callInvite,
              notificationId,
              createChannel(channelImportance));
        } else {
            Log.d(TAG, "calling NotificationCompat.Builder - NOT: Build.VERSION.SDK_INT (" + Build.VERSION.SDK_INT +  ") >= Build.VERSION_CODES.O ("  + Build.VERSION_CODES.O +  ")");
            //noinspection deprecation
            return new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.ic_call_end_white_24dp)
                    .setContentTitle(getApplicationName(context))
                    .setContentText(notificationText)
                    .setAutoCancel(true)
                    .setExtras(extras)
                    .setContentIntent(pendingIntent)
                    .setGroup(appName)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setColor(Color.rgb(214, 10, 37)).build();
        }
    }

    /**
     * Build a notification.
     *
     * @param text          the text of the notification
     * @param pendingIntent the body, pending intent for the notification
     * @param extras        extras passed with the notification
     * @return the builder
     */
    @TargetApi(Build.VERSION_CODES.O)
    private Notification buildNotification(Context context, String title, String text, PendingIntent pendingIntent, Bundle extras,
      final CallInvite callInvite,
      int notificationId,
      String channelId) {
        Log.d(TAG, "Inside buildNotification(...)");

        Intent rejectIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        rejectIntent.setAction(Constants.ACTION_REJECT);
        rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        rejectIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piRejectIntent = PendingIntent.getService(getApplicationContext(), 0, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent acceptIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piAcceptIntent = PendingIntent.getService(getApplicationContext(), 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action rejectAction = new Notification.Action.Builder(android.R.drawable.ic_menu_delete,getString(R.string.decline),piRejectIntent).build();
        Notification.Action answerAction = new Notification.Action.Builder(android.R.drawable.ic_menu_call, getString(R.string.answer), piAcceptIntent).build();

        Notification.Builder builder =
          new Notification.Builder(context, channelId)
                  .setSmallIcon(R.mipmap.app_icon_white)
//                  .setColorized(true)
//                  .setColor(ContextCompat.getColor(context, R.color.design_default_color_primary))
                  .setContentTitle(title)
                  .setContentText(text)
                  .setCategory(CATEGORY_CALL)
                  .setLights(Color.RED, 3000, 3000)
                  .setSound(null)
                  .setExtras(extras)
                  .setAutoCancel(true)
                  .addAction(rejectAction)
                  .addAction(answerAction)
                  .setFullScreenIntent(pendingIntent, true);

        return builder.build();
    }

    private String getCallNotificationText(CallInvite callInvite) {

        // Twilio allows sending custom parameters. The code below checks to see if there is a
        // parameter called callerId, if so we'll use that number. This allows us to show the
        // actual from callerId rather than the 'client:ID' notation that may be present when
        // calling from a server/mobile device.
        // If this is not provided we'll use the standard callInvite.from.
        // We will also pull the name of the PhoneNumber if provided.

        String result = callInvite.getFrom();
        Map<String, String> parameters = callInvite.getCustomParameters();

        if (parameters != null) {
            String number = parameters.get("callerId");
            String displayName = lookupContactNameByPhoneNumber(number);
            String formattedNumber = formatPhoneNumberForDisplay(number, true);
            if (displayName == null && formattedNumber != null) {
                number = formattedNumber;
                Log.d(TAG, "Contact Formatted Number: " + formattedNumber);
            }
            String callerId = displayName != null  ? displayName : number;

            Log.d(TAG, "Contact Display Name: " + displayName);

            String name = parameters.get("lynkName");
            String inviteParamCallerId = callerId != null ? callerId : callInvite.getFrom();
            if (name != null && !name.isEmpty()) {
                inviteParamCallerId += " calling " + name;
            }
            result = inviteParamCallerId;
        }
        return result;
    }

    @TargetApi(Build.VERSION_CODES.O)
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
        callInviteChannel.setLockscreenVisibility(VISIBILITY_PRIVATE);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(callInviteChannel);

        return channelId;
    }

    private void accept(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "Inside accept(CallInvite callInvite, int notificationId)");

        boolean running = isAppRunning(getApplicationContext(), "com.dormmom.flutter_twilio_voice");
        Log.d(TAG, "*** IS APP RUNNING = " + running);

        endForeground();
        bringAppToForeground();

        answer(callInvite);

//        Intent intent = new Intent();
//        intent.setAction(Constants.ACTION_ANSWERED);
//        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
//        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
//        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void answer(CallInvite callInvite) {
        Log.d(TAG, "Answering call");
        SoundManager.getInstance(getApplicationContext()).stopRinging();
        if (callInvite != null) {
            callInvite.accept(getApplicationContext(), callListener);
        }
    }

    private void reject(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "Inside reject(CallInvite callInvite)");
        SoundManager.getInstance(getApplicationContext()).stopRinging();
        endForeground();

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
        // TODO: Maybe set these flags below if app should display incoming.
//        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
//        this.startActivity(intent);
    }

    private void handleCancelledCall(Intent intent) {
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleIncomingCall(CallInvite callInvite, int notificationId) {

        Log.i(TAG, "handleIncomingCall -  CallInvite = " + callInvite);

        if (shouldDisplayAnswerNotification()) {
            Log.i(TAG, "Android Version " + Build.VERSION.SDK_INT + ". Displaying Popup Notificaiton for incoming call.");
            pluginDisplayedAnswerScreen = true;
            setCallInProgressNotification(callInvite, notificationId);
            sendCallInviteToActivity(callInvite, notificationId);
        } else {
            Log.i(TAG, "Android Version " + Build.VERSION.SDK_INT + ". Launching App to foreground");
            pluginDisplayedAnswerScreen = false;
            bringAppToForeground();
            sendCallInviteToActivity(callInvite, notificationId);
        }

        // TODO: Look at callInvite.getCallerInfo().
        // First glance this seems to just tell us if caller info is verified:
        // From the documents:
        // https://twilio.github.io/twilio-voice-android/docs/latest/com/twilio/voice/CallerInfo.html
        // public Boolean isVerified()
        // Returns `true` if the caller has been validated at 'A' level, `false` if the caller has been verified at any lower level or has failed validation. Returns `null` if no caller verification information is available or if stir status value is `null`.
    }

    private void endForeground() {
        stopForeground(true);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void setCallInProgressNotification(CallInvite callInvite, int notificationId) {
        if (isAppVisible()) {
            Log.i(TAG, "setCallInProgressNotification - app is visible.");
            startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_LOW));
        } else {
            Log.i(TAG, "setCallInProgressNotification - app is NOT visible.");
            startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private void bringAppToForeground() {
        Log.d(TAG, "Inside wakePlugin()");

        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_APP_TO_FOREGROUND);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.startActivity(intent);
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

    private String lookupContactNameByPhoneNumber(String searchNumber) {

        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(searchNumber));

            ArrayList<String> nameList = new ArrayList<>();
            String[] projection = new String[]{ContactsContract.PhoneLookup.HAS_PHONE_NUMBER, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.NUMBER};

            ContentResolver cr = getContentResolver();
            Cursor cur = cr.query(uri, projection, null, null, null);

            String displayName = null;
            if ((cur != null ? cur.getCount() : 0) > 0) {
                cur.moveToNext();
                String number = cur.getString(cur.getColumnIndex(
                        ContactsContract.PhoneLookup.NUMBER));
                String name = cur.getString(cur.getColumnIndex(
                        ContactsContract.PhoneLookup.DISPLAY_NAME));
                displayName = name != null && !name.isEmpty() ? name : null;
                Log.d(TAG, "Found PhoneNumber: " + number + ", Name: " + name);
                if (cur != null) {
                    cur.close();
                }
            }
            return displayName;
        }
        catch (Exception e) {
                Log.d("*** ERROR: Phone-Number Lookup: ", e.getMessage());
                return null;
        }

    }

    String formatPhoneNumberForDisplay(String phoneNumber, boolean hideLeadingOne) {
        // Remove any character that is not a number

        try {
            final String numbersOnly = phoneNumber.replaceAll("[^\\d.]", "");

            int length = numbersOnly.length();
            boolean hasLeadingOne = numbersOnly.charAt(0) == '1';

            // Check for supported phone number length
            if (!(length <= 10 || (length == 11 && hasLeadingOne))) {
                Log.e(TAG,"failed length test, length = $length");
                return null;
            }

            boolean hasAreaCode = (length >= 10);
            int sourceIndex = 0;

            // Leading 1
            String leadingOne = "";
            if (hasLeadingOne) {
                leadingOne = "1 ";
                sourceIndex += 1;
            }

            // Area code
            String areaCode = "";
            if (hasAreaCode) {
                int areaCodeLength = 3;
                areaCode = "(" + numbersOnly.substring(sourceIndex, areaCodeLength + sourceIndex) + ") ";
                sourceIndex += areaCodeLength;
            }

            // Prefix, 3 characters
            int prefixLength = 3;
            String prefix = numbersOnly.substring(sourceIndex, prefixLength + sourceIndex);
            sourceIndex += prefixLength;

            // Suffix, 4 characters
            int suffixLength = 4;
            String suffix = numbersOnly.substring(sourceIndex, suffixLength + sourceIndex);

            return (hideLeadingOne ? "" : leadingOne) +
                    areaCode +
                    prefix +
                    '-' +
                    suffix;
        } catch (Exception e) {
            Log.e(TAG, "** ERROR: failed formatting phone number '$phoneNumber' for display.");
            return null;
        }
    }

    public static boolean isAppRunning(final Context context, final String packageName) {
//        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
        if (procInfos != null)
        {
            for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                if (processInfo.processName.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

}
