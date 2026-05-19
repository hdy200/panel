package com.panel.app;

import android.app.Activity;
import android.webkit.JavascriptInterface;

public class WebAppInterface {
    private Activity activity;
    private int serverPort;

    public WebAppInterface(Activity activity) {
        this.activity = activity;
    }

    public void setServerPort(int port) {
        this.serverPort = port;
    }

    @JavascriptInterface
    public int getServerPort() {
        return serverPort;
    }

    @JavascriptInterface
    public void closeApp() {
        activity.runOnUiThread(() -> {
            activity.finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
}
