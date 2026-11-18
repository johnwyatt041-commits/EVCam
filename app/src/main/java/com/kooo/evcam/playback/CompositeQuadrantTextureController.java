package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;

import java.io.File;

/**
 * Plays B-Composite {@code _combined.mp4} files on a {@link TextureView} and applies aspect-preserving
 * affine transforms via {@link CompositeQuadrantMath} so users can zoom into any camera quadrant
 * ({@code front}/{@code back}/{@code left}/{@code right}) or view the full 2x2 mosaic with a single decoder.
 */
public final class CompositeQuadrantTextureController implements TextureView.SurfaceTextureListener {

    private static final String TAG = "CompositeTextureCtrl";

    public interface Callback {
        void onPrepared(int durationMs);

        void onProgressUpdate(int positionMs);

        void onPlaybackStateChanged(boolean playing);

        void onCompletion();

        void onError(String message);
    }

    private final Context context;
    private final TextureView textureView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    private Callback callback;
    @Nullable
    private MediaPlayer mediaPlayer;
    @Nullable
    private Surface surface;
    @Nullable
    private File currentFile;
    @Nullable
    private String currentQuadrant = VideoGroup.POSITION_COMBINED;

    private int generation = 0;
    private boolean isPrepared = false;
    private boolean isPlaying = false;
    private boolean playWhenPrepared = true;
    private int pendingSeekMs = 0;
    private int durationMs = 0;
    private float currentSpeed = 1.0f;
    private int videoWidth = 0;
    private int videoHeight = 0;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && isPrepared && mediaPlayer != null) {
                try {
                    if (callback != null) {
                        callback.onProgressUpdate(mediaPlayer.getCurrentPosition());
                    }
                } catch (IllegalStateException ignored) {
                }
                handler.postDelayed(this, 200L);
            }
        }
    };

    public CompositeQuadrantTextureController(@NonNull Context context, @Nullable TextureView textureView) {
        this.context = context.getApplicationContext();
        this.textureView = textureView;
        if (this.textureView != null) {
            this.textureView.setSurfaceTextureListener(this);
            this.textureView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyTransform());
        }
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    public void loadFile(@NonNull File combinedFile,
                         @Nullable String initialQuadrant,
                         int seekMs,
                         boolean autoPlay,
                         float speed) {
        this.currentQuadrant = normalizeQuadrant(initialQuadrant);
        this.currentSpeed = speed;
        this.pendingSeekMs = Math.max(0, seekMs);
        this.playWhenPrepared = autoPlay;

        if (combinedFile.equals(currentFile) && mediaPlayer != null && isPrepared) {
            applyTransform();
            setSpeed(speed);
            if (seekMs > 0) {
                seekTo(seekMs);
            }
            if (autoPlay && !isPlaying) {
                play();
            } else if (!autoPlay && isPlaying) {
                pause();
            }
            return;
        }

        stop();
        this.currentFile = combinedFile;
        prepareInternal();
    }

    public void setQuadrant(@Nullable String quadrant) {
        this.currentQuadrant = normalizeQuadrant(quadrant);
        applyTransform();
    }

    @NonNull
    public String getCurrentQuadrant() {
        return currentQuadrant != null ? currentQuadrant : VideoGroup.POSITION_COMBINED;
    }

    @Nullable
    public String handleDoubleTap(float x, float y) {
        if (textureView == null) {
            return null;
        }
        if (!VideoGroup.POSITION_COMBINED.equals(getCurrentQuadrant())) {
            setQuadrant(VideoGroup.POSITION_COMBINED);
            return VideoGroup.POSITION_COMBINED;
        }
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw <= 0 || vh <= 0) {
            return null;
        }
        String target = CompositeQuadrantMath.quadrantAtNormalized(x / (float) vw, y / (float) vh);
        if (target != null) {
            setQuadrant(target);
        }
        return target;
    }

    private void prepareInternal() {
        if (textureView == null || currentFile == null || !currentFile.exists()) {
            return;
        }
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) {
            return;
        }
        releaseMediaPlayerOnly();
        final int loadGen = ++generation;
        final MediaPlayer mp = new MediaPlayer();
        this.mediaPlayer = mp;
        this.surface = new Surface(st);

        try {
            mp.setSurface(surface);
            mp.setDataSource(currentFile.getAbsolutePath());
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                if (loadGen != generation) return;
                videoWidth = width;
                videoHeight = height;
                applyTransform();
            });
            mp.setOnPreparedListener(player -> {
                if (loadGen != generation) return;
                isPrepared = true;
                videoWidth = player.getVideoWidth();
                videoHeight = player.getVideoHeight();
                durationMs = Math.max(0, player.getDuration());
                player.setVolume(0f, 0f);
                applySpeed(player, currentSpeed);
                player.pause();
                abandonAudioFocus();
                applyTransform();

                if (callback != null) {
                    callback.onPrepared(durationMs);
                }
                if (pendingSeekMs > 0) {
                    try {
                        player.seekTo(pendingSeekMs);
                    } catch (IllegalStateException ignored) {
                    }
                }
                if (playWhenPrepared) {
                    play();
                } else {
                    isPlaying = false;
                    if (callback != null) {
                        callback.onPlaybackStateChanged(false);
                    }
                }
                if (callback != null) {
                    callback.onProgressUpdate(pendingSeekMs);
                }
            });
            mp.setOnCompletionListener(player -> {
                if (loadGen != generation) return;
                isPlaying = false;
                handler.removeCallbacks(progressRunnable);
                if (callback != null) {
                    callback.onPlaybackStateChanged(false);
                    callback.onCompletion();
                }
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (loadGen != generation) return true;
                isPlaying = false;
                handler.removeCallbacks(progressRunnable);
                if (callback != null) {
                    callback.onError("Composite video error: what=" + what + ", extra=" + extra);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.e(TAG, "Failed preparing composite player", e);
            releaseMediaPlayerOnly();
            if (callback != null) {
                callback.onError("无法加载合成视频: " + e.getMessage());
            }
        }
    }

    public void play() {
        playWhenPrepared = true;
        if (!isPrepared || mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.start();
            isPlaying = true;
            if (callback != null) {
                callback.onPlaybackStateChanged(true);
            }
            handler.removeCallbacks(progressRunnable);
            handler.postDelayed(progressRunnable, 200L);
        } catch (IllegalStateException e) {
            AppLog.w(TAG, "Play ignored in state: " + e.getMessage());
        }
    }

    public void pause() {
        playWhenPrepared = false;
        isPlaying = false;
        handler.removeCallbacks(progressRunnable);
        if (mediaPlayer != null && isPrepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
            }
        }
        if (callback != null) {
            callback.onPlaybackStateChanged(false);
        }
    }

    public void togglePlayPause() {
        if (isPlaying) {
            pause();
        } else {
            play();
        }
    }

    public void seekTo(int positionMs) {
        pendingSeekMs = Math.max(0, positionMs);
        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.seekTo(pendingSeekMs);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
        }
        return pendingSeekMs;
    }

    public int getDuration() {
        return durationMs;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (mediaPlayer != null && isPrepared) {
            applySpeed(mediaPlayer, speed);
        }
    }

    public void stop() {
        generation++;
        playWhenPrepared = false;
        isPrepared = false;
        isPlaying = false;
        durationMs = 0;
        pendingSeekMs = 0;
        currentFile = null;
        handler.removeCallbacks(progressRunnable);
        releaseMediaPlayerOnly();
    }

    public void release() {
        stop();
        callback = null;
    }

    private void releaseMediaPlayerOnly() {
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try {
                mp.setSurface(null);
            } catch (Exception ignored) {
            }
            try {
                mp.reset();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception ignored) {
            }
            surface = null;
        }
    }

    private void applyTransform() {
        if (textureView == null) {
            return;
        }
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        CompositeQuadrantMath.TransformSpec spec = CompositeQuadrantMath.computeTransform(
                vw, vh, videoWidth, videoHeight, currentQuadrant);
        Matrix matrix = new Matrix();
        matrix.setScale(spec.scaleX, spec.scaleY);
        matrix.postTranslate(spec.translateX, spec.translateY);
        textureView.setTransform(matrix);
    }

    private static String normalizeQuadrant(@Nullable String pos) {
        if (pos == null) {
            return VideoGroup.POSITION_COMBINED;
        }
        return pos;
    }

    private static void applySpeed(MediaPlayer mp, float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                boolean wasPlaying = mp.isPlaying();
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
                if (!wasPlaying && mp.isPlaying()) {
                    mp.pause();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .build();
            am.abandonAudioFocusRequest(req);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        if (currentFile != null && mediaPlayer == null) {
            prepareInternal();
        } else {
            applyTransform();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        applyTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        generation++;
        isPrepared = false;
        isPlaying = false;
        handler.removeCallbacks(progressRunnable);
        releaseMediaPlayerOnly();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }
}
