package com.kooo.evcam.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.surround.SurroundCalibrationManager;
import com.kooo.evcam.surround.SurroundCarModelRepository;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 360 本地校准文件与离线 PNG 车模导入弹窗/SAF 回退共用助手，
 * 供「360 画面」设置页与「4.3 实时标定与视野调校」子页共享。
 */
public final class SurroundAssetImportHelper {

    public static final String[] CALIBRATION_MIME_TYPES = {
            "application/json", "application/zip", "application/x-zip-compressed", "text/plain", "*/*"
    };
    public static final String[] CAR_MODEL_MIME_TYPES = {"image/png", "image/*"};

    private SurroundAssetImportHelper() {
    }

    public static void showLocalCalibrationPicker(@NonNull Fragment fragment,
                                                  @NonNull ActivityResultLauncher<String[]> safLauncher,
                                                  @NonNull Consumer<SurroundCalibrationManager.ImportResult> onResult) {
        showLocalCalibrationPicker(fragment, safLauncher, null, onResult);
    }

    public static void showLocalCalibrationPicker(@NonNull Fragment fragment,
                                                  @NonNull ActivityResultLauncher<String[]> safLauncher,
                                                  @Nullable ActivityResultLauncher<Uri> folderTreeLauncher,
                                                  @NonNull Consumer<SurroundCalibrationManager.ImportResult> onResult) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        List<File> candidates = SurroundCalibrationManager.findCalibrationCandidates(downloadDir);
        if (candidates.isEmpty()) {
            if (folderTreeLauncher != null) {
                showCalibrationImportSourceDialog(fragment, ctx, safLauncher, folderTreeLauncher);
            } else {
                launchDocumentPicker(fragment, ctx, safLauncher, CALIBRATION_MIME_TYPES,
                        "E5 calibration.json / .zip / 原厂文件夹");
            }
            return;
        }
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            File f = candidates.get(i);
            if (f.isDirectory()) {
                String parentName = f.getParentFile() != null && !"Download".equalsIgnoreCase(f.getParentFile().getName())
                        ? f.getParentFile().getName() + "/"
                        : "";
                labels[i] = "[原厂目录] " + parentName + f.getName();
            } else if (f.getParentFile() != null && !"Download".equalsIgnoreCase(f.getParentFile().getName())) {
                labels[i] = f.getParentFile().getName() + "/" + f.getName();
            } else {
                labels[i] = f.getName();
            }
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("选择 E5 校准文件或原厂目录（Download）")
                .setItems(labels, (d, which) -> {
                    File internalDir = new File(ctx.getFilesDir(), "surround");
                    onResult.accept(SurroundCalibrationManager.importCalibrationFile(candidates.get(which), internalDir));
                })
                .setNegativeButton("取消", null);
        if (folderTreeLauncher != null) {
            builder.setPositiveButton("选原厂文件夹",
                    (d, w) -> launchFolderTreePicker(fragment, ctx, folderTreeLauncher));
            builder.setNeutralButton("选文件(.json/.zip)",
                    (d, w) -> launchDocumentPicker(fragment, ctx, safLauncher, CALIBRATION_MIME_TYPES,
                            "E5 calibration.json / .zip"));
        } else {
            builder.setNeutralButton("系统文件选择器",
                    (d, w) -> launchDocumentPicker(fragment, ctx, safLauncher, CALIBRATION_MIME_TYPES,
                            "E5 calibration.json / .zip"));
        }
        builder.show();
    }

    private static void showCalibrationImportSourceDialog(@NonNull Fragment fragment,
                                                          @NonNull Context ctx,
                                                          @NonNull ActivityResultLauncher<String[]> safLauncher,
                                                          @NonNull ActivityResultLauncher<Uri> folderTreeLauncher) {
        String[] options = {
                "选择原厂标定文件夹（avm_calib_res / data，含 Table.db + .param）",
                "选择标定文件（calibration.json 或 .zip 压缩包）"
        };
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("导入 360 标定方案")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        launchFolderTreePicker(fragment, ctx, folderTreeLauncher);
                    } else {
                        launchDocumentPicker(fragment, ctx, safLauncher, CALIBRATION_MIME_TYPES,
                                "E5 calibration.json / .zip");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void launchFolderTreePicker(@NonNull Fragment fragment,
                                               @NonNull Context ctx,
                                               @NonNull ActivityResultLauncher<Uri> folderLauncher) {
        try {
            folderLauncher.launch(null);
        } catch (ActivityNotFoundException | SecurityException e) {
            showMissingPickerOrPermissionDialog(fragment, ctx, "原厂标定文件夹（Table.db + .param）");
        }
    }

    public static void showLocalCarModelPicker(@NonNull Fragment fragment,
                                               @NonNull ActivityResultLauncher<String[]> safLauncher,
                                               @NonNull Consumer<SurroundCarModelRepository.ImportResult> onResult) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        List<File> candidates = SurroundCarModelRepository.findCustomPngCandidates(downloadDir);
        showCandidateFilePicker(
                fragment,
                ctx,
                candidates,
                File::getName,
                "选择本地透明 PNG 车模（Download）",
                "透明 PNG 车模",
                CAR_MODEL_MIME_TYPES,
                safLauncher,
                selected -> {
                    File internalDir = new File(ctx.getFilesDir(), "surround");
                    onResult.accept(SurroundCarModelRepository.importCustomCarModelPng(selected, internalDir));
                });
    }

    public static void applyCalibrationImportResult(@NonNull Context ctx,
                                                    @NonNull AppConfig appConfig,
                                                    @NonNull SurroundCalibrationManager.ImportResult result,
                                                    @Nullable Runnable onApplied) {
        if (result.success && result.importedFilePath != null) {
            appConfig.setSurroundCustomCalibrationPath(result.importedFilePath);
            appConfig.setSurroundCalibrationSource(AppConfig.SURROUND_CALIBRATION_E5_CUSTOM);
            // 导入新本地标定文件时仅重置本地导入方案的微调槽，绝不影响内置星舰7方案槽位
            appConfig.resetSurroundExtrinsicAdjustmentForSlot(AppConfig.SURROUND_CALIBRATION_E5_CUSTOM);
            if (onApplied != null) {
                onApplied.run();
            }
            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show();
        } else {
            new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("校准文件导入失败")
                    .setMessage(result.message)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    public static void applyCarModelImportResult(@NonNull Context ctx,
                                                 @NonNull AppConfig appConfig,
                                                 @NonNull SurroundCarModelRepository.ImportResult result,
                                                 @Nullable Runnable onApplied) {
        if (result.success && result.importedFilePath != null) {
            appConfig.setSurroundCustomCarModelPath(result.importedFilePath);
            appConfig.setSurroundCarModelSource(AppConfig.SURROUND_CAR_MODEL_CUSTOM_PNG);
            if (onApplied != null) {
                onApplied.run();
            }
            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show();
        } else {
            new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("车模导入失败")
                    .setMessage(result.message)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private static void showCandidateFilePicker(@NonNull Fragment fragment,
                                                @NonNull Context ctx,
                                                @NonNull List<File> candidates,
                                                @NonNull Function<File, String> labelFormatter,
                                                @NonNull String dialogTitle,
                                                @NonNull String targetLabel,
                                                @NonNull String[] mimeTypes,
                                                @NonNull ActivityResultLauncher<String[]> safLauncher,
                                                @NonNull Consumer<File> onFileChosen) {
        if (candidates.isEmpty()) {
            launchDocumentPicker(fragment, ctx, safLauncher, mimeTypes, targetLabel);
            return;
        }
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = labelFormatter.apply(candidates.get(i));
        }
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(dialogTitle)
                .setItems(labels, (d, which) -> onFileChosen.accept(candidates.get(which)))
                .setNeutralButton("系统文件选择器",
                        (d, w) -> launchDocumentPicker(fragment, ctx, safLauncher, mimeTypes, targetLabel))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void launchDocumentPicker(@NonNull Fragment fragment,
                                             @NonNull Context ctx,
                                             @NonNull ActivityResultLauncher<String[]> launcher,
                                             @NonNull String[] mimeTypes,
                                             @NonNull String targetLabel) {
        try {
            launcher.launch(mimeTypes);
        } catch (ActivityNotFoundException | SecurityException e) {
            showMissingPickerOrPermissionDialog(fragment, ctx, targetLabel);
        }
    }

    private static void showMissingPickerOrPermissionDialog(@NonNull Fragment fragment,
                                                            @NonNull Context ctx,
                                                            @NonNull String targetLabel) {
        boolean needAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager();
        String message = needAllFiles
                ? "Download 目录下未扫描到可用" + targetLabel + "（Android 11+ 未授予「所有文件访问」权限时无法直接读取 Download 目录），且当前系统未提供文件选择器。\n\n请先授予文件访问权限并将文件放入 Download 目录。"
                : "Download 目录下未找到可用" + targetLabel + "，且当前系统未提供文件选择器。\n\n请将文件放入 Download 目录后重试。";
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("无法打开文件选择")
                .setMessage(message)
                .setNegativeButton("取消", null);
        if (needAllFiles) {
            builder.setPositiveButton("去授予文件权限", (d, w) -> openAllFilesAccessSettings(fragment, ctx));
        }
        builder.show();
    }

    private static void openAllFilesAccessSettings(@NonNull Fragment fragment, @NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            fragment.startActivity(intent);
        } catch (Exception e) {
            try {
                fragment.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                Toast.makeText(ctx, "请在系统设置中授予 EVCam 文件访问权限", Toast.LENGTH_LONG).show();
            }
        }
    }
}
