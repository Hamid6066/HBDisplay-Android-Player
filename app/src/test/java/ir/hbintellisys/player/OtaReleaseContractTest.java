package ir.hbintellisys.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class OtaReleaseContractTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private OtaReleaseContract.Approved validate(String status, String rollout,
                                                  int target, String id, String url,
                                                  String hash, long bytes) {
        return OtaReleaseContract.validate(status, rollout, 8, target, id, url, hash, bytes);
    }

    private void rejects(String status, String rollout, int target, String id,
                         String url, String hash, long bytes) {
        try {
            validate(status, rollout, target, id, url, hash, bytes);
            fail("Expected contract rejection");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed behavior.
        }
    }

    @Test public void acceptsOnlyApprovedHigherVersion() {
        OtaReleaseContract.Approved a = validate("update_available", "approved", 9,
                "release_9", "https://ota.example.invalid/releases/9.apk", HASH, 1024);
        assertEquals(9, a.versionCode);
        assertEquals("release_9", a.releaseId);
        assertEquals(1024, a.sizeBytes);
    }

    @Test public void rejectsUnapprovedAndRollback() {
        rejects("up_to_date", "approved", 9, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 100);
        rejects("update_available", "pending", 9, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 100);
        rejects("update_available", "approved", 8, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 100);
        rejects("update_available", "approved", 7, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 100);
    }

    @Test public void rejectsUnsafeTransportAndCredentials() {
        String[] urls = {
                "http://ota.example.invalid/9.apk",
                "https://user:pass@ota.example.invalid/9.apk",
                "https://ota.example.invalid/9.apk#fragment",
                "file:///tmp/9.apk",
                "https://ota.example.invalid:0/9.apk"
        };
        for (String url : urls)
            rejects("update_available", "approved", 9, "release_9", url, HASH, 100);
    }

    @Test public void rejectsMalformedMetadata() {
        rejects("update_available", "approved", 9, "../9",
                "https://ota.example.invalid/9.apk", HASH, 100);
        rejects("update_available", "approved", 9, "release_9",
                "https://ota.example.invalid/9.apk", "bad", 100);
        rejects("update_available", "approved", 9, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 0);
        rejects("update_available", "approved", 9, "release_9",
                "https://ota.example.invalid/9.apk", HASH, 151L * 1024 * 1024);
    }
}
