package com.kooo.evcam.playback;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.transfer.QrTransferDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared theme-styling and share-sheet helper used by both {@link PlaybackFragmentNew}
 * and {@link PhotoPlaybackFragmentNew}.
 */
final class PlaybackThemeAndShareHelper {

    private PlaybackThemeAndShareHelper() {}

    static void styleFilterButton(@Nullable Context context, @Nullable Button button, boolean selected) {
        if (context == null || button == null) {
            return;
        }
        int bgRes = selected ? R.color.button_accent : R.color.button_background;
        int fgRes = selected ? R.color.ui_on_accent : R.color.button_text;
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(ContextCompat.getColor(context, bgRes)));
        button.setTextColor(ContextCompat.getColor(context, fgRes));
    }

    static void styleToolbarButton(@Nullable Context context, @Nullable Button button, boolean accent) {
        styleFilterButton(context, button, accent);
    }

    static void showShareOptionsDialog(@NonNull Fragment fragment,
                                       @NonNull String title,
                                       @NonNull String message,
                                       @NonNull List<File> files,
                                       @NonNull String mimeType,
                                       @NonNull String subject,
                                       @NonNull String shareText,
                                       @NonNull String chooserTitle) {
        Context context = fragment.getContext();
        Activity activity = fragment.getActivity();
        if (context == null || activity == null || activity.isFinishing() || activity.isDestroyed() || !fragment.isAdded()) {
            return;
        }
        if (files.isEmpty()) {
            Toast.makeText(context, "没有可分享的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_share_options, null);
        TextView titleView = dialogView.findViewById(R.id.dialog_title);
        TextView messageView = dialogView.findViewById(R.id.dialog_message);
        View btnQr = dialogView.findViewById(R.id.btn_qr);
        View btnShare = dialogView.findViewById(R.id.btn_share);
        View btnClose = dialogView.findViewById(R.id.btn_close);

        titleView.setText(title);
        messageView.setText(message);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnQr.setOnClickListener(v -> {
            dialog.dismiss();
            showQrTransferDialog(fragment, files);
        });
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareFilesViaSystem(fragment, files, mimeType, subject, shareText, chooserTitle);
        });
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    static void showQrTransferDialog(@NonNull Fragment fragment, @NonNull List<File> files) {
        Activity activity = fragment.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !fragment.isAdded()) {
            return;
        }
        try {
            new QrTransferDialog(activity, files).show();
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开扫码分享窗口: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void shareFilesViaSystem(@NonNull Fragment fragment,
                                            @NonNull List<File> files,
                                            @NonNull String mimeType,
                                            @NonNull String subject,
                                            @NonNull String shareText,
                                            @NonNull String chooserTitle) {
        Context context = fragment.getContext();
        if (context == null || files.isEmpty()) {
            return;
        }
        try {
            String authority = context.getPackageName() + ".fileprovider";
            Intent shareIntent;
            if (files.size() == 1) {
                File file = files.get(0);
                if (!file.exists() || !file.canRead()) {
                    Toast.makeText(context, "文件不存在或无法读取", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(context, authority, file);
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mimeType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (File file : files) {
                    if (file.exists() && file.canRead()) {
                        uris.add(FileProvider.getUriForFile(context, authority, file));
                    }
                }
                if (uris.isEmpty()) {
                    Toast.makeText(context, "没有可分享的文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType(mimeType);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareText + " (" + uris.size() + "个文件)");
            }
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, chooserTitle);
            if (chooser.resolveActivity(context.getPackageManager()) != null) {
                fragment.startActivity(chooser);
            } else {
                Toast.makeText(context, "没有找到可以分享的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(context, "系统分享不支持当前存储路径，请使用二维码互传功能", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
