package hu.petya.txtrobot;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";
    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);

    protected WebView web;
    private boolean pageReady = false;
    private Intent pendingIntent;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;

    /** A szövegkijelölő menüből indított gyors mentés felülírja. */
    protected boolean isQuickMode() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CookieManager());
        prefs = getSharedPreferences("txtrobot", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingIntent != null) {
                    Intent i = pendingIntent;
                    pendingIntent = null;
                    deliver(i);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                openExternal(request.getUrl().toString());
                return true;
            }
        });
        pendingIntent = getIntent();
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) deliver(intent);
        else pendingIntent = intent;
    }

    private void deliver(Intent i) {
        if (i == null) return;
        String action = i.getAction();
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            callJs("onIncomingText", t == null ? "" : t.toString(), "");
        } else if (Intent.ACTION_SEND.equals(action)) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String sub = i.getStringExtra(Intent.EXTRA_SUBJECT);
            callJs("onIncomingShare", t == null ? "" : t.toString(), sub == null ? "" : sub);
        }
        // Egyszer feldolgozva: ne fusson le újra (pl. képernyő-elforgatáskor)
        i.setAction(Intent.ACTION_MAIN);
    }

    private void callJs(String fn, String a, String b) {
        String js = "window." + fn + " && window." + fn + "(" + JSONObject.quote(a) + "," + JSONObject.quote(b) + ")";
        web.evaluateJavascript(js, null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack ? String(window.onBack()) : 'false'", value -> {
            if (value == null || !value.contains("true")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Resp {
        int code;
        String url;
        String body;
    }

    private Resp httpGet(String start) throws IOException {
        String cur = start;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "sr,hr;q=0.9,hu;q=0.8,en;q=0.7");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Átirányítás cél nélkül");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            String ct = c.getContentType();
            c.disconnect();
            Resp r = new Resp();
            r.code = code;
            r.url = cur;
            r.body = new String(data, detectCharset(ct, data));
            return r;
        }
        throw new IOException("Túl sok átirányítás");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 8 * 1024 * 1024) break;
            }
            return bo.toByteArray();
        }
    }

    private static Charset detectCharset(String contentType, byte[] data) {
        String cs = null;
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) cs = m.group(1);
        }
        if (cs == null) {
            String head = new String(data, 0, Math.min(data.length, 8192), StandardCharsets.ISO_8859_1);
            Matcher m = CHARSET.matcher(head);
            if (m.find()) cs = m.group(1);
        }
        try {
            if (cs != null) return Charset.forName(cs.trim());
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    // ---------------------------------------------------------------- Fájlok

    private static String safe(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", " ").replaceAll("\\s+", " ").trim();
    }

    private String dirPart(String sub) {
        String base = safe(prefs.getString("baseFolder", "Dalszövegek"));
        if (base.isEmpty()) base = "Dalszövegek";
        String s = safe(sub);
        return s.isEmpty() ? base : base + "/" + s;
    }

    private Uri findExisting(ContentResolver cr, Uri coll, String rel, String name) {
        try (Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{rel, name}, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return Uri.withAppendedPath(coll, String.valueOf(id));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String writeFile(String sub, String rawName, String content) throws IOException {
        String name = safe(rawName);
        if (name.isEmpty()) name = "dalszoveg.txt";
        if (!name.toLowerCase().endsWith(".txt")) name += ".txt";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String dir = dirPart(sub);

        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_DOCUMENTS + "/" + dir + "/";
            ContentResolver cr = getContentResolver();
            Uri coll = MediaStore.Files.getContentUri("external");
            Uri uri = findExisting(cr, coll, rel, name);
            if (uri == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = cr.insert(coll, cv);
                if (uri == null) throw new IOException("Nem sikerült létrehozni a fájlt");
            }
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) throw new IOException("Nem írható");
                os.write(bytes);
            }
            return rel + name;
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), dir);
            if (!d.exists() && !d.mkdirs()) throw new IOException("Nem hozható létre a mappa: " + d);
            File f = new File(d, name);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(bytes);
            }
            return f.getAbsolutePath();
        }
    }

    private boolean exists(String sub, String rawName) {
        String name = safe(rawName);
        if (!name.toLowerCase().endsWith(".txt")) name += ".txt";
        String dir = dirPart(sub);
        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_DOCUMENTS + "/" + dir + "/";
            return findExisting(getContentResolver(), MediaStore.Files.getContentUri("external"), rel, name) != null;
        }
        File f = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), dir), name);
        return f.exists();
    }

    // ---------------------------------------------------------------- JS híd

    private class Bridge {

        @JavascriptInterface
        public void fetch(final String id, final String url) {
            pool.submit(() -> {
                boolean ok = false;
                int status = 0;
                String finalUrl = url;
                String body;
                try {
                    Resp r = httpGet(url);
                    status = r.code;
                    finalUrl = r.url;
                    ok = r.code >= 200 && r.code < 300;
                    body = r.body;
                } catch (Exception e) {
                    body = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String js = "window.__fetchDone(" + JSONObject.quote(id) + "," + ok + "," + status + ","
                        + JSONObject.quote(finalUrl) + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }

        @JavascriptInterface
        public String saveTxt(String sub, String name, String content) {
            JSONObject o = new JSONObject();
            try {
                String p = writeFile(sub, name, content == null ? "" : content);
                o.put("ok", true);
                o.put("path", p);
            } catch (Exception e) {
                try {
                    o.put("ok", false);
                    o.put("err", e.getMessage() == null ? e.toString() : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            return o.toString();
        }

        @JavascriptInterface
        public boolean fileExists(String sub, String name) {
            try {
                return exists(sub, name);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String getPref(String key, String def) {
            return prefs.getString(key, def);
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return "";
                CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public boolean isQuick() {
            return isQuickMode();
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(MainActivity.this::finish);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
