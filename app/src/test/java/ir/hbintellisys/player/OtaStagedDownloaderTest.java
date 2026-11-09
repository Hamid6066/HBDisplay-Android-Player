package ir.hbintellisys.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class OtaStagedDownloaderTest {
    @Test public void strongEtagOnly() {
        assertTrue(OtaStagedDownloader.validStrongEtag("\"release-v8\""));
        assertFalse(OtaStagedDownloader.validStrongEtag("W/\"release-v8\""));
        assertFalse(OtaStagedDownloader.validStrongEtag("release-v8"));
        assertFalse(OtaStagedDownloader.validStrongEtag("\"bad\\r\\nheader\""));
        assertFalse(OtaStagedDownloader.validStrongEtag(null));
    }

    @Test public void exactContentRangeForResume() {
        assertTrue(OtaStagedDownloader.validContentRange("bytes 100-199/200", 100, 100));
        assertFalse(OtaStagedDownloader.validContentRange("bytes 10-199/200", 100, 190));
        assertFalse(OtaStagedDownloader.validContentRange("bytes 100-199/200", 100, 99));
        assertFalse(OtaStagedDownloader.validContentRange("bytes 100-199/*", 100, 100));
        assertFalse(OtaStagedDownloader.validContentRange("bytes 100-199/199", 100, 100));
        assertFalse(OtaStagedDownloader.validContentRange(null, 100, 100));
    }
}
