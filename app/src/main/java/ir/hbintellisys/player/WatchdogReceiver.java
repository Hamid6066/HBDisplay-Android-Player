package ir.hbintellisys.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "HBDisplayWatchdog";
    private static final long WATCHDOG_INTERVAL_MS = 60_000L;
    private static final int REQUEST_CODE = 6066;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Watchdog tick: restoring HBDisplay Player");

        // Keep the private network alive as part of player recovery.
        BootReceiver.requestTailscaleConnect(context);

        Intent player = new Intent(context, MainActivity.class);
        player.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        try {
            context.startActivity(player);
        } catch (Exception e) {
            Log.e(TAG, "Unable to restore HBDisplay Player", e);
        } finally {
            schedule(context);
        }
    }

    public static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager unavailable");
            return;
        }

        Intent watchdogIntent = new Intent(context, WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                watchdogIntent,
                flags
        );

        long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else {
                alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            }
            Log.d(TAG, "Next watchdog scheduled in 60 seconds");
        } catch (SecurityException e) {
            // Newer Android versions can restrict exact alarms. Fall back to an
            // inexact wake-up alarm rather than losing self-recovery entirely.
            Log.w(TAG, "Exact alarm unavailable; using fallback alarm", e);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            }
        }
    }
}
