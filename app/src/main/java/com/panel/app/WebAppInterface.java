package com.panel.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
    private DashcamService dashcamService;

    public WebAppInterface(Context context) {}

    public void setDashcamService(DashcamService service) {
        this.dashcamService = service;
    }

    @JavascriptInterface
    public void startRecording(String dirType, String subDir) {
        if (dashcamService != null) {
            dashcamService.startRecording(dirType, subDir);
        }
    }

    @JavascriptInterface
    public void stopRecording() {
        if (dashcamService != null) {
            dashcamService.stopRecording();
        }
    }

    @JavascriptInterface
    public boolean isRecording() {
        return dashcamService != null && dashcamService.isRecording();
    }

    @JavascriptInterface
    public boolean isCameraOpen() {
        return dashcamService != null && dashcamService.isCameraOpen();
    }

    @JavascriptInterface
    public void switchCamera() {
        if (dashcamService != null) {
            dashcamService.switchCamera();
        }
    }

    @JavascriptInterface
    public void takePhoto() {
        if (dashcamService != null) {
            dashcamService.takePhoto();
        }
    }
}
