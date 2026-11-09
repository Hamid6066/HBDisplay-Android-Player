package ir.hbintellisys.player;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Explicitly invoked managed-device OTA install, never invoked from the display loop. */
final class OtaManagedInstaller {
    private OtaManagedInstaller() {}

    static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    static int installApproved(Context context, File verifiedApk, int approvedVersionCode)
            throws Exception {
        if (context == null || verifiedApk == null || !verifiedApk.isFile())
            throw new IllegalArgumentException("APK required");
        if (!isDeviceOwner(context))
            throw new SecurityException("Device Owner enrollment required");
        File allowed = new File(context.getFilesDir(), "ota").getCanonicalFile();
        if (!allowed.equals(verifiedApk.getCanonicalFile().getParentFile()))
            throw new SecurityException("APK outside private OTA directory");
        OtaApkIdentityVerifier.verify(context, verifiedApk, approvedVersionCode);

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (FileInputStream input = new FileInputStream(verifiedApk);
                 OutputStream output = session.openWrite("hbdisplay-update.apk", 0, verifiedApk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent result = new Intent(context, OtaInstallResultReceiver.class);
            result.setAction(OtaInstallResultReceiver.ACTION_RESULT);
            result.setPackage(context.getPackageName());
            PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(callback.getIntentSender());
            committed = true;
            return sessionId;
        } finally {
            if (!committed) installer.abandonSession(sessionId);
        }
    }
}
