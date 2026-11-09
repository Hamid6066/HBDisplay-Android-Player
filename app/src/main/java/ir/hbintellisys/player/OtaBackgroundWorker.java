package ir.hbintellisys.player;

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

    Future<?> stageApprovedRelease(final URL source, final File privateOtaDir,
                                    final String releaseId, final String sha256,
                                    final Callback callback) {
        return stageApprovedRelease(source, privateOtaDir, releaseId, sha256, -1L, callback);
    }

    Future<?> stageApprovedRelease(final OtaReleaseContract.Approved approved,
                                    final File privateOtaDir, final Callback callback) {
        if (approved == null) throw new IllegalArgumentException("approved release required");
        return stageApprovedRelease(approved.apkUrl, privateOtaDir, approved.releaseId,
                approved.sha256, approved.sizeBytes, callback);
    }

    private Future<?> stageApprovedRelease(final URL source, final File privateOtaDir,
                                    final String releaseId, final String sha256,
                                    final long sizeBytes, final Callback callback) {
        if (callback == null) throw new IllegalArgumentException("callback required");
        if (!running.compareAndSet(false, true))
            throw new IllegalStateException("OTA staging already running");
        try {
            return downloadExecutor.submit(() -> {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    File apk = OtaStagedDownloader.stage(source, privateOtaDir, releaseId, sha256, sizeBytes);
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
