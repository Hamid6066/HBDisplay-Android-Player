package ir.hbintellisys.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "HBDisplayPlayer";
    private static final String PREFS = "hbdisplay_player";
    private static final String KEY_URL = "display_url";
    private static final String KEY_SITE = "site_code";
    private static final long HEALTH_INTERVAL_MS = 15_000L;
    private static final long RETRY_DELAY_MS = 10_000L;
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final long SCREENSHOT_INTERVAL_MS = 300_000L;
    private static final int SCREENSHOT_JPEG_QUALITY = 82;

    // Signage pages were designed against a 160-dpi CSS coordinate system.
    private static final int SIGNAGE_BASE_DPI = 160;

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeRunning = new AtomicBoolean(false);
    private final AtomicBoolean screenshotRunning = new AtomicBoolean(false);
    private long lastScreenshotAttemptMs = 0L;
    private boolean offline = false;
    private boolean pageShown = false;
    private String displayUrl;
    private String siteCode;
    private File offlineCacheDir;

    private final Runnable healthLoop = new Runnable() {
        @Override public void run() {
            probeServer();
            handler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();
        loadConfiguration(getIntent());
        offlineCacheDir = new File(getFilesDir(), "offline_cache");
        if (!offlineCacheDir.exists() && !offlineCacheDir.mkdirs()) {
            Log.w(TAG, "Could not create offline cache directory");
        }
        BootReceiver.requestTailscaleConnect(this);
        WatchdogReceiver.schedule(this);
        createWebView();
        webView.loadUrl(displayUrl);
        handler.postDelayed(healthLoop, 5_000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String oldUrl = displayUrl;
        loadConfiguration(intent);
        BootReceiver.requestTailscaleConnect(this);
        WatchdogReceiver.schedule(this);
        if (webView != null && !displayUrl.equals(oldUrl)) {
            offline = false;
            pageShown = false;
            webView.loadUrl(displayUrl);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        BootReceiver.requestTailscaleConnect(this);
        WatchdogReceiver.schedule(this);
    }

    @Override protected void onStop() {
        WatchdogReceiver.schedule(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void loadConfiguration(Intent intent) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_URL, BuildConfig.DEFAULT_DISPLAY_URL);
        String savedSite = prefs.getString(KEY_SITE, BuildConfig.DEFAULT_SITE_CODE);
        if (intent != null) {
            String suppliedUrl = intent.getStringExtra(KEY_URL);
            String suppliedSite = intent.getStringExtra(KEY_SITE);
            if (suppliedUrl != null && (suppliedUrl.startsWith("http://") || suppliedUrl.startsWith("https://"))) savedUrl = suppliedUrl.trim();
            if (suppliedSite != null && !suppliedSite.trim().isEmpty()) savedSite = suppliedSite.trim();
        }
        displayUrl = savedUrl;
        siteCode = savedSite;
        prefs.edit().putString(KEY_URL, displayUrl).putString(KEY_SITE, siteCode).apply();
        Log.i(TAG, "Configuration site=" + siteCode + " url=" + displayUrl);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF000000);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        int scalePercent = getSignageScalePercent();
        webView.setInitialScale(scalePercent);
        Log.i(TAG, "WebView initial scale=" + scalePercent + "%");

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null || !"GET".equalsIgnoreCase(request.getMethod())) {
                    return super.shouldInterceptRequest(view, request);
                }
                String url = request.getUrl().toString();
                if (!isCacheableUrl(url)) return super.shouldInterceptRequest(view, request);
                WebResourceResponse response = fetchOrReadCached(url);
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(TAG, "Loading: " + url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    pageShown = true;
                    applySignageViewport(view);
                }
                applyImmersiveMode();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "Main page load failed: " + error);
                    if (!hasCachedUrl(displayUrl)) showOfflinePage();
                    scheduleRetry();
                }
            }
        });
    }

    private boolean isCacheableUrl(String value) {
        if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) return false;
        try {
            URL base = new URL(displayUrl);
            URL target = new URL(value);
            return base.getHost().equalsIgnoreCase(target.getHost()) && effectivePort(base) == effectivePort(target);
        } catch (Exception e) {
            return false;
        }
    }

    private int effectivePort(URL url) {
        if (url.getPort() > 0) return url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    private WebResourceResponse fetchOrReadCached(String value) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(value).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "HBDisplay-Player/0.2 site/" + siteCode);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                byte[] body = readFully(connection.getInputStream());
                String contentType = connection.getContentType();
                CacheMeta meta = parseContentType(contentType, value);
                writeCache(value, body, meta.mime, meta.encoding);
                return new WebResourceResponse(meta.mime, meta.encoding, new ByteArrayInputStream(body));
            }
            Log.w(TAG, "HTTP " + status + " for " + value + "; trying offline cache");
        } catch (Exception e) {
            Log.d(TAG, "Network miss for " + value + ": " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return readCache(value);
    }

    private byte[] readFully(InputStream input) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private CacheMeta parseContentType(String contentType, String value) {
        String mime = null;
        String encoding = "UTF-8";
        if (contentType != null) {
            String[] parts = contentType.split(";");
            if (parts.length > 0) mime = parts[0].trim();
            for (String part : parts) {
                String p = part.trim().toLowerCase(Locale.US);
                if (p.startsWith("charset=")) encoding = part.substring(part.indexOf('=') + 1).trim();
            }
        }
        if (mime == null || mime.isEmpty()) mime = guessMime(value);
        return new CacheMeta(mime, encoding);
    }

    private String guessMime(String value) {
        String u = value.toLowerCase(Locale.US);
        if (u.contains("/api/") || u.endsWith(".json")) return "application/json";
        if (u.endsWith(".css")) return "text/css";
        if (u.endsWith(".js")) return "application/javascript";
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".mp4")) return "video/mp4";
        if (u.endsWith(".woff2")) return "font/woff2";
        if (u.endsWith(".woff")) return "font/woff";
        if (u.endsWith(".ttf")) return "font/ttf";
        return "text/html";
    }

    private void writeCache(String value, byte[] body, String mime, String encoding) {
        try {
            String canonical = canonicalCacheUrl(value);
            String key = sha256(canonical);
            File bodyTmp = new File(offlineCacheDir, key + ".body.tmp");
            File metaTmp = new File(offlineCacheDir, key + ".meta.tmp");
            File bodyFile = new File(offlineCacheDir, key + ".body");
            File metaFile = new File(offlineCacheDir, key + ".meta");

            try (FileOutputStream out = new FileOutputStream(bodyTmp)) {
                out.write(body);
                out.getFD().sync();
            }
            Properties props = new Properties();
            props.setProperty("url", value);
            props.setProperty("canonical_url", canonical);
            props.setProperty("mime", mime == null ? "application/octet-stream" : mime);
            props.setProperty("encoding", encoding == null ? "UTF-8" : encoding);
            props.setProperty("saved_at", Long.toString(System.currentTimeMillis()));
            try (FileOutputStream out = new FileOutputStream(metaTmp)) {
                props.store(out, "HBDisplay persistent offline cache");
                out.getFD().sync();
            }

            if (bodyFile.exists() && !bodyFile.delete()) Log.w(TAG, "Could not replace cached body");
            if (metaFile.exists() && !metaFile.delete()) Log.w(TAG, "Could not replace cached metadata");
            if (!bodyTmp.renameTo(bodyFile)) throw new Exception("body rename failed");
            if (!metaTmp.renameTo(metaFile)) throw new Exception("meta rename failed");
            Log.d(TAG, "Cached " + value + " as " + canonical + " bytes=" + body.length);
        } catch (Exception e) {
            Log.w(TAG, "Cache write failed for " + value + ": " + e.getMessage());
        }
    }

    private WebResourceResponse readCache(String value) {
        try {
            String canonical = canonicalCacheUrl(value);
            String key = sha256(canonical);
            File bodyFile = new File(offlineCacheDir, key + ".body");
            File metaFile = new File(offlineCacheDir, key + ".meta");
            if (!bodyFile.isFile() || !metaFile.isFile()) return null;
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(metaFile)) {
                props.load(in);
            }
            String mime = props.getProperty("mime", guessMime(value));
            String encoding = props.getProperty("encoding", "UTF-8");
            Log.i(TAG, "OFFLINE CACHE HIT: " + value + " via " + canonical);
            return new WebResourceResponse(mime, encoding, new FileInputStream(bodyFile));
        } catch (Exception e) {
            Log.w(TAG, "Cache read failed for " + value + ": " + e.getMessage());
            return null;
        }
    }

    private boolean hasCachedUrl(String value) {
        try {
            String key = sha256(canonicalCacheUrl(value));
            return new File(offlineCacheDir, key + ".body").isFile() && new File(offlineCacheDir, key + ".meta").isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private String canonicalCacheUrl(String value) {
        if (value == null) return "";
        try {
            URL url = new URL(value);
            String query = url.getQuery();
            if (query == null || query.isEmpty()) return value;

            StringBuilder kept = new StringBuilder();
            for (String part : query.split("&")) {
                if (part == null || part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String name = eq >= 0 ? part.substring(0, eq) : part;
                // HBDisplay uses ?_=timestamp purely as a cache-buster. It must
                // not create a different persistent cache entry on every refresh.
                if ("_".equals(name)) continue;
                if (kept.length() > 0) kept.append('&');
                kept.append(part);
            }

            StringBuilder out = new StringBuilder();
            out.append(url.getProtocol()).append("://").append(url.getAuthority());
            String path = url.getPath();
            if (path != null && !path.isEmpty()) out.append(path);
            else out.append('/');
            if (kept.length() > 0) out.append('?').append(kept);
            return out.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private int getSignageScalePercent() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi > 0 ? dm.densityDpi : SIGNAGE_BASE_DPI;
        int percent = Math.round((SIGNAGE_BASE_DPI * 100f) / densityDpi);
        return Math.max(50, Math.min(100, percent));
    }

    private void applySignageViewport(WebView view) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi > 0 ? dm.densityDpi : SIGNAGE_BASE_DPI;
        float scale = Math.min(1f, (float) SIGNAGE_BASE_DPI / (float) densityDpi);
        int physicalWidth = dm.widthPixels > 0 ? dm.widthPixels : 1280;

        String js = String.format(Locale.US,
                "(function(){" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                "m.setAttribute('content','width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no');" +
                "document.documentElement.style.overflowX='hidden';" +
                "document.body.style.margin='0';" +
                "return {innerWidth:window.innerWidth,scrollWidth:document.documentElement.scrollWidth,scale:%.4f};" +
                "})()",
                physicalWidth, scale, scale, scale, scale);

        view.evaluateJavascript(js, result -> Log.i(TAG, "Viewport=" + result));
    }

    private void showOfflinePage() {
        if (offline || webView == null || pageShown || hasCachedUrl(displayUrl)) return;
        offline = true;
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>html,body{margin:0;width:100%;height:100%;background:#000;color:#fff;font-family:sans-serif;}" +
                ".c{height:100%;display:flex;align-items:center;justify-content:center;flex-direction:column;}" +
                "h1{font-size:38px;margin:0 0 12px}p{font-size:20px;opacity:.7}</style></head>" +
                "<body><div class='c'><h1>HBDisplay</h1><p>Connecting to server...</p></div></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void scheduleRetry() {
        handler.postDelayed(() -> {
            BootReceiver.requestTailscaleConnect(this);
            probeServer();
        }, RETRY_DELAY_MS);
    }

    private void probeServer() {
        if (!probeRunning.compareAndSet(false, true)) return;
        final String urlToProbe = displayUrl;
        executor.execute(() -> {
            boolean reachable = false;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlToProbe);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(5_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "HBDisplay-Player/0.2 site/" + siteCode);
                int status = connection.getResponseCode();
                reachable = status >= 200 && status < 500;
                Log.d(TAG, "Heartbeat status=" + status + " site=" + siteCode);
            } catch (Exception e) {
                Log.w(TAG, "Heartbeat failed: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                probeRunning.set(false);
            }
            final boolean serverReachable = reachable;
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || webView == null) return;
                if (serverReachable) {
                    if (offline) {
                        offline = false;
                        pageShown = false;
                        webView.loadUrl(displayUrl);
                    }
                    maybeCaptureAndUploadScreenshot();
                } else {
                    BootReceiver.requestTailscaleConnect(this);
                    if (!pageShown && !hasCachedUrl(displayUrl)) showOfflinePage();
                }
            });
        });
    }

    private void maybeCaptureAndUploadScreenshot() {
        if (webView == null || !pageShown || offline || screenshotRunning.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastScreenshotAttemptMs < SCREENSHOT_INTERVAL_MS) return;
        lastScreenshotAttemptMs = now;

        if (!screenshotRunning.compareAndSet(false, true)) return;

        webView.post(() -> {
            try {
                if (isFinishing() || isDestroyed() || webView == null || offline || !pageShown) {
                    screenshotRunning.set(false);
                    return;
                }

                int width = webView.getWidth();
                int height = webView.getHeight();
                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "Live screenshot skipped: WebView size=" + width + "x" + height);
                    screenshotRunning.set(false);
                    return;
                }

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                webView.draw(canvas);

                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, jpeg);
                bitmap.recycle();

                if (!compressed) {
                    Log.w(TAG, "Live screenshot JPEG compression failed");
                    screenshotRunning.set(false);
                    return;
                }

                byte[] payload = jpeg.toByteArray();
                executor.execute(() -> uploadLiveScreenshot(payload));
            } catch (Exception e) {
                screenshotRunning.set(false);
                Log.w(TAG, "Live screenshot capture failed: " + e.getMessage());
            }
        });
    }

    private void uploadLiveScreenshot(byte[] jpeg) {
        HttpURLConnection connection = null;
        try {
            URL base = new URL(displayUrl);
            URL endpoint = new URL(base.getProtocol() + "://" + base.getAuthority() + "/api/live/screenshot");

            String boundary = "----HBDisplay" + System.currentTimeMillis();
            String head =
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"site_code\"\r\n\r\n" +
                    siteCode + "\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"aftab-live.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";

            byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
            byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "HBDisplay-Player/0.3 site/" + siteCode);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(headBytes.length + jpeg.length + tailBytes.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(headBytes);
                out.write(jpeg);
                out.write(tailBytes);
                out.flush();
            }

            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                Log.i(TAG, "Live screenshot uploaded status=" + status + " bytes=" + jpeg.length);
            } else {
                Log.w(TAG, "Live screenshot upload HTTP " + status);
            }
        } catch (Exception e) {
            Log.w(TAG, "Live screenshot upload failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            screenshotRunning.set(false);
        }
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static class CacheMeta {
        final String mime;
        final String encoding;
        CacheMeta(String mime, String encoding) {
            this.mime = mime;
            this.encoding = encoding;
        }
    }
}
