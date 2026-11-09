package ir.hbintellisys.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;

/** Explicit user-confirmed installation handoff; never silently installs. */
final class OtaInstallHandoff {
    private OtaInstallHandoff() {}

    static void requestUserInstall(Activity activity, File verifiedApk, int approvedVersionCode) throws Exception {
        if (activity == null || verifiedApk == null || !verifiedApk.isFile()
                || !verifiedApk.getName().endsWith(".apk"))
            throw new IllegalArgumentException("Verified staged APK required");
        File approvedDirectory = new File(activity.getFilesDir(), "ota").getCanonicalFile();
        if (!approvedDirectory.equals(verifiedApk.getCanonicalFile().getParentFile()))
            throw new SecurityException("APK outside private OTA directory");
        OtaApkIdentityVerifier.verify(activity, verifiedApk, approvedVersionCode);
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".ota-files", verifiedApk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }
}
