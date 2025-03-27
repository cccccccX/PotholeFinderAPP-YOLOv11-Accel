package com.hzcu.potholeDetection;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
public class MainActivity2 extends AppCompatActivity implements SensorEventListener{

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1;
    private TextView textViewAcceleration, textViewTime;
    private Button buttonToggleCollection;
    private SensorManager sensorManager;
    private float[] latestAcceleration = new float[3];
    private boolean isCollecting = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTimeRunnable = this::updateTimeUI;

    private Yolov11Ncnn yolov11ncnn = new Yolov11Ncnn();
    private int cameraFacing = 1;

    private Spinner spinnerModel;
    private Spinner spinnerCPUGPU;
    private int currentModel = 0;
    private int currentCpugpu = 0;

    private SurfaceView cameraView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 先设置路径，再打开相机和加载模型
        // 获取应用专用目录
        File rootDir = getExternalFilesDir(null);
        if (rootDir != null) {
            String rootPath = rootDir.getAbsolutePath();
            if (!rootPath.endsWith("/")) {
                rootPath += "/";
            }
            Log.d("MainActivity", "Setting root path: " + rootPath);
            yolov11ncnn.setRootPath(rootPath);
        } else {
            Log.e("MainActivity", "getExternalFilesDir(null) returned null");
        }
        // 抽取控件初始化
        initView();

        // 检查并请求相机权限（封装权限请求逻辑）
        checkAndRequestCameraPermission();

        startUpdateTimeThread();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        buttonToggleCollection.setOnClickListener(v -> toggleCollection());
    }
    /**
     * 封装控件的查找和初始化
     */
    private void initView() {
        cameraView = findViewById(R.id.cameraview);
        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                yolov11ncnn.setOutputWindow(holder.getSurface());
            }
            @Override
            public void surfaceCreated(SurfaceHolder holder) { }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) { }
        });

        Button buttonSwitchCamera = findViewById(R.id.buttonSwitchCamera);
        buttonSwitchCamera.setOnClickListener(v -> {
            int newFacing = 1 - cameraFacing;
            yolov11ncnn.closeCamera();
            yolov11ncnn.openCamera(newFacing);
            cameraFacing = newFacing;
        });

        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != currentModel) {
                    currentModel = position;
                    loadModel();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        spinnerCPUGPU = findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != currentCpugpu) {
                    currentCpugpu = position;
                    loadModel();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        textViewAcceleration = findViewById(R.id.textView_acceleration);
        textViewTime = findViewById(R.id.textView_time);
        buttonToggleCollection = findViewById(R.id.button_toggle_collection);
    }
    /**
     * 封装相机权限的检查和请求逻辑，确保仅在获得权限后才调用 openCamera() 和 loadModel()。
     */
    private void checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            // 如果已经授权，直接打开摄像头并加载模型
            yolov11ncnn.openCamera(cameraFacing);
            loadModel();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户授予权限后，打开相机
                yolov11ncnn.openCamera(cameraFacing);
                loadModel();
            } else {
                // 用户拒绝权限，提示或处理相应逻辑
            }
        }
    }

    /**
     * 加载目标检测模型
     */
    private void loadModel() {
        boolean ret_init = yolov11ncnn.loadModel(getAssets(), currentModel, currentCpugpu);
        if (!ret_init)
        {
            Log.e("MainActivity", "yolov11ncnn loadModel failed");
        }
    }

    /**
     * 屏幕时间显示线程
     */
    private void startUpdateTimeThread() {
        handler.post(updateTimeRunnable);
    }

    private void updateTimeUI() {
        textViewTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        handler.postDelayed(updateTimeRunnable, 1000); // 每隔一秒更新一次时间
    }

    /**
     * 数据采集开关（通过JNI在C++中实现图像存储）
     */
    private void toggleCollection() {
        isCollecting = !isCollecting;
        if (isCollecting) {
            buttonToggleCollection.setText("Stop Collection");
            yolov11ncnn.setCollectionState(true);
        } else {
            buttonToggleCollection.setText("Start Collection");
            yolov11ncnn.setCollectionState(false);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, latestAcceleration, 0, Math.min(event.values.length, latestAcceleration.length));
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
            yolov11ncnn.setCollectionState(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        yolov11ncnn.setCollectionState(false);
    }
}