package com.hzcu.potholeDetection;

// MainActivity.java
import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.camera.core.Preview;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import android.location.LocationListener;
import android.location.LocationManager;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;

//import android.support.v4.app.ActivityCompat;

import androidx.core.app.ActivityCompat;
//import android.support.v4.content.ContextCompat;
import androidx.core.content.ContextCompat;

public class MainActivity2 extends AppCompatActivity implements SensorEventListener{

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "MainActivity";
    private TextView textViewAcceleration, textViewLocation, textViewTime;
    private Button buttonToggleCollection;
    private SensorManager sensorManager;
    private float[] latestAcceleration = new float[3];
    private ImageCapture imageCapture;
    private Timer timer;
    private boolean isCollecting = false;
    private LocationManager locationManager;
    private Location currentLocation;  // 用于存储最新的位置信息
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTimeRunnable = this::updateTimeUI;

    public static final int REQUEST_CAMERA = 100;

    private Yolov11Ncnn yolov11ncnn = new Yolov11Ncnn();
    private int facing = 1;

    private Spinner spinnerModel;
    private Spinner spinnerCPUGPU;
    private int current_model = 0;
    private int current_cpugpu = 0;

    private SurfaceView cameraView;

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateLocationUI(location);
            currentLocation = location;  // 更新最新的位置信息
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraView = (SurfaceView) findViewById(R.id.cameraview);

        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
//        cameraView.getHolder().addCallback(this);
        cameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                yolov11ncnn.setOutputWindow(holder.getSurface());
            }

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
            }
        });
        Button buttonSwitchCamera = (Button) findViewById(R.id.buttonSwitchCamera);
        buttonSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {

                int new_facing = 1 - facing;

                yolov11ncnn.closeCamera();

                yolov11ncnn.openCamera(new_facing);

                facing = new_facing;
            }
        });
        spinnerModel = (Spinner) findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_model)
                {
                    current_model = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        spinnerCPUGPU = (Spinner) findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_cpugpu)
                {
                    current_cpugpu = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

//        previewView = findViewById(R.id.preview_view);
        textViewAcceleration = findViewById(R.id.textView_acceleration);
        textViewLocation = findViewById(R.id.textView_location);
        textViewTime = findViewById(R.id.textView_time);
        buttonToggleCollection = findViewById(R.id.button_toggle_collection);
        startUpdateTimeThread();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            yolov11ncnn.openCamera(facing);
            reload();
//            startCamera();
            requestLocationUpdates();
        }

        buttonToggleCollection.setOnClickListener(v -> toggleCollection());
    }
    private void reload() {
        boolean ret_init = yolov11ncnn.loadModel(getAssets(), current_model, current_cpugpu);
        if (!ret_init)
        {
            Log.e("MainActivity", "yolov11ncnn loadModel failed");
        }
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        try {
            // 开始监听来自 GPS 提供者的定位更新
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
            // 如果你也想使用网络提供者的位置更新，取消下面这行的注释
//             locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
        } catch (SecurityException e) {
            Log.e(TAG, "Error requesting location updates", e);
        }
    }

    private void getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        try {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                updateLocationUI(lastKnownLocation);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Error getting last known location", e);
        }
    }
    private void startUpdateTimeThread() {
        handler.post(updateTimeRunnable);
    }

    private void updateTimeUI() {
        textViewTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        handler.postDelayed(updateTimeRunnable, 1000); // 每隔一秒更新一次时间
    }
    private void saveData() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission not granted to get location");
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());

        File dataFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "data.txt");

        try (FileWriter writer = new FileWriter(dataFile, true)) { // 使用追加模式
            writer.append(String.format("Timestamp: %s\n", timestamp));
            writer.append(String.format("Acceleration: X=%.2f, Y=%.2f, Z=%.2f\n",
                    latestAcceleration[0], latestAcceleration[1], latestAcceleration[2]));
            if (currentLocation != null) {
                writer.append(String.format("Location: Latitude=%.4f, Longitude=%.4f\n",
                        currentLocation.getLatitude(), currentLocation.getLongitude()));
            } else {
                writer.append("Location: Not available\n");
            }
            writer.append("-------------\n"); // 分隔符
        } catch (IOException e) {
            Log.e(TAG, "Error writing data", e);
        }

        // Capture and Save Image
//        takePhoto(timestamp);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.length > 0) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            if (allPermissionsGranted) {
                getLastKnownLocation();
//                startCamera(); // 如果需要的话，也可以在这里启动相机
            } else {
                Log.w(TAG, "One or more permissions denied");
            }
        }
    }

    private void updateLocationUI(Location location) {
        runOnUiThread(() -> {
            textViewLocation.setText(String.format("Latitude: %.4f\nLongitude: %.4f",
                    location.getLatitude(), location.getLongitude()));
        });
    }

    private void toggleCollection() {
        isCollecting = !isCollecting;
        if (isCollecting) {
            buttonToggleCollection.setText("Stop Collection");
            startCollection();
        } else {
            buttonToggleCollection.setText("Start Collection");
            stopCollection();
        }
    }

    private void startCollection() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                saveData();
            }
        }, 0, 1000); // 每一秒执行一次
    }

    private void stopCollection() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

//    private void takePhoto(String timestamp) {
//        imageCapture.takePicture(
//                new ImageCapture.OutputFileOptions.Builder(
//                        new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "image_" + timestamp + ".jpg"))
//                        .build(),
//                ContextCompat.getMainExecutor(this),
//                new ImageCapture.OnImageSavedCallback() {
//                    @Override
//                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
//                        Log.d(TAG, "Image saved successfully.");
//                    }
//
//                    @Override
//                    public void onError(@NonNull ImageCaptureException exception) {
//                        Log.e(TAG, "Error taking photo", exception);
//                    }
//                });
//    }

//    private void startCamera() {
//        ProcessCameraProvider.getInstance(this).addListener(() -> {
//            try {
//                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
//
//                Preview preview = new Preview.Builder().build();
//                preview.setSurfaceProvider(previewView.getSurfaceProvider());
//
//                imageCapture = new ImageCapture.Builder().build();
//
//                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
//
//                cameraProvider.unbindAll();
//                cameraProvider.bindToLifecycle(
//                        ((LifecycleOwner) this), cameraSelector, preview, imageCapture);
//
//            } catch (ExecutionException | InterruptedException e) {
//                Log.e(TAG, "Use case binding failed", e);
//            }
//        }, ContextCompat.getMainExecutor(this));
//    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 更新最新的加速度数据
            System.arraycopy(event.values, 0, latestAcceleration, 0, Math.min(event.values.length, latestAcceleration.length));

            // 更新UI线程中的文本视图
            runOnUiThread(() -> {
                textViewAcceleration.setText(String.format("X: %.2f\nY: %.2f\nZ: %.2f",
                        latestAcceleration[0], latestAcceleration[1], latestAcceleration[2]));
            });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 在这里处理传感器精度变化（可选）
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
        if (isCollecting) {
            startCollection();
        }
        // 当应用返回前台时重新请求位置更新
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        stopCollection();
        // 当应用暂停时停止位置更新以节省电量
        locationManager.removeUpdates(locationListener);
    }
}