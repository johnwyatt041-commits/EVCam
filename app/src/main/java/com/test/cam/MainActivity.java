package com.test.cam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.test.cam.camera.MultiCameraManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    // 根据Android版本动态获取需要的权限
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 12及以下
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private AutoFitTextureView textureFront, textureBack, textureLeft, textureRight;
    private Button btnStartRecord, btnStopRecord;
    private MultiCameraManager cameraManager;
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器

    // 日志相关
    private TextView logText;
    private ScrollView logScroll;
    private TextView logToggle;
    private boolean logExpanded = false;
    private StringBuilder logBuffer = new StringBuilder();
    private Process logcatProcess;
    private Thread logcatThread;
    private volatile boolean logcatRunning = false;
    private boolean logcatStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigationDrawer();

        // 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recordingLayout = findViewById(R.id.main);
        fragmentContainer = findViewById(R.id.fragment_container);

        textureFront = findViewById(R.id.texture_front);
        textureBack = findViewById(R.id.texture_back);
        textureLeft = findViewById(R.id.texture_left);
        textureRight = findViewById(R.id.texture_right);

        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);

        // 初始化日志视图
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        logToggle = findViewById(R.id.log_toggle);

        // 日志面板点击事件
        findViewById(R.id.log_header).setOnClickListener(v -> toggleLogPanel());

        // 菜单按钮点击事件
        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        btnStartRecord.setOnClickListener(v -> startRecording());
        btnStopRecord.setOnClickListener(v -> stopRecording());

        // 为每个TextureView添加Surface监听器
        TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                Log.d(TAG, "TextureView ready: " + textureReadyCount + "/4");
                appendLog("TextureView就绪: " + textureReadyCount + "/4 (尺寸: " + width + "x" + height + ")");

                // 当所有TextureView都准备好后，初始化摄像头
                if (textureReadyCount == 4 && checkPermissions()) {
                    appendLog("所有TextureView已就绪,开始初始化摄像头");
                    initCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "TextureView size changed: " + width + "x" + height);
                appendLog("TextureView尺寸变化: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                Log.d(TAG, "TextureView destroyed, remaining: " + textureReadyCount);
                appendLog("TextureView销毁,剩余: " + textureReadyCount);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
                // 不需要处理每帧更新
            }
        };

        textureFront.setSurfaceTextureListener(surfaceTextureListener);
        textureBack.setSurfaceTextureListener(surfaceTextureListener);
        textureLeft.setSurfaceTextureListener(surfaceTextureListener);
        textureRight.setSurfaceTextureListener(surfaceTextureListener);

        // 初始化日志
        appendLog("应用启动");
    }

    /**
     * 设置导航抽屉
     */
    private void setupNavigationDrawer() {
        // 设置导航菜单点击监听
        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_recording) {
                // 显示录制界面
                showRecordingInterface();
            } else if (itemId == R.id.nav_playback) {
                // 显示回看界面
                showPlaybackInterface();
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
    }

    /**
     * 显示录制界面
     */
    private void showRecordingInterface() {
        // 清除所有Fragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (Fragment fragment : fragmentManager.getFragments()) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }

        // 显示录制布局，隐藏Fragment容器
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
    }

    /**
     * 显示回看界面
     */
    private void showPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示PlaybackFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PlaybackFragment());
        transaction.commit();
    }

    /**
     * 切换日志面板显示/隐藏
     */
    private void toggleLogPanel() {
        logExpanded = !logExpanded;
        if (logExpanded) {
            logScroll.setVisibility(android.view.View.VISIBLE);
            logToggle.setText("▲");
        } else {
            logScroll.setVisibility(android.view.View.GONE);
            logToggle.setText("▼");
        }
    }

    /**
     * 添加日志信息
     */
    private void appendLog(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String logLine = timestamp + " - " + message + "\n";

        runOnUiThread(() -> {
            logBuffer.append(logLine);
            // 限制日志缓冲区大小
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, logBuffer.length() - 20000);
            }
            logText.setText(logBuffer.toString());
            // 自动滚动到底部
            logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });

        // 同时输出到 Logcat
        Log.d(TAG, message);
    }

    private void appendLogcatLine(String line) {
        if (logText == null || logScroll == null) {
            return;
        }
        runOnUiThread(() -> {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, logBuffer.length() - 20000);
            }
            logText.setText(logBuffer.toString());
            logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private void startLogcatReader() {
        if (logcatThread != null) {
            return;
        }
        logcatRunning = true;
        logcatThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-v", "time",
                        "-s", "CameraService:V", "Camera3-Device:V", "Camera3-Stream:V", "Camera3-Output:V", "camera3:V"
                );
                pb.redirectErrorStream(true);
                logcatProcess = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()))) {
                    String line;
                    while (logcatRunning && (line = reader.readLine()) != null) {
                        appendLogcatLine(line);
                    }
                }
            } catch (IOException e) {
                appendLog("logcat启动失败: " + e.getMessage());
            }
        }, "LogcatReader");
        logcatThread.start();
    }

    private void stopLogcatReader() {
        logcatRunning = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }

    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                appendLog("缺少权限: " + permission);
                return false;
            }
        }
        appendLog("权限检查通过");
        return true;
    }

    private void requestPermissions() {
        Log.d(TAG, "Requesting permissions...");
        appendLog("请求权限...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                appendLog("权限已授予");
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount == 4) {
                    initCamera();
                }
            } else {
                appendLog("权限被拒绝,应用退出");
                Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有TextureView都准备好
        if (textureReadyCount < 4) {
            Log.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/4");
            appendLog("TextureView未就绪: " + textureReadyCount + "/4");
            return;
        }

        appendLog("开始初始化摄像头...");
        if (!logcatStarted) {
            logcatStarted = true;
            startLogcatReader();
            appendLog("logcat reader started");
        }
        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(4);
        appendLog("open all 4 cameras");

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            appendLog("摄像头 " + cameraId + ": " + status);

            // 如果摄像头断开或被占用，提示用户
            if (status.contains("错误") || status.contains("断开")) {
                runOnUiThread(() -> {
                    if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 被占用，正在自动重连...",
                            Toast.LENGTH_SHORT).show();
                    } else if (status.contains("max reconnect attempts")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                            Toast.LENGTH_LONG).show();
                    }
                });
            } else if (status.contains("已打开") || status.contains("预览已启动")) {
                // 摄像头恢复正常
                runOnUiThread(() -> {
                    // 可以在这里添加恢复提示，但为了避免过多提示，暂时注释
                    // Toast.makeText(MainActivity.this, "摄像头 " + cameraId + " 已恢复", Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            appendLog("摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            // 根据 camera key 设置对应 TextureView 的宽高比
            runOnUiThread(() -> {
                AutoFitTextureView textureView = null;
                switch (cameraKey) {
                    case "front":
                        textureView = textureFront;
                        break;
                    case "back":
                        textureView = textureBack;
                        break;
                    case "left":
                        textureView = textureLeft;
                        break;
                    case "right":
                        textureView = textureRight;
                        break;
                }
                if (textureView != null) {
                    textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                    appendLog("设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight());
                }
            });
        });

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                Log.d(TAG, "Available cameras: " + cameraIds.length);
                appendLog("检测到 " + cameraIds.length + " 个摄像头");

                for (String id : cameraIds) {
                    Log.d(TAG, "Camera ID: " + id);
                    appendLog("摄像头ID: " + id);
                }

                // 根据可用摄像头数量初始化
                if (cameraIds.length >= 4) {
                    // 有4个或更多摄像头
                    appendLog("使用4路摄像头模式");
                    cameraManager.initCameras(
                            cameraIds[0], textureFront,
                            cameraIds[1], textureBack,
                            cameraIds[2], textureLeft,
                            cameraIds[3], textureRight
                    );
                } else if (cameraIds.length >= 2) {
                    // 只有2个摄像头，使用前两个位置
                    appendLog("使用2路摄像头模式(复用显示)");
                    cameraManager.initCameras(
                            cameraIds[0], textureFront,
                            cameraIds[1], textureBack,
                            cameraIds[0], textureLeft,  // 复用第一个
                            cameraIds[1], textureRight  // 复用第二个
                    );
                } else if (cameraIds.length == 1) {
                    // 只有1个摄像头，所有位置使用同一个
                    appendLog("使用1路摄像头模式(复用显示)");
                    cameraManager.initCameras(
                            cameraIds[0], textureFront,
                            cameraIds[0], textureBack,
                            cameraIds[0], textureLeft,
                            cameraIds[0], textureRight
                    );
                } else {
                    appendLog("错误: 没有可用的摄像头");
                    Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 打开所有摄像头
                appendLog("正在打开摄像头...");
                cameraManager.openAllCameras();

                Log.d(TAG, "Camera initialized with " + cameraIds.length + " cameras");
                appendLog("摄像头初始化完成");
                Toast.makeText(this, "已打开 " + cameraIds.length + " 个摄像头", Toast.LENGTH_SHORT).show();

            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to access camera", e);
                appendLog("摄像头访问失败: " + e.getMessage());
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startRecording() {
        if (cameraManager != null && !cameraManager.isRecording()) {
            appendLog("开始录制...");
            appendLog("提示: 视频将自动分段，每段1分钟");
            boolean success = cameraManager.startRecording();
            if (success) {
                btnStartRecord.setEnabled(false);
                btnStopRecord.setEnabled(true);
                appendLog("录制已开始（自动分段模式）");
                Toast.makeText(this, "开始录制（每1分钟自动分段）", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Recording started");
            } else {
                appendLog("录制失败");
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (cameraManager != null) {
            appendLog("停止录制...");
            cameraManager.stopRecording();
            btnStartRecord.setEnabled(true);
            btnStopRecord.setEnabled(false);
            appendLog("录制已停止");
            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Recording stopped");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogcatReader();
        if (cameraManager != null) {
            cameraManager.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}
