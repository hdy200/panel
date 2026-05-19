package com.panel.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class WebAppInterface {
    private Activity activity;

    public WebAppInterface(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void showToast(String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    @JavascriptInterface
    public void closeApp() {
        activity.runOnUiThread(() -> {
            activity.finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
}
