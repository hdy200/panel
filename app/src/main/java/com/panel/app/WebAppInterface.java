package com.panel.app;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class WebAppInterface {
    private static final String TAG = "DashcamBridge";
    private Context context;
    private MediaRecorder audioRecorder;
    private String audioFilePath;

    public WebAppInterface(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public String saveFile(String base64Data, String fileName, String dirType, String subDir) {
        return saveToStorage(base64Data, fileName, dirType, subDir);
    }

    @JavascriptInterface
    public void startAudioRecording() {
        try {
            File cacheDir = context.getCacheDir();
            if (!cacheDir.exists()) cacheDir.mkdirs();
            audioFilePath = new File(cacheDir, "dashcam_audio_" + System.currentTimeMillis() + ".webm").getAbsolutePath();

            audioRecorder = new MediaRecorder();
            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

            if (Build.VERSION.SDK_INT >= 31) {
                audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
                audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                audioRecorder.setAudioSamplingRate(48000);
            } else {
                audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
                audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                audioRecorder.setAudioSamplingRate(44100);
                audioRecorder.setAudioEncodingBitRate(96000);
            }

            audioRecorder.setOutputFile(audioFilePath);
            audioRecorder.prepare();
            audioRecorder.start();
        } catch (Exception e) {
            Log.e(TAG, "Audio start failed", e);
        }
    }

    @JavascriptInterface
    public void stopAudioRecording() {
        if (audioRecorder != null) {
            try { audioRecorder.stop(); } catch (Exception e) { }
            try { audioRecorder.release(); } catch (Exception e) { }
            audioRecorder = null;
        }
    }

    @JavascriptInterface
    public String mergeAndSave(String videoBase64, String fileName, String dirType, String subDir, boolean unused) {
        stopAudioRecording();

        // API 29+ has MUXER_OUTPUT_WEBM, API 31+ can record Opus in WebM
        if (audioFilePath != null && new File(audioFilePath).exists()) {
            try {
                byte[] merged = mergeToWebM(videoBase64, audioFilePath);
                if (merged != null) {
                    String mergedBase64 = Base64.encodeToString(merged, Base64.NO_WRAP);
                    return saveToStorage(mergedBase64, fileName.replace(".webm", ".webm"), dirType, subDir);
                }
            } catch (Exception e) {
                Log.e(TAG, "Merge failed", e);
            }
        }

        return saveToStorage(videoBase64, fileName, dirType, subDir);
    }

    private byte[] mergeToWebM(String videoBase64, String audioPath) throws Exception {
        // Need WebM muxer (API 29+) AND compatible audio format (Opus, API 31+)
        if (Build.VERSION.SDK_INT < 29) return null;

        byte[] videoData = Base64.decode(videoBase64, Base64.DEFAULT);
        File videoFile = new File(context.getCacheDir(), "merge_v_" + System.currentTimeMillis() + ".webm");
        FileOutputStream fos = new FileOutputStream(videoFile);
        fos.write(videoData);
        fos.close();

        // Check if audio file is WebM format (Opus)
        if (!isWebMFile(audioPath)) {
            videoFile.delete();
            return null;
        }

        File outputFile = new File(context.getCacheDir(), "merge_out_" + System.currentTimeMillis() + ".webm");
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();

        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioPath);

            MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM);

            int videoTrack = -1, audioTrackMux = -1;

            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat fmt = videoExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i);
                    videoTrack = muxer.addTrack(fmt);
                    break;
                }
            }

            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat fmt = audioExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i);
                    audioTrackMux = muxer.addTrack(fmt);
                    break;
                }
            }

            if (videoTrack < 0) {
                muxer.release();
                videoExtractor.release();
                audioExtractor.release();
                outputFile.delete();
                videoFile.delete();
                return null;
            }

            muxer.start();
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (true) {
                info.offset = 0;
                info.size = videoExtractor.readSampleData(buf, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = videoExtractor.getSampleTime();
                info.flags = videoExtractor.getSampleFlags();
                muxer.writeSampleData(videoTrack, buf, info);
                videoExtractor.advance();
            }

            if (audioTrackMux >= 0) {
                while (true) {
                    info.offset = 0;
                    info.size = audioExtractor.readSampleData(buf, 0);
                    if (info.size < 0) break;
                    info.presentationTimeUs = audioExtractor.getSampleTime();
                    info.flags = audioExtractor.getSampleFlags();
                    muxer.writeSampleData(audioTrackMux, buf, info);
                    audioExtractor.advance();
                }
            }

            muxer.stop();
            muxer.release();

            FileInputStream fis = new FileInputStream(outputFile);
            byte[] result = new byte[(int) outputFile.length()];
            fis.read(result);
            fis.close();

            outputFile.delete();
            videoFile.delete();
            return result;

        } finally {
            try { videoExtractor.release(); } catch (Exception e) { }
            try { audioExtractor.release(); } catch (Exception e) { }
        }
    }

    private boolean isWebMFile(String path) {
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(path);
            boolean hasAudio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    hasAudio = true;
                    break;
                }
            }
            extractor.release();
            return hasAudio;
        } catch (Exception e) {
            return false;
        }
    }

    private String saveToStorage(String base64Data, String fileName, String dirType, String subDir) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
            boolean isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".webm");

            String relativePath;
            if ("DCIM".equals(dirType)) {
                relativePath = Environment.DIRECTORY_DCIM + "/" + (subDir != null && !subDir.isEmpty() ? subDir : "Dashcam");
            } else if ("MOVIES".equals(dirType)) {
                relativePath = Environment.DIRECTORY_MOVIES + "/" + (subDir != null && !subDir.isEmpty() ? subDir : "Dashcam");
            } else {
                relativePath = Environment.DIRECTORY_PICTURES + "/" + (subDir != null && !subDir.isEmpty() ? subDir : "Dashcam");
            }

            String mimeType = isVideo ? "video/webm" : "image/jpeg";
            Uri collection = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(relativePath).getAbsolutePath());
                if (!dir.exists()) dir.mkdirs();
                values.put(MediaStore.MediaColumns.DATA, new File(dir, fileName).getAbsolutePath());
            }

            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri != null) {
                OutputStream os = context.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(data);
                    os.close();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(uri, values, null, null);
                    }
                    return uri.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        }
        return null;
    }
}
