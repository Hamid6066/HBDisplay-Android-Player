package ir.hbintellisys.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Records PackageInstaller outcome; pending user action is not reported as success. */
public final class OtaInstallResultReceiver extends BroadcastReceiver {
    static final String ACTION_RESULT = "ir.hbintellisys.player.OTA_INSTALL_RESULT";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i("HBDisplayOTA", "PackageInstaller committed update successfully");
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.w("HBDisplayOTA", "Installer requires user action; not an automatic success");
        } else {
            Log.e("HBDisplayOTA", "PackageInstaller failure status=" + status
                    + " detail=" + detail);
        }
    }
}
