package com.panel.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
    private Context context;
    private DashcamService dashcamService;

    public WebAppInterface(Context context) {
        this.context = context;
    }

    public void setDashcamService(DashcamService service) {
        this.dashcamService = service;
    }

    @JavascriptInterface
    public String startRecording(String dirType, String subDir) {
        if (dashcamService != null) {
            boolean ok = dashcamService.startRecording(dirType, subDir);
            return ok ? "ok" : "camera_not_ready";
        }
        return "service_not_ready";
    }

    @JavascriptInterface
    public String stopRecording() {
        if (dashcamService != null) {
            dashcamService.stopRecording();
            return "ok";
        }
        return "service_not_ready";
    }

    @JavascriptInterface
    public boolean isRecording() {
        return dashcamService != null && dashcamService.isRecording();
    }

    @JavascriptInterface
    public void switchCamera() {
        if (dashcamService != null) {
            dashcamService.switchCamera();
        }
    }

    @JavascriptInterface
    public String takePhoto() {
        return "todo_camera_photo";
    }
}
