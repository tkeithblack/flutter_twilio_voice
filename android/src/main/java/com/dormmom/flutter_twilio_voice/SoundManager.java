package com.dormmom.flutter_twilio_voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
// TODO: Uncomment after skd 31
//import android.os.VibratorManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import static android.content.Context.AUDIO_SERVICE;

public class SoundManager {

    private float volume;
    private int vibrateMode;
    private static SoundManager instance;
    private Ringtone ringTone;
    private Context appContext;
    private  Vibrator vibrator;

    static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};
    private static final String TAG = SoundManager.class.getSimpleName();

    private SoundManager(Context context) {
        // AudioManager audio settings for adjusting the volume
        AudioManager audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        volume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
        appContext = context;

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            // TODO: Uncomment the following once supporting sdk 31
            if (BuildConfig.DEBUG) {
                throw new AssertionError("Need to uncomment code, we're running sdk 31 or greater!");
            }
//            VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
//            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            // backward compatibility for Android API < 31,
            // VibratorManager was only added on API level 31 release.
            // noinspection deprecation
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    public static SoundManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundManager(context);
        }
        return instance;
    }

    public void playRinging() {
        Log.d(TAG, "SoundManager.playRinging()");
        Uri ringToneUri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE);
        ringTone = RingtoneManager.getRingtone(appContext, ringToneUri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringTone.setLooping(true);
            ringTone.setVolume(volume);
        }
        if (ringTone != null && !ringTone.isPlaying()) {
            ringTone.play();
            startVibrator();
        }
    }

    public void stopRinging() {
        Log.d(TAG, "SoundManager.stopRinging()");
        stopVibrator();
        if (ringTone != null && ringTone.isPlaying()) {
            ringTone.stop();
            ringTone = null;
        }
    }

    public void startVibrator() {
        if (shouldVibrate()) {
            Log.d(TAG, "START Vibrator");

            // TODO: Load the vibration pattern from Android settings.
            // Could not find an API to get this, need to research more.
            long[] pattern = DEFAULT_VIBRATE_PATTERN;

            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }
    }

    public void stopVibrator() {
        Log.d(TAG, "STOP Vibrator called");
        if (vibrator != null) {
            Log.d(TAG, "STOPPING Vibrator");
            vibrator.cancel();
        }
    }

    boolean shouldVibrate() {
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if (Settings.System.getInt(appContext.getContentResolver(), "vibrate_when_ringing", 0) > 0) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    public void release() {
        ringTone = null;
        instance = null;
    }
}
