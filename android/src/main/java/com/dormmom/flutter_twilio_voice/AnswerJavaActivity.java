package com.dormmom.flutter_twilio_voice;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;


public class AnswerJavaActivity extends AppCompatActivity {

    private static String TAG = "AnswerActivity";
    public static final String TwilioPreferences = "mx.TwilioPreferences";
    TwilioSingleton twSingleton() {
        return TwilioSingleton.getInstance(getApplicationContext());
    }
    private boolean isReceiverRegistered = false;
    AnswerScreenReceiver answerScreenReceiver;

    private CallInvite activeCallInvite;
    private int activeCallNotificationId;
    private static final int MIC_PERMISSION_REQUEST_CODE = 17893;
    private PowerManager.WakeLock wakeLock;
    private TextView tvUserName;
    private TextView tvCallStatus;
    private ImageView btnAnswer;
    private ImageView btnReject;
    private AppCompatActivity activity;

    @Override
    protected void finalize() throws Throwable {
        unregisterReceiver();
        super.finalize();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        registerReceiver();

        setContentView(R.layout.activity_answer);
        activity = this;

        tvUserName = (TextView) findViewById(R.id.tvCallerId);
        tvCallStatus = (TextView) findViewById(R.id.tvCallStatus);
        btnAnswer = (ImageView) findViewById(R.id.btnAnswer);
        btnReject = (ImageView) findViewById(R.id.btnReject);

        KeyguardManager kgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isKeyguardUp = kgm.inKeyguardRestrictedInputMode();
        Log.d(TAG, "isKeyguardUp = " + isKeyguardUp);

        if (isKeyguardUp) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
                kgm.requestDismissKeyguard(this, null);
            } else {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
                wakeLock.acquire(60 * 1000L /*10 minutes*/);

                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
            }

        }
        handleIncomingCallIntent(getIntent());
    }

    private void registerReceiver() {
        if (!isReceiverRegistered) {
            answerScreenReceiver = new AnswerScreenReceiver(this);
            Log.d(TAG, "registerReceiver");
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    answerScreenReceiver, intentFilter);
            isReceiverRegistered = true;
        }
    }

    private void unregisterReceiver() {
        Log.d(TAG, "UN-registerReceiver");
        if (isReceiverRegistered) {
            twSingleton().unregisterPlugin();
            LocalBroadcastManager.getInstance(this).unregisterReceiver(answerScreenReceiver);
            isReceiverRegistered = false;
            answerScreenReceiver = null;
        }
    }

    private static class AnswerScreenReceiver extends BroadcastReceiver {

        private final AnswerJavaActivity answerPage;

        private AnswerScreenReceiver(AnswerJavaActivity answerPage) {
            this.answerPage = answerPage;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast for action " + action);

            if (action != null)
                switch (action) {
                    case Constants.ACTION_CANCEL_CALL:
                        answerPage.newCancelCallClickListener(intent);
                        break;
                    default:
                        Log.d(TAG, "Received broadcast for other action " + action);
                        break;
                }
        }
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            Log.d(TAG, "handleIncomingCallIntent-");
            String action = intent.getAction();
            activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            tvCallStatus.setText(R.string.incoming_call_title);
            Log.d(TAG, action);
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                case Constants.ACTION_INCOMING_CALL_NOTIFICATION:
                    configCallUI();
                    break;
                case Constants.ACTION_CANCEL_CALL:
                    newCancelCallClickListener(intent);
                    break;
                case Constants.ACTION_ACCEPT:
                    checkPermissionsAndAccept();
                    break;
//                case Constants.ACTION_REJECT:
//                    newCancelCallClickListener();
//                    break;
                default: {
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getAction() != null) {
            Log.d(TAG, "onNewIntent-");
            Log.d(TAG, intent.getAction());
            switch (intent.getAction()) {
                case Constants.ACTION_CANCEL_CALL:
                    newCancelCallClickListener(intent);
                    break;
                default: {
                }
            }
        }
    }

    private void configCallUI() {
        Log.d(TAG, "configCallUI");
        if (activeCallInvite != null) {

            String fromId = twSingleton().getCallerId(activeCallInvite);
            String lineName = TwilioSingleton.getLineName(activeCallInvite);

            if (fromId != null && !fromId.isEmpty())
                tvUserName.setText(fromId);

            if (lineName != null && !lineName.isEmpty())
                tvCallStatus.setText(lineName);

            btnAnswer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "onCLick");
//                    answer(activeCallInvite);
                    checkPermissionsAndAccept();
                }
            });

            btnReject.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rejectCallClickListener();
                }
            });
        }
    }

    private void checkPermissionsAndAccept(){
        Log.d(TAG, "Clicked accept");
        if (!checkPermissionForMicrophone()) {
            Log.d(TAG, "requestAudioPermissions");
            requestAudioPermissions();
        } else {
            Log.d(TAG, "newAnswerCallClickListener");
            acceptCall();
        }
    }

    private void answer(CallInvite callInvite) {
        Log.d(TAG, "Answering call");
        SoundManager.getInstance(getApplicationContext()).stopRinging();
        if (callInvite != null) {
            callInvite.accept(getApplicationContext(), twSingleton().getCallListener());
        }
    }


    private void acceptCall() {
        Log.d(TAG, "Accepting call");
        Intent acceptIntent = new Intent(this, IncomingCallNotificationService.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, activeCallInvite);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, activeCallNotificationId);
        Log.d(TAG, "Clicked accept startService");
        startService(acceptIntent);
        finishAndRemoveTask();
    }

    private void newCancelCallClickListener(Intent intent) {
        Log.d(TAG, "Canceled call");
        finishAndRemoveTask();
    }

    private void rejectCallClickListener() {
        Log.d(TAG, "Reject Call Click listener");
        twSingleton().decrementActiveInviteCount();
        if (activeCallInvite != null) {
            Intent rejectIntent = new Intent(this, IncomingCallNotificationService.class);
            rejectIntent.setAction(Constants.ACTION_REJECT);
            rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, activeCallInvite);
            startService(rejectIntent);
            finishAndRemoveTask();
        }
    }

    private Boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermissions() {
        String[] permissions = {Manifest.permission.RECORD_AUDIO};
        Log.d(TAG, "requestAudioPermissions");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, permissions, MIC_PERMISSION_REQUEST_CODE);
            } else {
                ActivityCompat.requestPermissions(this, permissions, MIC_PERMISSION_REQUEST_CODE);
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestAudioPermissions-> permission granted->newAnswerCallClickListener");
            acceptCall();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == MIC_PERMISSION_REQUEST_CODE) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permissions needed. Please allow in your application settings.", Toast.LENGTH_LONG).show();
                rejectCallClickListener();
            } else {
                acceptCall();
            }
        } else {
            throw new IllegalStateException("Unexpected value: " + requestCode);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null) {
            wakeLock.release();
        }
    }
}