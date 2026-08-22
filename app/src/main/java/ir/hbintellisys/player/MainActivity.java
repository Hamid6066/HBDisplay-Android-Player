package ir.hbintellisys.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
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

    // The Setareh/AFTAB dashboard is visually much cleaner when rendered on a
    // slightly wider virtual canvas and then fitted to the physical 5:4 panel.
    // This prevents the three dashboard columns from becoming cramped while
    // still filling the complete 1280x1024 display without cropping.
    private static final int SIGNAGE_VIRTUAL_WIDTH_CSS = 1440;

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeRunning = new AtomicBoolean(false);
    private boolean offline = false;
    private String displayUrl;
    private String siteCode;

    private final Runnable healthLoop = new Runnable() {
        @Override
        public void run() {
            probeServer();
            handler.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        applyImmersiveMode();
        loadConfiguration(getIntent());
        BootReceiver.requestTailscaleConnect(this);
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
        if (webView != null && !displayUrl.equals(oldUrl)) {
            offline = false;
            webView.loadUrl(displayUrl);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        BootReceiver.requestTailscaleConnect(this);
    }

    @Override
    protected void onDestroy() {
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
            if (suppliedUrl != null && (suppliedUrl.startsWith("http://") || suppliedUrl.startsWith("https://"))) {
                savedUrl = suppliedUrl.trim();
            }
            if (suppliedSite != null && !suppliedSite.trim().isEmpty()) {
                savedSite = suppliedSite.trim();
            }
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
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(TAG, "Loading: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    offline = false;
                    applySignageViewport(view);
                }
                applyImmersiveMode();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    Log.w(TAG, "Main page load failed: " + error);
                    showOfflinePage();
                    scheduleRetry();
                }
            }
        });
    }

    private void applySignageViewport(WebView view) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density <= 0f ? 1f : dm.density;
        float cssDeviceWidth = dm.widthPixels / density;
        float scale = cssDeviceWidth / SIGNAGE_VIRTUAL_WIDTH_CSS;

        // Keep a sane bound in case an unusual OEM reports bad metrics.
        scale = Math.max(0.45f, Math.min(1.0f, scale));
        final float finalScale = scale;

        String js = String.format(Locale.US,
                "(function(){" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                "m.setAttribute('content','width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no');" +
                "document.documentElement.style.minWidth='%dpx';" +
                "document.documentElement.style.width='%dpx';" +
                "document.documentElement.style.overflowX='hidden';" +
                "document.body.style.minWidth='%dpx';" +
                "document.body.style.width='%dpx';" +
                "document.body.style.margin='0';" +
                "void(document.body.offsetWidth);" +
                "return {w:window.innerWidth,dw:document.documentElement.scrollWidth,scale:%.4f};" +
                "})()",
                SIGNAGE_VIRTUAL_WIDTH_CSS,
                finalScale, finalScale, finalScale,
                SIGNAGE_VIRTUAL_WIDTH_CSS,
                SIGNAGE_VIRTUAL_WIDTH_CSS,
                SIGNAGE_VIRTUAL_WIDTH_CSS,
                SIGNAGE_VIRTUAL_WIDTH_CSS,
                finalScale);

        view.evaluateJavascript(js, result ->
                Log.i(TAG, "Signage viewport applied: " + result));
    }

    private void showOfflinePage() {
        if (offline || webView == null) {
            return;
        }
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
        if (!probeRunning.compareAndSet(false, true)) {
            return;
        }
        final String urlToProbe = displayUrl;
        executor.execute(() -> {
            boolean reachable = false;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlToProbe);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(4_000);
                connection.setReadTimeout(5_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "HBDisplay-Player/0.1 site/" + siteCode);
                int status = connection.getResponseCode();
                reachable = status >= 200 && status < 500;
                Log.d(TAG, "Heartbeat status=" + status + " site=" + siteCode);
            } catch (Exception e) {
                Log.w(TAG, "Heartbeat failed: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                probeRunning.set(false);
            }

            final boolean serverReachable = reachable;
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || webView == null) {
                    return;
                }
                if (serverReachable) {
                    if (offline) {
                        offline = false;
                        webView.loadUrl(displayUrl);
                    }
                } else {
                    BootReceiver.requestTailscaleConnect(this);
                    showOfflinePage();
                }
            });
        });
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
