package com.panel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class DashcamService extends Service {
    private static final String TAG = "DashcamService";
    private static final String CHANNEL_ID = "dashcam_channel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("运行中"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public String saveFile(byte[] data, String fileName, String mimeType, String dirType, String subDir) {
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
            Log.e(TAG, "saveFile failed", e);
        }
        return null;
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
}
