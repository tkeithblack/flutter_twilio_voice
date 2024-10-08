package com.dormmom.flutter_twilio_voice;

import android.Manifest;
import android.app.Activity;
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

        twSingleton().displayScreenIfUnderKeylock(this);

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

    private static final int REQUEST_CODE_MANAGE_CALLS = 1001; // You can use any unique number

    private void checkPermissionsAndAccept(){
        Log.d(TAG, "Clicked accept");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.MANAGE_OWN_CALLS}, REQUEST_CODE_MANAGE_CALLS);
            }
        }

        checkBluetoothPermissions();
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

    private void checkBluetoothPermissions() {
        // If device is Android 12 (API level 31) or higher, then we must check/ask for bluetooth permission.
        // Otherwise, return as no aciton is required.
        if (Build.VERSION.SDK_INT < 31)
            return;

        String[] PERMISSIONS_BLUETOOTH = {
            Manifest.permission.BLUETOOTH_CONNECT,
        };

        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_BLUETOOTH,
                    1
            );
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
    }
}