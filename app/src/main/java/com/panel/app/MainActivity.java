package com.panel.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "Dashcam";
    private static final int PERM_REQ = 100;
    private static final long SEGMENT_MS = 60000;
    private static final Size RECORD_SIZE = new Size(1280, 720);

    private TextureView textureView;
    private Button btnRecord, btnPhoto, btnExit;
    private TextView ovTime, ovGPS, ovRecTime, ovLabel;
    private TextView clockDisplay, clockDate, speedDisplay;
    private TextView gpsText, latDisplay, lngDisplay, recTime;
    private View gpsIndicator;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String cameraId;
    private Surface previewSurface;
    private volatile boolean isRecording;
    private int sensorOrientation;
    private int cameraFacing = CameraCharacteristics.LENS_FACING_BACK;

    private LocationManager locationManager;
    private boolean gpsFixed;
    private double currentLat, currentLng, currentSpeed;

    private long recStartTime;
    private String currentSaveUri;
    private String subDir = "Dashcam";
    private boolean cameraReady;
    private boolean permissionsOk;
    private boolean surfaceOk;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            mainHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            updateOverlay();
            mainHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.cameraPreview);
        btnRecord = findViewById(R.id.btnRecord);
        btnPhoto = findViewById(R.id.btnPhoto);
        btnExit = findViewById(R.id.btnExit);
        ovLabel = findViewById(R.id.ovLabel);
        ovTime = findViewById(R.id.ovTime);
        ovGPS = findViewById(R.id.ovGPS);
        ovRecTime = findViewById(R.id.ovRecTime);
        clockDisplay = findViewById(R.id.clockDisplay);
        clockDate = findViewById(R.id.clockDate);
        speedDisplay = findViewById(R.id.speedDisplay);
        gpsText = findViewById(R.id.gpsText);
        latDisplay = findViewById(R.id.latDisplay);
        lngDisplay = findViewById(R.id.lngDisplay);
        recTime = findViewById(R.id.recTime);
        gpsIndicator = findViewById(R.id.gpsIndicator);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        textureView.setSurfaceTextureListener(surfaceListener);

        btnRecord.setOnClickListener(v -> toggleRecord());
        btnPhoto.setOnClickListener(v -> takePhoto());
        btnExit.setOnClickListener(v -> exitApp());

        checkPermissions();
    }

    // ========== Permissions ==========

    private void checkPermissions() {
        String[] needed = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION };
        boolean ok = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        }
        if (ok) {
            start();
        } else {
            ActivityCompat.requestPermissions(this, needed, PERM_REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQ) {
            start();
        }
    }

    private void start() {
        permissionsOk = true;
        startGPS();
        mainHandler.post(clockTick);
        mainHandler.post(overlayTick);
        tryOpenCamera();
    }

    private void tryOpenCamera() {
        if (permissionsOk && surfaceOk && previewSurface != null && previewSurface.isValid()) {
            openCamera();
        }
    }

    // ========== TextureView Surface ==========

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            previewSurface = new Surface(st);
            surfaceOk = true;
            tryOpenCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            previewSurface = new Surface(st);
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            previewSurface = null;
            surfaceOk = false;
            closeCamera();
            return false;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    };

    // ========== Camera ==========

    private void openCamera() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == cameraFacing) { cameraId = id; break; }
            }
            if (cameraId == null) cameraId = ids[0];
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    cameraDevice = d;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); }
            }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera", e);
        }
    }

    private void closeCamera() {
        closeSession();
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        cameraReady = false;
    }

    private void closeSession() {
        if (captureSession != null) {
            try { captureSession.abortCaptures(); } catch (Exception ignored) {}
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
    }

    private void startPreview() {
        if (cameraDevice == null || previewSurface == null || !previewSurface.isValid()) return;
        closeSession();
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            captureSession = s;
                            cameraReady = true;
                            try { s.setRepeatingRequest(b.build(), null, mainHandler); } catch (Exception ignored) {}
                            mainHandler.postDelayed(() -> { if (!isRecording) autoRecord(); }, 1000);
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {}
                    }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "startPreview", e);
        }
    }

    // ========== Recording ==========

    private void toggleRecord() {
        if (isRecording) { stopRecording(); }
        else { startRecording(); }
    }

    private void startRecording() {
        if (cameraDevice == null || isRecording || !cameraReady) return;
        closeSession();
        try {
            MediaRecorder mr = new MediaRecorder();
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setVideoSize(RECORD_SIZE.getWidth(), RECORD_SIZE.getHeight());
            mr.setVideoFrameRate(30);
            mr.setVideoEncodingBitRate(5000000);
            mr.setAudioSamplingRate(44100);
            mr.setAudioEncodingBitRate(96000);

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "dashcam_" + ts + ".mp4";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + subDir);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) { startPreview(); return; }
                currentSaveUri = uri.toString();
                mr.setOutputFile(getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor());
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), subDir);
                if (!dir.exists()) dir.mkdirs();
                currentSaveUri = new File(dir, fileName).getAbsolutePath();
                mr.setOutputFile(currentSaveUri);
            }

            mr.setOrientationHint(sensorOrientation);
            mr.prepare();

            Surface recorderSurface = mr.getSurface();
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(recorderSurface);
            if (previewSurface != null && previewSurface.isValid()) {
                surfaces.add(previewSurface);
            }

            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface s : surfaces) b.addTarget(s);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            captureSession = s;
                            mediaRecorder = mr;
                            isRecording = true;
                            recStartTime = System.currentTimeMillis();
                            try { s.setRepeatingRequest(b.build(), null, mainHandler); } catch (Exception ignored) {}
                            mr.start();
                            updateRecUI(true);
                            Toast.makeText(MainActivity.this, "⏺ 开始录像", Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            mr.release();
                            startPreview();
                        }
                    }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "startRecording", e);
            startPreview();
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (currentSaveUri != null) {
            if (currentSaveUri.startsWith("content://")) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(Uri.parse(currentSaveUri), cv, null, null);
                } catch (Exception ignored) {}
            } else {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, new File(currentSaveUri).getName());
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    cv.put(MediaStore.MediaColumns.DATA, currentSaveUri);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + subDir);
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    }
                    getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                } catch (Exception ignored) {}
            }
            Toast.makeText(this, "✅ 录像已保存: " + currentSaveUri, Toast.LENGTH_LONG).show();
            currentSaveUri = null;
        }
        closeSession();
        startPreview();
        updateRecUI(false);
    }

    private void autoRecord() {
        if (!isRecording && cameraReady) startRecording();
    }

    // called periodically to check for 60s segment end
    private void checkSegment() {
        if (!isRecording || recStartTime == 0) return;
        if (System.currentTimeMillis() - recStartTime >= SEGMENT_MS) {
            stopRecording();
            mainHandler.postDelayed(this::startRecording, 300);
        }
    }

    // ========== Photo ==========

    private void takePhoto() {
        if (cameraDevice == null || captureSession == null) return;
        try {
            ImageReader reader = ImageReader.newInstance(RECORD_SIZE.getWidth(), RECORD_SIZE.getHeight(), ImageFormat.JPEG, 1);
            Surface photoSurface = reader.getSurface();

            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(photoSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "photo_" + ts + ".jpg";

            reader.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireLatestImage()) {
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);

                    Uri savedUri = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + subDir);
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (uri != null) {
                            OutputStream os = getContentResolver().openOutputStream(uri);
                            if (os != null) { os.write(bytes); os.close(); }
                            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            getContentResolver().update(uri, cv, null, null);
                            savedUri = uri;
                        }
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), subDir);
                        if (!dir.exists()) dir.mkdirs();
                        File f = new File(dir, fileName);
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(bytes); fos.close();
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                        cv.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
                        savedUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    }
                    if (savedUri != null) {
                        Toast.makeText(MainActivity.this, "📸 照片已保存: " + savedUri, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "photo save", e);
                }
                reader.close();
                resumeAfterPhoto();
            }, mainHandler);

            captureSession.stopRepeating();
            captureSession.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
                    resumeAfterPhoto();
                }
            }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "takePhoto", e);
            resumeAfterPhoto();
        }
    }

    private void resumeAfterPhoto() {
        if (cameraDevice == null) return;
        closeSession();
        try {
            List<Surface> targets = new ArrayList<>();
            int tpl = isRecording && mediaRecorder != null ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(tpl);
            if (isRecording && mediaRecorder != null) {
                Surface rs = mediaRecorder.getSurface();
                if (rs != null && rs.isValid()) { targets.add(rs); b.addTarget(rs); }
            }
            if (previewSurface != null && previewSurface.isValid()) { targets.add(previewSurface); b.addTarget(previewSurface); }
            if (targets.isEmpty()) return;
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    captureSession = s;
                    try { s.setRepeatingRequest(b.build(), null, mainHandler); } catch (Exception ignored) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {}
            }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "resumeAfterPhoto", e);
        }
    }

    // ========== GPS ==========

    private void startGPS() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
        } catch (Exception e) {
            runOnUiThread(() -> gpsText.setText("GPS不可用"));
        }
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            currentLat = loc.getLatitude();
            currentLng = loc.getLongitude();
            currentSpeed = loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0;
            gpsFixed = true;
            runOnUiThread(() -> {
                latDisplay.setText(String.format(Locale.US, "%.6f", currentLat));
                lngDisplay.setText(String.format(Locale.US, "%.6f", currentLng));
                speedDisplay.setText(String.valueOf(Math.round(currentSpeed)));
                gpsText.setText("GPS 已定位");
                gpsIndicator.setBackgroundColor(0xff34c759);
            });
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {
            runOnUiThread(() -> {
                gpsText.setText("GPS 已关闭");
                gpsIndicator.setBackgroundColor(0xffff3b30);
            });
        }
    };

    // ========== UI Updates ==========

    private void updateClock() {
        Date d = new Date();
        clockDisplay.setText(String.format("%02d:%02d", d.getHours(), d.getMinutes()));
        String[] dow = {"日","一","二","三","四","五","六"};
        clockDate.setText(d.getYear() + 1900 + "年" + (d.getMonth() + 1) + "月" + d.getDate() + "日 周" + dow[d.getDay()]);
    }

    private void updateOverlay() {
        Date d = new Date();
        String timeStr = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                d.getYear() + 1900, d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds());
        ovTime.setText(timeStr);
        String gpsStr = gpsFixed ? String.format(Locale.US, "GPS: %.6f, %.6f  %d km/h", currentLat, currentLng, Math.round(currentSpeed)) : "GPS: --";
        ovGPS.setText(gpsStr);

        if (isRecording) {
            long sec = (System.currentTimeMillis() - recStartTime) / 1000;
            ovRecTime.setText(String.format("%02d:%02d", sec / 60, sec % 60));
            ovRecTime.setVisibility(View.VISIBLE);
            recTime.setVisibility(View.VISIBLE);
            recTime.setText(String.format("%02d:%02d", sec / 60, sec % 60));
            checkSegment();
        }
    }

    private void updateRecUI(boolean on) {
        runOnUiThread(() -> {
            if (on) {
                btnRecord.setText("停止录像");
                btnRecord.setBackgroundResource(R.drawable.btn_red);
            } else {
                btnRecord.setText("开始录像");
                btnRecord.setBackgroundResource(R.drawable.btn_gray);
            }
        });
    }

    // ========== Exit ==========

    private void exitApp() {
        if (isRecording) stopRecording();
        closeCamera();
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ========== Lifecycle ==========

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing() || isChangingConfigurations()) {
            if (isRecording) stopRecording();
            closeCamera();
        }
    }

    @Override
    protected void onDestroy() {
        if (isRecording) stopRecording();
        closeCamera();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
