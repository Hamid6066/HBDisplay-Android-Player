package ir.hbintellisys.player;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Device-owner enrollment component for dedicated HBDisplay signage devices. */
public final class HbDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override public void onEnabled(Context context, Intent intent) {
        Log.i("HBDisplayDeviceAdmin", "Device administration enabled");
    }
    @Override public void onDisabled(Context context, Intent intent) {
        Log.i("HBDisplayDeviceAdmin", "Device administration disabled");
    }
}
