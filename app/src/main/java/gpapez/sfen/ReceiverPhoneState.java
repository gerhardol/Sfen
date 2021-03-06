package gpapez.sfen;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.os.PowerManager;
import android.os.Vibrator;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.gson.Gson;

import java.util.Calendar;

/**
 * Created by Gregor on 30.7.2014.
 */
public class ReceiverPhoneState extends PhoneStateListener {

    /**
     * phone riniging phone state
     * @param state
     * @param incomingNumber
     */
    public void onCallStateChanged (int state, String incomingNumber) {
        /**
         * callstatechanged VARIABLES
         */
        Vibrator v;
        Ringtone ringtone;

        /**
         * retrieve current profile
         *
         * if there is none, skip whole process
         */
        Profile profile = Profile.getActiveProfile();

        if (profile == null)
            return ;


        /**
         * get audiomanager object
         */
        AudioManager audioManager = (AudioManager)BackgroundService
                .getInstance().getSystemService(Context.AUDIO_SERVICE);


        switch (state) {

            /*
            States:
            TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.CALL_STATE_OFFHOOK
            */

            /**
             * Device call state: Off-hook. At least one call exists that is dialing, active,
             * or on hold, and no calls are ringing or waiting
             *
             * Here, we store setting in shared preferences that we're Offhook
             */
            case TelephonyManager.CALL_STATE_OFFHOOK:
                Preferences.getSharedPreferences().edit().putBoolean(
                        "CALL_STATE_OFFHOOK",
                        true).apply();


                break;

            /**
             * FINISHED TALKING!
             *
             * Its idle time, we don't have any calls active. Set CALL_STATE_HOOK
             * in preferences to FALSE
             */
            case TelephonyManager.CALL_STATE_IDLE:

                Preferences.getSharedPreferences().edit().putBoolean(
                        "CALL_STATE_OFFHOOK",
                        false).apply();

                /**
                 * since we're not on the phone anymore, change default sound volume to the one
                 * set at notification.
                */
                audioManager.setStreamVolume(
                        AudioManager.STREAM_RING,
                        profile.getVolumeNotification(),
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);

                /**
                 * set ringer mode first, depending on our profile decisions
                 */
                if (profile.getVolumeNotification() == 0 && !profile.isVibrate()) {
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                }

                else if (profile.getVolumeNotification() == 0 && profile.isVibrate()) {
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                }
                else {
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                }



                break;

            /**
             * RRRRRRRRRRRRIIIIIIIIIIIIIINNNNNNNNNNNNGGGGGGGGGGGGG
             *
             * when phone is ringing, check our currently active profile and quickly set
             * desired options!
             */
            case TelephonyManager.CALL_STATE_RINGING:

                //System.out.println("call from number "+ incomingNumber);
                // 031123456

                /**
                 * if we already have another call active, stop executing actions
                 */
                if (Preferences.getSharedPreferences().getBoolean("CALL_STATE_OFFHOOK", false)) {
                    break ;
                }

                /**
                 * set ringer mode first, depending on our profile decisions
                 */
                if (profile.getVolumeRingtone() == 0 && !profile.isVibrate())
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);

                else if (profile.getVolumeRingtone() == 0 && profile.isVibrate())
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

                else
                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);


                /**
                 * loudness setting
                 */
                int loudness = profile.getVolumeRingtone();

                /**
                 * Number on Allow / Deny list?
                 */
                if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL &&
                        CallAllowDeny.isNumberOnAllowOrDenyList(incomingNumber, CallAllowDeny.TYPE.DENY)
                        ) {

//                    System.out.println(incomingNumber +" is on DENY list.");

                    audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    loudness = 0;

                }

                else if ((audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT ||
                        audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) &&
                        CallAllowDeny.isNumberOnAllowOrDenyList(incomingNumber, CallAllowDeny.TYPE.ALLOW)
                        ) {

//                    System.out.println(incomingNumber +" is on ALLOW list.");

                    audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    loudness = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);

                }


                /**
                 * set loudness-
                 */
                audioManager.setStreamVolume(
                        AudioManager.STREAM_RING,
                        loudness,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);


                break;
        }

    }


    public void onCellLocationChanged(CellLocation cellLocation){
        super.onCellLocationChanged(cellLocation);

        /**
         *
         * if new location doesn't throw any errors, continue, otherwise, stop actions
         *
         */

        CellConnectionInfo cellInfo = new CellConnectionInfo(Main.getInstance());

        Log.d("sfen", "Got mobile cell: " + cellInfo.getCellId()+ ":" + cellInfo.isError()+ ":" + cellInfo.getError());

        // when we change cells, we have to wake up to send broadcast
        // TBD: Is wakelock required to log?
        PowerManager pm = (PowerManager) BackgroundService.getInstance()
                .getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sfen");
        mWakeLock.acquire();

        Cell.recordCellId(cellInfo);

        if (cellInfo.isError()) {
            Log.d("sfen", "No mobile: "+ cellInfo.getError());
        }
        else {

        /**
         * send broadcast when CELL LOCATION CHANGES
         */
        BackgroundService.getInstance().sendBroadcast("CELL_LOCATION_CHANGED");
        }

        // close down the wakelock
        mWakeLock.release();

    }

}
