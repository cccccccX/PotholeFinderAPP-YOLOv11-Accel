package com.hzcu.potholeDetection;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    ////////////【目标检测部分】////////////
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1;
    private SurfaceView cameraView;
    private Yolov11Ncnn yolov11ncnn = new Yolov11Ncnn();
    private int cameraFacing = 1;
    private Spinner spinnerModel, spinnerCPUGPU;
    private int currentModel = 0, currentCpugpu = 0;
    private Button buttonToggleCollection;
    private TextView textViewAcceleration, textViewTime, textViewGyro;
    //（可选）用于显示检测计数（加速度检测触发次数），如果布局中有该控件
    private TextView tvCount;
    private boolean isCollecting = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTimeRunnable = this::updateTimeUI;

    ////////////【加速度检测部分】////////////
    // 传感器管理
    private SensorManager sensorManager;
    private Sensor mAccelsensor, mGyrosensor;
    // 窗口采样数据
    private int dataPoint = 0, WINDOW_SIZE = 50;
    private float[] x = new float[WINDOW_SIZE], y = new float[WINDOW_SIZE], z = new float[WINDOW_SIZE];
    private float[] xg = new float[WINDOW_SIZE], yg = new float[WINDOW_SIZE], zg = new float[WINDOW_SIZE];
    private float sumx, sumy, sumz, soqx, soqy, soqz;
    private float meanx, meany, meanz;
    private float sdx, sdy, sdz;

    private float sumxg, sumyg, sumzg, soqxg, soqyg, soqzg;
    private float meanxg, meanyg, meanzg;
    private float sdxg, sdyg, sdzg;


    // SVM 预测及检测状态
    private double prediction = 0;
    private boolean enable_detection = true, potholeDetected = false, accelTriggered = false;
    private long time = 0;
    private String sensorName;
    private String effectOfRiding = "None";
    private int count = -1;

    // 文件记录
    private PrintWriter rawDataWriter, featureDataWriter;
    private File rawDataFile, featureDataFile;
    private long windowStartTime;

    ////////////【定位部分】////////////
    private LocationManager locationManager;
    private double lat = 0, lon = 0, speed = 0;
    private TextView textViewLocation;

    ////////////【声音提示 & Native方法】////////////
    private MediaPlayer mp;

    private SVMPredictor svmPredictor = new SVMPredictor();
    // 在类成员变量部分添加：
    private Button buttonMarkTimestamp;
    private PrintWriter timestampWriter;
    private File timestampFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 基于目标检测界面（假设使用 activity_main2.xml）
        setContentView(R.layout.activity_main);
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

        // 初始化界面控件
        initView();

        // 检查相机权限并打开摄像头、加载模型
        checkAndRequestCameraPermission();
        startUpdateTimeThread();

        // 初始化传感器管理及获取加速度和陀螺仪传感器
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelsensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyrosensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // 初始化加速度检测功能：文件记录、窗口起始时间
        initFiles();
        initTimestampFile();  // 新增：初始化记录时间戳的文件
        windowStartTime = System.currentTimeMillis();

        // 检查位置权限并初始化定位
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    100);
        } else {
            initLocation();
        }

        // 初始化声音提示（需要在 res/raw 目录下有 short_blips 文件）
        mp = MediaPlayer.create(this, R.raw.short_blips);

        // 加速度采集开关（如需结合目标检测数据采集，可在点击按钮时调用）
        buttonToggleCollection.setOnClickListener(v -> toggleCollection());
    }
    // 新增：初始化记录时间戳文件的方法
    private void initTimestampFile() {
        try {
            File dir = getExternalFilesDir(null);
            timestampFile = new File(dir, "timestamp_marks.csv");
            timestampWriter = new PrintWriter(new FileOutputStream(timestampFile, true));
            if (timestampFile.length() == 0) {
                timestampWriter.println("timestamp");
                timestampWriter.flush();
                Log.d("FileInit", "Timestamp header written.");
            }
        } catch (Exception e) {
            Log.e("FileInit", "Error initializing timestamp file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    /**
     * 初始化界面控件
     */
    private void initView() {
        // 目标检测相关
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
        textViewGyro = findViewById(R.id.textView_gyroscope);
        textViewTime = findViewById(R.id.textView_time);
        textViewLocation = findViewById(R.id.textView_location);
        buttonToggleCollection = findViewById(R.id.button_toggle_collection);
        // 如果布局中存在用于显示检测计数的控件，则获取
        tvCount = findViewById(R.id.tvDetected);
        // 新增：绑定记录时间戳按钮
        buttonMarkTimestamp = findViewById(R.id.button_mark_timestamp);
        buttonMarkTimestamp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long ts = System.currentTimeMillis();
                if (timestampWriter != null) {
                    timestampWriter.println(ts);
                    timestampWriter.flush();
                }
                Toast.makeText(MainActivity.this, "已记录时间戳: " + ts, Toast.LENGTH_SHORT).show();
                Log.d("Timestamp", "Manual timestamp recorded: " + ts);
            }
        });
    }


    /**
     * 检查相机权限并请求，权限通过后打开摄像头和加载模型
     */
    private void checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            yolov11ncnn.openCamera(cameraFacing);
            loadModel();
        }
    }

    /**
     * 权限请求结果处理
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                yolov11ncnn.openCamera(cameraFacing);
                loadModel();
            } else {
                // 可添加权限拒绝的提示处理
            }
        } else if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initLocation();
            } else {
                Toast.makeText(this, "需要位置权限才能正常工作", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 加载目标检测模型
     */
    private void loadModel() {
        boolean ret_init = yolov11ncnn.loadModel(getAssets(), currentModel, currentCpugpu);
        if (!ret_init) {
            Log.e("MainActivity", "yolov11ncnn loadModel failed");
        }
    }

    /**
     * 启动每秒更新时间显示
     */
    private void startUpdateTimeThread() {
        handler.post(updateTimeRunnable);
    }

    private void updateTimeUI() {
        textViewTime.setText("当前时间：" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        handler.postDelayed(updateTimeRunnable, 1000);
    }

    /**
     * 数据采集开关
     */
    private void toggleCollection() {
        isCollecting = !isCollecting;
        if (isCollecting) {
            buttonToggleCollection.setText("结束采集");
//            yolov11ncnn.setCollectionState(true);
        } else {
            buttonToggleCollection.setText("开始采集");
            yolov11ncnn.setCollectionState(false);
        }
    }

    /**
     * 传感器数据变化处理
     */
    @Override
    public void onSensorChanged(SensorEvent event) {


        // -----【显示传感器数据部分】-----
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            runOnUiThread(() -> textViewAcceleration.setText(
                    String.format("加速度数据：\nX: %.2f\nY: %.2f\nZ: %.2f", event.values[0], event.values[1], event.values[2])
            ));
        }
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            runOnUiThread(() -> textViewGyro.setText(
                    String.format("角速度数据：\nX: %.2f\nY: %.2f\nZ: %.2f", event.values[0], event.values[1], event.values[2])
            ));
        }


        // -----【记录原始传感器数据（包括GPS坐标）】-----
        sensorName = event.sensor.getName();
        long currentTime = System.currentTimeMillis();
        if (isCollecting && rawDataWriter != null) {
            String rawLine = currentTime + "," + sensorName + ","
                    + event.values[0] + "," + event.values[1] + "," + event.values[2]
                    + "," + lat + "," + lon;
            rawDataWriter.println(rawLine);
            rawDataWriter.flush();
            Log.d("FileWrite", "Raw data written: " + rawLine);
        }

        // -----【简单节流】-----
        if (System.currentTimeMillis() - time < 15) {
            return;
        }
        time = System.currentTimeMillis();

        if (!enable_detection) {
            return;
        }

        // -----【更新循环缓冲区】-----
        dataPoint = (dataPoint + 1) % WINDOW_SIZE;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            xg[dataPoint] = event.values[0];
            yg[dataPoint] = event.values[1];
            zg[dataPoint] = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            x[dataPoint] = event.values[0];
            y[dataPoint] = event.values[1];
            z[dataPoint] = event.values[2];
        }

        // -----【计算加速度窗口内各轴均值、方差及标准差（加速度）】-----
        sumx = sumy = sumz = soqx = soqy = soqz = 0;
        for (int j = 0; j < WINDOW_SIZE; j++) {
            sumx += x[j];
            sumy += y[j];
            sumz += z[j];
            soqx += x[j] * x[j];
            soqy += y[j] * y[j];
            soqz += z[j] * z[j];
        }
        meanx = sumx / WINDOW_SIZE;
        meany = sumy / WINDOW_SIZE;
        meanz = sumz / WINDOW_SIZE;
        float varx = Math.abs((soqx / WINDOW_SIZE) - (meanx * meanx));
        float vary = Math.abs((soqy / WINDOW_SIZE) - (meany * meany));
        float varz = Math.abs((soqz / WINDOW_SIZE) - (meanz * meanz));
        sdx = (float) Math.sqrt(varx);
        sdy = (float) Math.sqrt(vary);
        sdz = (float) Math.sqrt(varz);


        // -----【计算窗口内各轴均值、方差及标准差（陀螺仪）】-----
        sumxg = sumyg = sumzg = soqxg = soqyg = soqzg = 0;
        for (int j = 0; j < WINDOW_SIZE; j++) {
            sumxg += xg[j];
            sumyg += yg[j];
            sumzg += zg[j];
            soqxg += xg[j] * xg[j];
            soqyg += yg[j] * yg[j];
            soqzg += zg[j] * zg[j];
        }
        meanxg = sumxg / WINDOW_SIZE;
        meanyg = sumyg / WINDOW_SIZE;
        meanzg = sumzg / WINDOW_SIZE;
        float varxg = Math.abs((soqxg / WINDOW_SIZE) - (meanxg * meanxg));
        float varyg = Math.abs((soqyg / WINDOW_SIZE) - (meanyg * meanyg));
        float varzg = Math.abs((soqzg / WINDOW_SIZE) - (meanzg * meanzg));
        sdxg = (float) Math.sqrt(varxg);
        sdyg = (float) Math.sqrt(varyg);
        sdzg = (float) Math.sqrt(varzg);


        // -----【构造特征向量并调用 SVM 预测】-----
        double[] arr = {meanx, meany, meanz, sdx, sdy, sdz, meanxg, meanyg, meanzg, sdxg, sdyg, sdzg};
        prediction = svmPredictor.doubleFromJNI(arr);
//        prediction = 1;


        // -----【将特征数据写入文件（记录窗口起始与结束时间及特征值和预测结果）】-----
        if (isCollecting && featureDataWriter != null) {
            long currentTimeWindow = System.currentTimeMillis();
            String featureLine = windowStartTime + "," + currentTimeWindow + ","
                    + meanx + "," + meany + "," + meanz + ","
                    + sdx + "," + sdy + "," + sdz + ","
                    + meanxg + "," + meanyg + "," + meanzg + ","
                    + sdxg + "," + sdyg + "," + sdzg + ","
                    + prediction;
            featureDataWriter.println(featureLine);
            featureDataWriter.flush();
            Log.d("FileWrite", "Feature data written: " + featureLine);
            windowStartTime = currentTimeWindow;
        }


        // -----【根据预测结果进行显示与声音提示（此处设定 prediction==-1 表示检测到坑洞）】-----
        if (potholeDetected && prediction == 1) {
            potholeDetected = false;
            if (tvCount != null) {
                tvCount.setTextColor(Color.GRAY);
            }
        }
        if (prediction == -1 && !potholeDetected) {
            if (tvCount != null) {
                tvCount.setTextColor(Color.RED);
            }
            mp.start();
            potholeDetected = true;
            time = System.currentTimeMillis() + 2000; // 超时2秒
            count++;
            if (sdz > 4.8) {
                effectOfRiding = "High";
            } else if (sdz > 2.8) {
                effectOfRiding = "Moderate";
            } else if (sdz > 1.8) {
                effectOfRiding = "Low";
            } else {
                effectOfRiding = "Very Low";
            }
            effectOfRiding = "\"" + effectOfRiding + "\"";
            accelTriggered = true;
            if (tvCount != null) {
                tvCount.setText("加速度检测：" + count);
            }
        }
        Log.d("MainActivity", "Pred: " + prediction);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 可根据需要处理
    }

    ////////////【定位监听】////////////
    @Override
    public void onLocationChanged(Location location) {
        lat = location.getLatitude();
        lon = location.getLongitude();
        speed = location.getSpeed();
        textViewLocation.setText("纬度：" + lat + " 经度：" + lon + " 速度：" + speed);
//        Toast.makeText(getApplicationContext(), "Location Updated", Toast.LENGTH_LONG).show();
        Log.d("MainActivity", "Latitude:" + lat + ", Longitude:" + lon);
        // 更新 native 层的车辆速度
        yolov11ncnn.setVehicleSpeed(speed);
    }
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override
    public void onProviderEnabled(String provider) { }
    @Override
    public void onProviderDisabled(String provider) { }

    ////////////【生命周期】////////////
    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, mAccelsensor, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, mGyrosensor, SensorManager.SENSOR_DELAY_GAME);
        if (isCollecting) {
//            yolov11ncnn.setCollectionState(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        yolov11ncnn.setCollectionState(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rawDataWriter != null) {
            rawDataWriter.close();
            Log.d("FileWrite", "RawDataWriter closed.");
        }
        if (featureDataWriter != null) {
            featureDataWriter.close();
            Log.d("FileWrite", "FeatureDataWriter closed.");
        }
        if (timestampWriter != null) {
            timestampWriter.close();
            Log.d("FileWrite", "TimestampWriter closed.");
        }
    }

    ////////////【文件初始化】////////////
    private void initFiles() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                Log.d("FileInit", "External Files Dir: " + dir.getAbsolutePath());
            } else {
                Log.e("FileInit", "getExternalFilesDir(null) returned null");
            }
            rawDataFile = new File(getExternalFilesDir(null), "raw_sensor_data.csv");
            featureDataFile = new File(getExternalFilesDir(null), "feature_data.csv");
            rawDataWriter = new PrintWriter(new FileOutputStream(rawDataFile, true));
            featureDataWriter = new PrintWriter(new FileOutputStream(featureDataFile, true));
            if (rawDataFile.length() == 0) {
                rawDataWriter.println("timestamp,sensor_type,acc_x,acc_y,acc_z,lat,lon");
                rawDataWriter.flush();
                Log.d("FileInit", "Raw data header written.");
            }
            if (featureDataFile.length() == 0) {
                featureDataWriter.println("window_start,window_end,meanx,meany,meanz,sdx,sdy,sdz,meanxg,meanyg,meanzg,sdxg,sdyg,sdzg,label");
                featureDataWriter.flush();
                Log.d("FileInit", "Feature data header written.");
            }
        } catch (Exception e) {
            Log.e("FileInit", "Error initializing file writers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    ////////////【定位初始化】////////////
    private void initLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.e("Location", "Location permission not granted in initLocation()");
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        Log.d("Location", "Location updates requested.");
    }
}
