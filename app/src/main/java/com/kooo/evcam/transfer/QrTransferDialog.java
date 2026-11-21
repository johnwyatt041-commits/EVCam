package com.kooo.evcam.transfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QR code & URL controller dialog backed by {@link LanShareService}.
 * Closing the dialog leaves the service alive for background downloads until the 60s idle timeout.
 */
public class QrTransferDialog extends ComponentDialog implements LanShareService.StateListener {

    private static final String TAG = "QrTransferDialog";

    private TextView dialogTitle;
    private ImageView qrCodeImage;
    private ProgressBar qrLoading;
    private TextView serverStatus;
    private TextView transferStatus;
    private TextView urlText;
    private TextView fileListHeader;
    private TextView libraryModeNotice;
    private Button btnCopyUrl;
    private Spinner ipSpinner;
    private Button btnHotspotSettings;
    private ListView fileListView;
    private Button btnRefreshIp;
    private Button btnKeepBackground;
    private Button btnStopServer;

    private final FileTransferServer.ShareMode shareMode;
    private final List<File> filesToTransfer;
    private final List<LanNetworkSelector.NetworkEndpoint> availableEndpoints = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lan-nic-scan");
        t.setDaemon(true);
        return t;
    });

    @Nullable
    private FileListAdapter fileListAdapter;
    @Nullable
    private String selectedIp;
    @Nullable
    private String lastRenderedQrUrl;
    @Nullable
    private String lastScanDiagnostic;
    private int scanGeneration = 0;
    private boolean isScanning = false;
    private boolean awaitingServiceStart = false;
    private boolean listenerRegistered = false;
    private boolean initialStartCompleted = false;
    private boolean suppressSpinnerSelection = false;
    private volatile boolean isDestroying = false;

    public QrTransferDialog(@NonNull Context context, List<File> files) {
        super(context, R.style.Theme_Cam_MaterialAlertDialog);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("文件列表不能为空");
        }
        this.shareMode = FileTransferServer.ShareMode.SELECTED_FILES;
        this.filesToTransfer = new ArrayList<>(files);
    }

    private QrTransferDialog(@NonNull Context context, FileTransferServer.ShareMode shareMode) {
        super(context, R.style.Theme_Cam_MaterialAlertDialog);
        this.shareMode = shareMode;
        this.filesToTransfer = Collections.emptyList();
    }

    public static void showLibraryShare(@NonNull Context context) {
        new QrTransferDialog(context, FileTransferServer.ShareMode.LIBRARY).show();
    }

    private boolean isDestructionStarted() {
        return isDestroying || !isShowing() || isHostActivityDestroyed(getContext());
    }

    private static boolean isHostActivityDestroyed(@Nullable Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                Activity activity = (Activity) current;
                return activity.isFinishing() || activity.isDestroyed();
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
        setCanceledOnTouchOutside(false);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showLeaveConfirmationDialog();
            }
        });

        setContentView(R.layout.dialog_qr_transfer_v2);
        initViews();
        setupListeners();
        initModeContent();

        LanShareService.LanShareState existing = LanShareService.getCurrentState();
        String initialPreferIp = existing.running ? existing.boundIp : null;
        scanIpAddressesAsync(initialPreferIp, true, false, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStateListener();
        if (!initialStartCompleted) {
            initialStartCompleted = true;
            return;
        }
        LanShareService.LanShareState state = LanShareService.getCurrentState();
        if (!isScanning && ((!state.running && !state.starting) || selectedIp == null)) {
            scanIpAddressesAsync(selectedIp, true, false, false);
        }
    }

    private void registerStateListener() {
        if (!listenerRegistered) {
            listenerRegistered = true;
            LanShareService.addStateListener(this);
        }
    }

    private void unregisterStateListener() {
        if (listenerRegistered) {
            listenerRegistered = false;
            LanShareService.removeStateListener(this);
        }
    }

    private void initViews() {
        dialogTitle = findViewById(R.id.dialog_title);
        qrCodeImage = findViewById(R.id.qr_code_image);
        qrLoading = findViewById(R.id.qr_loading);
        serverStatus = findViewById(R.id.server_status);
        transferStatus = findViewById(R.id.transfer_status);
        urlText = findViewById(R.id.url_text);
        fileListHeader = findViewById(R.id.file_list_header);
        libraryModeNotice = findViewById(R.id.library_mode_notice);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
        ipSpinner = findViewById(R.id.ip_spinner);
        btnHotspotSettings = findViewById(R.id.btn_hotspot_settings);
        fileListView = findViewById(R.id.file_list_view);
        btnRefreshIp = findViewById(R.id.btn_refresh_ip);
        btnKeepBackground = findViewById(R.id.btn_keep_background);
        btnStopServer = findViewById(R.id.btn_stop_server);

        if (getWindow() != null) {
            int width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.86);
            getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void initModeContent() {
        if (shareMode == FileTransferServer.ShareMode.LIBRARY) {
            dialogTitle.setText("Wi-Fi 浏览器全库回看分享");
            fileListHeader.setVisibility(View.GONE);
            fileListView.setVisibility(View.GONE);
            libraryModeNotice.setVisibility(View.VISIBLE);
        } else {
            dialogTitle.setText("扫码互传（已选 " + filesToTransfer.size() + " 个文件）");
            fileListHeader.setVisibility(View.VISIBLE);
            fileListView.setVisibility(View.VISIBLE);
            libraryModeNotice.setVisibility(View.GONE);
            fileListAdapter = new FileListAdapter(getContext(), filesToTransfer);
            fileListView.setAdapter(fileListAdapter);
        }
    }

    private void setupListeners() {
        btnHotspotSettings.setOnClickListener(v -> openHotspotSettings());

        btnRefreshIp.setOnClickListener(v -> scanIpAddressesAsync(selectedIp, true, true, false));

        btnKeepBackground.setOnClickListener(v -> {
            Toast.makeText(getContext(), "已转入后台继续分享（60 秒无访问自动关闭）", Toast.LENGTH_SHORT).show();
            dismiss();
        });

        btnStopServer.setOnClickListener(v -> {
            awaitingServiceStart = false;
            LanShareService.stopShare(getContext());
            dismiss();
        });

        btnCopyUrl.setOnClickListener(v -> {
            String url = urlText.getText().toString();
            if (url.startsWith("http://")) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("EVCam LAN Share URL", url));
                    Toast.makeText(getContext(), "网址已复制到剪贴板", Toast.LENGTH_SHORT).show();
                }
            }
        });

        ipSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerSelection || position < 0 || position >= availableEndpoints.size()) {
                    return;
                }
                String chosenIp = availableEndpoints.get(position).ip;
                if (chosenIp.equals(selectedIp)) {
                    return;
                }
                selectedIp = chosenIp;
                AppLog.i(TAG, "Switch LAN IP to " + chosenIp);
                awaitingServiceStart = true;
                LanShareService.switchIp(getContext(), chosenIp);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void openHotspotSettings() {
        Context context = getContext();
        Intent[] candidates = new Intent[] {
                new Intent("android.settings.WIFI_TETHER_SETTINGS"),
                new Intent("com.android.settings.TETHER_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Intent.ACTION_MAIN).setClassName(
                        "com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent(Settings.ACTION_WIFI_SETTINGS)
        };
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return;
            } catch (Exception e) {
                AppLog.d(TAG, "Settings intent failed (" + intent.getAction() + "): " + e.getMessage());
            }
        }
        Toast.makeText(context, "无法直达热点设置，请手动前往系统设置开启热点或 Wi-Fi", Toast.LENGTH_LONG).show();
    }

    private void scanIpAddressesAsync(@Nullable String preferIp,
                                      boolean startOrSwitchService,
                                      boolean showRefreshToast,
                                      boolean forceEnsureStart) {
        final int generation = ++scanGeneration;
        isScanning = true;
        if (!LanShareService.getCurrentState().running) {
            qrLoading.setVisibility(View.VISIBLE);
            serverStatus.setText("正在检测局域网网卡...");
            transferStatus.setText("等待检测...");
        }

        final Context appContext = getContext().getApplicationContext();
        try {
            scanExecutor.execute(() -> {
                LanNetworkSelector.ScanResult result = LanNetworkSelector.scanLocalEndpoints(appContext);
                mainHandler.post(() -> applyScanResult(
                        generation, result, preferIp, startOrSwitchService, showRefreshToast, forceEnsureStart));
            });
        } catch (Exception e) {
            isScanning = false;
            AppLog.w(TAG, "Failed scheduling LAN NIC scan: " + e.getMessage());
        }
    }

    private void applyScanResult(int generation,
                                 LanNetworkSelector.ScanResult result,
                                 @Nullable String preferIp,
                                 boolean startOrSwitchService,
                                 boolean showRefreshToast,
                                 boolean forceEnsureStart) {
        if (generation != scanGeneration || isDestroying || isHostActivityDestroyed(getContext())) {
            return;
        }
        isScanning = false;
        lastScanDiagnostic = result.diagnosticSummary;

        availableEndpoints.clear();
        availableEndpoints.addAll(result.endpoints);

        List<String> labels = new ArrayList<>();
        int selectedIndex = 0;
        if (availableEndpoints.isEmpty()) {
            selectedIp = null;
            labels.add("未检测到可用局域网/热点网卡");
        } else {
            for (int i = 0; i < availableEndpoints.size(); i++) {
                LanNetworkSelector.NetworkEndpoint ep = availableEndpoints.get(i);
                labels.add(ep.toString());
                if (preferIp != null && preferIp.equals(ep.ip)) {
                    selectedIndex = i;
                }
            }
            selectedIp = availableEndpoints.get(selectedIndex).ip;
        }

        suppressSpinnerSelection = true;
        try {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getContext(), android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ipSpinner.setAdapter(adapter);
            ipSpinner.setSelection(selectedIndex);
        } finally {
            suppressSpinnerSelection = false;
        }

        LanShareService.LanShareState state = LanShareService.getCurrentState();
        if (selectedIp == null) {
            awaitingServiceStart = false;
            if (!state.running && !state.starting) {
                showNoNicUiState();
            }
            if (showRefreshToast) {
                Toast.makeText(getContext(), "仍未检测到可用网卡", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (showRefreshToast) {
            Toast.makeText(getContext(),
                    "已刷新网卡列表（共 " + availableEndpoints.size() + " 个）",
                    Toast.LENGTH_SHORT).show();
        }

        if (startOrSwitchService) {
            if (forceEnsureStart || (!state.running && !state.starting)) {
                ensureServiceStarted();
            } else if (state.running && !selectedIp.equals(state.boundIp)) {
                awaitingServiceStart = true;
                LanShareService.switchIp(getContext(), selectedIp);
            }
        }
    }

    private void showNoNicUiState() {
        qrLoading.setVisibility(View.GONE);
        qrCodeImage.setImageDrawable(null);
        lastRenderedQrUrl = null;
        urlText.setText("http://...");
        StringBuilder msg = new StringBuilder("未检测到可用的局域网或热点网络，请开启车机热点/Wi-Fi 后点击“刷新网卡”");
        if (lastScanDiagnostic != null && !lastScanDiagnostic.isEmpty()) {
            msg.append("\n已检测网卡：").append(lastScanDiagnostic);
        }
        serverStatus.setText(msg.toString());
        transferStatus.setText("请确认网络后点击“刷新网卡”");
    }

    private void ensureServiceStarted() {
        if (selectedIp == null) {
            awaitingServiceStart = false;
            showNoNicUiState();
            return;
        }
        awaitingServiceStart = true;
        qrLoading.setVisibility(View.VISIBLE);
        if (!LanShareService.getCurrentState().running) {
            serverStatus.setText("正在启动局域网分享服务...");
        }
        if (shareMode == FileTransferServer.ShareMode.LIBRARY) {
            LanShareService.startLibraryShare(getContext(), selectedIp);
        } else {
            LanShareService.startSelectedShare(getContext(), filesToTransfer, selectedIp);
        }
    }

    @Override
    public void onStateChanged(LanShareService.LanShareState state) {
        if (isDestructionStarted()) {
            return;
        }
        if (state.starting || state.running || state.errorMessage != null) {
            awaitingServiceStart = false;
        }
        if (state.starting) {
            qrLoading.setVisibility(View.VISIBLE);
            serverStatus.setText("正在启动局域网分享服务...");
            transferStatus.setText(state.statusText);
            return;
        }

        if (state.errorMessage != null && !state.running) {
            qrLoading.setVisibility(View.GONE);
            qrCodeImage.setImageDrawable(null);
            lastRenderedQrUrl = null;
            serverStatus.setText("服务异常: " + state.errorMessage);
            transferStatus.setText("请确认网络后点击“刷新网卡”");
            urlText.setText("http://...");
            return;
        }

        if (!state.running) {
            if (isScanning) {
                qrLoading.setVisibility(View.VISIBLE);
                serverStatus.setText("正在检测局域网网卡...");
                transferStatus.setText("等待检测...");
            } else if (awaitingServiceStart) {
                qrLoading.setVisibility(View.VISIBLE);
                serverStatus.setText("正在启动局域网分享服务...");
                transferStatus.setText("等待连接...");
            } else if (selectedIp == null) {
                showNoNicUiState();
            } else {
                qrLoading.setVisibility(View.GONE);
                qrCodeImage.setImageDrawable(null);
                lastRenderedQrUrl = null;
                urlText.setText("http://...");
                serverStatus.setText("局域网分享未运行");
            }
            return;
        }

        if (state.boundIp != null && !state.boundIp.equals(selectedIp)) {
            syncSpinnerToIp(state.boundIp);
        }

        serverStatus.setText(String.format(Locale.US,
                "已就绪 (%s:%d · 活跃连接 %d · 空闲 %ds 自动关闭)",
                state.boundIp, state.port, state.activeStreams, state.idleRemainingSec));
        transferStatus.setText(state.statusText);

        if (state.shareUrl != null) {
            urlText.setText(state.shareUrl);
            if (!state.shareUrl.equals(lastRenderedQrUrl)) {
                renderQrCode(state.shareUrl);
            }
        }

        if (fileListAdapter != null && state.fileStatuses != null) {
            fileListAdapter.applyStatuses(state.fileStatuses);
        }
    }

    private void syncSpinnerToIp(String boundIp) {
        for (int i = 0; i < availableEndpoints.size(); i++) {
            if (boundIp.equals(availableEndpoints.get(i).ip)) {
                suppressSpinnerSelection = true;
                try {
                    selectedIp = boundIp;
                    ipSpinner.setSelection(i);
                } finally {
                    suppressSpinnerSelection = false;
                }
                break;
            }
        }
    }

    private void renderQrCode(String qrUrl) {
        lastRenderedQrUrl = qrUrl;
        int qrSize = (int) (getContext().getResources().getDisplayMetrics().density * 190);
        Bitmap qrBitmap = QrCodeGenerator.generateQRCode(qrUrl, qrSize, qrSize);
        if (qrBitmap != null) {
            qrCodeImage.setImageBitmap(qrBitmap);
            qrLoading.setVisibility(View.GONE);
        }
    }

    @Override
    public void dismiss() {
        isDestroying = true;
        scanGeneration++;
        unregisterStateListener();
        scanExecutor.shutdownNow();
        super.dismiss();
    }

    @Override
    protected void onStop() {
        unregisterStateListener();
        super.onStop();
    }

    private void showLeaveConfirmationDialog() {
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("离开分享窗口")
                .setMessage("离开弹窗后手机仍可在后台继续浏览或下载（60 秒无人访问自动关闭）；若需立即断开，请选“停止分享”。")
                .setPositiveButton("后台继续", (d, w) -> dismiss())
                .setNegativeButton("停止分享", (d, w) -> {
                    awaitingServiceStart = false;
                    LanShareService.stopShare(getContext());
                    dismiss();
                })
                .setNeutralButton("留在此页", null)
                .show();
    }

    private static final class FileListAdapter extends BaseAdapter {
        private final Context context;
        private final List<File> files;
        private final List<String> statuses;

        FileListAdapter(Context context, List<File> files) {
            this.context = context;
            this.files = files;
            this.statuses = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                statuses.add("等待");
            }
        }

        void applyStatuses(Map<String, String> statusMap) {
            boolean changed = false;
            for (int i = 0; i < files.size(); i++) {
                String next = statusMap.get(files.get(i).getName());
                if (next != null && !next.equals(statuses.get(i))) {
                    statuses.set(i, next);
                    changed = true;
                }
            }
            if (changed) {
                notifyDataSetChanged();
            }
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_transfer_file, parent, false);
            }
            File file = files.get(position);
            TextView fileName = convertView.findViewById(R.id.file_name);
            TextView fileSize = convertView.findViewById(R.id.file_size);
            TextView fileStatus = convertView.findViewById(R.id.file_status);

            fileName.setText(file.getName());
            fileSize.setText(formatFileSize(file.length()));
            String status = statuses.get(position);
            fileStatus.setText(status);

            if ("完成".equals(status)) {
                fileStatus.setTextColor(ContextCompat.getColor(context, R.color.ui_success));
            } else if ("中断".equals(status) || "失败".equals(status)) {
                fileStatus.setTextColor(ContextCompat.getColor(context, R.color.ui_danger));
            } else {
                fileStatus.setTextColor(ContextCompat.getColor(context, R.color.button_accent));
            }
            return convertView;
        }

        private static String formatFileSize(long size) {
            if (size < 1024L) {
                return size + " B";
            } else if (size < 1024L * 1024L) {
                return String.format(Locale.US, "%.2f KB", size / 1024.0);
            } else if (size < 1024L * 1024L * 1024L) {
                return String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0));
            } else {
                return String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
            }
        }
    }
}
