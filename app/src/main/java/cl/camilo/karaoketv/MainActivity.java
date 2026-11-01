package cl.camilo.karaoketv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Toda la interfaz vive en assets/index.html (reproductor de YouTube + letras).
 * Esta actividad aloja el WebView y le da lo que una página web no tiene:
 *  - peticiones HTTP sin CORS, guardar la clave de YouTube y el teclado;
 *  - un pequeño servidor web en la red local para mandar canciones desde el celular;
 *  - descargar e instalar actualizaciones publicadas en GitHub.
 */
public class MainActivity extends Activity {

    // YouTube exige que la página declare un origen https propio (si no: error 152/153).
    // Se usa el identificador de la app, que no es un dominio real: solo sirve de "remitente".
    private static final String HOST = "cl.camilo.karaoketv";
    private static final String BASE_URL = "https://" + HOST + "/";
    private static final int[] PORTS = {8080, 8181, 8282};

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService serverPool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ServerSocket server;
    private volatile int serverPort = 0;
    private volatile String remoteState = "{}";

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

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Archivos propios (por ejemplo qrcode.js) se sirven desde assets.
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    String p = u.getPath();
                    if (p != null && p.length() > 1 && !p.contains("..")) {
                        try {
                            return new WebResourceResponse(mimeFor(p), "utf-8", getAssets().open(p.substring(1)));
                        } catch (IOException ignored) {
                            return null;
                        }
                    }
                }
                return null;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        startServer();
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
            case KeyEvent.KEYCODE_MEDIA_NEXT: media = "next"; break;
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
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.appResume && appResume()", null);
    }

    @Override
    protected void onDestroy() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        serverPool.shutdownNow();
        pool.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* utilidades                                                          */
    /* ------------------------------------------------------------------ */

    private static String mimeFor(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private String readAsset(String name) {
        try {
            return new String(readAssetBytes(name), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "<h1 style='color:white'>No se pudo cargar la interfaz: " + ex + "</h1>";
        }
    }

    private byte[] readAssetBytes(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
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

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String name = ni.getName() == null ? "" : ni.getName();
                    if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("eth"))) return ip;
                    if (a.isSiteLocalAddress() && fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* servidor local: el celular abre http://IP-DE-LA-TV:8080             */
    /* ------------------------------------------------------------------ */

    private void startServer() {
        Thread t = new Thread(() -> {
            for (int port : PORTS) {
                try {
                    server = new ServerSocket(port);
                    serverPort = port;
                    break;
                } catch (IOException ignored) {
                }
            }
            if (server == null) return;
            while (!server.isClosed()) {
                try {
                    final Socket sock = server.accept();
                    serverPool.execute(() -> handle(sock));
                } catch (IOException e) {
                    break;
                } catch (Exception e) {
                    // pool cerrado al salir
                    break;
                }
            }
        }, "karaoke-remote");
        t.setDaemon(true);
        t.start();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int len = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try {
                        len = Integer.parseInt(h.substring(c + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            byte[] body = new byte[Math.max(0, Math.min(len, 65536))];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) break;
                off += n;
            }
            String text = new String(body, 0, off, StandardCharsets.UTF_8);

            OutputStream out = sock.getOutputStream();
            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", readAssetBytes("remote.html"));
            } else if ("GET".equals(method) && path.equals("/api/state")) {
                send(out, 200, "application/json; charset=utf-8", remoteState.getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(method) && path.startsWith("/api/") && path.length() < 32) {
                String kind = path.substring(5);
                callJs("window.remoteCmd && remoteCmd(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")");
                send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else {
                send(out, 404, "text/plain; charset=utf-8", "No encontrado".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* actualizaciones                                                     */
    /* ------------------------------------------------------------------ */

    private void updateStatus(String s) {
        callJs("window.updateStatus && updateStatus(" + JSONObject.quote(s) + ")");
    }

    private void downloadAndInstall(String url) {
        HttpURLConnection c = null;
        try {
            updateStatus("Descargando…");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "KaraokeTV");
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            long total = c.getContentLength();
            File f = new File(getCacheDir(), "update.apk");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                long got = 0, last = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - last > 300) {
                        last = now;
                        updateStatus("Descargando… " + (got * 100 / total) + "%");
                    }
                }
            }
            final Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("content://" + getPackageName() + ".apk/update.apk"),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            main.post(() -> {
                try {
                    startActivity(i);
                    updateStatus("Pulsa «Instalar» en la ventana de Android.");
                } catch (Exception e) {
                    updateStatus("No se pudo abrir el instalador: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            updateStatus("Error al descargar: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ------------------------------------------------------------------ */
    /* métodos que la página llama como window.Android.xxx()               */
    /* ------------------------------------------------------------------ */

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
        public int getVersion() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
                //noinspection deprecation
                return pi.versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public String getRemoteUrl() {
            String ip = localIp();
            if (ip == null || serverPort == 0) return "";
            return "http://" + ip + (serverPort == 80 ? "" : ":" + serverPort);
        }

        @JavascriptInterface
        public void setRemoteState(String json) {
            remoteState = json == null ? "{}" : json;
        }

        @JavascriptInterface
        public void installUpdate(final String url) {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                main.post(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                        updateStatus("Activa «Permitir de esta fuente», vuelve con ATRÁS y pulsa Actualizar otra vez.");
                    } catch (Exception e) {
                        updateStatus("En Ajustes de la TV permite que Karaoke TV instale apps desconocidas y vuelve a intentar.");
                    }
                });
                return;
            }
            pool.execute(() -> downloadAndInstall(url));
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
