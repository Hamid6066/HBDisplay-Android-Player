package ir.hbintellisys.player;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Inert OTA staging utility. Nothing calls this class in v0.8 development.
 * No install permission, no WebView access, no offline signage cache access.
 * Must only be invoked from a dedicated background worker after release approval.
 */
final class OtaStagedDownloader {
    private static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 8000;

    private OtaStagedDownloader() {}

    static File stage(URL source, File privateOtaDir, String releaseId, String expectedSha256)
            throws Exception {
        if (!"https".equalsIgnoreCase(source.getProtocol()) || source.getUserInfo() != null)
            throw new IOException("HTTPS required");
        if (!releaseId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IOException("Invalid release ID");
        if (!expectedSha256.matches("[a-fA-F0-9]{64}"))
            throw new IOException("Invalid SHA256");
        if (!privateOtaDir.isDirectory() && !privateOtaDir.mkdirs())
            throw new IOException("Cannot create OTA staging directory");

        File part = new File(privateOtaDir, releaseId + ".part");
        File etagFile = new File(privateOtaDir, releaseId + ".etag");
        File complete = new File(privateOtaDir, releaseId + ".apk");
        if (complete.isFile() && matchesSha256(complete, expectedSha256))
            return complete;
        if (complete.exists() && !complete.delete())
            throw new IOException("Cannot clear stale completed APK");

        long existing = part.isFile() ? part.length() : 0;
        String etag = etagFile.isFile() ? readSmallText(etagFile) : null;
        // A strong ETag is required to resume safely across elevator connectivity gaps.
        if (existing <= 0 || existing >= MAX_APK_BYTES || etag == null ||
                !etag.startsWith("\"") || etag.startsWith("W/")) {
            resetPartial(part, etagFile);
            existing = 0;
            etag = null;
        }

        HttpURLConnection conn = openHttps(source);
        try {
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
                conn.setRequestProperty("If-Range", etag);
            }
            int status = conn.getResponseCode();
            boolean append = existing > 0 && status == 206;
            if (status == 416 && existing > 0) {
                // Stale range: clear partial; next check starts from byte zero.
                resetPartial(part, etagFile);
                throw new IOException("HTTP 416: stale partial reset; retry download");
            }
            if (status != 200 && !append)
                throw new IOException("Unexpected HTTP " + status);

            String responseEtag = conn.getHeaderField("ETag");
            if (append) {
                String contentRange = conn.getHeaderField("Content-Range");
                if (!validContentRange(contentRange, existing, conn.getContentLengthLong())
                        || !etag.equals(responseEtag)) {
                    resetPartial(part, etagFile);
                    throw new IOException("Invalid resume Content-Range or ETag; partial reset");
                }
            } else {
                resetPartial(part, etagFile);
                existing = 0;
            }

            long declared = conn.getContentLengthLong();
            if (declared < 0 || declared + existing > MAX_APK_BYTES)
                throw new IOException("Invalid APK content length");
            long free = privateOtaDir.getUsableSpace();
            if (free < declared + 10L * 1024 * 1024)
                throw new IOException("Insufficient storage");

            // Save validator before streaming, so interrupted downloads can resume.
            if (responseEtag != null && responseEtag.startsWith("\"")
                    && responseEtag.length() <= 200)
                writeSmallText(etagFile, responseEtag);
            else if (etagFile.exists() && !etagFile.delete())
                throw new IOException("Cannot clear stale ETag");

            long written = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted())
                        throw new IOException("Download interrupted");
                    written += n;
                    if (existing + written > MAX_APK_BYTES || written > declared)
                        throw new IOException("Download exceeded expected size");
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            if (written != declared)
                throw new IOException("Truncated APK response");

            if (!matchesSha256(part, expectedSha256)) {
                resetPartial(part, etagFile);
                throw new IOException("APK SHA256 mismatch");
            }
            // Rename only fully verified bytes. This is NOT signature verification.
            Files.move(part.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (etagFile.exists() && !etagFile.delete())
                throw new IOException("Verified APK staged but ETag cleanup failed");
            return complete;
        } finally {
            conn.disconnect();
        }
    }

    static boolean validStrongEtag(String value) {
        return value != null && value.length() >= 2 && value.length() <= 200
                && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    static boolean validContentRange(String value, long start, long contentLength) {
        if (value == null || contentLength <= 0 || start < 0) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(value);
        if (!m.matches()) return false;
        try {
            long first = Long.parseLong(m.group(1));
            long last = Long.parseLong(m.group(2));
            long total = Long.parseLong(m.group(3));
            return first == start && last >= first && total > last
                    && last - first + 1 == contentLength && total <= MAX_APK_BYTES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static HttpURLConnection openHttps(URL url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private static void resetPartial(File part, File etagFile) throws IOException {
        if (part.exists() && !part.delete()) throw new IOException("Cannot reset partial APK");
        if (etagFile.exists() && !etagFile.delete()) throw new IOException("Cannot reset ETag");
    }

    private static String readSmallText(File file) throws IOException {
        if (file.length() > 200) throw new IOException("ETag too large");
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeSmallText(File file, String value) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean matchesSha256(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte b : digest.digest()) actual.append(String.format(Locale.US, "%02x", b & 0xff));
        return actual.toString().equalsIgnoreCase(expected);
    }
}
