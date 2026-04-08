package com.panel.app;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.util.Log;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements LocationListener, SensorEventListener {
    private static final String TAG = "PanelApp";
    private PowerManager.WakeLock wakeLock;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private TextView speedText;
    private TextView coordText;
    private TextView headingText;
    private double lastLat, lastLon;
    private long lastTime;
    private float currentSpeed;
    private TextureView frontCameraView;
    private TextureView backCameraView;
    private CameraDevice frontCamera;
    private CameraDevice backCamera;
    private Semaphore cameraOpenCloseLock = new Semaphore(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PanelApp::WakeLock");
        wakeLock.acquire();

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.HORIZONTAL);
        mainLayout.setBackgroundColor(0xFF1A1A1A);

        LinearLayout leftPanel = new LinearLayout(this);
        leftPanel.setOrientation(LinearLayout.VERTICAL);
        leftPanel.setPadding(40, 40, 40, 40);

        speedText = new TextView(this);
        speedText.setTextSize(120);
        speedText.setTextColor(0xFF00FF00);
        speedText.setText("0.0");
        leftPanel.addView(speedText);

        TextView unitText = new TextView(this);
        unitText.setTextSize(32);
        unitText.setTextColor(0xFF888888);
        unitText.setText("km/h");
        leftPanel.addView(unitText);

        LinearLayout infoPanel = new LinearLayout(this);
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setPadding(0, 40, 0, 0);

        coordText = new TextView(this);
        coordText.setTextSize(20);
        coordText.setTextColor(0xFFFFFFFF);
        coordText.setText("坐标: --");
        infoPanel.addView(coordText);

        headingText = new TextView(this);
        headingText.setTextSize(20);
        headingText.setTextColor(0xFFFFFFFF);
        headingText.setText("方向: --");
        infoPanel.addView(headingText);

        leftPanel.addView(infoPanel);

        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setWeightSum(2);

        frontCameraView = new TextureView(this);
        frontCameraView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        rightPanel.addView(frontCameraView);

        backCameraView = new TextureView(this);
        backCameraView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        rightPanel.addView(backCameraView);

        mainLayout.addView(leftPanel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        mainLayout.addView(rightPanel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        setContentView(mainLayout);

        initSensors();
        requestPermissions();
    }

    private void initSensors() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private void requestPermissions() {
        String[] permissions = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        };
        ActivityCompat.requestPermissions(this, permissions, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 100, 0, this);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied", e);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        
        double[] gcj02 = wgs84ToGcj02(lat, lon);
        
        long now = System.currentTimeMillis();
        if (lastTime > 0) {
            float distance = distanceBetween(lastLat, lastLon, gcj02[0], gcj02[1]);
            double timeDiff = (now - lastTime) / 1000.0;
            if (timeDiff > 0) {
                currentSpeed = (distance / 1000.0f) / ((float)timeDiff / 3600.0f);
            }
        }
        
        lastLat = gcj02[0];
        lastLon = gcj02[1];
        lastTime = now;

        runOnUiThread(() -> {
            speedText.setText(String.format("%.1f", currentSpeed));
            coordText.setText(String.format("坐标: %.6f, %.6f", gcj02[0], gcj02[1]));
            if (location.hasBearing()) {
                headingText.setText(String.format("方向: %.0f°", location.getBearing()));
            }
        });
    }

    public double[] wgs84ToGcj02(double lat, double lon) {
        double a = 6378137.0;
        double ee = 0.00669437999014;
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y;
        ret += 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private float distanceBetween(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (earthRadius * c);
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {}

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}