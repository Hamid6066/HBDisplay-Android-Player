package ir.hbintellisys.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Read-only APK identity gate. Must run AFTER the staged file's SHA256 matches
 * authenticated metadata and BEFORE proposing installation. Never installs.
 */
final class OtaApkIdentityVerifier {
    // Existing permanent HBDisplay release signing certificate (SHA-256).
    private static final String TRUSTED_CERT_SHA256 =
            "902F0A4CAB95EC6CF02AF83B58E2ECD8844D3C473A4DFCDEB93B92C4DECEC646";
    private OtaApkIdentityVerifier() {}

    static void verify(Context context, File apk, int expectedVersionCode) throws Exception {
        if (context == null || apk == null || !apk.isFile() || expectedVersionCode <= 0)
            throw new SecurityException("Invalid APK verification inputs");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null || !context.getPackageName().equals(info.packageName))
            throw new SecurityException("APK package identity mismatch");
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (version != expectedVersionCode)
            throw new SecurityException("APK version does not match approved release");
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null || info.signingInfo.hasMultipleSigners())
                throw new SecurityException("Unsupported APK signing configuration");
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length != 1)
            throw new SecurityException("Expected one trusted APK signer");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder actual = new StringBuilder();
        for (byte b : digest) actual.append(String.format(Locale.US, "%02X", b & 0xff));
        if (!TRUSTED_CERT_SHA256.equals(actual.toString()))
            throw new SecurityException("APK signing certificate mismatch");
    }
}
