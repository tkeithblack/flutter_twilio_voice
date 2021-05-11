package com.dormmom.flutter_twilio_voice;

import androidx.annotation.NonNull;

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

public class CallListener {

    private static FlutterTwilioVoicePlugin twilioPlugin = null;

    private final String TAG = "TwilioCallListener";
    public static void registerPlugin(FlutterTwilioVoicePlugin plugin) {
        twilioPlugin = plugin;
    }

    public static void unregisterPlugin() {
        twilioPlugin = null;
    }

    public Call.Listener callListener() {
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
            public void onConnectFailure(Call call, CallException error) {
                if (twilioPlugin != null)
                    twilioPlugin.audioSwitch.deactivate();

                Log.d(TAG, "Connect failure");
                String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);

                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.connect_failed.name());
                if (error != null)
                    params.put("error", error.getLocalizedMessage());
                sendPhoneCallEvents(params);
                if (twilioPlugin != null)
                    twilioPlugin.resetActiveInviteCount();
            }

            @Override
            public void onConnected(Call call) {

                if (twilioPlugin != null) {
                    twilioPlugin.audioSwitch.activate();
                    twilioPlugin.queryAndSendAudioDeviceInfo();
                    twilioPlugin.handleCallConnect(call);
                }
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.d(TAG, "onReconnecting");
                HashMap<String, Object> params = callToParams(call);
                params.put("event", CallState.reconnecting.name());
                if (callException != null)
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
                if (twilioPlugin != null)
                    twilioPlugin.audioSwitch.deactivate();
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
                if (twilioPlugin != null) {
                    twilioPlugin.outgoingFromNumber = null;
                    twilioPlugin.outgoingToNumber = null;
                    twilioPlugin.activeCallInvite = null;
                    twilioPlugin.resetActiveInviteCount();
                }
            }
        };
    }

    private static void sendPhoneCallEvents(HashMap<String, Object> params) {
        if (twilioPlugin != null)
            twilioPlugin.sendPhoneCallEvents(params);
    }

    private static HashMap<String, Object> callToParams(Call call) {
        if (twilioPlugin != null) {
            return twilioPlugin.callToParams(call);
        }
        return new HashMap<>();
    }

}

