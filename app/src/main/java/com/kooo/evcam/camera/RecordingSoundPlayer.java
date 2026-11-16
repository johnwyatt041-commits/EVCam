package com.kooo.evcam.camera;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 3.4 开始/结束录制提示音播放器与自定义音效管理（支持系统提示音、Download <=20MB 音频导入、不播放）。
 * 异步播放并在完成后自动释放，不抢占车机媒体音频焦点、不阻塞录制线程。
 */
public final class RecordingSoundPlayer {

    private static final String TAG = "RecordingSoundPlayer";

    public static final String MODE_NONE = "NONE";
    public static final String MODE_SYSTEM = "SYSTEM";
    public static final String MODE_CUSTOM = "CUSTOM";

    public static final long MAX_CUSTOM_SOUND_BYTES = 20L * 1024L * 1024L;
    private static final String SOUNDS_DIR_NAME = "sounds";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private RecordingSoundPlayer() {
    }

    public static boolean isValidCustomSoundSize(long lengthBytes) {
        return lengthBytes > 0 && lengthBytes <= MAX_CUSTOM_SOUND_BYTES;
    }

    public static boolean isSupportedAudioFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || lower.endsWith(".m4a")
                || lower.endsWith(".aac")
                || lower.endsWith(".flac");
    }

    /** 扫描系统 Download 目录下符合大小（<=20MB）与格式的音频文件。 */
    public static List<File> listDownloadAudioFiles() {
        List<File> result = new ArrayList<>();
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.exists() || !downloadDir.isDirectory()) {
            return result;
        }
        File[] files = downloadDir.listFiles();
        if (files == null) {
            return result;
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (file != null && file.isFile()
                    && isSupportedAudioFileName(file.getName())
                    && isValidCustomSoundSize(file.length())) {
                result.add(file);
            }
        }
        return result;
    }

    /** 复制 Download 中的音频文件到应用私有目录 files/sounds/，校验 <=20MB。 */
    public static File importCustomSoundFile(Context context, File sourceFile, boolean isStartSound) throws IOException {
        if (context == null || sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            throw new IOException("Source sound file not found");
        }
        if (!isSupportedAudioFileName(sourceFile.getName())) {
            throw new IOException("Unsupported audio format");
        }
        if (!isValidCustomSoundSize(sourceFile.length())) {
            throw new IOException("Audio file exceeds 20MB limit");
        }
        File destFile = buildDestSoundFile(context, sourceFile.getName(), isStartSound);
        try (InputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(destFile, false)) {
            copyLimited(in, out, MAX_CUSTOM_SOUND_BYTES);
        }
        return destFile;
    }

    /** 从系统文件选择器 Uri 复制音频文件到应用私有目录，校验 <=20MB。 */
    public static File importCustomSoundUri(Context context, Uri uri, boolean isStartSound) throws IOException {
        if (context == null || uri == null) {
            throw new IOException("Invalid sound uri");
        }
        String displayName = resolveDisplayName(context, uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = isStartSound ? "start_sound.mp3" : "stop_sound.mp3";
        }
        if (!isSupportedAudioFileName(displayName)) {
            throw new IOException("Unsupported audio format: " + displayName);
        }
        long declaredSize = resolveUriSize(context, uri);
        if (declaredSize > MAX_CUSTOM_SOUND_BYTES) {
            throw new IOException("Audio file exceeds 20MB limit");
        }
        File destFile = buildDestSoundFile(context, displayName, isStartSound);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open audio uri");
            }
            try (FileOutputStream out = new FileOutputStream(destFile, false)) {
                long copied = copyLimited(in, out, MAX_CUSTOM_SOUND_BYTES);
                if (copied <= 0) {
                    if (destFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        destFile.delete();
                    }
                    throw new IOException("Empty audio file");
                }
            }
        } catch (IOException e) {
            if (destFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destFile.delete();
            }
            throw e;
        }
        return destFile;
    }

    private static File buildDestSoundFile(Context context, String rawName, boolean isStartSound) throws IOException {
        File soundsDir = new File(context.getFilesDir(), SOUNDS_DIR_NAME);
        if (!soundsDir.exists() && !soundsDir.mkdirs()) {
            throw new IOException("Unable to create sounds directory");
        }
        String sanitized = rawName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String prefix = isStartSound ? "start_" : "stop_";
        return new File(soundsDir, prefix + sanitized);
    }

    private static long copyLimited(InputStream in, FileOutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Audio file exceeds 20MB limit");
            }
            out.write(buffer, 0, read);
        }
        out.flush();
        return total;
    }

    private static String resolveDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private static long resolveUriSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    public static void playStartSound(Context context, AppConfig appConfig) {
        if (context == null || appConfig == null) return;
        playSound(context.getApplicationContext(),
                appConfig.getRecordStartSoundMode(),
                appConfig.getRecordStartSoundPath(),
                true);
    }

    public static void playStopSound(Context context, AppConfig appConfig) {
        if (context == null || appConfig == null) return;
        playSound(context.getApplicationContext(),
                appConfig.getRecordStopSoundMode(),
                appConfig.getRecordStopSoundPath(),
                false);
    }

    public static void playSound(Context context, String mode, String customPath, boolean isStartSound) {
        if (context == null || MODE_NONE.equals(mode) || mode == null) {
            return;
        }
        if (MODE_CUSTOM.equals(mode)) {
            if (customPath != null && !customPath.isEmpty()) {
                File soundFile = new File(customPath);
                if (soundFile.exists() && soundFile.isFile() && isValidCustomSoundSize(soundFile.length())) {
                    playCustomFileAsync(soundFile, isStartSound);
                    return;
                }
            }
            AppLog.w(TAG, "Custom sound missing or invalid, fallback to system tone: " + customPath);
        }
        playSystemTone(isStartSound);
    }

    private static void playSystemTone(boolean isStartSound) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            int tone = isStartSound ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_PROMPT;
            tg.startTone(tone, 180);
            MAIN_HANDLER.postDelayed(() -> {
                try {
                    tg.release();
                } catch (Exception ignored) {
                }
            }, 260);
        } catch (Exception e) {
            AppLog.w(TAG, "System tone play failed: " + e.getMessage());
        }
    }

    private static void playCustomFileAsync(File soundFile, boolean isStartSound) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(soundFile.getAbsolutePath());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> safeRelease(mp));
            player.setOnErrorListener((mp, what, extra) -> {
                AppLog.w(TAG, "Custom sound playback error (" + what + "/" + extra + "), fallback to tone");
                safeRelease(mp);
                playSystemTone(isStartSound);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to play custom sound: " + e.getMessage());
            playSystemTone(isStartSound);
        }
    }

    private static void safeRelease(MediaPlayer mp) {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
    }
}
