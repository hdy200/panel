package com.panel.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int PERMISSION_CODE_13 = 101;

    private WebView webView;
    private TextureView textureView;
    private ServerSocket serverSocket;
    private int serverPort;

    private DashcamService dashcamService;
    private boolean serviceBound = false;
    private boolean surfaceReady = false;
    private boolean cameraOpened = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            dashcamService = ((DashcamService.LocalBinder) service).getService();
            serviceBound = true;
            dashcamService.setCameraStateListener(cameraStateListener);
            if (webAppInterface != null) {
                webAppInterface.setDashcamService(dashcamService);
            }
            if (surfaceReady && !cameraOpened) {
                openCamera();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private DashcamService.OnCameraStateListener cameraStateListener =
            new DashcamService.OnCameraStateListener() {
        @Override
        public void onCameraOpened() {
            cameraOpened = true;
            runOnUiThread(() -> {
                if (dashcamService != null && textureView.isAvailable()) {
                    configureTextureTransform(dashcamService.getSensorOrientation());
                }
            });
        }
        @Override
        public void onCameraError(String msg) {
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "摄像头错误: " + msg, Toast.LENGTH_LONG).show());
        }
        @Override
        public void onRecordingStarted() {}
        @Override
        public void onRecordingStopped(String savedPath) {}
    };

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
        textureView = findViewById(R.id.cameraPreview);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surfaceReady = true;
                ViewGroup.LayoutParams lp = textureView.getLayoutParams();
                DisplayMetrics dm = getResources().getDisplayMetrics();
                lp.height = (int)(dm.heightPixels * 0.35);
                textureView.setLayoutParams(lp);
                if (serviceBound && dashcamService != null) {
                    dashcamService.setPreviewSurface(new Surface(st));
                    if (!cameraOpened) openCamera();
                }
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                if (serviceBound && dashcamService != null) {
                    dashcamService.setPreviewSurface(new Surface(st));
                }
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                if (serviceBound && dashcamService != null) {
                    dashcamService.clearPreviewSurface();
                }
                return false;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        checkPermissionsAndStart();
    }

    private void openCamera() {
        if (dashcamService == null) return;
        int w = textureView.getWidth() > 0 ? textureView.getWidth() : 1280;
        int h = textureView.getHeight() > 0 ? textureView.getHeight() : 720;
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st != null) {
            dashcamService.setPreviewSurface(new Surface(st));
        }
        dashcamService.openCamera(w, h, false);
    }

    private void configureTextureTransform(int sensorOrientation) {
        if (dashcamService == null) return;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);

        Size ps = getPreviewSize();
        int bufferWidth = ps.getHeight();
        int bufferHeight = ps.getWidth();
        if (sensorOrientation == 90 || sensorOrientation == 270) {
            bufferWidth = ps.getHeight();
            bufferHeight = ps.getWidth();
        } else {
            bufferWidth = ps.getWidth();
            bufferHeight = ps.getHeight();
        }

        RectF bufferRect = new RectF(0, 0, bufferWidth, bufferHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / bufferHeight,
                    (float) viewWidth / bufferWidth);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private Size getPreviewSize() {
        if (dashcamService != null) return dashcamService.getPreviewSize();
        return new Size(1280, 720);
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

    private WebAppInterface webAppInterface;

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(0x00000000);
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

        webAppInterface = new WebAppInterface(this);
        webView.addJavascriptInterface(webAppInterface, "Android");

        Intent serviceIntent = new Intent(this, DashcamService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        webView.loadUrl("http://localhost:" + serverPort + "/dashcam.html");
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { reader.close(); client.close(); return; }

            String path = requestLine.split(" ")[1];

            OutputStream os = client.getOutputStream();

            if ("/dashcam.html".equals(path)) {
                String html = readAsset("dashcam.html");
                byte[] data = html.getBytes("UTF-8");
                os.write("HTTP/1.1 200 OK\r\n".getBytes());
                os.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
                os.write(("Content-Length: " + data.length + "\r\n").getBytes());
                os.write("Connection: close\r\n\r\n".getBytes());
                os.write(data);
            } else {
                os.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            }

            os.flush();
            os.close();
            reader.close();
            client.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
