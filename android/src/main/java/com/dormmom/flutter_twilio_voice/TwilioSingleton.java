package com.dormmom.flutter_twilio_voice;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.audioswitch.AudioDevice;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.audioswitch.AudioSwitch;
import com.twilio.voice.CallInvite;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import android.content.ContentResolver;

import org.jetbrains.annotations.NotNull;

import kotlin.Unit;

// The purpose of this class is to encapsulate those items that are needed by
// both FlutterTwilioVoicePlugin and IncomingCallNotificationService.
// Normally FlutterTwilioVoicePlugin is instantiated first when the app is launched.
// However, if there is an incoming call when the app is not running the service
// IncomingCallNotificationService is launched first. As call invites, connections, etc.
// are processed by the IncomingCallNotificationService, all call messages are processed by
// Call.Listener(), which normally sends them on to the Flutter app. However, if the app is
// not yet started then FlutterTwilioVoicePlugin is not yet created.
//
// This solution moves Call.Listener() and other resources such as audioSwitch processing
// to this Singleton. This way these resources are available even if FlutterTwilioVoicePlugin is not.
// As soon as FlutterTwilioVoicePlugin is instantiated it registers with
// TwilioSingleton.registerPlugin() and at that time the singleton will begin forwarding
// all call progress on to the App via FlutterTwilioVoicePlugin.

public class TwilioSingleton {

    private static TwilioSingleton instance;
    private FlutterTwilioVoicePlugin twilioPlugin = null;
    private final Context appContext;
    private final Call.Listener callListener;

    // Shared members
    CallInvite activeCallInvite;
    int activeCallNotificationId;
    AudioSwitch audioSwitch;
    Call activeCall;
    String outgoingFromNumber;
    String outgoingToNumber;


    private TwilioSingleton(Context context) {
        // AudioManager audio settings for adjusting the volume
        appContext = context;
        callListener = createCallListener();
        startAudioSwitchListener();
    }


    private static final String TAG = "TwilioSingleton";
    public void registerPlugin(FlutterTwilioVoicePlugin plugin) {
        Log.d(TAG,"registerPlugin");
        twilioPlugin = plugin;
    }

    public void unregisterPlugin() {
        Log.d(TAG,"UN-registerPlugin");
        twilioPlugin = null;
    }

    public static TwilioSingleton getInstance(Context context) {
        if (instance == null) {
            instance = new TwilioSingleton(context);
        }
        return instance;
    }

    public Call.Listener getCallListener() {
        return callListener;
    }
    private Call.Listener createCallListener() {
        return new Call.Listener() {
            /*
             * This callback is emitted once before the Call.Listener.onConnected() callback when
             * the callee is being alerted of a Call. The behavior of this callback is determined by
             * the answerOnBridge flag provided in the Dial verb of your TwiML application
             * associated with this client. If the answerOnBridge flag is false, which is the
             * default, the Call.Listener.onConnected() callback will be emitted immediately after
             * Call.Listener. FonRinging(). If the answerOnBridge flag is true, this will cause the
             * call to emit the onConnected callback only after the call is answered.
             * See answeronbridge for more details on how to use it with the Dial TwiML verb. If the
             * twiML response contains a Say verb, then the call will emit the
             * Call.Listener.onConnected callback immediately after Call.Listener.onRinging() is
             * raised, irrespective of the value of answerOnBridge being set to true or false
             */
            @Override
            public void onRinging(Call call) {
                Log.d(TAG, "ringing");

                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.ringing.name());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onConnectFailure(@NotNull Call call, @NotNull CallException error) {
                audioSwitch.deactivate();

                Log.d(TAG, "Connect failure");
                String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);

                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.connect_failed.name());
                params.put("error", error.getLocalizedMessage());
                sendPhoneCallEvents(params);
                resetActiveInviteCount();
            }

            @Override
            public void onConnected(@NotNull Call call) {
                Log.d(TAG, "onConnected");
                audioSwitch.activate();
                handleCallConnect(call);
                queryAndSendAudioDeviceInfo();
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.d(TAG, "onReconnecting");
                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.reconnecting.name());
                params.put("error", callException.getLocalizedMessage());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.d(TAG, "onReconnected");

                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.reconnected.name());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onDisconnected(Call call, CallException error) {
                audioSwitch.deactivate();

                Log.d(TAG, "onDisconnected");
                if (error != null) {
                    String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                    Log.e(TAG, message);
                }

                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.call_ended.name());
                if (error != null)
                    params.put("error", error.getLocalizedMessage());

                sendPhoneCallEvents(params);
                resetActiveInviteCount();

                Intent intent = new Intent(appContext, BackgroundCallJavaActivity.class);
                intent.setAction(Constants.ACTION_DISCONNECT);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);

                outgoingFromNumber = null;
                outgoingToNumber = null;
            }
        };
    }

    void handleCallConnect(Call call) {
        Log.d(TAG, "Connected");
        activeCall = call;

        HashMap<String, Object> params = callToParams (call);
        params.put("event", CallState.connected.name());
        sendPhoneCallEvents(params);
    }

    void disconnect() {
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
            outgoingFromNumber = null;
            outgoingToNumber = null;
            activeCallInvite = null;
            resetActiveInviteCount();

            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", CallState.call_ended.name());
            sendPhoneCallEvents(params);
        }
    }

    void hold() {
        if (activeCall != null) {
            boolean hold = activeCall.isOnHold();
            activeCall.hold(!hold);

            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", hold ? CallState.unhold.name() : CallState.hold.name());
            sendPhoneCallEvents(params);
        }
    }

    boolean mute() {
        if (activeCall != null) {
            boolean mute = activeCall.isMuted();
            activeCall.mute(!mute);

            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", mute ? CallState.unmute.name() : CallState.mute.name());
            sendPhoneCallEvents(params);
            return !mute;
        }
        return false;
    }

    void toggleSpeaker(boolean speakerOn) {
        List<AudioDevice> availableAudioDevices = audioSwitch.getAvailableAudioDevices();
        AudioDevice currentDevice = audioSwitch.getSelectedAudioDevice();

        for (AudioDevice a : availableAudioDevices) {
            String type = FlutterTwilioVoicePlugin.getAudioDeviceType(a);
            if ((type == "speaker" && speakerOn) || (type == "earpiece" && !speakerOn)) {
                Log.i(TAG, "Switching from " + currentDevice.getName() + " to " + a.getName());
                audioSwitch.selectDevice(a);
                break;
            }
        }
    }

    private void sendPhoneCallEvents(HashMap<String, Object> params) {
        if (twilioPlugin != null)
            twilioPlugin.sendPhoneCallEvents(params);
    }

    private HashMap<String, Object> callToParams(Call call) {
        if (twilioPlugin != null) {
            return twilioPlugin.callToParams(call);
        }
        return new HashMap<>();
    }

    void queryAndSendAudioDeviceInfo() {

        AudioDevice selectedDevice = audioSwitch.getSelectedAudioDevice();
        List<AudioDevice> audioDevices = audioSwitch.getAvailableAudioDevices();

        if (twilioPlugin != null)
            twilioPlugin.sendAudioDeviceInfo(audioDevices, selectedDevice);
    }

    private void startAudioSwitchListener() {
        audioSwitch = new AudioSwitch(appContext);

        // This is a callback that is called any time the audio devices change.
        audioSwitch.start((audioDevices, selectedDevice) -> {
            Log.i(TAG, "Audio Device Changed. New Device: " + selectedDevice);
            if (twilioPlugin != null)
                twilioPlugin.sendAudioDeviceInfo(audioDevices, selectedDevice);

            return Unit.INSTANCE;
        });

    }

    private Date lastInviteTime;
    private final static int CALL_INVITE_STALE_SECONDS = 30;
    int activeInviteCount = 0; // Used for handling dup call invites.

    boolean initiateIncomingCall(@NonNull CallInvite callInvite, int notificationId, boolean replay) {

        // Below we have logic to prevent multiple incoming callInvites. This section
        // checks to make sure the activeInviteCount isn't an old value, if so it will now
        // be reset. This prevents us from getting in a situation where we missed an
        // activeInviteCount reset and can never again receive calls.
        // NOTE: All of this complicated code is because sometimes Twilio sends us more than
        // one callInvite.
        //
        // TODO: When we begin handling a second incoming call we need to revisit this code.
        // Currently we 'may' get in a situation where a second call would not be answered for
        // CALL_INVITE_STALE_SECONDS.
        if (lastInviteTime != null) {
            Date now = new Date();
            long diff = now.getTime() - lastInviteTime.getTime();
            long seconds = diff / 1000;
            if (seconds > CALL_INVITE_STALE_SECONDS) {
                Log.d(TAG, "Last callInvite was " + seconds + "seconds ago, resetting activeInviteCount.");
                resetActiveInviteCount();
            }
        }
        lastInviteTime = new Date();

        Log.d(TAG, "handleIncomingCall: activeInviteCount = " + activeInviteCount);

        // Sometimes we get more than one invite from Twilio for the same call.
        // When this happens the we should not process the second invite.
        incrementActiveInviteCount();
        if (activeInviteCount == 1) {
            // TODO: See if we can read callerInfo from getCallerInfo();
            // First glance this seems to just tell us if caller info is verified:
            // From the documents:
            // https://twilio.github.io/twilio-voice-android/docs/latest/com/twilio/voice/CallerInfo.html
            // public Boolean isVerified()
            // Returns `true` if the caller has been validated at 'A' level, `false` if the caller has been verified at any lower level or has failed validation. Returns `null` if no caller verification information is available or if stir status value is `null`.

            activeCallInvite = callInvite;
            activeCallNotificationId = notificationId;
            SoundManager.getInstance(appContext).playRinging();
            return true;

        } else {
            Log.d(TAG, "Skipping callInvite, already handling another invite. New Invite:" + callInvite);
        }
        return false;
    }

    void resetActiveInviteCount() {
        activeInviteCount = 0;
    }

    void incrementActiveInviteCount() {
        activeInviteCount++;
        Log.d(TAG, "Invite Count = " + activeInviteCount);
    }

    void decrementActiveInviteCount() {
        if (activeInviteCount > 0)
            activeInviteCount--;
    }

    // Helper Functions

    String getCallerId(CallInvite callInvite) {

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
            return displayName != null  ? displayName : number;
        }
        return result;
    }

    static String getLineName(CallInvite callInvite) {
        Map<String, String> parameters = callInvite.getCustomParameters();

        if (parameters != null) {
            return parameters.get("lynkName");
        }
        return null;
    }

    String lookupContactNameByPhoneNumber(String searchNumber) {

        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(searchNumber));

            ArrayList<String> nameList = new ArrayList<>();
            String[] projection = new String[]{ContactsContract.PhoneLookup.HAS_PHONE_NUMBER, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.NUMBER};

            ContentResolver cr = appContext.getContentResolver();
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

    static String formatPhoneNumberForDisplay(String phoneNumber, boolean hideLeadingOne) {
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

    public  boolean isAppRunning(String packageName) {
        final ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
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


    // Debug functions
    /*
     * Show the current available audio devices.
     */
    private void showAudioDevices() {
        AudioDevice selectedDevice = audioSwitch.getSelectedAudioDevice();
        List<AudioDevice> availableAudioDevices = audioSwitch.getAvailableAudioDevices();

        if (selectedDevice != null) {
            int selectedDeviceIndex = availableAudioDevices.indexOf(selectedDevice);

            AudioDevice currentDevice = audioSwitch.getSelectedAudioDevice();
            Log.i(TAG, "Selected Device: " + currentDevice);

            ArrayList<AudioDevice> audioDevices = new ArrayList<>();

            for (AudioDevice a : availableAudioDevices) {
                Log.i(TAG, "Available Device: " + a.getName());
                audioDevices.add(a);
            }
            for (AudioDevice a : audioDevices) {
                if (!a.equals(currentDevice)) {
                    audioSwitch.selectDevice(a);
                    Log.i(TAG, "Switched from " + currentDevice.getName() + " to " + a.getName());
                    break;
                }
            }
            ArrayList<String> audioDeviceNames = new ArrayList<>();
            for (AudioDevice a : audioDevices) {
                audioDeviceNames.add(a.getName());
            }
        }
    }
}

