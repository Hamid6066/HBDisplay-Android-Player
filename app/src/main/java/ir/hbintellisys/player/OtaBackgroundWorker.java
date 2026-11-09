package ir.hbintellisys.player;

import android.content.Context;
import java.io.File;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicitly invoked OTA staging worker. Does not install APKs, touch WebView,
 * request network recovery, or modify the offline signage cache.
 * No automatic trigger is installed in this development version.
 */
final class OtaBackgroundWorker {
    interface Callback {
        void onStaged(File apk);
        void onFailure(Exception error);
    }

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hbdisplay-ota-staging");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    Future<?> stageApprovedRelease(final Context context,
                                    final OtaReleaseContract.Approved approved,
                                    final File privateOtaDir, final Callback callback) {
        if (context == null) throw new IllegalArgumentException("context required");
        if (approved == null) throw new IllegalArgumentException("approved release required");
        return stageApprovedRelease(context.getApplicationContext(), approved.apkUrl,
                privateOtaDir, approved.releaseId, approved.sha256,
                approved.sizeBytes, approved.versionCode, callback);
    }

    private Future<?> stageApprovedRelease(final Context context, final URL source,
                                    final File privateOtaDir, final String releaseId,
                                    final String sha256, final long sizeBytes,
                                    final int expectedVersionCode, final Callback callback) {
        if (callback == null) throw new IllegalArgumentException("callback required");
        if (!running.compareAndSet(false, true))
            throw new IllegalStateException("OTA staging already running");
        try {
            return downloadExecutor.submit(() -> {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    File apk = OtaStagedDownloader.stage(source, privateOtaDir, releaseId, sha256, sizeBytes);
                    OtaApkIdentityVerifier.verify(context, apk, expectedVersionCode);
                    if (!Thread.currentThread().isInterrupted()) callback.onStaged(apk);
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) callback.onFailure(e);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    boolean isRunning() { return running.get(); }

    void shutdown() {
        downloadExecutor.shutdownNow();
    }
}
