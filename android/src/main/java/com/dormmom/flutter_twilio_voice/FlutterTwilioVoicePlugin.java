package com.dormmom.flutter_twilio_voice;
import com.dormmom.flutter_twilio_voice.fcm.VoiceFirebaseMessagingService;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.LogLevel;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;
import com.twilio.voice.Voice;
import com.twilio.audioswitch.AudioSwitch;
import com.twilio.audioswitch.AudioDevice;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import kotlin.Unit;

import java.util.List;
import java.util.Map;

import com.google.firebase.messaging.RemoteMessage;

enum CallState {
    ringing, connected, reconnecting, reconnected, connect_failed, call_invite, call_invite_canceled, call_reject, call_ended,
    unhold, hold, unmute, mute, speaker_on, speaker_off, audio_route_change
}

enum CallDirection {
    incoming, outgoing
}


public class FlutterTwilioVoicePlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler,
  ActivityAware, PluginRegistry.NewIntentListener {

    static VoiceFirebaseMessagingService vms = new VoiceFirebaseMessagingService();

    private AudioSwitch audioSwitch;
    private static final String CHANNEL_NAME = "flutter_twilio_voice";
    private static final String TAG = "TwilioVoicePlugin";
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;

    private String accessToken;
    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    private NotificationManager notificationManager;
    //private SoundPoolManager soundPoolManager;
    private CallInvite activeCallInvite;
    private int activeInviteCount = 0; // Used for handling dup call invites.
    private Call activeCall;
    private int activeCallNotificationId;
    private Context context;
    private Activity activity;

    RegistrationListener registrationListener = registrationListener();
    UnregistrationListener unregistrationListener = unregistrationListener();
    Call.Listener callListener = callListener();
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private EventChannel.EventSink eventSink;
    private String fcmToken;
    private boolean callOutgoing;

    private String outgoingFromNumber;
    private String outgoingToNumber;

    @Override
    public void onAttachedToEngine(FlutterPluginBinding flutterPluginBinding) {
        Log.i(TAG, "onAttachedToEngine()");
        register(flutterPluginBinding.getFlutterEngine().getDartExecutor(), this, flutterPluginBinding.getApplicationContext());
    }

    private static void register(BinaryMessenger messenger, FlutterTwilioVoicePlugin plugin, Context context) {
        plugin.methodChannel = new MethodChannel(messenger, CHANNEL_NAME + "/messages");
        plugin.methodChannel.setMethodCallHandler(plugin);


        plugin.eventChannel = new EventChannel(messenger, CHANNEL_NAME + "/events");
        plugin.eventChannel.setStreamHandler(plugin);

        plugin.context = context;
        //plugin.soundPoolManager = SoundPoolManager.getInstance(context);

        plugin.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        plugin.voiceBroadcastReceiver = new VoiceBroadcastReceiver(plugin);
        plugin.audioSwitch = new AudioSwitch(context);

        plugin.registerReceiver();

        /*
         * Needed for setting/abandoning audio focus during a call
         */
        plugin.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        plugin.audioManager.setSpeakerphoneOn(true);

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        //setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        Voice.setLogLevel(LogLevel.INFO);
        Log.i(TAG, "TwilioVoice Version: " + Voice.getVersion());
        Log.i(TAG, "TwilioVoice Log Level: " + Voice.getLogLevel());
    }

    /** Plugin registration. */
    public static void registerWith(PluginRegistry.Registrar registrar) {
        // Detect if we've been launched in background
        if (registrar.activity() == null) {
            return;
        }

        final FlutterTwilioVoicePlugin instance = new FlutterTwilioVoicePlugin();
        instance.activity = registrar.activity();
        register(registrar.messenger(), instance, registrar.context());
        registrar.addNewIntentListener(instance);
    }

    private void handleIncomingCallIntent(Intent intent) {
       if (intent != null && intent.getAction() != null) {
           String action = intent.getAction();
           Log.d(TAG, "==============>> Handling incoming call intent for action " + action);
            callOutgoing = false;

            switch (action) {
            case Constants.ACTION_INCOMING_CALL:
                int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                handleIncomingCall(callInvite, notificationId);
                break;
            case Constants.ACTION_INCOMING_CALL_NOTIFICATION:
                activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                Log.d(TAG, "activeNotificationId: " + activeCallNotificationId);
                break;
            case Constants.ACTION_CANCEL_CALL:
                activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(Constants.CANCELLED_CALL_INVITE);

                String callError = null;
                if (intent.hasExtra(Constants.CANCELLED_CALL_INVITE_ERROR))
                    callError = intent.getStringExtra(Constants.CANCELLED_CALL_INVITE_ERROR);
                handleCancel(cancelledCallInvite, callError);
                break;
            case Constants.ACTION_ACCEPT:
                activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                answer();
                break;
            default:
                Log.e(TAG, "handleIncomingCallIntent action NOT handled. " + action);
                break;
            }
       }
    }

    // private void showIncomingCallDialog() {
    //     this.handleIncomingCall(activeCallInvite.getFrom(), activeCallInvite.getTo());
    // }

    void incrementActiveInviteCount() {
        activeInviteCount++;
        Log.d(TAG, "Invite Count = " + activeInviteCount);
    }

    void decrementActiveInviteCount() {
        if (activeInviteCount > 0)
            activeInviteCount--;
    }

    void resetActiveInviteCount() {
        activeInviteCount = 0;
    }

    private Date lastInviteTime;
    private final static int CALL_INVITE_STALE_SECONDS = 30;

    private void handleIncomingCall(@NonNull CallInvite callInvite, int notificationId) {

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
//            CallerInfo callerInfo = activeCallInvite.getCallerInfo();

            activeCallInvite = callInvite;
            activeCallNotificationId = notificationId;

            Log.d(TAG, "Processing call callInvite: " + callInvite);

            showWhenInBackground();

            HashMap<String, Object> params = paramsFromCallInvite(callInvite, CallState.call_invite);
            sendPhoneCallEvents(params);

            SoundManager.getInstance(context).playRinging();
        } else {
            Log.d(TAG, "Skipping callInvite, already handling another invite. New Invite:" + callInvite);
        }
    }

    private HashMap<String, Object> paramsFromCallInvite(CallInvite callInvite, CallState event) {

        final HashMap<String, Object> params = new HashMap<>();
        params.put("event", event.name());
        params.put("from", callInvite.getFrom());
        params.put("to", callInvite.getTo());
        params.put("sid", callInvite.getCallSid());
        params.put("direction", CallDirection.incoming.name());

        Object customParameters = callInvite.getCustomParameters();
        if (customParameters != null)
            params.put("customParameters",  customParameters);

        return params;
    }

    private void handleCancel(@NonNull CancelledCallInvite cancelledCallInvite, @Nullable String callErrorDescription) {
        //if (alertDialog != null && alertDialog.isShowing()) {

        Log.d(TAG, "handleCancel: activeInviteCount = " + activeInviteCount);

        // Sometimes we get more than one invite from Twilio for the same call.
        // When this happens the second invite will not be answered, which causes
        // us to receive a cancelInvite for that second invite.
        // However, we don't want to hangup when there is still an
        // active call. Therefore, do not send the cancel invite message until
        // activeInvite count is == 1.
        decrementActiveInviteCount();
        if (activeInviteCount  == 0) {
            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", CallState.call_invite_canceled.name());
            params.put("from", cancelledCallInvite.getFrom());
            params.put("to", cancelledCallInvite.getTo());
            params.put("sid", cancelledCallInvite.getCallSid());
            params.put("direction",  CallDirection.incoming.name());
            if (callErrorDescription != null)
                params.put("error",  callErrorDescription);

            sendPhoneCallEvents(params);

            callOutgoing = false;
            SoundManager.getInstance(context).stopRinging();

            activeCallInvite = null;
        }
    }

    private void showWhenInBackground() {
        // These flags ensure that the activity can be launched when the screen is locked.
        Window window = activity.getWindow();

        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // to wake up screen
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        PowerManager.WakeLock wakeLock = pm.newWakeLock((PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP), "flutter.twilio.wakelock:TAG");
        wakeLock.acquire();

        // to release screen lock
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        KeyguardManager.KeyguardLock keyguardLock = keyguardManager.newKeyguardLock("flutter.twilio.wakelock:TAG");
        keyguardLock.disableKeyguard();
    }

    private void registerReceiver() {
        if (!isReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_CALLINVITE);
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL);
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            intentFilter.addAction(Constants.ACTION_FCM_TOKEN);
            LocalBroadcastManager.getInstance(this.activity).registerReceiver(
              voiceBroadcastReceiver, intentFilter);
            isReceiverRegistered = true;

            startAudioSwitchListener();
        }
    }

    private void unregisterReceiver() {
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this.activity).unregisterReceiver(voiceBroadcastReceiver);
            isReceiverRegistered = false;

            audioSwitch.stop();
        }
    }

    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(String accessToken, String fcmToken) {
                Log.d(TAG, "Successfully registered FCM " + fcmToken);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format("Registration Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
            }
        };
    }

    private UnregistrationListener unregistrationListener() {
        return new UnregistrationListener() {
            @Override
            public void onUnregistered(String accessToken, String fcmToken) {
                Log.d(TAG, "Successfully registered FCM " + fcmToken);
                Log.d(TAG, "Successfully registered FCM " + fcmToken);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format("Unregistration Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
            }
        };
    }

    private static class VoiceBroadcastReceiver extends BroadcastReceiver {

        private final FlutterTwilioVoicePlugin plugin;

        private VoiceBroadcastReceiver(FlutterTwilioVoicePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast for action " + action);

            // This message was grabbed from the firebase_messaging plugin broadcast.
            if (action != null && action.equals(Constants.ACTION_CALLINVITE)) {
                RemoteMessage remoteMessage = intent.getParcelableExtra(Constants.EXTRA_CALLINVITE_MESSAGE);

                Log.d(TAG, "handling FirebaseNotificaiton message. RemoteMessage: " + remoteMessage);

                Intent vmsIntent = new Intent(context , VoiceFirebaseMessagingService.class);
                vmsIntent.setAction(action);

                vmsIntent.putExtra(Constants.EXTRA_CALLINVITE_MESSAGE, remoteMessage);
                context.startService(vmsIntent);
                return;
            }

            if (action != null && (action.equals(Constants.ACTION_INCOMING_CALL) || action.equals(Constants.ACTION_CANCEL_CALL))) {
                /*
                 * Handle the incoming or cancelled call invite
                 */
                Log.d(TAG, "inside onReceive if calling handleIncomingCallIntent()");

                plugin.handleIncomingCallIntent(intent);
            }
        }
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     *
     * If a valid google-services.json has not been provided or the FirebaseInstanceId has not been
     * initialized the fcmToken will be null.
     *
     * In the case where the FirebaseInstanceId has not yet been initialized the
     * VoiceFirebaseInstanceIDService.onTokenRefresh should result in a LocalBroadcast to this
     * activity which will attempt registerForCallInvites again.
     *
     */
    private void registerForCallInvites() {
        if (this.accessToken != null && this.fcmToken != null) {
            Log.i(TAG, "Registering with FCM");
            Voice.register(this.accessToken, Voice.RegistrationChannel.FCM, this.fcmToken, registrationListener);
        }
    }

    private void unregisterForCallInvites() {
        Log.i(TAG, "unregisterForCallInvites()");
        if (this.accessToken != null && this.fcmToken != null) {
            Log.i(TAG, "Un-registering with FCM");
            Voice.unregister(this.accessToken, Voice.RegistrationChannel.FCM, this.fcmToken, unregistrationListener);
            this.accessToken = null;
            this.fcmToken = null;
        }
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding flutterPluginBinding) {
        Log.i(TAG, "onDetachedFromEngine()");
        Log.d(TAG, "Detatched from Flutter engine");
        //soundPoolManager.release();
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        Log.i(TAG, "onListen()");
        Log.i(TAG, "Setting event sink");
        this.eventSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
        Log.i(TAG, "onCancel()");
        Log.i(TAG, "Removing event sink");
        this.eventSink = null;
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        if (call.method.equals("tokens")) {
            Log.d(TAG, "Setting up tokens");
            this.accessToken = call.argument("accessToken");
            this.fcmToken = call.argument("fcmToken");
            this.registerForCallInvites();
            result.success(true);
        } else if (call.method.equals("sendDigits")) {
            String digits = call.argument("digits");
            if (this.activeCall != null) {
                Log.d(TAG, "Sending digits " + digits);
                this.activeCall.sendDigits(digits);
            }
            result.success(true);
        } else if (call.method.equals("hangUp")) {
            Log.d(TAG, "Hanging up");
            this.disconnect();
            result.success(true);
        } else if (call.method.equals("toggleSpeaker")) {
            boolean speakerIsOn = call.argument("speakerIsOn");
            toggleSpeaker(speakerIsOn);
            result.success(true);
        } else if (call.method.equals("selectAudioDevice")) {
            String deviceID = call.argument("deviceID");
            selectAudioDevice(deviceID);
            result.success(true);
        } else if (call.method.equals("muteCall")) {
            Log.d(TAG, "Muting call");
            this.mute();
            result.success(true);
        } else if (call.method.equals("isOnCall")) {
            Log.d(TAG, "Is on call invoked");
            result.success(this.activeCall != null);
        } else if (call.method.equals("holdCall")) {
            Log.d(TAG, "Hold call invoked");
            this.hold();
            result.success(true);
        } else if (call.method.equals("answer")) {
            Log.d(TAG, "Answering call");
            this.answer();
            result.success(true);
        } else if (call.method.equals("reject")) {
            Log.d(TAG, "Rejecting call");
            this.reject();
            result.success(true);
        } else if (call.method.equals("unregister")) {
            this.unregisterForCallInvites();
            result.success(true);
        } else if (call.method.equals("makeCall")) {
            Log.d(TAG, "Making new call");
            final HashMap<String, String> params = new HashMap<>();
            params.put("To", call.argument("to").toString());
            params.put("From", call.argument("from").toString());

            Map<String, String> arguments = (Map<String, String>)call.arguments;
            // Add optional parameters.
            for (Map.Entry<String,String> entry : arguments.entrySet()) {
                String key =  entry.getKey();
                String value = entry.getValue();
                if (key != "to" && key != "from") {
                    params.put(key, value);
                }
            }
            outgoingFromNumber = params.get("From");
            outgoingToNumber = params.get("To");
            this.callOutgoing = true;
            final ConnectOptions connectOptions = new ConnectOptions.Builder(this.accessToken)
              .params(params)
              .build();
            this.activeCall = Voice.connect(this.activity, connectOptions, this.callListener);
            result.success(true);

//        } else if (call.method.equals("incomingVoipMessage")) {
//            Log.d(TAG, "incomingVoipMessage");
//
//            final HashMap<String, Object> message = call.argument("message");
//            result.success(handleInviteMessage(message));
//
        } else {
            result.notImplemented();
        }
    }

    /*
     * Accept an incoming Call
     */
    private void answer() {
        Log.d(TAG, "Answering call");
        SoundManager.getInstance(context).stopRinging();
        if (activeCallInvite != null) {
            activeCallInvite.accept(context, callListener);
        }
    }

    private void reject() {
        SoundManager.getInstance(context).stopRinging();
        if (activeCallInvite != null) {

            HashMap<String, Object> params = paramsFromCallInvite(activeCallInvite, CallState.call_reject);
            sendPhoneCallEvents(params);

            activeCallInvite.reject(context);
            activeCall = null;
            outgoingFromNumber = null;
            outgoingToNumber = null;
            activeCallInvite = null;
        }
        resetActiveInviteCount();
    }

    @Override
    public boolean onNewIntent(Intent intent) {
        Log.d(TAG, "Inside onNewIntent, Intent: " + intent);
        activity.setIntent(intent);
        this.handleIncomingCallIntent(intent);
        return false;
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "Inside onAttachedToActivity()");
        this.activity = activityPluginBinding.getActivity();
        activityPluginBinding.addOnNewIntentListener(this);
        registerReceiver();
        /*
         * Ensure the microphone permission is enabled
         */
        if (!this.checkPermissionForMicrophone()) {
            this.requestPermissionForMicrophone();
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges()");

        this.activity = null;
        unregisterReceiver();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        this.activity = activityPluginBinding.getActivity();
        activityPluginBinding.addOnNewIntentListener(this);
        registerReceiver();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity()");

        unregisterReceiver();
        this.activity = null;
    }

    private Call.Listener callListener() {
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

                HashMap<String, Object> params = callToParams (call);
                params.put("event", CallState.ringing.name());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onConnectFailure(Call call, CallException error) {
                audioSwitch.deactivate();
                Log.d(TAG, "Connect failure");
                String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);

                HashMap<String, Object> params = callToParams (call);
                params.put("event", CallState.connect_failed.name());
                if (error != null)
                    params.put("error", error.getLocalizedMessage());
                sendPhoneCallEvents(params);
                resetActiveInviteCount();
            }

            @Override
            public void onConnected(Call call) {

                audioSwitch.activate();
                queryAndSendAudioDeviceInfo();

                Log.d(TAG, "Connected");
                activeCall = call;

                HashMap<String, Object> params = callToParams (call);
                params.put("event", CallState.connected.name());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.d(TAG, "onReconnecting");
                HashMap<String, Object> params = callToParams (call);
                params.put("event", CallState.reconnecting.name());
                if (callException != null)
                    params.put("error", callException.getLocalizedMessage());
                sendPhoneCallEvents(params);
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.d(TAG, "onReconnected");

                HashMap<String, Object> params = callToParams (call);
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

                HashMap<String, Object> params = callToParams (call);
                params.put("event", CallState.call_ended.name());
                if (error != null)
                    params.put("error", error.getLocalizedMessage());

                sendPhoneCallEvents(params);
                outgoingFromNumber = null;
                outgoingToNumber = null;
                activeCallInvite = null;
                resetActiveInviteCount();
            }
        };

    }

    private void disconnect() {
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

    private void hold() {
        if (activeCall != null) {
            boolean hold = activeCall.isOnHold();
            activeCall.hold(!hold);

            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", hold ? CallState.unhold.name() : CallState.hold.name());
            sendPhoneCallEvents(params);
        }
    }

    private void mute() {
        if (activeCall != null) {
            boolean mute = activeCall.isMuted();
            activeCall.mute(!mute);

            final HashMap<String, Object> params = new HashMap<>();
            params.put("event", mute ? CallState.unmute.name() : CallState.mute.name());
            sendPhoneCallEvents(params);
        }
    }

    private boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(this.context, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this.activity, Manifest.permission.RECORD_AUDIO)) {

        } else {
            ActivityCompat.requestPermissions(this.activity,
              new String[]{Manifest.permission.RECORD_AUDIO},
              MIC_PERMISSION_REQUEST_CODE);
        }
    }

    private void sendPhoneCallEvents(HashMap<String, Object> params) {
        if (eventSink == null)
            return;

        eventSink.success(params);
    }

    private HashMap<String, Object> callToParams (Call call) {

        final HashMap<String, Object> params = new HashMap<>();

        String from = "";
        String to = "";

        if (call.getFrom() != null)
            from = call.getFrom();
        else if (callOutgoing)
            from = outgoingFromNumber;

        if (call.getTo() != null)
            to = call.getTo();
        else if (callOutgoing)
            to = outgoingToNumber;

        if (from != null)
            params.put("from", from);
        if (to != null)
            params.put("to", to);

        if (call.getSid() != null)
            params.put("sid", call.getSid());

        params.put("direction", (callOutgoing ? CallDirection.outgoing.name() : CallDirection.incoming.name()));

        return params;
    }

    private void startAudioSwitchListener() {
        // This is a callback that is called any time the audio devices change.
        audioSwitch.start((audioDevices, selectedDevice) -> {
            Log.i(TAG, "Audio Device Changed. New Device: " + selectedDevice);
            sendAudioDeviceInfo(audioDevices, selectedDevice);

            return Unit.INSTANCE;
        });
    }

    private void queryAndSendAudioDeviceInfo() {
        AudioDevice selectedDevice = audioSwitch.getSelectedAudioDevice();
        List<AudioDevice> audioDevices = audioSwitch.getAvailableAudioDevices();

        sendAudioDeviceInfo(audioDevices, selectedDevice);
    }

    private void sendAudioDeviceInfo(List<? extends AudioDevice> audioDevices, AudioDevice selectedDevice) {
        boolean hasBluetooth = false;
        for (AudioDevice a : audioDevices) {
            if (a instanceof AudioDevice.BluetoothHeadset) {
                hasBluetooth = true;
                break;
            }
        }
        // Send event to client to let it know of the new audio status
        final HashMap<String, Object> params = new HashMap<>();
        params.put("event",  CallState.audio_route_change.name());
        params.put("bluetooth_available",  hasBluetooth);
        params.put("speaker_on",  selectedDevice instanceof AudioDevice.Speakerphone);
        params.put("devices",  audioDevicesToJSON(audioDevices, selectedDevice));

        Log.i(TAG, "Audio device changed, params: " + params);
        sendPhoneCallEvents(params);
    }

    private List<HashMap<String, Object>> audioDevicesToJSON(List<? extends AudioDevice> audioDevices, AudioDevice selectedDevice) {

        // Send event to client to let it know of the new audio status
        final ArrayList audioDevicesList = new ArrayList();

        if (audioDevices != null) {
            for (AudioDevice audioDevice : audioDevices) {

                final HashMap<String, Object> audioDeviceJSON = new HashMap<>();
                audioDeviceJSON.put("id",  getAudioDeviceID(audioDevice));
                audioDeviceJSON.put("name",  audioDevice.getName());
                audioDeviceJSON.put("selected",  selectedDevice != null ? audioDevice.equals(selectedDevice) : false);
                audioDeviceJSON.put("type",  getAudioDeviceType(audioDevice));
                audioDevicesList.add(audioDeviceJSON);
            }
        }
        return audioDevicesList;
    }

    private String getAudioDeviceType(AudioDevice audioDevice) {
        if (audioDevice instanceof AudioDevice.BluetoothHeadset) {
            return "bluetooth";
        } else if (audioDevice instanceof AudioDevice.WiredHeadset) {
            return "wired_headset";
        } else if (audioDevice instanceof AudioDevice.Earpiece) {
            return "earpiece";
        } else if (audioDevice instanceof AudioDevice.Speakerphone) {
            return "speaker";
        }
        return null;
    }

    // NOTE: Currently the device ID is the same as the device name,
    //       however, by using this method if that changes all we have
    //       to do is modify this one function.
    private String getAudioDeviceID(AudioDevice audioDevice) {
        return audioDevice.getName();
    }

    private void toggleSpeaker(boolean speakerOn) {
        List<AudioDevice> availableAudioDevices = audioSwitch.getAvailableAudioDevices();
        AudioDevice currentDevice = audioSwitch.getSelectedAudioDevice();

        for (AudioDevice a : availableAudioDevices) {
            String type = getAudioDeviceType(a);
            if ((type == "speaker" && speakerOn) || (type == "earpiece" && !speakerOn)) {
                Log.i(TAG, "Switching from " + currentDevice.getName() + " to " + a.getName());
                audioSwitch.selectDevice(a);
                break;
            }
        }
    }

    private void selectAudioDevice(String deviceID) {
        List<AudioDevice> availableAudioDevices = audioSwitch.getAvailableAudioDevices();
        AudioDevice currentDevice = audioSwitch.getSelectedAudioDevice();

        // Look for device with selected ID
        for (AudioDevice a : availableAudioDevices) {
            String id = getAudioDeviceID(a);
            if (id.equals(deviceID)) {
                Log.i(TAG, "Switching from " + currentDevice.getName() + " to " + a.getName());
                audioSwitch.selectDevice(a);
                break;
            }
        }
    }

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
