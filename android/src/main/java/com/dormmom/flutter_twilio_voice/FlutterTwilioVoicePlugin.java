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
import androidx.appcompat.app.AppCompatActivity;
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

    static AudioSwitch audioSwitch;

    private static final String CHANNEL_NAME = "flutter_twilio_voice";
    private static final String TAG = "TwilioVoicePlugin";
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;

    private String accessToken;
    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    private NotificationManager notificationManager;
    CallInvite activeCallInvite;
    private int activeInviteCount = 0; // Used for handling dup call invites.
    private Call activeCall;
    private int activeCallNotificationId;
    private Context context;
    private Activity activity;

    RegistrationListener registrationListener = registrationListener();
    UnregistrationListener unregistrationListener = unregistrationListener();
    Call.Listener callListener = new CallListener().callListener();
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private EventChannel.EventSink eventSink;
    private String fcmToken;
    private boolean callOutgoing;

    String outgoingFromNumber;
    String outgoingToNumber;

    protected void finalize ()
    {
        CallListener.unregisterPlugin();
    }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding flutterPluginBinding) {
        Log.i(TAG, "onAttachedToEngine()");
        register(flutterPluginBinding.getFlutterEngine().getDartExecutor(), this, flutterPluginBinding.getApplicationContext());

    }

    private static void register(BinaryMessenger messenger, FlutterTwilioVoicePlugin plugin, Context context) {
//        CallListener.registerPlugin(plugin);

        plugin.methodChannel = new MethodChannel(messenger, CHANNEL_NAME + "/messages");
        plugin.methodChannel.setMethodCallHandler(plugin);


        plugin.eventChannel = new EventChannel(messenger, CHANNEL_NAME + "/events");
        plugin.eventChannel.setStreamHandler(plugin);

        plugin.context = context;

        plugin.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        plugin.voiceBroadcastReceiver = new VoiceBroadcastReceiver(plugin);
//        plugin.audioSwitch = new AudioSwitch(context);

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

    private void handleIncomingCallIntent(Intent intent) {
       if (intent != null && intent.getAction() != null) {
           String action = intent.getAction();
           Log.d(TAG, "==============>> Handling incoming call intent for action " + action);
            callOutgoing = false;

            switch (action) {
            case Constants.ACTION_INCOMING_CALL:
                int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                handleIncomingCall(callInvite, notificationId, false);
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
//            case Constants.ACTION_ANSWERED:
//                activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
//                activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
//                answer();
//                break;
            case Constants.ACTION_DECLINED:
                activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
                activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                reject(true);
                break;
            case Constants.ACTION_APP_TO_FOREGROUND:
                showWhenInBackground();
                break;
            default:
                Log.e(TAG, "handleIncomingCallIntent action NOT handled. " + action);
                break;
            }
       }
    }

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

    private void handleIncomingCall(@NonNull CallInvite callInvite, int notificationId, boolean replay) {

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
//            showWhenInBackground();

            HashMap<String, Object> params = paramsFromCallInvite(callInvite, CallState.call_invite);
            if (replay) {
                params.put("replay", true);
            } else {
                SoundManager.getInstance(context).playRinging();
            }
            sendPhoneCallEvents(params);
        } else {
            Log.d(TAG, "Skipping callInvite, already handling another invite. New Invite:" + callInvite);
        }
    }

    void handleCallConnect(Call call) {
        Log.d(TAG, "Connected");
        activeCall = call;

        HashMap<String, Object> params = callToParams (call);
        params.put("event", CallState.connected.name());
        sendPhoneCallEvents(params);
    }

    private HashMap<String, Object> paramsFromCallInvite(CallInvite callInvite, CallState event) {

        final HashMap<String, Object> params = new HashMap<>();
        params.put("event", event.name());
        params.put("from", callInvite.getFrom());
        params.put("to", callInvite.getTo());
        params.put("sid", callInvite.getCallSid());
        params.put("direction", CallDirection.incoming.name());

        if (IncomingCallNotificationService.pluginDisplayedAnswerScreen) {
            // This parameter lets the app know the incoming call screen is handled.
            params.put("pluginDisplayedAnswerScreen", true);
        }

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
        if (activity == null) return;

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
            CallListener.registerPlugin(this);

            Log.d(TAG, "registerReceiver");
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_CALLINVITE);
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL);
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            intentFilter.addAction(Constants.ACTION_FCM_TOKEN);
            intentFilter.addAction(Constants.ACTION_ANSWERED);
            intentFilter.addAction(Constants.ACTION_DECLINED);
                LocalBroadcastManager.getInstance(this.activity).registerReceiver(
              voiceBroadcastReceiver, intentFilter);
            isReceiverRegistered = true;

            startAudioSwitchListener();
        }
    }

    private void unregisterReceiver() {
        Log.d(TAG, "UN-registerReceiver");
        if (isReceiverRegistered) {
            CallListener.unregisterPlugin();
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

            if (action != null)
                switch (action) {
                    case Constants.ACTION_INCOMING_CALL:
                    case Constants.ACTION_CANCEL_CALL:
                    case Constants.ACTION_REJECT:
                    case Constants.ACTION_ACCEPT:
                    case Constants.ACTION_ANSWERED:
                    case Constants.ACTION_DECLINED:

                        /*
                         * Handle the incoming or cancelled call invite
                         */
                        plugin.handleIncomingCallIntent(intent);
                        break;
                    default:
                        Log.d(TAG, "Received broadcast for other action " + action);
                        break;

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

            connected : Bool {
                get {
                    if let call = self.call, call.state == TVOCallState.connected {
                        return true
                    }
                    return false
                }
            }
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
            this.reject(false);
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
        } else if (call.method.equals("replayCallConnection")) {
            // This method was created for situations where the Flutter App does not
            // receive the inital CallInvite. This can occur if the app is in the background
            // a Notification window answers the call.
            //
            // When the replayed callInvite is sent an extra parameter will be set 'replay: true'
            if (activeCallInvite != null && activeCall != null) {
                handleIncomingCall(activeCallInvite, activeCallNotificationId , true);
                handleCallConnect(activeCall);
                result.success(true);
                return;
            }
            result.success(false);
        }
        else {
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

    /// This reject function is called when the user clicks the decline button in the Flutter interface.
    private void reject(boolean calledFromNotificationPopup) {
        if (activeCallInvite != null) {
            HashMap<String, Object> params = paramsFromCallInvite(activeCallInvite, CallState.call_reject);
            sendPhoneCallEvents(params);

            /// If reject is called when the user clicks the decline button from a popup notification
            /// then we will not call activeCallInvite.reject() here as it is done in the  Notification popup.
            /// This is done because we might get a call with the app closed or in the background.
            /// In such case this reject funciton will not be called as the app is not in memory.
            /// However, if the reject call comes from the Flutter app then we DO need to call reject().
            if (!calledFromNotificationPopup) {
                SoundManager.getInstance(context).stopRinging();
                activeCallInvite.reject(context);
            }
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

                handleCallConnect(call);
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

    void sendPhoneCallEvents(HashMap<String, Object> params) {
        if (eventSink == null)
            return;

        eventSink.success(params);
    }

    HashMap<String, Object> callToParams (Call call) {

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
        audioSwitch = new AudioSwitch(context);

        // This is a callback that is called any time the audio devices change.
        audioSwitch.start((audioDevices, selectedDevice) -> {
            Log.i(TAG, "Audio Device Changed. New Device: " + selectedDevice);
            sendAudioDeviceInfo(audioDevices, selectedDevice);

            return Unit.INSTANCE;
        });
    }

    void queryAndSendAudioDeviceInfo() {
        if (audioSwitch == null) return;

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
        if (audioSwitch == null) return;

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
        if (audioSwitch == null) return;

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
        if (audioSwitch == null) return;

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
