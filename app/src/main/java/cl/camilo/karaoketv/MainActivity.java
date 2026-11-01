package cl.camilo.karaoketv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Toda la interfaz vive en assets/index.html (reproductor de YouTube + letras).
 * Esta actividad solo aloja el WebView y le da tres cosas que una página web
 * no tiene: peticiones HTTP sin CORS, guardar la clave de YouTube y el teclado.
 */
public class MainActivity extends Activity {

    // YouTube exige que la página declare un origen https propio (si no: error 152/153).
    // Se usa el identificador de la app, que no es un dominio real: solo sirve de "remitente".
    private static final String BASE_URL = "https://cl.camilo.karaoketv/";

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("karaoke", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Nunca dejar que la página principal navegue fuera de la app.
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        web.loadDataWithBaseURL(BASE_URL, readAsset("index.html"), "text/html", "utf-8", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.appBack ? appBack() : false", value -> {
                    if (!"true".equals(value)) finish();
                });
            }
            return true;
        }
        String media = null;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: media = "playpause"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: media = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: media = "pause"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: media = "ff"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: media = "rw"; break;
        }
        if (media != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                web.evaluateJavascript("window.appMedia && appMedia('" + media + "')", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.appPause && appPause()", null);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<h1 style='color:white'>No se pudo cargar la interfaz: " + ex + "</h1>";
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void callJs(String script) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    /** Métodos que la página llama como window.Android.xxx() */
    private class Bridge {

        @JavascriptInterface
        public String getKey() {
            return prefs.getString("ytKey", "");
        }

        @JavascriptInterface
        public void setKey(String key) {
            prefs.edit().putString("ytKey", key == null ? "" : key.trim()).apply();
        }

        @JavascriptInterface
        public void httpGet(final String url, final String callbackId) {
            pool.execute(() -> {
                int status = 0;
                String body;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "KaraokeTV/1.0 (Android TV; uso personal)");
                    c.setRequestProperty("Accept", "application/json");
                    status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    body = String.valueOf(ex.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                callJs("window.__httpDone(" + JSONObject.quote(callbackId) + "," + status + ","
                        + JSONObject.quote(body) + ")");
            });
        }

        @JavascriptInterface
        public void showKeyboard() {
            main.post(() -> {
                if (web == null) return;
                web.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface
        public void hideKeyboard() {
            main.post(() -> {
                if (web == null) return;
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            });
        }
    }
}
