package gpapez.sfen;

import android.app.AlarmManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.location.Geofence;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Created by Gregor on 10.7.2014.
 *
 * main background process service; this one
 * loads and runs when we press back or home button
 */
public class BackgroundService extends Service {

    /**
     *
     * VARIABLES
     *
     */


    /**
     * BackgroundService specific variables
     */
    // singleton variable
    private static BackgroundService sInstance;

    // intent variable
    protected static Intent sIntent;

    protected boolean isOneRunning = false;
    protected boolean isOneStopping = false;

    // variable will get updated from Receiver after disconnecting from Wifi
    protected String mLatestSSID = "";

    /**
     * event loop variable for when we want to run event as action. if we get into an eventloop,
     * we're screwed.
     */
    protected boolean mEventLoopInProgress = false;

    // mReceiver object which runs on start
    private Receiver mReceiver;
    protected String receiverAction = "";

    protected Util mUtil = new Util();

    // alarmmanager
    private Alarm mAlarm;
    private ArrayList<Alarm> mActiveAlarms = new ArrayList<Alarm>();

    // geofence init
    private GeoLocation mGeoLocation;
    protected List<Geofence> mTriggeredGeofences = new ArrayList<Geofence>();
    protected int mTriggeredGeoFenceTransition = -1;

    // telephony
    protected ReceiverPhoneState mPhoneReceiver;
    protected TelephonyManager mPhoneManager;

    // root manager
    protected Sudo mSudo;

    // notification
    protected Notification mNotification;

    /**
     * async task variable
     */
    AsyncTask<Void,Void,Void> mAsyncTask;

    /**
     * EVENTS, PROFILES
     */
    protected ArrayList<Event> events = new ArrayList<Event>();
    protected ArrayList<Profile> profiles = new ArrayList<Profile>();


    /**
     * event with all actions
     */
    protected Event eventToRun;

    // preferences object
    protected Preferences mPreferences;


    /***********************************************************************************************
     *
     * CONSTRUCTORS
     *
     ***********************************************************************************************
     */
    @Override
    public void onCreate() {
        sInstance = this;
    }

    /**
     * onStartCommand (INIT!)
     *
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        /**
         * singleton intent
         */
        sIntent = intent;

        /**
         * notification object has to be defined in background checker. even if app is closed,
         * notification iwthing background service will continue (until we close down
         * the applicaton through Exit menu)
         */
        mNotification = new Notification();


        /**
         * GETTING EVENTS from PREFERENCES
         */
        //mPreferences = new Preferences(Main.getInstance());
        if (Main.getInstance() == null) {
            System.out.println("Main class not loaded yet!");
            return 1;
        }

        mPreferences = new Preferences(Main.getInstance());


        if (mPreferences != null) {

            events = (ArrayList<Event>) mPreferences.getPreferences("events", Preferences.REQUEST_TYPE.EVENTS);

            if (events == null) {
                events = new ArrayList<Event>();
            }

            // first time run, set events to not running
            else {
                for (int i = 0; i < events.size(); i++) {
                    //System.out.println("set event as not running.");
                    events.get(i).setRunning(false);
                    events.get(i).setHasRun(false);
                }

            }


            profiles = (ArrayList<Profile>) mPreferences.getPreferences("profiles", Preferences.REQUEST_TYPE.PROFILES);

            if (profiles == null) {
                profiles = new ArrayList<Profile>();
            }

        }


        /**
         * alarm list variable
         */
        mActiveAlarms = new ArrayList<Alarm>();

        /**
         * sudo async class
         *
         */
        mSudo = new Sudo();

        // trigger sudo at start so we won't have problems later
        if (Preferences.getSharedPreferences().getBoolean("rootEnable", false)) {
            mSudo.isRootEnabled();
        }

        /**
         * GeoLocation library
         */
        mGeoLocation = new GeoLocation(sInstance);


        /**
         * mReceiver init
         */
        mReceiver = new Receiver();
        sInstance.registerReceiver(mReceiver, mReceiver.createIntentFilter());


        /**
         * phone state receiver
         */
        // start phone listener
        mPhoneManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneReceiver = new ReceiverPhoneState();

        mPhoneManager.listen(mPhoneReceiver,
                PhoneStateListener.LISTEN_CALL_STATE |
                PhoneStateListener.LISTEN_CELL_LOCATION
        );



        // also check for the first time and never again, for the condition triggers
        // refresh condition timers
        if (events.size() > 0)
            updateEventConditionTimers(events);

        /**
         *
         * if there are events or not, we have to update notification.
         *
         */
        mNotification.showNotification();

        /**
         * if event fragment is set, refresh view
         */
        if (Main.getInstance().fragmentEvent != null)
            Main.getInstance().fragmentEvent.refreshEventsView();



        /**
         * START_STICKY
         *
         * if this service's process is killed while it is started (after returning from
         * onStartCommand(Intent, int, int)), then leave it in the started state but don't
         * retain this delivered intent.
         */
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     *
     * DESTRUCTOR
     *
     */
    @Override
    public void onDestroy() {

        // unregister our receiver-- i we don't have receiver registered
        try {
            unregisterReceiver(mReceiver);
        }
        catch (IllegalArgumentException e) {
            Log.d("sfen", "Receiver didn't exist. No need to unregister.");
        }
        mReceiver = null;

        // cancel phone state listener
        if (mPhoneManager != null)
            mPhoneManager.listen(mPhoneReceiver,PhoneStateListener.LISTEN_NONE);


        // cancel alarms (if up)
        if (mActiveAlarms.size() > 0) {
            ArrayList<Alarm> mAlarmsDelete = new ArrayList<Alarm>();
            for (Alarm single : mActiveAlarms) {
                mAlarmsDelete.add(single);
            }
            //mActiveAlarms.removeAll(mAlarmsDelete);

            if (mAlarmsDelete.size() > 0) {
                //System.out.println("*** deleting "+ mAlarmsDelete.size() +" alarms.");
                for (Alarm single : mAlarmsDelete) {
                    // stop the alarm
                    single.RemoveAlarm();

                    // remove it from array of active alarms
                    mActiveAlarms.remove(single);
                }
            }

        }

        if (mActiveAlarms.size() == 0)
            Log.d("sfen", "All alarms removed.");

        // save new alarms to preferences
        //mPreferences.setPreferences("alarms", mActiveAlarms);

        // destroy all geofences
        if (mGeoLocation != null)
            mGeoLocation.RemoveGeofences(mGeoLocation.getTransitionPendingIntent());


        // clear notification
        mNotification.Destroy();


        // destroy superclass
        super.onDestroy();
    }


    /**
     * SINGLETON INSTANCE
     *
     * Singleton function that returns the current instance of our class
     * if it does not exist, it creates new instance.
     * @return instance of current class
     */
    public static BackgroundService getInstance() {
        if (sInstance == null) {
            return new BackgroundService();
        }
        else
            return sInstance;
    }


    /***********************************************************************************************
     *
     *
     * EVENT CHECKER!
     *
     * This function os the reason of our existence! It is this function which is going to check
     * if there is any event that can be triggered by eny broadcast!
     *
     ***********************************************************************************************
     */
    protected void EventFinder(Context context, Intent intent) {
        // loop through all events, check only the enabled and NON-delayed ones..

        // sort events by priority
        Collections.sort(events);
        eventToRun = new Event();

        for (Event e : events) {
            if (e.isEnabled() & !e.isDelayed() ) {

                /**
                 * any broadcast EXCEPT "EVENT_ENABLED" can re-trigger loading actions IF
                 * we told him to runOnce only.
                 */
                // event runs only once? then all broadcasts EXCEPT event_enable can re-run it
                boolean canContinueRunning = false;

                if (receiverAction.equals(getClass().getPackage().getName() +".EVENT_ENABLED")
                        && e.isRunOnce() && e.isHasRun() && !e.isForceRun())
                    canContinueRunning = false;
                else
                    canContinueRunning = true;


                if (e.areEventConditionsMet(context, intent, e) && canContinueRunning) {

                    isOneRunning = true;



                    /**
                     * if we have intent extra from alarm trigger as FORCE_RUN, set all events
                     * to force run!
                     */
                    if (intent.getAction().equals(getClass().getPackage().getName() + ".ALARM_TRIGGER") &&
                            intent.getStringExtra("ALARM_TRIGGER_EXTRA") != null &&
                            intent.getStringExtra("ALARM_TRIGGER_EXTRA").equals("FORCE_RUN")) {
                        Log.d("sfen", "Force running events after ending time-range...");
                        e.setForceRun(true);
                        e.setRunning(false);
                    }


                    // wow. conditions are met! you know what that means?
                    // we trigger actions!
                    runEvent(context, intent, e);

                }
                // conditions aren't met; switch event to not running (if maybe they were)
                else {
                    e.setRunning(false);

                }
            }
            // maybe it isn't enabled
            // maybe it did run already
            // or something else o_O
            else {
                e.setRunning(false);

                /**
                 * delayed event has to initialize here
                 */
                if (e.isDelayed() && e.isEnabled()) {
                    runEventDelayedInit(context, intent, e);
                }
            }
        }

        // if there's no events running OR events stopping, clear notification
        if ((!isOneRunning && isOneStopping) || (!isOneRunning && !isOneStopping)) {
            Log.d("sfen", "no events running.");
        }

        /**
         * start actions from running event!
         */
        else {

            if (eventToRun != null) {

                //runEvent(context, intent, eventToRun);
                startSingleEvent(eventToRun);

                if (eventToRun.getProfile() != null) {
                    Log.d("sfen", "Running profile: "+ eventToRun.getProfile().getName());
                }

                if (eventToRun.getActions().size() > 0) {
                    Log.d("sfen", "Running next actions:");

                    for (DialogOptions single : eventToRun.getActions()) {

                        Log.d("sfen", "> " + single.getOptionType());

                    }

                }


                eventToRun = null;
            }

        }

        // clear all variables
        isOneRunning = false;
        isOneStopping = false;
        mTriggeredGeoFenceTransition = -1;
        mTriggeredGeofences = null;


        // if we have main activity window open, refresh them
        if (Main.getInstance().isVisible && Main.getInstance().mTabPosition == 0) {
            //Main.getInstance().refreshEventsView();
            Main.getInstance().refreshCurrentView();
        }

        /**
         * if we have data to show or not, we are going to show notification now and we're
         * going to win this. o_O
         */
        mNotification.showNotification();

    }


    /***********************************************************************************************
     *
     * RUN ACTIONS
     *
     ***********************************************************************************************
     */
    protected void runActions(ArrayList<DialogOptions> actions) {


        WifiManager wifiManager;
        ConnectivityManager conMan;

        // loop through all actions and run them
        for (DialogOptions act : actions) {
            Log.i("sfen", "action: "+ act.getTitle() +"("+ act.getOptionType() +")");

            switch (act.getOptionType()) {

                // popup notification!
                case ACT_NOTIFICATION:
                    //mUtil.showNotification(sInstance, "Sfen - "+ e.getName(), e.getName(), R.drawable.ic_launcher);
                    //mNotification.saveData(p.getName(), p.getName(), p.getIcon());

                    break;

                // enable or disable wifi connection
                case ACT_WIFIENABLE:

                    wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

                    if (!wifiManager.isWifiEnabled())
                        wifiManager.setWifiEnabled(true);

                    break;

                case ACT_WIFIDISABLE:

                    wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);

                    if (wifiManager.isWifiEnabled())
                        wifiManager.setWifiEnabled(false);

                    break;

                // enable or disable data connection
                // could result in exceptions if user doesn't have root privileges
                case ACT_MOBILEENABLE:

                    conMan = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

                    //NetworkInfo.State mobile = conMan.getNetworkInfo(0).getState();

                    // if not connected yet, meaning enable it now!
                    //if (conMan.getNetworkInfo(0).getState() != NetworkInfo.State.CONNECTED) {

                    // continue only if we have root
                    //mUtil.callRootCommand("svc data enable");
                    mSudo.callRootCommand("svc data enable");

                    //}


                    break;

                case ACT_MOBILEDISABLE:

                    // opposite of ACT_MOBILEENABLE
                    mSudo.callRootCommand("svc data disable");

                    break;

                case ACT_VIBRATE:

                    Vibrator v = (Vibrator) sInstance.getSystemService(Context.VIBRATOR_SERVICE);

                    // if our device doesn't have a vibrator, why continue?
                    if (!v.hasVibrator()) {
                        Log.e("sfen", "Phone doesn't have a vibrator.");
                        break;
                    }


                    // retrieve vibration type
                    String vibType = act.getSetting("vibrationtype");

                    // create pattern for vibration depending on vibration type
                    // parameters for vibPattern
                    // delay on start,
                    // milisec vibrate
                    // sleep for
                    long[] vibPattern = {0, 100, 100};
                    if (vibType.equals("Short")) {
                        vibPattern = new long[]{0, 100, 100};
                    }
                    else if (vibType.equals("Medium")) {
                        vibPattern = new long[]{0, 300, 100};
                    }
                    else if (vibType.equals("Long")) {
                        vibPattern = new long[]{0, 500, 100};
                    }


                    // The '-1' here means to vibrate once
                    // '0' would make the pattern vibrate indefinitely
                    // 3 would mean pattern run 3 times.
                    v.vibrate(vibPattern, -1);


                    break;

                case ACT_DIALOGWITHTEXT:

                    // get text from settings
                    String mText = act.getSetting("text");

                    // replace occurences of strings with real parameters
                    mText = mUtil.replaceTextPatterns(mText);

                    final WindowManager manager =
                            (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
                    layoutParams.gravity = Gravity.CENTER;
                    layoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
                    layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    //layoutParams.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
                    //layoutParams.screenBrightness = 0.9f;

                    final View newView = View.inflate(sInstance.getApplicationContext(), R.layout.dialog_windowmanager, null);

                    Button okButton = (Button) newView.findViewById(R.id.wm_button);
                    TextView info = (TextView) newView.findViewById(R.id.wm_text);
                    info.setText(mText);

                    okButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            manager.removeView(newView);
                        }
                    });

                    manager.addView(newView, layoutParams);

                    // show dialog since we have text and what not
                    /*
                    final AlertDialog.Builder builder = new AlertDialog.Builder(Main.getInstance());

                    builder.setIcon(R.drawable.ic_launcher);
                    builder.setTitle("Sfen!");
                    builder.setMessage(mText);
                    builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.dismiss();
                        }
                    });

                    builder.show();
                    */


                    break;

                case ACT_PLAYSOUND:
                    break;
                case ACT_OPENAPPLICATION:

                    String packageName = act.getSetting("packagename");

                    PackageManager pm = getPackageManager();
                    // open app
                    //Intent appIntent = new Intent(Intent.ACTION_MAIN);
                    //appIntent.setClassName("com.android.settings", "com.android.settings.Settings");
                    final Intent appIntent = pm.getLaunchIntentForPackage(packageName);
                    if (appIntent != null) {

                        mAsyncTask = new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... voids) {
                                startActivity(appIntent);
                                return null;
                            }
                        }.execute();


                        //startActivity(appIntent);
                    }


                    break;

                case ACT_OPENSHORTCUT:

                    /**
                     * get Intent from saved setting
                     *
                     * http://stackoverflow.com/questions/22533432/create-object-from-gson-string-doesnt-work
                     */
//                    class UriDeserializer implements JsonDeserializer<Uri> {
//                        @Override
//                        public Uri deserialize(final JsonElement src, final Type srcType,
//                                               final JsonDeserializationContext context) throws JsonParseException {
//                            return Uri.parse(src.getAsString());
//                        }
//                    }
//
//                    Gson gsonIntent = new GsonBuilder()
//                            .registerTypeAdapter(Uri.class, new UriDeserializer())
//                            .create();
//
//                    Intent intent = gsonIntent.fromJson(act.getSetting("shortcut_intent"), Intent.class);
//                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                    System.out.println("*** Opening intent\n" + act.getSetting("shortcut_intent"));
                    Log.d("sfen", "Intent URI\n" + act.getSetting("intent_uri"));

                    Intent intent = new Intent();
                    try {
                        intent = Intent.parseUri(act.getSetting("intent_uri"), Intent.URI_INTENT_SCHEME);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    catch (Exception e) {
                        Log.e("sfen", "Parse URI exception!");
                        e.printStackTrace();
                    }

                    try {
                        startActivity(intent);
                    }
                    catch (Exception e) {
                        Log.e("sfen", "Fatal error when trying to call new Shortcut!");
                        e.printStackTrace();
                    }

                    break;


                case ACT_LOCKSCREENDISABLE:

                    Main.getInstance().getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

                    break;

                case ACT_PLAYSFEN:

                    MediaPlayer mp = MediaPlayer.create(sInstance, R.raw.sfen_sound);
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            mp.release();
                        }

                    });
                    mp.start();

                    break;

                case ACT_LOCKSCREENENABLE:

                    //KeyguardManager keyguardManager = (KeyguardManager)getSystemService(sInstance.KEYGUARD_SERVICE);
                    //KeyguardManager.KeyguardLock lock = keyguardManager.newKeyguardLock(KEYGUARD_SERVICE);
                    //lock.reenableKeyguard();

                    //DevicePolicyManager mDPM = new DevicePolicyManager();
                    //mDPM.lockNow();

                    //Main.getInstance().getWindow().addFlags(WindowManager.LayoutParams.KEY);

                    break;

                case ACT_RUNEVENT:

                    /**
                     * running event as action will allow us to rerun specified event. There is one
                     * trick, we can go into huge loop here, so avoid it at all costs.
                     */

                    int eventUniqueID = Integer.parseInt(act.getSetting("EVENT_UNIQUEID"));

                    Event e = Event.returnEventByUniqueID(eventUniqueID);

                    if (e != null) {

                        if (!mEventLoopInProgress) {
                            mEventLoopInProgress = true;

                            e.setForceRun(true);
                            runEvent(BackgroundService.getInstance(), sIntent, e);

                            mEventLoopInProgress = false;
                        }

                    }


                    break;


                case ACT_RUNSCRIPT:

                    /**
                     * THIS IS YOUR OWN FAULT IF YOU SCREW THIS UP...
                     */
                    String fileToRun = act.getSetting("FILE");
                    File f = new File(fileToRun);

                    try {
                        //String[] cmd = new String[]{"/system/bin/sh", "-c", "ls "+ fileToRun};
                        // command above just returns:  /storage/emulated/0/Download/test.sh

//                        System.out.println("bash script path: "+ f.getAbsolutePath());


                        //Log.d("sfen", "Running command /system/bin/sh "+ fileToRun);

                        // working command
                        String[] cmd = new String[]{"/system/bin/sh", "-c", fileToRun};
                        //String[] cmd = new String[]{fileToRun};

                        /**
                         * set running directory to *.sh directory
                         */
                        Process process = Runtime.getRuntime().exec(cmd, null,
                                f.getParentFile()
                        );

                        int exitValue = process.waitFor();

                        Log.d("sfen", "Script exit value: " + exitValue);
                        BufferedReader buf = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        String line = "";
                        while ((line = buf.readLine()) != null) {
                            Log.d("sfen", "Script response: " + line);
                        }

                    }
                    catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }


                    break;


                default:
                    Log.d("sfen", "No case match ("+ act.getOptionType() +" in runActions).");

                    break;

            }
        }


    }

    /***********************************************************************************************
     * RUN EVENT ACTIONS
     *
     * Runs all actions in specified Event
     ***********************************************************************************************
     */
    protected void runEventActions(ArrayList<DialogOptions> actions) {


        runActions(actions);

        /**
         * update notification
         */
        mNotification.showNotification();


    }

    /***********************************************************************************************
     * RUN PROFILE ACTIONS
     *
     * Runs all actions in specified Profile
     ***********************************************************************************************
     */
    protected void runProfileActions(Profile p) {


        ArrayList<DialogOptions> actions = p.getActions();


        runActions(actions);

        /**
         * update notification
         */
        mNotification.showNotification();


    }


    /***********************************************************************************************
     * RUN PROFILE SETTINGS
     *
     * Runs all specified settings of profile
     ***********************************************************************************************
     */
    protected void runProfileSettings(Profile p) {

        /**
         * DISPLAY BRIGHTNESS
         * http://developer.android.com/reference/android/provider/Settings.System.html
         *
         * Continue only if we are NOT using default settings
         */
        if (!p.isBrightnessDefault()) {

            /**
             * DISPLAY BRIGHTNESS AUTO
             */
            if (p.isBrightnessAuto()) {
//            Log.i("sfen", "Brightness set to auto.");
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                );
            }
            /**
             * DISPLAY BRIGHTNESS MANUAL
             */
            else {
//            Log.i("sfen", "Brightness set to "+ p.getBrightnessValue() +".");

                if (p.getBrightnessValue() == 0)
                    p.setBrightnessValue(10);

                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                );

                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, (p.getBrightnessValue()));
            }

        }


        /**
         * VIBRATION
         *
         * set vibration mode when ringing
         */
        Settings.System.putInt(getContentResolver(), "vibrate_when_ringing",
                p.isVibrate() ? 1 : 0
        );


        /**
         * RINGTONE SOUNDS
         */
        if (!p.isDefaultRingtone() && p.getRingtone() != null && p.getRingtone().toString().length() > 0) {

            RingtoneManager.setActualDefaultRingtoneUri(this,
                    RingtoneManager.TYPE_RINGTONE,
                    p.getRingtone());

        }

        /**
         * NOTIFICATION SOUNDS
         */
        if (!p.isDefaultNotification() && p.getNotification() != null && p.getNotification().toString().length() > 0) {

            RingtoneManager.setActualDefaultRingtoneUri(this,
                    RingtoneManager.TYPE_NOTIFICATION,
                    p.getNotification());

        }


        /**
         * LOUDNESS
         */
        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);


        /**
         * we have to set default volume for ringing to max stream volume
         *
         * even though we set it like that, when we receive new phone call, volume will be set
         * then. after phone call is dismissed or canceled, we set it back to the volume of
         * notification sound.
         */
        audioManager.setStreamVolume(
                AudioManager.STREAM_RING,
                //p.getVolumeRingtone(),
                p.getVolumeNotification(),
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);

        /**
         * keep in mind ringtone & notification loudness is merged since 4.2.
         * bypassing this with checking receiver
         */
        audioManager.setStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                p.getVolumeNotification(),
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);


        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                p.getVolumeMusic(),
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);

        audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                p.getVolumeAlarm(),
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);

        //System.out.println("max volume for ringing: "+ audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) + );

        /**
         * change sound mode depending on the volume set and if vibration is checked
         */
        if (p.getVolumeNotification() == 0 && !p.isVibrate()) {
            //audioManager.setMode(AudioManager.RINGER_MODE_SILENT);
            audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        }

        else if (p.getVolumeNotification() == 0 && p.isVibrate()) {
            //audioManager.setMode(AudioManager.RINGER_MODE_VIBRATE);
            audioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        }
        else {
            //audioManager.setMode(AudioManager.RINGER_MODE_NORMAL);
            audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }



    }


    /***********************************************************************************************
     * RUN ACTIONS
     * @param context
     * @param intent
     * @param e
     ***********************************************************************************************
     */
    protected void runEvent(Context context, Intent intent, Event e) {
        // if event is already running
        // don't re-run actions
        if (
                (e.isRunning() && !e.isForceRun()) ||
                (e.isRunning() && e.isForceRun()) ||
                (!e.isEnabled() && !e.isForceRun())
                ) {
            Log.e("sfen", e.getName() +" is already running. Skipping actions.");

            /**
             * even if disabled, let's check if event has notifications and overwrite
             * them
             */
            //e.updateNotification();

            return ;
        }


        /**
         *
         * last check before running- do we have any ACTIVE event with higher priority?
         *
         * if yes, don't run!
         *
         */
        boolean isHigherPriorityEventRunning =
                Event.hasAnyRunningEventHigherPriority(e);
        if (isHigherPriorityEventRunning) {

            if (!e.isForceRun())
                Log.d("sfen", "Higher priority event is already running. Execution of "+ e.getName() +
                        " stopped.");

            else
                Log.d("sfen", "Higher priority event is running but we are force running "+
                        e.getName() +".");
        }

        /**
         * THERE IS NO HIGHER PRIORITY running events. execute profile & actions
         * but if it is and our event is forcerunning, let it pass
         */
        if (!isHigherPriorityEventRunning  ||
                (isHigherPriorityEventRunning && e.isForceRun())
                ) {

            /**
             *
             * store to log
             *
             */
            Logs.addToLog(e, Calendar.getInstance(), intent.getAction());


            /**
             *
             * IF WE HAVE EVENT ACTIONS
             *
             */
            if (e.getActions().size() > 0) {

                // add all actions & profile to eventToRun
                //eventToRun.addActions(e.getActions());
                addActionsToEventToRun(e.getActions());

            }


            /**
             *
             * IF WE HAVE PROFILE SELECTED
             *
             */
            if (e.getProfile() != null) {

                eventToRun.setProfile(e.getProfile());

            }


        }


        // first time actions are run. now set event to running.
        e.setRunning(true);

        // set hasrun boolean to true also
        e.setHasRun(true);

        // disable force run
        e.setForceRun(false);


    }


    /**
     *
     * RUN DELAYED EVENT INIT
     *
     *
     */
    protected void runEventDelayedInit(Context context, Intent intent, Event e) {

        /**
         * run new alarm with extra intent
         *
         * #1 event id
         * #2 force_recheck
         */
        e.setRunning(false);
        e.setHasRun(false);

        /**
         * remove active alarm if any
         */
        Alarm toDelete = null;
        for (Alarm single : mActiveAlarms) {
            if (single.getAlarmID() == e.getUniqueID()) {
                toDelete = single;
            }
        }


        if (toDelete != null) {
            toDelete.RemoveAlarm();

            // remove it from array of active alarms
            mActiveAlarms.remove(toDelete);

            Log.d("sfen", "Previous alarm for delayed event "+ e.getName() +" deleted.");
        }

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, e.getDelayMinutes());


        Alarm delayedAlarm = new Alarm(context, e.getUniqueID());

        delayedAlarm.setAlarmID(e.getUniqueID());
        delayedAlarm.mIntentExtra = "EVENTDELAYED_"+ e.isDelayRecheckConditions() +"_"+ e.getUniqueID();
        //System.out.println("extra intent for delayed event: "+ delayedAlarm.mIntentExtra);
        delayedAlarm.CreateAlarm(calendar);



    }

    /***********************************************************************************************
     * Function will create event condition timers
     * or geofaces if needed by specific condition. Easy as pie.
     *
     * @param events Array Of Events (can be only one too)
     ***********************************************************************************************
     */
    protected void updateEventConditionTimers(ArrayList<Event> events) {

        // set internal variables so after we fill arrays of any kind, we start the triggers
        // Geofence ID's are the same as EVENT unique ID + condition unique ID
        List<String> mGeoIds = new ArrayList<String>();
        ArrayList<Geofence> mGeofences = new ArrayList<Geofence>();

        // alarmmanager array, one when adding, one when removing
        ArrayList<Alarm> mAlarmsCreate = new ArrayList<Alarm>();
        ArrayList<Alarm> mAlarmsDelete = new ArrayList<Alarm>();

        // BroadcastReceiver Filters
        ArrayList<String> mReceiverFilters = new ArrayList<String>();

        for (Event e : events) {

            for (DialogOptions single : e.getConditions()) {


                // generate hashcode
                String hashCode = e.getUniqueID() +""+ single.getUniqueID();

                switch (single.getOptionType()) {

                    case LOCATION_ENTER:
                    case LOCATION_LEAVE:

                        //Geofence.GEOFENCE_TRANSITION_ENTER    = 1
                        //Geofence.GEOFENCE_TRANSITION_EXIT     = 2
                        //Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT = 3
                        //transition type should ALWAYS be enter & exit!

                        // IF EVENT ENABLED, add geofences
                        if (e.isEnabled()) {
                            // THIS IS CALLED WHEN ADDING/UPDATING
                            Geofence fence = new Geofence.Builder()
                                    .setRequestId(hashCode)
                                            // when entering this geofence
                                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                                    .setCircularRegion(
                                            Double.parseDouble(single.getSetting("latitude")),
                                            Double.parseDouble(single.getSetting("longitude")),
                                            Float.parseFloat(single.getSetting("radius")) // radius in meters
                                    )
                                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                    .build();

                            mGeofences.add(fence);
                            fence = null;

                        }

                        // IF EVENT DISABLED, remove geofences
                        else {
                            // THIS IS CALLED WHEN REMOVING
                            mGeoIds.add(hashCode);

                        }

                        hashCode = "";

                        break;

                    /**
                     * timerange adds alarms to specific condition time sets
                     * one at start and one on end+1min
                     */
                    case TIMERANGE:

                        // unique id
                        int uniqueId = e.getUniqueID() + e.getConditions().indexOf(single);

                        //System.out.println("*** TIMERANGE");

                        //System.out.println("showing current alarms: "+ single.getAlarms().toString());
                        //System.out.println("showing all active alarms: "+ mActiveAlarms.toString());

                        // check if we're enabling or disabling event.
                        if (e.isEnabled()) {
                            Log.d("sfen", "Creating alarms (2) for condition: "+ single.getTitle() +" of "+ e.getName());

                            // interval for both created alarms will be 24 hours
                            long interval = AlarmManager.INTERVAL_DAY;

                            /**
                             * current time
                             */
                            Calendar timeCurrent = Calendar.getInstance();

                            /*
                            Create starting time and start it on specific time.

                            We are going to repeat this alarm every day on the same time to check
                            if all event conditions are met.
                            */
                            //System.out.println("start time");
                            Calendar timeStart = Calendar.getInstance();
                            timeStart.setTimeInMillis(System.currentTimeMillis());
                            timeStart.set(Calendar.HOUR_OF_DAY, Integer.parseInt(single.getSetting("fromHour")));
                            timeStart.set(Calendar.MINUTE, Integer.parseInt(single.getSetting("fromMinute")));
                            timeStart.set(Calendar.SECOND, 0);

                            mAlarm = new Alarm(sInstance, uniqueId + 0);
                            mAlarm.CreateAlarmRepeating(timeStart, interval);
                            mActiveAlarms.add(mAlarm);


                            /*
                            Create ending time. It will trigger the same as starting, but, we will
                            add 1 minute to it, so it triggers when timerange is over.
                             */
                            //System.out.println("end time");
                            Calendar timeEnd = Calendar.getInstance();
                            timeEnd.setTimeInMillis(System.currentTimeMillis());
                            timeEnd.set(Calendar.HOUR_OF_DAY, Integer.parseInt(single.getSetting("toHour")));
                            timeEnd.set(Calendar.MINUTE, Integer.parseInt(single.getSetting("toMinute")));
                            //timeEnd.add(Calendar.MINUTE, 1);
                            timeEnd.set(Calendar.SECOND, 5);


                            // TIMES CHECK.
                            // if endTime is lower than startTime, it usually means endTime has to
                            // be tomorrow
                            if (timeEnd.before(timeStart)) {

                                if (!timeCurrent.before(timeEnd))
                                    timeEnd.add(Calendar.DATE, 1);


                            }


                            mAlarm = new Alarm(sInstance, uniqueId + 1);
                            mAlarm.mIntentExtra = "FORCE_RUN";
                            mAlarm.CreateAlarmRepeating(timeEnd, interval);
                            mActiveAlarms.add(mAlarm);

                        }
                        // disabling event, stop all timers
                        else {
                            Log.d("sfen", "Disabling alarm(s) for condition: " + single.getTitle() +" of "+ e.getName());

                            // remove every single one
                            for (Alarm singleAlarm : mActiveAlarms) {
//                                System.out.println(singleAlarm.getAlarmID() +" vs "+ (uniqueId+0) +" unid "+ uniqueId);
//                                System.out.println(singleAlarm.getAlarmID() +" vs "+ (uniqueId+1) +" unid "+ uniqueId);
                                if (
                                        singleAlarm.getAlarmID() == (uniqueId+0) ||
                                        singleAlarm.getAlarmID() == (uniqueId+1)
                                        ) {
                                    // we found a match in active alarms
                                    //mActiveAlarms.remove(singleAlarm);
                                    mAlarmsDelete.add(singleAlarm);
                                }

                            }
                        }

                        break;

                    /**
                     * TIME
                     */
                    case TIME:

                        // unique id
                        uniqueId = e.getUniqueID() + e.getConditions().indexOf(single);

                        // if enabling event, start single alarm
                        if (e.isEnabled()) {
                            Log.d("sfen", "Creating alarms for condition: "+ single.getTitle() +" of "+ e.getName());

                            // interval for timer
                            long interval = AlarmManager.INTERVAL_DAY;

                            Calendar timeStart = Calendar.getInstance();
                            timeStart.setTimeInMillis(System.currentTimeMillis());
                            timeStart.set(Calendar.HOUR_OF_DAY, Integer.parseInt(single.getSetting("hour")));
                            timeStart.set(Calendar.MINUTE, Integer.parseInt(single.getSetting("minute")));
                            timeStart.set(Calendar.SECOND, 0);

                            mAlarm = new Alarm(sInstance, uniqueId + 0);
                            mAlarm.CreateAlarmRepeating(timeStart, interval);
                            mActiveAlarms.add(mAlarm);

                            // second alarm should trigger 10 minutes after timestart to recheck
                            // why? because, it is possible alarm wont start on correct minute
                            // that's why i've implemented
                            timeStart.add(Calendar.MINUTE, 10);

                            mAlarm = new Alarm(sInstance, uniqueId + 1);
                            mAlarm.CreateAlarmRepeating(timeStart, interval);
                            mActiveAlarms.add(mAlarm);

                        }

                        // if disabling event, delete single alarm
                        else {

                            // remove alarm
                            for (Alarm singleAlarm : mActiveAlarms) {
                                if (
                                        singleAlarm.getAlarmID() == (uniqueId+0) ||
                                                singleAlarm.getAlarmID() == (uniqueId+1)
                                        ) {
                                    // we found a match in active alarms
                                    //mActiveAlarms.remove(singleAlarm);
                                    mAlarmsDelete.add(singleAlarm);
                                }

                            }

                        }


                        break;

                    /**
                     * DAYSOFWEEK
                     */
                    case DAYSOFWEEK:

                        // if enabling event, start single alarm
                        if (e.isEnabled()) {
                            Log.d("sfen", "Creating alarms for condition: "+ single.getTitle() +" of "+ e.getName());

                            //System.out.println("*** SETTINGS for condition are:");
                            //System.out.println(single.getSettings().toString());

                            // no matter what days we picked, we only need 1 recurring alarm and repeat
                            // it every day, one second after midnight.
                            if (single.getSetting("selectedDays") != null) {

                                long interval = AlarmManager.INTERVAL_DAY;

                                Calendar timeStart = Calendar.getInstance();
                                timeStart.setTimeInMillis(System.currentTimeMillis());
                                timeStart.set(Calendar.HOUR_OF_DAY, 0);
                                timeStart.set(Calendar.MINUTE, 1);
                                timeStart.set(Calendar.SECOND, 0);

                                mAlarm = new Alarm(sInstance, single.getUniqueID());
                                mAlarm.CreateAlarmRepeating(timeStart, interval);
                                mActiveAlarms.add(mAlarm);


                            }


                        }
                        // if disabling event, delete single alarm
                        else {

                            // remove alarm
                            for (Alarm singleAlarm : mActiveAlarms) {
                                if (singleAlarm.getAlarmID() == single.getUniqueID()) {
                                    // we found a match in active alarms
                                    //mActiveAlarms.remove(singleAlarm);
                                    mAlarmsDelete.add(singleAlarm);
                                }

                            }

                        }

                        break;


                    case WIFI_CONNECT:
                    case WIFI_DISCONNECT:

                        if (e.isEnabled())
                            mReceiverFilters.add("android.net.wifi.STATE_CHANGE");

                        break;

                    case BATTERY_LEVEL:
                    case BATTERY_STATUS:

                        if (e.isEnabled())
                            mReceiverFilters.add("android.intent.action.BATTERY_CHANGED");

                        break;

                    case GPS_ENABLED:
                    case GPS_DISABLED:

                        if (e.isEnabled())
                            mReceiverFilters.add("android.location.MODE_CHANGED");

                        break;

                    case SCREEN_ON:
                    case SCREEN_OFF:

                        //mReceiverFilters.add(LocationManager.MODE_CHANGED_ACTION);
                        if (e.isEnabled()) {
                            mReceiverFilters.add("android.intent.action.SCREEN_ON");
                            mReceiverFilters.add("android.intent.action.SCREEN_OFF");
                        }

                        break;

                    case BLUETOOTH_CONNECTED:
                    case BLUETOOTH_DISCONNECTED:

                        if (e.isEnabled()) {
                            mReceiverFilters.add("android.bluetooth.device.action.ACL_CONNECTED");
                            mReceiverFilters.add("android.bluetooth.device.action.ACL_DISCONNECTED");
                            mReceiverFilters.add("android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED");
                        }

                        break;

                    case HEADSET_CONNECTED:
                    case HEADSET_DISCONNECTED:

                        if (e.isEnabled())
                            mReceiverFilters.add("android.intent.action.HEADSET_PLUG");

                        break;

                    default:
                        Log.d("sfen", "No case match ("+ single.getOptionType() +" in updateEventConditionTimers).");

                        break;

                }
            }

            /**
             * checking if we need extra prerequisites for ACTIONS
             */
//            for (DialogOptions single : e.getConditions()) {
//
//                // ROOT?
//                // ACT_MOBILEENABLE, ACT_MOBILEDISABLE
//                if ((single.getOptionType() == DialogOptions.type.ACT_MOBILEENABLE ||
//                        single.getOptionType() == DialogOptions.type.ACT_MOBILEDISABLE) &&
//                        mGeoLocation == null
//                        ) {
//                    // start GeoLocation class
//                    //mGeoLocation = new GeoLocation(sInstance);
//                    Log.i("sfen", "Enabling Root mode. Needed for "+ single.getTitle() +" in "+ e.getName() +"");
//
//                    //mUtil.callRootCommand("");
//                    mSudo.isRootEnabled();
//                }
//
//
//            }



            // disable event and set it to running=off
            e.setForceRun(false);
        }

        // after checked all conditions of events, run triggers

        // TRIGGERS FOR ALL EVENTS

        // REMOVING FENCES
        if (mGeoIds.size() > 0) {
            // destroy specific ID's
            mGeoLocation.RemoveGeofencesById(mGeoIds);
        }

        // ADDING FENCES
        else if (mGeofences.size() > 0) {
            mGeoLocation.AddGeofences(mGeofences);
        }

        // TIMERS
        if (mAlarmsDelete.size() > 0) {
            //System.out.println("*** deleting "+ mAlarmsDelete.size() +" alarms.");
            for (Alarm single : mAlarmsDelete) {
                Log.d("sfen", "Deleting alarm "+ single.getAlarmID());
                // stop the alarm
                single.RemoveAlarm();

                // remove it from array of active alarms
                mActiveAlarms.remove(single);

            }

            mAlarmsDelete = null;
        }


        /**
         * add filters to array of allowables
         * then clear array
         */
        mReceiver.addFiltersToAllowable(mReceiverFilters);
        mReceiverFilters.clear();


    }




    /**
     *
     * SEND NEW BROADCAST
     *
     * NON-STATIC!
     */
    protected void sendBroadcast(String broadcast) {
        if (broadcast.length() > 0) {
            Intent intent = new Intent();
            //android.util.Log.e("send broadcast", getClass().getPackage().getName() +"."+ broadcast);

            intent.setAction(getClass().getPackage().getName() +"."+ broadcast);
            sendBroadcast(intent);
        }
    }


    /**
     *
     * Add actions to eventToRun event;
     *
     * CHECK IF types already exist and overwrite them!
     *
     */
    private void addActionsToEventToRun(ArrayList<DialogOptions> actions) {

        /**
         * loop all current actions with the ones we're adding
         *
         * compare each and overwrite it
         */
        boolean newExists = false;
        int newExistOnIndex = -1;

        if (eventToRun == null)
            eventToRun = new Event();

        for (int i = 0; i < actions.size(); i++) {

            for (int j = 0; j < eventToRun.getActions().size(); j++) {

                System.out.println("is type " + actions.get(i).getOptionType() + " already in existing array? " +
                        eventToRun.getActions().get(j).getOptionType());

                /**
                 * check types
                 */

                /**
                 * IF BOTH TYPE EQUAL
                 */
                if (eventToRun.getActions().get(j).getOptionType() == actions.get(i).getOptionType()
                        ||

                        /**
                         * IF MOBILE DATA Connected/Disconnected
                         */
                        // mobile data
                        (
                                eventToRun.getActions().get(j).getOptionType() == DialogOptions.type.ACT_MOBILEENABLE &&
                                        actions.get(i).getOptionType() == DialogOptions.type.ACT_MOBILEDISABLE
                                        ||
                                        eventToRun.getActions().get(j).getOptionType() == DialogOptions.type.ACT_MOBILEDISABLE &&
                                                actions.get(i).getOptionType() == DialogOptions.type.ACT_MOBILEENABLE
                        )

                        ||

                        /**
                         * IF WIFI Connected/Disconnected
                         */
                        // wifi connections
                        (
                                eventToRun.getActions().get(j).getOptionType() == DialogOptions.type.ACT_WIFIENABLE &&
                                        actions.get(i).getOptionType() == DialogOptions.type.ACT_WIFIDISABLE
                                        ||
                                        eventToRun.getActions().get(j).getOptionType() == DialogOptions.type.ACT_WIFIDISABLE &&
                                                actions.get(i).getOptionType() == DialogOptions.type.ACT_WIFIDISABLE
                        )


                        ) {
                    newExists = true;
                    newExistOnIndex = j;
                    break;
                }

            }


            // update if action exists
            if (newExists) {

                //System.out.println("updating existing type");
                eventToRun.getActions().set(newExistOnIndex, actions.get(i));

            }

            // it doesn't exist yet, add it
            else {

                //System.out.println("adding new type");
                eventToRun.getActions().add(actions.get(i));

            }


            // reset variables
            newExists = false;
            newExistOnIndex = -1;

        }

        /**
         * if eventToRun doesn't have any actions yet, add them
         */
        if (eventToRun.getActions().size() == 0)
            eventToRun.addActions(actions);

    }


    /**
     * START SINGLE EVENT
     */
    protected void startSingleEvent(Event e) {


        /**
         * IF WE HAVE ACTIONS
         */
        if (e.getActions().size() > 0) {

            runEventActions(e.getActions());

        }


        /**
         *
         * IF WE HAVE PROFILE SELECTED
         *
         */
        Profile p = e.getProfile();
        if (p != null) {

            /**
             * check if current active profile is locked, if so,
             * check until when
             *
             * if active profile == current profile, skip this step.
             */

            Profile activeProfile = Profile.getActiveProfile();
            if (activeProfile != null &&
                    activeProfile.isLocked() &&
                    activeProfile != p) {

                Calendar calendar = Calendar.getInstance();

                // if active profile lock time is still in action
                if (calendar.before(activeProfile.getIsLockedUntil())) {
                    Log.i("sfen", "Profile "+ p.getName() +" won't run. Active profile is locked!");
                    return ;

                }

            }

            /**
             * there's no profile locked anymore, but if we set current profile to active
             * and we set it to locked, get amount of minutes and create lock time
             */
            if (p.isLocked()) {

                // create new calendar time
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.MINUTE, p.getIsLockedFor());

                p.setIsLockedUntil(calendar);

                Log.d("sfen", "Profile "+ p.getName() +" locked until "+
                        Util.getDateLong(calendar, Main.getInstance()));

            }





            /**
             * set new as active
             */
            Profile.updateActiveProfile(e.getProfile().getUniqueID());

            /**
             * run profile actions
             */
            runProfileActions(e.getProfile());


            /**
             * run profile settings
             */
            runProfileSettings(e.getProfile());

        }

    }

    protected void startSingleEvent() {

        startSingleEvent(eventToRun);

    }

//    @Override
//    public void onTaskRemoved(Intent rootIntent) {
//        //super.onTaskRemoved(rootIntent);
//
//        Intent restartService = new Intent(getApplicationContext(),
//                this.getClass());
//        restartService.setPackage(getPackageName());
//        PendingIntent restartServicePI = PendingIntent.getService(
//                getApplicationContext(), 1, restartService,
//                PendingIntent.FLAG_ONE_SHOT);
//        AlarmManager alarmService = (AlarmManager)getApplicationContext().getSystemService(Context.ALARM_SERVICE);
//        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() +1000, restartServicePI);
//
//    }
}
