package com.panel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
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
    private CaptureRequest.Builder previewRequestBuilder;
    private Surface previewSurface;
    private boolean isRecording = false;
    private boolean recorderStarted = false;
    private boolean cameraOpen = false;
    private String currentFilePath;
    private String currentDirType = "MOVIES";
    private String currentSubDir = "Dashcam";
    private int cameraFacing = CameraCharacteristics.LENS_FACING_BACK;
    private int sensorOrientation = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnCameraStateListener cameraStateListener;

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

    public void setCameraStateListener(OnCameraStateListener listener) {
        this.cameraStateListener = listener;
    }

    public void openCamera(int width, int height, boolean useFrontCamera) {
        previewSize = new Size(width, height);
        cameraFacing = useFrontCamera
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        doOpenCamera();
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
                    updateCameraSession();
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

    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        if (cameraOpen && cameraDevice != null && surface != null && surface.isValid()) {
            updateCameraSession();
        }
    }

    public void clearPreviewSurface() {
        this.previewSurface = null;
        if (isRecording) {
            updateCameraSession();
        } else {
            closeCaptureSession();
        }
    }

    public boolean startRecording(String dirType, String subDir) {
        if (cameraDevice == null || isRecording) return false;
        if (!cameraOpen) return false;
        currentDirType = dirType != null ? dirType : "MOVIES";
        currentSubDir = subDir != null && !subDir.isEmpty() ? subDir : "Dashcam";

        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(5000000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(96000);

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
                    mediaRecorder.setOutputFile(getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor());
                } else {
                    return false;
                }
            } else {
                File movieDir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), currentSubDir);
                if (!movieDir.exists()) movieDir.mkdirs();
                currentFilePath = new File(movieDir, fileName).getAbsolutePath();
                mediaRecorder.setOutputFile(currentFilePath);
            }

            mediaRecorder.setOrientationHint(sensorOrientation);
            mediaRecorder.prepare();

            isRecording = true;
            recorderStarted = false;
            updateCameraSession();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            isRecording = false;
            return false;
        }
    }

    public void stopRecording() {
        stopRecordingInternal();
    }

    private void stopRecordingInternal() {
        if (!isRecording) return;
        isRecording = false;

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "MediaRecorder stop failed", e);
            }
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
                    String relativePath = Environment.DIRECTORY_MOVIES + "/" +
                            (currentSubDir != null && !currentSubDir.isEmpty() ? currentSubDir : "Dashcam");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                }
                try {
                    Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (cameraStateListener != null && uri != null)
                        cameraStateListener.onRecordingStopped(uri.toString());
                } catch (Exception e) {
                    Log.e(TAG, "MediaStore insert failed", e);
                }
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

        updateCameraSession();
        updateNotification("运行中");
    }

    private void updateCameraSession() {
        if (cameraDevice == null) return;
        closeCaptureSession();

        try {
            List<Surface> surfaces = new ArrayList<>();

            if (isRecording && mediaRecorder != null) {
                Surface recorderSurface = mediaRecorder.getSurface();
                if (recorderSurface != null && recorderSurface.isValid()) {
                    surfaces.add(recorderSurface);
                }
            }

            if (previewSurface != null && previewSurface.isValid()) {
                surfaces.add(previewSurface);
            }

            if (surfaces.isEmpty()) return;

            int template = isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            previewRequestBuilder = cameraDevice.createCaptureRequest(template);
            for (Surface s : surfaces) {
                previewRequestBuilder.addTarget(s);
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mainHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "updateCameraSession setRepeatingRequest failed", e);
                            }
                            if (isRecording && mediaRecorder != null && !recorderStarted) {
                                recorderStarted = true;
                                try {
                                    mediaRecorder.start();
                                } catch (Exception e) {
                                    Log.e(TAG, "mediaRecorder.start failed", e);
                                }
                                updateNotification("⏺ 录像中");
                                if (cameraStateListener != null)
                                    cameraStateListener.onRecordingStarted();
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "updateCameraSession configure failed");
                            if (isRecording) {
                                isRecording = false;
                                try { mediaRecorder.release(); } catch (Exception e) {}
                                mediaRecorder = null;
                            }
                        }
                    }, mainHandler);

        } catch (Exception e) {
            Log.e(TAG, "updateCameraSession failed", e);
        }
    }

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
    }

    public boolean isRecording() { return isRecording; }
    public boolean isCameraOpen() { return cameraOpen; }
    public Size getPreviewSize() { return previewSize; }
    public int getSensorOrientation() { return sensorOrientation; }
    public int getCameraFacing() { return cameraFacing; }
}
