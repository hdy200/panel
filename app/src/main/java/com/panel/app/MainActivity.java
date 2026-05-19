package com.panel.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_CODE_13 = 101;

    private WebView webView;
    private ServerSocket serverSocket;
    private int serverPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        checkPermissionsAndStart();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(0xff000000);
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        Intent serviceIntent = new Intent(this, DashcamService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        webView.loadUrl("http://localhost:" + serverPort + "/dashcam.html");
    }

    private void checkPermissionsAndStart() {
        String[] needed = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
        };

        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_CODE_13);
            }
        }

        if (allGranted) {
            startLocalServer();
        } else {
            ActivityCompat.requestPermissions(this, needed, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "权限被拒绝: " + permissions[i], Toast.LENGTH_LONG).show();
                }
            }
            startLocalServer();
        }
    }

    private void startLocalServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
                serverPort = serverSocket.getLocalPort();
                runOnUiThread(this::setupWebView);
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    handleRequest(client);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleRequest(Socket client) {
        try {
            InputStream in = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) { reader.close(); client.close(); return; }

            String method = requestLine.split(" ")[0];
            String path = requestLine.split(" ")[1];

            OutputStream os = client.getOutputStream();
            byte[] responseBody;
            String contentType = "text/html; charset=UTF-8";

            if ("POST".equals(method) && "/saveFile".equals(path)) {
                String fileName = null, mimeType = "video/mp4", dirType = "MOVIES", subDir = "Dashcam";
                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Content-Length:")) contentLength = Integer.parseInt(line.substring(15).trim());
                    if (line.startsWith("X-File-Name:")) fileName = URLDecoder.decode(line.substring(12).trim(), "UTF-8");
                    if (line.startsWith("X-File-Type:")) mimeType = line.substring(12).trim();
                    if (line.startsWith("X-Dir-Type:")) dirType = line.substring(11).trim();
                    if (line.startsWith("X-Sub-Dir:")) subDir = URLDecoder.decode(line.substring(10).trim(), "UTF-8");
                }

                byte[] body = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = in.read(body, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }

                if (fileName == null) fileName = "file_" + System.currentTimeMillis() + ".mp4";
                String savedUri = saveFileToMediaStore(body, fileName, mimeType, dirType, subDir);
                String resp = savedUri != null ? savedUri : "ERROR";
                responseBody = resp.getBytes("UTF-8");
                contentType = "text/plain; charset=UTF-8";
            } else if ("/dashcam.html".equals(path)) {
                String html = readAsset("dashcam.html");
                responseBody = html.getBytes("UTF-8");
            } else {
                responseBody = "404".getBytes("UTF-8");
            }

            os.write("HTTP/1.1 200 OK\r\n".getBytes());
            os.write(("Content-Type: " + contentType + "\r\n").getBytes());
            os.write(("Content-Length: " + responseBody.length + "\r\n").getBytes());
            os.write("Connection: close\r\n\r\n".getBytes());
            os.write(responseBody);
            os.flush();
            os.close();
            reader.close();
            client.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String saveFileToMediaStore(byte[] data, String fileName, String mimeType, String dirType, String subDir) {
        if (subDir == null || subDir.isEmpty()) subDir = "Dashcam";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String relativePath;
                if ("PICTURES".equals(dirType)) relativePath = Environment.DIRECTORY_PICTURES + "/" + subDir;
                else if ("DCIM".equals(dirType)) relativePath = Environment.DIRECTORY_DCIM + "/" + subDir;
                else relativePath = Environment.DIRECTORY_MOVIES + "/" + subDir;

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri;
                if (mimeType.startsWith("video")) {
                    uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                } else {
                    uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                }
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) { os.write(data); os.close(); }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    return uri.toString();
                }
            } else {
                String root;
                if ("PICTURES".equals(dirType)) root = Environment.DIRECTORY_PICTURES;
                else if ("DCIM".equals(dirType)) root = Environment.DIRECTORY_DCIM;
                else root = Environment.DIRECTORY_MOVIES;

                File dir = new File(Environment.getExternalStoragePublicDirectory(root), subDir);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());

                Uri uri;
                if (mimeType.startsWith("video")) {
                    uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                } else {
                    uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                }
                return uri != null ? uri.toString() : file.getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String readAsset(String filename) throws Exception {
        InputStream is = getAssets().open(filename);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toString("UTF-8");
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}
