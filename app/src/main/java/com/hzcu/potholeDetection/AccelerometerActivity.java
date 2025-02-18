package com.hzcu.potholeDetection;

// AccelerometerActivity.java
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.widget.TextView;

public class AccelerometerActivity extends AppCompatActivity implements SensorEventListener {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "AccelerometerActivity";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private TextView textViewAcceleration;
    private TextView textViewLocation;
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accelerometer);

        textViewAcceleration = findViewById(R.id.textView_acceleration);
        textViewLocation = findViewById(R.id.textView_location);

        // 获取传感器管理器的实例
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // 获取默认的加速度传感器
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // 初始化 FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 请求位置权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            getLastLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getLastLocation();
        } else {
            Log.w(TAG, "Permission denied");
        }
    }

    private void getLastLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        updateLocationUI(location);
                    } else {
                        Log.w(TAG, "Last known location is null.");
                        textViewLocation.setText("Last known location is null.");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error trying to get last GPS location", e);
                    textViewLocation.setText("Error getting location.");
                });
    }

    private void updateLocationUI(Location location) {
        runOnUiThread(() -> {
            textViewLocation.setText(String.format("Latitude: %.4f\nLongitude: %.4f",
                    location.getLatitude(), location.getLongitude()));
            Log.d(TAG, "Updated location UI with latitude: " + location.getLatitude() + ", longitude: " + location.getLongitude());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册监听器
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        // 尝试再次获取位置信息（以防之前没有成功）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            getLastLocation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册监听器以节省电量
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // 更新UI线程中的文本视图
            runOnUiThread(() -> {
                textViewAcceleration.setText(String.format("X: %.2f\nY: %.2f\nZ: %.2f", x, y, z));
            });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 在这里处理传感器精度变化（可选）
    }
}