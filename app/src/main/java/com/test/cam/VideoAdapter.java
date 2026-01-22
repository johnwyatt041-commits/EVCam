package com.test.cam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 视频列表适配器
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {
    private final Context context;
    private final List<File> videoFiles;
    private OnVideoDeleteListener deleteListener;

    public interface OnVideoDeleteListener {
        void onVideoDeleted();
    }

    public VideoAdapter(Context context, List<File> videoFiles) {
        this.context = context;
        this.videoFiles = videoFiles;
    }

    public void setOnVideoDeleteListener(OnVideoDeleteListener listener) {
        this.deleteListener = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        File videoFile = videoFiles.get(position);

        // 设置文件名
        holder.videoName.setText(videoFile.getName());

        // 设置文件大小
        long sizeInBytes = videoFile.length();
        String sizeStr;
        if (sizeInBytes < 1024) {
            sizeStr = sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            sizeStr = String.format(Locale.getDefault(), "%.2f KB", sizeInBytes / 1024.0);
        } else if (sizeInBytes < 1024 * 1024 * 1024) {
            sizeStr = String.format(Locale.getDefault(), "%.2f MB", sizeInBytes / (1024.0 * 1024.0));
        } else {
            sizeStr = String.format(Locale.getDefault(), "%.2f GB", sizeInBytes / (1024.0 * 1024.0 * 1024.0));
        }
        holder.videoSize.setText(sizeStr);

        // 设置修改日期
        long lastModified = videoFile.lastModified();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(lastModified));
        holder.videoDate.setText(dateStr);

        // 播放按钮
        holder.btnPlay.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    videoFile
            );
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            try {
                context.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(context, "无法播放视频: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 删除按钮
        holder.btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要删除 " + videoFile.getName() + " 吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        if (videoFile.delete()) {
                            videoFiles.remove(position);
                            notifyItemRemoved(position);
                            notifyItemRangeChanged(position, videoFiles.size());
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();

                            if (deleteListener != null) {
                                deleteListener.onVideoDeleted();
                            }
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        TextView videoName;
        TextView videoSize;
        TextView videoDate;
        Button btnPlay;
        Button btnDelete;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoName = itemView.findViewById(R.id.video_name);
            videoSize = itemView.findViewById(R.id.video_size);
            videoDate = itemView.findViewById(R.id.video_date);
            btnPlay = itemView.findViewById(R.id.btn_play);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
