package com.panel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashcamService extends Service {
    private static final String TAG = "DashcamService";
    private static final String CHANNEL_ID = "dashcam_channel";
    private static final int NOTIFICATION_ID = 1;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String cameraId;
    private Size previewSize = new Size(1280, 720);
    private Surface previewSurface;
    private volatile boolean isRecording = false;
    private boolean cameraOpen = false;
    private String currentFilePath;
    private String currentDirType = "MOVIES";
    private String currentSubDir = "Dashcam";
    private int cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int sensorOrientation = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnCameraStateListener cameraStateListener;
    private ImageReader photoReader;

    interface OnCameraStateListener {
        void onCameraOpened();
        void onCameraError(String msg);
        void onRecordingStarted();
        void onRecordingStopped(String savedPath);
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends android.os.Binder {
        DashcamService getService() { return DashcamService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("运行中"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        stopRecordingInternal();
        closeCamera();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "行车记录仪", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台录像通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("行车记录仪")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {}
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    public void setCameraStateListener(OnCameraStateListener listener) {
        this.cameraStateListener = listener;
    }

    // ========== Camera open / close ==========

    public void openCamera(int width, int height, boolean useFrontCamera) {
        previewSize = new Size(width, height);
        cameraFacing = useFrontCamera
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        runOnMain(this::doOpenCamera);
    }

    private void doOpenCamera() {
        if (cameraOpen) return;
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == cameraFacing) {
                    cameraId = id;
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    break;
                }
            }
            if (cameraId == null) {
                cameraId = cameraManager.getCameraIdList()[0];
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    cameraOpen = true;
                    startPreviewSession();
                    if (cameraStateListener != null)
                        cameraStateListener.onCameraOpened();
                }
                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraOpen = false;
                }
                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    cameraOpen = false;
                    if (cameraStateListener != null)
                        cameraStateListener.onCameraError("Camera error: " + error);
                }
            }, mainHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            if (cameraStateListener != null)
                cameraStateListener.onCameraError(e.getMessage());
        }
    }

    // ========== Preview surface management ==========

    public void setPreviewSurface(Surface surface) {
        runOnMain(() -> {
            this.previewSurface = surface;
            if (!isRecording && cameraOpen && surface != null && surface.isValid()) {
                startPreviewSession();
            }
        });
    }

    public void clearPreviewSurface() {
        runOnMain(() -> {
            this.previewSurface = null;
            if (!isRecording) {
                closeCaptureSession();
            }
        });
    }

    // ========== Preview (no recording) ==========

    public void startPreviewSession() {
        runOnMain(() -> {
            if (cameraDevice == null || isRecording) return;
            if (previewSurface == null || !previewSurface.isValid()) return;
            closeCaptureSession();
            try {
                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                cameraDevice.createCaptureSession(
                        java.util.Collections.singletonList(previewSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(builder.build(), null, mainHandler);
                                } catch (Exception e) {}
                            }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "preview session failed");
                            }
                        }, mainHandler);
            } catch (Exception e) {
                Log.e(TAG, "startPreviewSession failed", e);
            }
        });
    }

    // ========== Recording ==========

    public void startRecording(String dirType, String subDir) {
        runOnMain(() -> {
            if (cameraDevice == null || isRecording || !cameraOpen) return;
            currentDirType = dirType != null ? dirType : "MOVIES";
            currentSubDir = subDir != null && !subDir.isEmpty() ? subDir : "Dashcam";

            try {
                closeCaptureSession();

                MediaRecorder mr = new MediaRecorder();
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mr.setAudioSource(MediaRecorder.AudioSource.MIC);
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mr.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
                mr.setVideoFrameRate(30);
                mr.setVideoEncodingBitRate(5000000);
                mr.setAudioSamplingRate(44100);
                mr.setAudioEncodingBitRate(96000);

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "dashcam_" + ts + ".mp4";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String relativePath = Environment.DIRECTORY_MOVIES + "/" + currentSubDir;
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        currentFilePath = uri.toString();
                        mr.setOutputFile(getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor());
                    } else { return; }
                } else {
                    File movieDir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES), currentSubDir);
                    if (!movieDir.exists()) movieDir.mkdirs();
                    currentFilePath = new File(movieDir, fileName).getAbsolutePath();
                    mr.setOutputFile(currentFilePath);
                }

                mr.setOrientationHint(sensorOrientation);
                mr.prepare();

                Surface recorderSurface = mr.getSurface();

                List<Surface> surfaces = new ArrayList<>();
                surfaces.add(recorderSurface);
                if (previewSurface != null && previewSurface.isValid()) {
                    surfaces.add(previewSurface);
                }

                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                for (Surface s : surfaces) builder.addTarget(s);
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

                cameraDevice.createCaptureSession(surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                mediaRecorder = mr;
                                isRecording = true;
                                try {
                                    session.setRepeatingRequest(builder.build(), null, mainHandler);
                                } catch (Exception e) {}
                                mr.start();
                                updateNotification("⏺ 录像中");
                                if (cameraStateListener != null)
                                    cameraStateListener.onRecordingStarted();
                            }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "record session configure failed");
                                mr.release();
                            }
                        }, mainHandler);

            } catch (Exception e) {
                Log.e(TAG, "startRecording failed", e);
            }
        });
    }

    public void stopRecording() {
        runOnMain(this::stopRecordingInternal);
    }

    private void stopRecordingInternal() {
        if (!isRecording) return;
        isRecording = false;

        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception e) {}
            mediaRecorder.release();
            mediaRecorder = null;
        }

        if (currentFilePath != null && !currentFilePath.startsWith("content://")) {
            File file = new File(currentFilePath);
            if (file.exists()) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                values.put(MediaStore.MediaColumns.DATA, currentFilePath);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String rp = Environment.DIRECTORY_MOVIES + "/" +
                            (currentSubDir != null && !currentSubDir.isEmpty() ? currentSubDir : "Dashcam");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, rp);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                }
                try {
                    Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (cameraStateListener != null && uri != null)
                        cameraStateListener.onRecordingStopped(uri.toString());
                } catch (Exception e) {}
            }
        } else if (currentFilePath != null && currentFilePath.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(currentFilePath);
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                if (cameraStateListener != null)
                    cameraStateListener.onRecordingStopped(currentFilePath);
            } catch (Exception e) {}
        }

        currentFilePath = null;
        closeCaptureSession();
        startPreviewSession();
        updateNotification("运行中");
    }

    // ========== Photo capture ==========

    public void takePhoto() {
        runOnMain(() -> {
            if (cameraDevice == null || captureSession == null) return;
            try {
                int w = previewSize.getWidth();
                int h = previewSize.getHeight();

                ImageReader reader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 1);
                final Surface photoSurface = reader.getSurface();

                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(photoSurface);
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);

                final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

                reader.setOnImageAvailableListener(r -> {
                    try (Image image = r.acquireLatestImage()) {
                        if (image == null) return;
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);

                        String fileName = "photo_" + ts + ".jpg";
                        String mimeType = "image/jpeg";

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            String rp = Environment.DIRECTORY_PICTURES + "/" +
                                    (currentSubDir != null && !currentSubDir.isEmpty() ? currentSubDir : "Dashcam");
                            ContentValues v = new ContentValues();
                            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                            v.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rp);
                            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                            if (uri != null) {
                                OutputStream os = getContentResolver().openOutputStream(uri);
                                if (os != null) { os.write(bytes); os.close(); }
                                v.clear();
                                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                                getContentResolver().update(uri, v, null, null);
                            }
                        } else {
                            File dir = new File(Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_PICTURES), currentSubDir);
                            if (!dir.exists()) dir.mkdirs();
                            File f = new File(dir, fileName);
                            FileOutputStream fos = new FileOutputStream(f);
                            fos.write(bytes);
                            fos.close();

                            ContentValues v = new ContentValues();
                            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                            v.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                            v.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
                            getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "photo save failed", e);
                    }
                    reader.close();
                }, mainHandler);

                // Stop repeating, capture single, then resume
                captureSession.stopRepeating();
                captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        try {
                            session.setRepeatingRequest(
                                    captureSession.getDevice().createCaptureRequest(
                                            isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW)
                                            .build(), null, mainHandler);
                        } catch (Exception e) {}
                    }
                }, mainHandler);

            } catch (Exception e) {
                Log.e(TAG, "takePhoto failed", e);
            }
        });
    }

    // ========== Utility ==========

    private void closeCaptureSession() {
        if (captureSession != null) {
            try { captureSession.abortCaptures(); } catch (Exception e) {}
            try { captureSession.close(); } catch (Exception e) {}
            captureSession = null;
        }
    }

    private void closeCamera() {
        closeCaptureSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        cameraOpen = false;
    }

    public void switchCamera() {
        runOnMain(() -> {
            boolean wasRecording = isRecording;
            String dirType = currentDirType;
            String subDir = currentSubDir;
            if (wasRecording) stopRecordingInternal();
            closeCamera();
            cameraFacing = cameraFacing == CameraCharacteristics.LENS_FACING_BACK
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            doOpenCamera();
            if (wasRecording) {
                mainHandler.postDelayed(() -> startRecording(dirType, subDir), 600);
            }
        });
    }

    public boolean isRecording() { return isRecording; }
    public boolean isCameraOpen() { return cameraOpen; }
    public Size getPreviewSize() { return previewSize; }
    public int getSensorOrientation() { return sensorOrientation; }
    public int getCameraFacing() { return cameraFacing; }
}
