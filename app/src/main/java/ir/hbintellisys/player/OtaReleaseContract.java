package ir.hbintellisys.player;

import java.net.URL;
import java.util.Locale;

/**
 * Pure-Java validation of the authenticated OTA check response.
 * Parsing JSON and authenticating the device belong to the HTTPS transport layer.
 * A validated release is NOT permission to install an APK.
 */
final class OtaReleaseContract {
    static final class Approved {
        final String releaseId;
        final int versionCode;
        final URL apkUrl;
        final String sha256;
        final long sizeBytes;

        Approved(String releaseId, int versionCode, URL apkUrl, String sha256, long sizeBytes) {
            this.releaseId = releaseId;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }
    }

    private OtaReleaseContract() {}

    static Approved validate(String status, String rollout, int currentVersion,
                             int targetVersion, String releaseId, String apkUrl,
                             String sha256, long sizeBytes) {
        if (!"update_available".equals(status) || !"approved".equals(rollout))
            throw new IllegalArgumentException("Release not approved");
        if (currentVersion < 1 || targetVersion <= currentVersion)
            throw new IllegalArgumentException("Non-increasing version code");
        if (releaseId == null || !releaseId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid release ID");
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Invalid APK digest");
        if (sizeBytes <= 0 || sizeBytes > 150L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid APK size");
        try {
            URL url = new URL(apkUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                    || url.getHost() == null || url.getHost().isEmpty()
                    || url.getRef() != null || url.getPort() == 0)
                throw new IllegalArgumentException("Unsafe APK URL");
            return new Approved(releaseId, targetVersion, url,
                    sha256.toLowerCase(Locale.US), sizeBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsafe APK URL", e);
        }
    }
}
