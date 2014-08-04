package gpapez.sfen;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Main extends Activity {

    // singleton object
    private static Main sInstance = null;

    // background service object
    protected static Intent bgService = null;

    // if main activity is visible or not (will change onResume and onPause)
    protected boolean isVisible = false;

    // this variable will set itself to false on the last line of onCreate method
    private boolean isCreating = true;

    // position of current tab
    protected int mTabPosition = 0;

    // tab fragment
    // create fragments
    protected FragmentEvent fragmentEvent;
    protected FragmentProfile fragmentProfile;


    // preferences object
    protected Preferences mPreferences;

    // HashMap with options. Instead of creating more variables to use around
    // activities, I've created HashMap; putting settings here if needed.
    HashMap<String, String> options = new HashMap<String, String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // set singleton instance
        sInstance = this;

        // start preferences
        mPreferences = new Preferences(this);

        /**
         * create fragment objects
         */
        fragmentEvent = new FragmentEvent();
        fragmentProfile = new FragmentProfile();


        // Set up the action bar to show tabs.
        final ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // for each of the sections in the app, add a tab to the action bar.
        actionBar.addTab(actionBar.newTab().setText("Events")
                .setTag("Events")
                .setTabListener(new MainTabListener(fragmentEvent)));
        actionBar.addTab(actionBar.newTab().setText("Profiles")
                .setTag("Profiles")
                .setTabListener(new MainTabListener(fragmentProfile)));
//        actionBar.addTab(actionBar.newTab().setText("Whitelists")
//                .setTabListener(this));

        /**
         * Find our service in the list of all running services
         * If found, get its intent and we're good to continue its activity
         *
         * Otherwise, create new BackGround service.
         */
        boolean mIsBackgroundServiceRunning = false;

        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);

        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            //System.out.println("service: "+ service.service.getClassName());
            if ((getClass().getPackage().getName() +".BackgroundService").equals(service.service.getClassName())) {
                System.out.println("our background service is running.");

                bgService = BackgroundService.getInstance().sIntent;

                if (bgService != null) {

                    System.out.println(bgService.toString());
                    mIsBackgroundServiceRunning = true;
                    sendBroadcast("EVENT_ENABLED");
                    break;
                }
            }
        }

        if (!mIsBackgroundServiceRunning) {
            bgService = new Intent(this, BackgroundService.class);
            startService(bgService);
        }


        // end of onCreate
        isCreating = false;
    }




    @Override
    protected void onResume() {
        super.onResume();

        // set to visible
        isVisible = true;

        //if (options.get("eventSave") == "1") {
        //addNewEvent();
        //if (mTabPosition == 0) {
            refreshCurrentView();
        //}
    }

    @Override
    protected void onPause() {
        super.onPause();

        // set to invisible
        isVisible = false;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);

        // set visibility of menu items with icons
        if (mTabPosition == 0) {
            menu.findItem(R.id.event_add_new).setVisible(true);
            menu.findItem(R.id.profile_add_new).setVisible(false);
            menu.findItem(R.id.whitelist_add_new).setVisible(false);
        }
        // profiles
        else if (mTabPosition == 1) {
            menu.findItem(R.id.event_add_new).setVisible(false);
            menu.findItem(R.id.profile_add_new).setVisible(true);
            menu.findItem(R.id.whitelist_add_new).setVisible(false);
        }
        // whitelist
        else if (mTabPosition == 2) {
            menu.findItem(R.id.event_add_new).setVisible(false);
            menu.findItem(R.id.profile_add_new).setVisible(false);
            menu.findItem(R.id.whitelist_add_new).setVisible(true);
        }

        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.event_add_new) {
            // TODO: if no profiles, show messagebox and return false;
            if (BackgroundService.getInstance().profiles.size() == 0) {
                Util.showMessageBox("You have to create at least one profile first!", true);
                return false;
            }

            startActivity(new Intent(this, EventActivity.class));

            return true;
        }

        if (id == R.id.profile_add_new) {
            startActivity(new Intent(this, ProfileActivity.class));

            return true;
        }

        // close the application and, of course background process
        if (id == R.id.action_exit) {
            // quitting? so soon? ask nicely, windows mode, if person is sure!
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder
                    .setMessage("Service will be stopped and Events won't be triggered.\n\nAre you sure?")
                    .setIcon(getResources().getDrawable(R.drawable.ic_launcher))
                    .setTitle(getString(R.string.error))
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            if (bgService != null) {
                                stopService(bgService);
                                BackgroundService.getInstance().stopForeground(true);
                            }
                            finish();

                        }
                    })

                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.dismiss();
                            //return;
                        }
                    });

            // open the dialog now :)
            builder.show();

        }

        if (id == R.id.action_preferences) {

            startActivity(new Intent(this, PreferencesActivity.class));

        }

        // export settings
        if (id == R.id.action_export) {
            Gson gson = new Gson();

            HashMap<String, String> exportedValues = new HashMap<String, String>();

            exportedValues.put("events", gson.toJson(BackgroundService.getInstance().events));

            //System.out.println("EVENTS\n==========================\n"+ exportedValues.get("events"));

            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Sfen", exportedValues.get("events"));
            clipboard.setPrimaryClip(clip);

            Util.showMessageBox("Events exported to clipboard!", true);

        }

        // import settings
        if (id == R.id.action_import) {
            final TextView info = new TextView(sInstance);
            info.setText("Paste import string:");
            final EditText input = new EditText(sInstance);
            LinearLayout newView = new LinearLayout(sInstance);
            LinearLayout.LayoutParams parms = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            newView.setLayoutParams(parms);
            newView.setOrientation(LinearLayout.VERTICAL);
            newView.setPadding(15, 15, 15, 15);
            newView.addView(info, 0);
            newView.addView(input, 1);

            final AlertDialog.Builder builder = new AlertDialog.Builder(Main.getInstance());

            builder
                    .setView(newView)
                    .setIcon(R.drawable.ic_launcher)
                    .setTitle("Sfen!")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();

                            if (input.length() == 0) {
                                Util.showMessageBox("Nothing to import!", true);
                                return;
                            }

                            Gson gson = new Gson();

                            String json = input.getText().toString();

                            //protected ArrayList<Event> events = new ArrayList<Event>();
                            ArrayList<Event> mPastedEvents = gson.fromJson(json, new TypeToken<List<Event>>(){}.getType());

                            // add to current EVENTS array
                            BackgroundService.getInstance().events.addAll(0, mPastedEvents);

                            // refresh view
                            //refreshEventsView();
                            refreshCurrentView();



                            // save action & create new row
                            /*
                            final DialogOptions cond = new DialogOptions(opt.getTitle(), opt.getDescription(),
                                    opt.getIcon(), opt.getOptionType());

                            cond.setSetting("text1", opt.getTitle());
                            cond.setSetting("text2", input.getText().toString());
                            cond.setSetting("text", input.getText().toString());

                            if (isEditing)
                                removeConditionOrAction(index, opt);

                            addNewConditionOrAction(context, cond, 0);*/

                        }
                    })
                            //.set
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // just close the dialog if we didn't select the days
                            dialog.dismiss();

                        }
                    });


            builder.show();



        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();

        // adding instance of Analytics object
        EasyTracker.getInstance(this).activityStart(this);
    }


    @Override
    public void onStop() {
        super.onStop();
        // finishing analytics activity.
        EasyTracker.getInstance(this).activityStop(this);  // Add this method.
    }


    /**
     * SINGLETON INSTANCE
     *
     * Singleton function that returns the current instance of our class
     * if it does not exist, it creates new instance.
     * @return instance of current class
     */
    public static Main getInstance() {
//        if (sInstance == null) {
//            return new Main();
//        }
//        else
            return sInstance;
    }


    /**
     * SEND NEW BROADCAST
     */
    protected void sendBroadcast(String broadcast) {
        if (broadcast.length() > 0) {
            Intent intent = new Intent();
            //android.util.Log.e("send broadcast", sInstance.getClass().getPackage().getName() +"."+ broadcast);
            intent.setAction(sInstance.getClass().getPackage().getName() +"."+ broadcast);
            sendBroadcast(intent);
        }
    }

    /**
     * call proper view refresh based on current tab selected
     */
    protected void refreshCurrentView() {
        String tag = "";
        if (mTabPosition == 0) {
            fragmentEvent.refreshEventsView();
        }
        else if (mTabPosition == 1) {
            fragmentProfile.refreshProfilesView();
        }

    }




}
