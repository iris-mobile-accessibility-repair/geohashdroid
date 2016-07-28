/**
 * AlarmService.java
 * Copyright (C)2015 Nicholas Killewald
 * 
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENCE file at the toplevel.
 */
package net.exclaimindustries.geohashdroid.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.util.Log;

import com.commonsware.cwac.wakeful.WakefulIntentService;

import net.exclaimindustries.geohashdroid.R;
import net.exclaimindustries.geohashdroid.activities.CentralMap;
import net.exclaimindustries.geohashdroid.util.GHDConstants;
import net.exclaimindustries.geohashdroid.util.Graticule;
import net.exclaimindustries.geohashdroid.util.HashBuilder;
import net.exclaimindustries.geohashdroid.util.Info;
import net.exclaimindustries.geohashdroid.util.KnownLocation;
import net.exclaimindustries.geohashdroid.util.UnitConverter;
import net.exclaimindustries.tools.AndroidUtil;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

/**
 * <p>
 * <code>AlarmService</code> is a background service that retrieves the current stock
 * value around 9:30am ET (that is, a reasonable time after the opening of the
 * New York Stock Exchange, at which time the DJIA opening value is known).
 * It makes requests to {@link StockService}, which then stores the result away
 * in the cache so that later instances of hashing will have that data available
 * right away.
 * </p>
 * 
 * <p>
 * This WILL try to start itself at boot time (assuming we get the boot intent).
 * </p>
 *
 * @author Nicholas Killewald
 *
 */
public class AlarmService extends WakefulIntentService {
    
    private static final String DEBUG_TAG = "AlarmService";

    private AlarmManager mAlarmManager;
    private NotificationManager mNotificationManager;
    
    private Notification.Builder mNotificationBuilder;
    
    /**
     * Broadcast intent for the alarm that tells StockService that it's time to
     * go fetch a stock.  At that time, it'll retrieve stock data for "today"
     * and "yesterday".  In this case, "today" and "yesterday" are both relative
     * to when stock data is expected to exist for the actual "today"; for
     * instance, if this is called on a Saturday, "today" will be Friday (the
     * NYSE isn't open on Saturday, so Friday's open value is used) and
     * "yesterday" will also be Friday (both 30W and non-30W users get the same
     * hash data on Saturdays and Sundays).
     */
    private static final String STOCK_ALARM = "net.exclaimindustries.geohashdroid.STOCK_ALARM";

    /**
     * Broadcast intent for the alarm that tells StockService to try again on
     * a failed check due to the stock not being posted yet.  In practice, the
     * resulting action will be the same as STOCK_ALARM (cache the stocks). 
     * This is needed because otherwise it'd be considered the same intent,
     * meaning the single-shot alarm would cancel the first one.
     *
     * Do note, this intent should NOT be scheduled to be repeating.
     */
    private static final String STOCK_ALARM_RETRY = "net.exclaimindustries.geohashdroid.STOCK_ALARM_RETRY";

    /**
     * Intent sent when the network's come back up.  This tells the service to
     * shut off the receiver and otherwise behave as if it were a STOCK_ALARM.
     */
    private static final String STOCK_ALARM_NETWORK_BACK = "net.exclaimindustries.geohashdroid.STOCK_ALARM_NETWORK_BACK";
    
    /**
     * Directed intent to tell StockService to set the alarms.
     */
    public static final String STOCK_ALARM_ON = "net.exclaimindustries.geohashdroid.STOCK_ALARM_ON";

    /**
     * Directed intent to tell StockService to cancel the alarms.
     */
    public static final String STOCK_ALARM_OFF = "net.exclaimindustries.geohashdroid.STOCK_ALARM_OFF";

    /**
     * Directed intent to tell CentralMap to go directly to this Info.
     */
    public static final String START_INFO = "net.exclaimindustries.geohashdroid.START_INFO";
    /**
     * Directed intent to tell CentralMap to go directly to this Info, and it's
     * also a globalhash, and this helps make it different enough from the other
     * intent that isn't a globalhash such that PendingIntent won't overwrite
     * one with the other.
     */
    public static final String START_INFO_GLOBAL = "net.exclaimindustries.geohashdroid.START_INFO_GLOBAL";

    /**
     * This receiver listens for network connectivity changes in case we ran
     * into a problem with network connectivity and wanted to know if that
     * changed.
     */
    public static class NetworkReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(DEBUG_TAG, "Network status update!");
            if(AndroidUtil.isConnected(context)) {
                Log.d(DEBUG_TAG, "The network is back up!");
                
                // NETWORK'D!!!
                Intent i = new Intent(context, AlarmService.class);
                i.setAction(STOCK_ALARM_NETWORK_BACK);
                WakefulIntentService.sendWakefulWork(context, i);
            }
        }
    }
    
    /**
     * This wakes up the service when the party alarm starts.
     */
    public static class StockAlarmReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(DEBUG_TAG, "STOCK ALARM!!!  Action is " + intent.getAction());

            // Fire off the Intent to start up the service.  That'll handle all
            // of whatever we need handled.
            Intent i = new Intent(context, AlarmService.class);
            i.setAction(intent.getAction());
            WakefulIntentService.sendWakefulWork(context, i);
        }
    }
    
    /**
     * This listens for any update from StockService, throwing out anything that
     * isn't related to the alarm.
     */
    public static class StockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check the Intent for the alarm flag.  We'll just straight give up
            // if it's not an alarm, since we don't really care.
            Bundle stuff = intent.getBundleExtra(StockService.EXTRA_STUFF);
            int flags = 0;
            if(stuff != null) {
                flags = stuff.getInt(StockService.EXTRA_REQUEST_FLAGS, 0);
            }

            if((flags & StockService.FLAG_ALARM) != 0)
            {
                Log.d(DEBUG_TAG, "StockService returned with an alarming response!");
                
                // It's ours!  Send it to the wakeful part!
                intent.setClass(context, AlarmService.class);
                WakefulIntentService.sendWakefulWork(context, intent);
            }
        }
    }

    /**
     * When bootup happens, this makes sure AlarmService is ready to go if the
     * user's got that set up.
     */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // It's boot time!  We might need to flip on the party alarm!
            Log.i(DEBUG_TAG, "Gooooooood morning, 30W!  It's boot time in Geohashland!");

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if(prefs.getBoolean(GHDConstants.PREF_STOCK_ALARM, false)) {
                // Set the alarm!
                Log.i(DEBUG_TAG, "The stock alarm is now being started...");
                Intent i = new Intent(context, AlarmService.class);
                i.setAction(AlarmService.STOCK_ALARM_ON);
                WakefulIntentService.sendWakefulWork(context, i);
            } else {
                Log.i(DEBUG_TAG, "The stock alarm is off, nothing's being started.");
            }
        }
    }

    /**
     * This makes a 9:30am ET Calendar for today's date.  Note that even if a
     * Calendar is supplied, what will be returned will be in America/New_York,
     * using the date it is in New York right now.
     *
     * @param source if not null, use this as the base, rather than build up a
     *               new Calendar from scratch
     * @return a new Calendar for 9:30am ET for today's (or the supplied) date
     */
    private Calendar makeNineThirty(@Nullable Calendar source) {
        Calendar base;

        if(source == null) {
            base = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        } else {
            base = (Calendar)source.clone();
            base.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        }

        base.set(Calendar.HOUR_OF_DAY, 9);
        base.set(Calendar.MINUTE, 30);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        return base;
    }

    /**
     * Makes a new Calendar that represents the most recent probable date that
     * a stock would exist.  It does so by comparing the current time to 9:30am
     * ET of the same day.  If it's before 9:30am (and would thus be before we
     * can confidently say the NYSE has opened and a value reported), this will
     * rewind it by one day.  If it's after 9:30am, the date will remain the
     * same.  Note that the only important part of this is the date; the actual
     * time and time zone of the returned value are not guaranteed, though
     * chances are it'll be in the same time zone as what is given (or the
     * default time zone if not given).
     *
     * This implicitly assumes that source is today, if given.  This won't
     * return an accurate date if, say, source is next week.
     *
     * @param source if not null, use this as the base, rather than whatever
     *               the system considers the current time.
     * @return a new Calendar whose date is the most recent date a stock is
     *         likely to exist.
     */
    @NonNull
    private Calendar getMostRecentStockDate(@Nullable Calendar source) {
        Calendar base;

        if(source == null) {
            base = Calendar.getInstance();
        } else {
            base = (Calendar)source.clone();
        }

        // First, get 9:30 for today.
        Calendar nineThirty = makeNineThirty(base);

        // Then, compare it to the base.
        if(base.before(nineThirty)) {
            // It's before 9:30am!  Rewind!
            base.add(Calendar.DAY_OF_MONTH, -1);
        }

        // And that should be that!
        return base;
    }
    
    public AlarmService() {
        super("AlarmService");
    }
    
    private void showNotification(@NonNull Calendar date) {
        // The notification in this case just says when there's an active
        // network transaction going.  We don't need to bug the user that we're
        // waiting for a network connection, as chances are, the user's also
        // waiting for one, and doesn't need us reminding them of this fact.
        mNotificationBuilder.setContentText(
                getString(R.string.notification_detail,
                        DateFormat
                            .getDateInstance(DateFormat.MEDIUM)
                            .format(date.getTime())));
        
        mNotificationManager.notify(R.id.alarm_notification, mNotificationBuilder.build());
    }
    
    private void clearNotification() {
        mNotificationManager.cancel(R.id.alarm_notification);
    }
    
    private void snooze() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 30);
        
        Intent alarmIntent = new Intent(this, StockAlarmReceiver.class);
        alarmIntent.setAction(STOCK_ALARM_RETRY);
        
        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                cal.getTimeInMillis(),
                PendingIntent.getBroadcast(this, 0, alarmIntent, 0));
    }
    
    private void sendRequest(@NonNull Graticule g) {
        // The Graticule will be one of the dummies, as all we really care about
        // is if it's 30W or not.  And we don't really care about it THAT much,
        // just enough to put the right string in the notification.  Otherwise,
        // StockService works it out.
        Calendar cal = getMostRecentStockDate(null);
        
        Intent request = new Intent(this, StockService.class);
        request.setAction(StockService.ACTION_STOCK_REQUEST)
            .putExtra(StockService.EXTRA_GRATICULE, g)
            .putExtra(StockService.EXTRA_DATE, cal)
            .putExtra(StockService.EXTRA_REQUEST_ID, cal.getTimeInMillis() / 1000)
            .putExtra(StockService.EXTRA_REQUEST_FLAGS, StockService.FLAG_ALARM);
        
        // The notification goes up first.
        showNotification(Info.makeAdjustedCalendar(cal, g));
        
        // THEN we send the request.
        WakefulIntentService.sendWakefulWork(this, request);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Init these now, at create time.  The service MIGHT not die between
        // calls, after all.  Maybe.
        mAlarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Ready the notification!  The detail text will be set by date, of
        // course.
        mNotificationBuilder = new Notification.Builder(this)
            .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
            .setSmallIcon(R.drawable.ic_stat_file_file_download)
            .setContentTitle(getString(R.string.notification_title));
        
        // Oh, and if we're in Lollipop, we can go ahead and make this a public
        // Notification.  It's not really sensitive.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            mNotificationBuilder.setVisibility(Notification.VISIBILITY_PUBLIC);
    }
    
    @Override
    protected void doWakefulWork(Intent intent) {
        // If we've been told the network just came back, we can shut off the
        // network receiver.  If we're still in trouble network-wise, it'll go
        // right back on when we check in a second.
        if(intent.getAction().equals(STOCK_ALARM_NETWORK_BACK)) {
            AndroidUtil.setPackageComponentEnabled(this, NetworkReceiver.class, false);
        }
        
        if(intent.getAction().equals(STOCK_ALARM_OFF)) {
            // We've been told to stop all alarms!  While we're at it, abort any
            // in-progress connections, too!
            Log.d(DEBUG_TAG, "Got STOCK_ALARM_OFF!");
            mAlarmManager.cancel(PendingIntent.getBroadcast(this, 0, new Intent(STOCK_ALARM).setClass(this, StockAlarmReceiver.class), 0));
            mAlarmManager.cancel(PendingIntent.getBroadcast(this, 0, new Intent(STOCK_ALARM_RETRY).setClass(this, StockAlarmReceiver.class), 0));
            AndroidUtil.setPackageComponentEnabled(this, NetworkReceiver.class, false);
            clearNotification();
        } else if(intent.getAction().equals(STOCK_ALARM_ON)) {
            Log.d(DEBUG_TAG, "Got STOCK_ALARM_ON!");
            // At init time, set the alarm.  We're aiming at 9:30am ET (with any
            // applicable DST adjustments).  The NYSE opens at 9:00am ET, but in
            // the interests of possible clock discrepancies and such (not to
            // mention any delays in the stock reporting sites being updated),
            // we'll wait the extra half hour.  The first alarm should be the
            // NEXT 9:30am ET.  If the user wants to take a chance and get a
            // stock value closer to 9:00am ET than that, well, they can do it
            // themselves.
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
            Calendar alarmTime = makeNineThirty(cal);

            if(alarmTime.before(cal)) {
                alarmTime.add(Calendar.DAY_OF_MONTH, 1);
            }
            
            Intent alarmIntent = new Intent(STOCK_ALARM);
            alarmIntent.setClass(this, StockAlarmReceiver.class);
            
            Log.d(DEBUG_TAG, "Setting a daily wakeup alarm starting at " + alarmTime.getTime().toString());
            
            mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP,
                    alarmTime.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    PendingIntent.getBroadcast(this, 0, alarmIntent, 0));
                        
            // AlarmManager sends out broadcasts, and the receiver we've got
            // will wake the service back up, so we can stop everything right
            // now.
        } else  if(intent.getAction().equals(STOCK_ALARM)
                || intent.getAction().equals(STOCK_ALARM_RETRY)
                || intent.getAction().equals(STOCK_ALARM_NETWORK_BACK)
                || intent.getAction().equals(StockService.ACTION_STOCK_RESULT)) {
            // Aha!  NOW we've got something!
            Log.d(DEBUG_TAG, "AlarmService has business to attend to!");

            // If we got the REAL stock alarm while still waiting on the RETRY
            // alarm (i.e. the server kept reporting the stock wasn't posted all
            // day until the next 9:30), we should stop the retry alarm.  It'll
            // get set back up if the stock is STILL unavailable, and by
            // shutting it down here, we preferably avoid acting on two alarms
            // at the same time.
            mAlarmManager.cancel(PendingIntent.getBroadcast(this, 0, new Intent(STOCK_ALARM_RETRY).setClass(this, StockAlarmReceiver.class), 0));
            
            // StockService takes care of all the network connectivity checks
            // and other things that the alarm-checking StockService used to
            // take care of.  It'll also tell us if the stock hasn't been
            // posted just yet.  So, we can count on that for error checking.
            if(intent.getAction().equals(StockService.ACTION_STOCK_RESULT)) {
                Log.d(DEBUG_TAG, "Just got a stock result!");

                Bundle bun = intent.getBundleExtra(StockService.EXTRA_STUFF);
                bun.setClassLoader(getClassLoader());

                int result = bun.getInt(StockService.EXTRA_RESPONSE_CODE, StockService.RESPONSE_NOT_POSTED_YET);
                Graticule g = bun.getParcelable(StockService.EXTRA_GRATICULE);
                
                if(result == StockService.RESPONSE_NO_CONNECTION) {
                    // No connection means we just set up the receiver and wait.
                    // And wait.  And wait.
                    Log.d(DEBUG_TAG, "No network connection available, waiting until we get one...");
                    AndroidUtil.setPackageComponentEnabled(this, NetworkReceiver.class, true);
                    clearNotification();
                    return;
                }
                
                if(result == StockService.RESPONSE_NOT_POSTED_YET) {
                    // Not posted yet means we hit the snooze and try again in a
                    // half hour or so.  Good night!
                    Log.d(DEBUG_TAG, "Stock wasn't posted yet, snoozing for a half hour...");
                    snooze();
                    clearNotification();
                    return;
                }
                
                if(result == StockService.RESPONSE_NETWORK_ERROR) {
                    // A network error that ISN'T "no connection" is really bad.
                    // So bad, in fact, that we're not even going to bother
                    // figuring it out.  Give up now.
                    Log.w(DEBUG_TAG, "Network reported an error, NOT rescheduling!");
                    clearNotification();
                    return;
                }
                
                if(result == StockService.RESPONSE_OKAY) {
                    // An okay response means the Graticule IS good.  If not,
                    // fix StockService.
                    if(g == null) {
                        Log.w(DEBUG_TAG, "g is somehow null in AlarmService?");
                        clearNotification();
                    } else if(g.uses30WRule()) {
                        // If the response we just checked for was a 30W one and
                        // it came back okay, then we fire off a check for the
                        // non-30W one.
                        Log.d(DEBUG_TAG, "That was the 30W response, going up to non-30W...");
                        sendRequest(GHDConstants.DUMMY_TODAY);
                    } else {
                        // If, however, we got the non-30W back, then our job is
                        // done!  Yay!
                        Log.d(DEBUG_TAG, "The 30W response!  We're done!");
                        clearNotification();

                        // And since it's done, we can go off to the part where
                        // we deal with KnownLocations!
                        doKnownLocations();
                    }
                }
            } else {
                // If it's NOT a result, that means we're starting a new check
                // at a 30W hash for some reason.  Doesn't matter what reason.
                // We just need to do it.
                Log.d(DEBUG_TAG, "That wasn't a result, so asking for a 30W...");
                sendRequest(GHDConstants.DUMMY_YESTERDAY);
            }
        } else {
            // Stop doing this!
            Log.w(DEBUG_TAG, "Told to start on unknown action " + intent.getAction() + ", ignoring...");
        }
    }

    /**
     * Convenient container for all the data we need for matches.
     */
    private static class KnownLocationMatchData implements Comparable<KnownLocationMatchData> {
        public KnownLocation knownLocation;
        public Info bestInfo;
        public double distance;

        public KnownLocationMatchData(@NonNull KnownLocation kl, @NonNull Info info, double dist) {
            knownLocation = kl;
            bestInfo = info;
            distance = dist;
        }

        @Override
        public int compareTo(@NonNull KnownLocationMatchData another) {
            // We want to sort this by how close it is.  The LOWEST number
            // should go first (that's the closest one).  I hope I got the
            // order right.
            if(distance < another.distance) return -1;
            if(distance > another.distance) return 1;
            return 0;
        }
    }

    private void doKnownLocations() {
        // First things first, clear out any old notifications.  If those are
        // still around, they're from previous days, so they're no longer valid.
        mNotificationManager.cancel(R.id.alarm_known_location);
        mNotificationManager.cancel(R.id.alarm_known_location_global);

        List<KnownLocation> locations = KnownLocation.getAllKnownLocations(this);

        // If there are no KnownLocations, give up now.
        if(locations.isEmpty()) return;

        List<KnownLocationMatchData> matched = new LinkedList<>();
        List<KnownLocationMatchData> matchedGlobal = new LinkedList<>();

        Calendar today = Calendar.getInstance();

        Info global = HashBuilder.getStoredInfo(this, today, null);

        for(KnownLocation kl : locations) {
            // Every KnownLocation has a method to do this.  Maybe it's a wee
            // bit inefficient and inelegant, but it does the job.
            Info best = kl.getClosestInfo(this, today);

            if(kl.isCloseEnough(best.getFinalDestinationLatLng())) {
                KnownLocationMatchData data = new KnownLocationMatchData(kl, best, kl.getDistanceFrom(best));
                matched.add(data);
            }

            // The Globalhash will be handled as a separate notification,
            // because frankly, that's sort of special.
            if(global != null && kl.isCloseEnough(global.getFinalDestinationLatLng())) {
                KnownLocationMatchData data = new KnownLocationMatchData(kl, global, kl.getDistanceFrom(global));
                matchedGlobal.add(data);
            }
        }

        // Did we get anything?  Anything AT ALL?
        if(!matched.isEmpty()) {
            launchNotification(matched, START_INFO, R.id.alarm_known_location, R.string.known_locations_alarm_title);
        }

        // Now, the Globalhash notification.
        if(!matchedGlobal.isEmpty()) {
            launchNotification(matchedGlobal, START_INFO_GLOBAL, R.id.alarm_known_location_global, R.string.known_locations_alarm_title_global);
        }
    }

    private void launchNotification(@NonNull List<KnownLocationMatchData> matched,
                                    @NonNull String action,
                                    @IdRes int notificationId,
                                    @StringRes int titleId) {
        // So here's what we do: Note the BEST match in a notification, but also
        // mention the others.
        Collections.sort(matched);

        // First one's the winner!
        Notification.Builder builder = getFreshNotificationBuilder(matched, titleId);

        Bundle bun = new Bundle();
        bun.putParcelable(StockService.EXTRA_INFO, matched.get(0).bestInfo);

        Intent intent = new Intent(this, CentralMap.class)
                .setAction(action)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(StockService.EXTRA_STUFF, bun);

        builder.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0));

        mNotificationManager.notify(notificationId, builder.build());
    }

    private Notification.Builder getFreshNotificationBuilder(@NonNull List<KnownLocationMatchData> data, @StringRes int titleId) {
        KnownLocationMatchData match = data.get(0);
        String contentText = getString(R.string.known_locations_alarm_distance,
                UnitConverter.makeDistanceString(this, UnitConverter.DISTANCE_FORMAT_SHORT, (float)match.distance),
                match.knownLocation.getName());
        String summaryText = getResources().getQuantityString(R.plurals.known_locations_alarm_more, data.size() - 1, data.size() - 1);

        Notification.Builder builder = new Notification.Builder(this)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setSmallIcon(R.drawable.ic_stat_av_new_releases)
                .setAutoCancel(true)
                .setOngoing(false)
                .setLights(Color.WHITE, 500, 2000)
                .setContentText(contentText)
                .setContentTitle(getString(titleId));

        // If there's more than one known location nearby, make the notification
        // expandable with a bit of extra text mentioning just how many more.
        if(data.size() > 1) {
            builder.setStyle(new Notification.BigTextStyle()
                    .bigText(contentText)
                    .setSummaryText(summaryText));
        }

        // Since these notifications will be displaying location names, they
        // may as well be private.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setVisibility(Notification.VISIBILITY_PRIVATE);

        return builder;
    }
}
