package com.kooo.evcam.playback;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.ui.UiThemeController;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频回看页：支持按日期与小时分组、.lock 锁定保护、A 版四路同步播放、
 * B 版合成视频 TextureView 象限放大、以及修复后的 A/B 全路径扫码与系统分享。
 */
public class PlaybackFragmentNew extends Fragment implements UiThemeController.ThemeAware {
    private static final String TAG = "PlaybackFragmentNew";
    private static final List<String> COMPOSITE_CYCLE_ORDER = Arrays.asList(
            VideoGroup.POSITION_COMBINED,
            VideoGroup.POSITION_FRONT,
            VideoGroup.POSITION_BACK,
            VideoGroup.POSITION_LEFT,
            VideoGroup.POSITION_RIGHT);

    private View rootView;
    private RecyclerView videoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private View noSelectionHint;
    private Button btnMenu, btnRefresh, btnMultiSelect, btnHome;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect, btnShareSelected;
    private Button btnFilterAll, btnFilterToday, btnFilterLocked;
    private TextView selectedCount;
    private View toolbar, multiSelectToolbar, filterBar, listContainer, controlsLayout, previewContainer;

    private View multiViewLayout, singleViewLayout;
    private VideoView videoFront, videoBack, videoLeft, videoRight, videoSingle;
    private TextureView textureSingle;
    private FrameLayout frameFront, frameBack, frameLeft, frameRight;
    private TextView labelFront, labelBack, labelLeft, labelRight, labelSingle;
    private TextView placeholderFront, placeholderBack, placeholderLeft, placeholderRight;

    private Button btnPlayPause, btnViewMode, btnSpeed, btnLockToggle;
    private SeekBar seekBar;
    private TextView currentTime, totalTime;

    private final List<DateSection<VideoGroup>> dateSections = new ArrayList<>();
    private VideoGroup currentGroup;
    private ExpandableVideoGroupAdapter adapter;
    private MultiVideoPlayerManager playerManager;
    private CompositeQuadrantTextureController compositeController;

    private View listLoadingView;
    private TextView listLoadingText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    @Nullable
    private ExecutorService scanExecutor;

    private int filterMode = PlaybackTimelineBuilder.FILTER_ALL;
    private boolean isMultiSelectMode = false;
    private boolean isSingleMode = false;
    private boolean isCompositeActive = false;
    private String currentSinglePosition = VideoGroup.POSITION_FRONT;
    private String compositeQuadrant = VideoGroup.POSITION_COMBINED;
    private float currentSpeed = 1.0f;
    private boolean isDraggingSeekBar = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_playback_new, container, false);
        initViews(rootView);
        initPlayers();
        setupListeners();
        setupDoubleTapListeners();
        applyFilterChipsTheme(requireContext());
        updateVideoList();
        applyStatusBarInsets(rootView);
        return rootView;
    }

    private void initViews(View view) {
        toolbar = view.findViewById(R.id.toolbar);
        multiSelectToolbar = view.findViewById(R.id.multi_select_toolbar);
        filterBar = view.findViewById(R.id.filter_bar);
        listContainer = view.findViewById(R.id.list_container);
        controlsLayout = view.findViewById(R.id.controls_layout);
        previewContainer = view.findViewById(R.id.preview_container);

        btnMenu = view.findViewById(R.id.btn_menu);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMultiSelect = view.findViewById(R.id.btn_multi_select);
        btnHome = view.findViewById(R.id.btn_home);
        currentDatetime = view.findViewById(R.id.current_datetime);

        btnFilterAll = view.findViewById(R.id.btn_filter_all);
        btnFilterToday = view.findViewById(R.id.btn_filter_today);
        btnFilterLocked = view.findViewById(R.id.btn_filter_locked);

        btnSelectAll = view.findViewById(R.id.btn_select_all);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelect = view.findViewById(R.id.btn_cancel_select);
        btnShareSelected = view.findViewById(R.id.btn_share_selected);
        selectedCount = view.findViewById(R.id.selected_count);

        videoList = view.findViewById(R.id.video_list);
        emptyText = view.findViewById(R.id.empty_text);
        listLoadingView = view.findViewById(R.id.list_loading_view);
        listLoadingText = view.findViewById(R.id.list_loading_text);
        noSelectionHint = view.findViewById(R.id.no_selection_hint);

        multiViewLayout = view.findViewById(R.id.multi_view_layout);
        singleViewLayout = view.findViewById(R.id.single_view_layout);

        videoFront = view.findViewById(R.id.video_front);
        videoBack = view.findViewById(R.id.video_back);
        videoLeft = view.findViewById(R.id.video_left);
        videoRight = view.findViewById(R.id.video_right);
        videoSingle = view.findViewById(R.id.video_single);
        textureSingle = view.findViewById(R.id.texture_single);

        frameFront = view.findViewById(R.id.frame_front);
        frameBack = view.findViewById(R.id.frame_back);
        frameLeft = view.findViewById(R.id.frame_left);
        frameRight = view.findViewById(R.id.frame_right);

        labelFront = view.findViewById(R.id.label_front);
        labelBack = view.findViewById(R.id.label_back);
        labelLeft = view.findViewById(R.id.label_left);
        labelRight = view.findViewById(R.id.label_right);
        labelSingle = view.findViewById(R.id.label_single);

        placeholderFront = view.findViewById(R.id.placeholder_front);
        placeholderBack = view.findViewById(R.id.placeholder_back);
        placeholderLeft = view.findViewById(R.id.placeholder_left);
        placeholderRight = view.findViewById(R.id.placeholder_right);

        btnPlayPause = view.findViewById(R.id.btn_play_pause);
        btnViewMode = view.findViewById(R.id.btn_view_mode);
        btnSpeed = view.findViewById(R.id.btn_speed);
        btnLockToggle = view.findViewById(R.id.btn_lock_toggle);
        seekBar = view.findViewById(R.id.seek_bar);
        currentTime = view.findViewById(R.id.current_time);
        totalTime = view.findViewById(R.id.total_time);

        adapter = new ExpandableVideoGroupAdapter(getContext(), dateSections);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return adapter.isFullSpanPosition(position) ? 2 : 1;
                }
            });
            videoList.setLayoutManager(gridLayoutManager);
        } else {
            videoList.setLayoutManager(new LinearLayoutManager(getContext()));
        }
        videoList.setAdapter(adapter);

        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
    }

    private void initPlayers() {
        playerManager = new MultiVideoPlayerManager(getContext());
        playerManager.setVideoViews(videoFront, videoBack, videoLeft, videoRight, videoSingle);
        playerManager.setPlaybackListener(new MultiVideoPlayerManager.OnPlaybackListener() {
            @Override
            public void onPrepared(int duration) {
                if (isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> applyPreparedDuration(duration));
            }

            @Override
            public void onProgressUpdate(int currentPosition) {
                if (isCompositeActive || getActivity() == null || isDraggingSeekBar) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(currentPosition);
                    currentTime.setText(formatTime(currentPosition));
                });
            }

            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                if (isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> btnPlayPause.setText(isPlaying ? "暂停" : "播放"));
            }

            @Override
            public void onCompletion() {
                if (isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(0);
                    currentTime.setText(formatTime(0));
                });
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Player warning: " + message);
            }

            @Override
            public void onSingleVideoPrepared() {
                if (isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (videoSingle != null) {
                        videoSingle.setVisibility(View.VISIBLE);
                    }
                });
            }
        });

        compositeController = new CompositeQuadrantTextureController(requireContext(), textureSingle);
        compositeController.setCallback(new CompositeQuadrantTextureController.Callback() {
            @Override
            public void onPrepared(int durationMs) {
                if (!isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> applyPreparedDuration(durationMs));
            }

            @Override
            public void onProgressUpdate(int positionMs) {
                if (!isCompositeActive || getActivity() == null || isDraggingSeekBar) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(positionMs);
                    currentTime.setText(formatTime(positionMs));
                });
            }

            @Override
            public void onPlaybackStateChanged(boolean playing) {
                if (!isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> btnPlayPause.setText(playing ? "暂停" : "播放"));
            }

            @Override
            public void onCompletion() {
                if (!isCompositeActive || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(0);
                    currentTime.setText(formatTime(0));
                });
            }

            @Override
            public void onError(String message) {
                if (!isCompositeActive || getContext() == null) return;
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyPreparedDuration(int duration) {
        if (duration > 0) {
            seekBar.setMax(duration);
            totalTime.setText(formatTime(duration));
            currentTime.setText(formatTime(0));
        } else {
            totalTime.setText("--:--");
            currentTime.setText("00:00");
        }
    }

    private void setupListeners() {
        if (btnMenu != null) btnMenu.setVisibility(View.GONE);
        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToRecordingInterface();
                }
            });
        }
        btnRefresh.setOnClickListener(v -> updateVideoList());
        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());
        btnSelectAll.setOnClickListener(v -> selectAll());
        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());
        btnShareSelected.setOnClickListener(v -> shareSelected());

        if (btnFilterAll != null) {
            btnFilterAll.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_ALL));
        }
        if (btnFilterToday != null) {
            btnFilterToday.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_TODAY));
        }
        if (btnFilterLocked != null) {
            btnFilterLocked.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_LOCKED));
        }

        adapter.setOnItemClickListener((group, position) -> loadVideoGroup(group));
        adapter.setOnItemSelectedListener(group -> updateSelectedCount());
        adapter.setOnItemLongClickListener((group, position) -> {
            if (adapter.isMultiSelectMode()) {
                shareSelected();
            } else {
                showSingleGroupActionsDialog(group);
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isCompositeActive) {
                compositeController.togglePlayPause();
            } else {
                playerManager.togglePlayPause();
            }
        });

        btnViewMode.setOnClickListener(v -> cycleViewMode());

        btnSpeed.setOnClickListener(v -> {
            if (isCompositeActive) {
                currentSpeed = nextSpeed(currentSpeed);
                compositeController.setSpeed(currentSpeed);
            } else {
                currentSpeed = playerManager.cycleSpeed();
            }
            btnSpeed.setText(String.format(Locale.getDefault(), "%.1fx", currentSpeed));
        });

        if (btnLockToggle != null) {
            btnLockToggle.setOnClickListener(v -> {
                if (currentGroup != null) {
                    toggleLockForGroup(currentGroup);
                }
            });
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isDraggingSeekBar = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isDraggingSeekBar = false;
                if (isCompositeActive) {
                    compositeController.seekTo(seekBar.getProgress());
                } else {
                    playerManager.seekTo(seekBar.getProgress());
                }
            }
        });
    }

    private void switchFilterMode(int newMode) {
        if (filterMode == newMode) {
            return;
        }
        filterMode = newMode;
        if (getContext() != null) {
            applyFilterChipsTheme(getContext());
        }
        updateVideoList();
    }

    private void setupDoubleTapListeners() {
        setupDoubleTap(frameFront, VideoGroup.POSITION_FRONT, "前");
        setupDoubleTap(frameBack, VideoGroup.POSITION_BACK, "后");
        setupDoubleTap(frameLeft, VideoGroup.POSITION_LEFT, "左");
        setupDoubleTap(frameRight, VideoGroup.POSITION_RIGHT, "右");

        if (singleViewLayout != null) {
            GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (isCompositeActive) {
                        String nextQuad = compositeController.handleDoubleTap(e.getX(), e.getY());
                        if (nextQuad != null) {
                            compositeQuadrant = nextQuad;
                            updateCompositeModeLabels();
                        }
                        return true;
                    }
                    if (isSingleMode) {
                        switchToMultiMode();
                    }
                    return true;
                }
            });
            singleViewLayout.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return true;
            });
        }
    }

    private void setupDoubleTap(View view, String position, String label) {
        if (view == null) return;
        GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isCompositeActive && !isSingleMode && playerManager.hasVideo(position)) {
                    switchToSingleMode(position, label);
                }
                return true;
            }
        });
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    private void switchToSingleMode(String position, String label) {
        isSingleMode = true;
        currentSinglePosition = position;
        labelSingle.setText(label);
        btnViewMode.setText(VideoGroup.POSITION_COMBINED.equals(position) ? label : label + "摄");

        if (videoSingle != null) {
            videoSingle.setVisibility(View.VISIBLE);
        }
        if (textureSingle != null) {
            textureSingle.setVisibility(View.GONE);
        }

        playerManager.setSingleMode(true, position);
        if (multiViewLayout != null) {
            multiViewLayout.postDelayed(() -> {
                if (isSingleMode && !isCompositeActive) {
                    multiViewLayout.setVisibility(View.GONE);
                    singleViewLayout.setVisibility(View.VISIBLE);
                }
            }, 200);
        }
    }

    private void switchToMultiMode() {
        if (isCompositeActive) {
            compositeQuadrant = VideoGroup.POSITION_COMBINED;
            compositeController.setQuadrant(compositeQuadrant);
            updateCompositeModeLabels();
            return;
        }
        isSingleMode = false;
        btnViewMode.setText("多路");
        playerManager.setSingleMode(false, null);
        singleViewLayout.setVisibility(View.GONE);
        multiViewLayout.setVisibility(View.VISIBLE);
    }

    private void cycleViewMode() {
        if (currentGroup == null) return;

        if (isCompositeActive) {
            int idx = COMPOSITE_CYCLE_ORDER.indexOf(compositeQuadrant);
            int nextIdx = (idx < 0) ? 0 : (idx + 1) % COMPOSITE_CYCLE_ORDER.size();
            compositeQuadrant = COMPOSITE_CYCLE_ORDER.get(nextIdx);
            compositeController.setQuadrant(compositeQuadrant);
            updateCompositeModeLabels();
            return;
        }

        List<String> availablePositions = new ArrayList<>();
        availablePositions.add("multi");
        if (currentGroup.hasVideo(VideoGroup.POSITION_FRONT)) availablePositions.add(VideoGroup.POSITION_FRONT);
        if (currentGroup.hasVideo(VideoGroup.POSITION_BACK)) availablePositions.add(VideoGroup.POSITION_BACK);
        if (currentGroup.hasVideo(VideoGroup.POSITION_LEFT)) availablePositions.add(VideoGroup.POSITION_LEFT);
        if (currentGroup.hasVideo(VideoGroup.POSITION_RIGHT)) availablePositions.add(VideoGroup.POSITION_RIGHT);

        String currentPos = isSingleMode ? currentSinglePosition : "multi";
        int currentIndex = availablePositions.indexOf(currentPos);
        if (currentIndex < 0) currentIndex = 0;

        String nextPos = availablePositions.get((currentIndex + 1) % availablePositions.size());
        if ("multi".equals(nextPos)) {
            switchToMultiMode();
        } else {
            switchToSingleMode(nextPos, getPositionLabel(nextPos));
        }
    }

    private void updateCompositeModeLabels() {
        if (VideoGroup.POSITION_COMBINED.equals(compositeQuadrant)) {
            btnViewMode.setText("合成");
            labelSingle.setText("合成全览（双击象限可放大单视角）");
        } else {
            String label = getPositionLabel(compositeQuadrant);
            btnViewMode.setText(label + "摄");
            labelSingle.setText(label + " · 双击画面还原全览");
        }
    }

    private String getPositionLabel(String position) {
        switch (position) {
            case VideoGroup.POSITION_FRONT: return "前";
            case VideoGroup.POSITION_BACK: return "后";
            case VideoGroup.POSITION_LEFT: return "左";
            case VideoGroup.POSITION_RIGHT: return "右";
            case VideoGroup.POSITION_COMBINED: return "合成";
            default: return "";
        }
    }

    private void loadVideoGroup(VideoGroup group) {
        this.currentGroup = group;
        noSelectionHint.setVisibility(View.GONE);
        currentDatetime.setText(group.getFormattedDateTime() + (group.isLocked() ? " · 已锁定" : ""));
        updateLockButtonState();

        File combinedFile = group.getVideoFile(VideoGroup.POSITION_COMBINED);
        boolean combined = combinedFile != null && combinedFile.isFile() && combinedFile.length() > 0L;

        if (combined) {
            isCompositeActive = true;
            isSingleMode = true;
            compositeQuadrant = VideoGroup.POSITION_COMBINED;
            playerManager.stopAll();

            multiViewLayout.setVisibility(View.GONE);
            singleViewLayout.setVisibility(View.VISIBLE);
            if (videoSingle != null) videoSingle.setVisibility(View.GONE);
            if (textureSingle != null) textureSingle.setVisibility(View.VISIBLE);
            btnViewMode.setEnabled(true);
            updateCompositeModeLabels();

            compositeController.loadFile(combinedFile, compositeQuadrant, 0, true, currentSpeed);
            return;
        }

        isCompositeActive = false;
        compositeController.stop();
        if (textureSingle != null) textureSingle.setVisibility(View.GONE);
        if (videoSingle != null) videoSingle.setVisibility(View.VISIBLE);
        btnViewMode.setEnabled(true);

        if (isSingleMode && !group.hasVideo(currentSinglePosition)) {
            isSingleMode = false;
        }
        if (isSingleMode) {
            multiViewLayout.setVisibility(View.GONE);
            singleViewLayout.setVisibility(View.VISIBLE);
            btnViewMode.setText(getPositionLabel(currentSinglePosition) + "摄");
            labelSingle.setText(getPositionLabel(currentSinglePosition));
        } else {
            multiViewLayout.setVisibility(View.VISIBLE);
            singleViewLayout.setVisibility(View.GONE);
            btnViewMode.setText("多路");
        }

        updatePlaceholders(group);
        playerManager.setSpeed(currentSpeed);
        playerManager.updateSingleModePosition(isSingleMode, currentSinglePosition);
        playerManager.loadVideoGroup(group);
    }

    private void updatePlaceholders(VideoGroup group) {
        boolean hasFront = group.hasVideo(VideoGroup.POSITION_FRONT);
        boolean hasBack = group.hasVideo(VideoGroup.POSITION_BACK);
        boolean hasLeft = group.hasVideo(VideoGroup.POSITION_LEFT);
        boolean hasRight = group.hasVideo(VideoGroup.POSITION_RIGHT);

        videoFront.setVisibility(hasFront ? View.VISIBLE : View.GONE);
        placeholderFront.setVisibility(hasFront ? View.GONE : View.VISIBLE);
        videoBack.setVisibility(hasBack ? View.VISIBLE : View.GONE);
        placeholderBack.setVisibility(hasBack ? View.GONE : View.VISIBLE);
        videoLeft.setVisibility(hasLeft ? View.VISIBLE : View.GONE);
        placeholderLeft.setVisibility(hasLeft ? View.GONE : View.VISIBLE);
        videoRight.setVisibility(hasRight ? View.VISIBLE : View.GONE);
        placeholderRight.setVisibility(hasRight ? View.GONE : View.VISIBLE);
    }

    public void refreshVideoList() {
        updateVideoList();
    }

    private void updateVideoList() {
        Context context = getContext();
        if (context == null || adapter == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final int generation = scanGeneration.incrementAndGet();
        final int requestedFilter = filterMode;

        showLoadingState();
        ensureScanExecutor().execute(() -> {
            if (generation != scanGeneration.get()) {
                return;
            }
            try {
                File saveDir = StorageHelper.getVideoDir(appCtx);
                PlaybackTimelineBuilder.VideoScanResult scan = PlaybackTimelineBuilder.scanVideoDirectory(
                        saveDir, requestedFilter, System.currentTimeMillis(),
                        ActiveRecordingRegistry.snapshot());
                if (generation != scanGeneration.get()) {
                    return;
                }
                mainHandler.post(() -> {
                    if (generation != scanGeneration.get()
                            || !isAdded()
                            || rootView == null
                            || adapter == null
                            || getContext() == null) {
                        return;
                    }
                    hideLoadingState();
                    dateSections.clear();
                    dateSections.addAll(scan.dateSections);
                    adapter.setActiveRecordingBanner(scan.activeRecording);
                    adapter.buildFlattenedList();
                    adapter.syncSelectionByGroup(currentGroup);
                    adapter.notifyDataSetChanged();

                    if (dateSections.isEmpty() && scan.activeRecording == null) {
                        showEmptyState();
                    } else {
                        videoList.setVisibility(View.VISIBLE);
                        emptyText.setVisibility(View.GONE);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to scan video directory", t);
                mainHandler.post(() -> {
                    if (generation != scanGeneration.get()
                            || !isAdded()
                            || rootView == null
                            || getContext() == null) {
                        return;
                    }
                    hideLoadingState();
                    if (dateSections.isEmpty()) {
                        showEmptyState();
                    } else {
                        videoList.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private ExecutorService ensureScanExecutor() {
        if (scanExecutor == null || scanExecutor.isShutdown()) {
            scanExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "PlaybackVideoScan");
                t.setDaemon(true);
                return t;
            });
        }
        return scanExecutor;
    }

    private void showLoadingState() {
        if (listLoadingView != null) {
            listLoadingView.setVisibility(View.VISIBLE);
        }
        if (videoList != null) {
            videoList.setVisibility(View.GONE);
        }
        if (emptyText != null) {
            emptyText.setVisibility(View.GONE);
        }
        if (btnRefresh != null) {
            btnRefresh.setEnabled(false);
            btnRefresh.setText("加载中");
        }
    }

    private void hideLoadingState() {
        if (listLoadingView != null) {
            listLoadingView.setVisibility(View.GONE);
        }
        if (btnRefresh != null) {
            btnRefresh.setEnabled(true);
            btnRefresh.setText("刷新");
        }
    }

    private void showEmptyState() {
        if (listLoadingView != null) {
            listLoadingView.setVisibility(View.GONE);
        }
        videoList.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        if (filterMode == PlaybackTimelineBuilder.FILTER_LOCKED) {
            emptyText.setText("暂无已锁定录像");
        } else if (filterMode == PlaybackTimelineBuilder.FILTER_TODAY) {
            emptyText.setText("今日暂无录像文件");
        } else {
            emptyText.setText("暂无录制文件");
        }
    }

    private void toggleLockForGroup(@NonNull VideoGroup group) {
        boolean targetLock = !group.isLocked();
        boolean ok = VideoLockStore.setGroupLocked(group, targetLock);
        if (!ok) {
            Toast.makeText(getContext(), "锁定状态更新失败，请检查存储权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getContext(),
                targetLock ? "已锁定该组录像（循环清理时将保留）" : "已解除锁定",
                Toast.LENGTH_SHORT).show();
        if (group == currentGroup) {
            currentDatetime.setText(group.getFormattedDateTime() + (group.isLocked() ? " · 已锁定" : ""));
            updateLockButtonState();
        }
        if (filterMode == PlaybackTimelineBuilder.FILTER_LOCKED && !targetLock) {
            updateVideoList();
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateLockButtonState() {
        if (btnLockToggle == null) return;
        if (currentGroup == null) {
            btnLockToggle.setVisibility(View.GONE);
            return;
        }
        btnLockToggle.setVisibility(View.VISIBLE);
        boolean locked = currentGroup.isLocked();
        btnLockToggle.setText(locked ? "已锁定" : "锁定");
        if (getContext() != null) {
            PlaybackThemeAndShareHelper.styleToolbarButton(getContext(), btnLockToggle, locked);
        }
    }

    private void showSingleGroupActionsDialog(@NonNull VideoGroup group) {
        if (getContext() == null) return;
        String lockAction = group.isLocked() ? "解除锁定（允许循环清理）" : "锁定录像（防循环覆盖与误删）";
        String[] items = {lockAction, "分享此组录像（扫码 / 系统）", "删除此组录像"};
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(group.getFormattedDateTime())
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        toggleLockForGroup(group);
                    } else if (which == 1) {
                        showShareDialog(group);
                    } else if (which == 2) {
                        confirmDeleteSingleGroup(group);
                    }
                })
                .show();
    }

    private void confirmDeleteSingleGroup(@NonNull VideoGroup group) {
        if (getContext() == null) return;
        if (group.isLocked()) {
            new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("录像已锁定保护")
                    .setMessage("该组录像已锁定（防循环覆盖与误删）。请先解除锁定后再删除。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage("确定要删除 " + group.getFormattedDateTime() + " 的全部视角录像吗？")
                .setPositiveButton("删除", (d, w) -> {
                    if (group == currentGroup) {
                        stopAllPlaybackAndResetPreview();
                    }
                    int deleted = group.deleteAll();
                    Toast.makeText(getContext(), "已删除 " + deleted + " 个视频文件", Toast.LENGTH_SHORT).show();
                    updateVideoList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleMultiSelectMode() {
        isMultiSelectMode = !isMultiSelectMode;
        adapter.clearSelection();
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.notifyDataSetChanged();

        if (isMultiSelectMode) {
            toolbar.setVisibility(View.GONE);
            multiSelectToolbar.setVisibility(View.VISIBLE);
            updateSelectedCount();
        } else {
            toolbar.setVisibility(View.VISIBLE);
            multiSelectToolbar.setVisibility(View.GONE);
        }
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        adapter.clearSelection();
        adapter.setMultiSelectMode(false);
        adapter.notifyDataSetChanged();
        toolbar.setVisibility(View.VISIBLE);
        multiSelectToolbar.setVisibility(View.GONE);
    }

    private void selectAll() {
        adapter.selectAll();
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        selectedCount.setText("已选择 " + adapter.getSelectedCount() + " 项");
    }

    private void deleteSelected() {
        Set<VideoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty() || getContext() == null) {
            return;
        }
        List<VideoGroup> unlockedTargets = new ArrayList<>();
        int lockedCount = 0;
        for (VideoGroup group : selectedGroups) {
            if (group.isLocked()) {
                lockedCount++;
            } else {
                unlockedTargets.add(group);
            }
        }

        if (unlockedTargets.isEmpty()) {
            new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("无法直接删除")
                    .setMessage("所选 " + lockedCount + " 组录像均已锁定保护。请先解除锁定后再删除。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        String msg = lockedCount > 0
                ? "已选中 " + selectedGroups.size() + " 组，其中 " + lockedCount
                + " 组已锁定将被跳过，仅删除其余 " + unlockedTargets.size() + " 组未锁定录像。"
                : "确定要删除选中的 " + unlockedTargets.size() + " 组视频吗？（包含该组全部摄像头录像）";
        String positiveLabel = lockedCount > 0 ? "仅删未锁定项" : "删除";

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage(msg)
                .setPositiveButton(positiveLabel, (dialog, which) -> {
                    int deletedCount = 0;
                    for (VideoGroup group : unlockedTargets) {
                        if (group == currentGroup) {
                            stopAllPlaybackAndResetPreview();
                        }
                        deletedCount += group.deleteAll();
                    }
                    adapter.clearSelection();
                    exitMultiSelectMode();
                    updateVideoList();
                    if (getContext() != null) {
                        Toast.makeText(getContext(), "已删除 " + deletedCount + " 个视频文件", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void stopAllPlaybackAndResetPreview() {
        isCompositeActive = false;
        currentGroup = null;
        if (playerManager != null) playerManager.stopAll();
        if (compositeController != null) compositeController.stop();
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
        currentDatetime.setText("");
        updateLockButtonState();
    }

    private void shareSelected() {
        Set<VideoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            Toast.makeText(getContext(), "请先选择要分享的视频", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> allVideoFiles = new ArrayList<>();
        for (VideoGroup group : selectedGroups) {
            allVideoFiles.addAll(group.getShareableFiles());
        }
        PlaybackThemeAndShareHelper.showShareOptionsDialog(
                this,
                "分享选中的视频",
                "已选择 " + selectedGroups.size() + " 组视频，共 " + allVideoFiles.size() + " 个文件",
                allVideoFiles,
                "video/mp4",
                "分享视频",
                "来自 EVCam 的视频分享",
                "分享视频到");
    }

    private void showShareDialog(VideoGroup group) {
        List<File> videoFiles = group.getShareableFiles();
        PlaybackThemeAndShareHelper.showShareOptionsDialog(
                this,
                "分享视频",
                "共 " + videoFiles.size() + " 个视频文件",
                videoFiles,
                "video/mp4",
                "分享视频",
                "来自 EVCam 的视频分享",
                "分享视频到");
    }

    private static float nextSpeed(float current) {
        float[] opts = MultiVideoPlayerManager.SPEED_OPTIONS;
        for (int i = 0; i < opts.length; i++) {
            if (Math.abs(opts[i] - current) < 0.05f) {
                return opts[(i + 1) % opts.length];
            }
        }
        return 1.0f;
    }

    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void applyStatusBarInsets(View view) {
        View toolbarView = view.findViewById(R.id.toolbar);
        if (toolbarView != null) {
            final int originalPaddingTop = toolbarView.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbarView);
        }
    }

    private void applyFilterChipsTheme(@NonNull Context context) {
        PlaybackThemeAndShareHelper.styleFilterButton(context, btnFilterAll, filterMode == PlaybackTimelineBuilder.FILTER_ALL);
        PlaybackThemeAndShareHelper.styleFilterButton(context, btnFilterToday, filterMode == PlaybackTimelineBuilder.FILTER_TODAY);
        PlaybackThemeAndShareHelper.styleFilterButton(context, btnFilterLocked, filterMode == PlaybackTimelineBuilder.FILTER_LOCKED);
        updateLockButtonState();
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (rootView == null || themedContext == null) return;
        rootView.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        if (toolbar != null) toolbar.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.menu_background));
        if (multiSelectToolbar != null) multiSelectToolbar.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.menu_background));
        if (listContainer != null) listContainer.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        if (filterBar != null) filterBar.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        if (previewContainer != null) previewContainer.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.preview_background));
        if (controlsLayout != null) controlsLayout.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.preview_controls_background));
        if (currentDatetime != null) currentDatetime.setTextColor(ContextCompat.getColor(themedContext, R.color.text_secondary));
        if (emptyText != null) emptyText.setTextColor(ContextCompat.getColor(themedContext, R.color.text_secondary));
        if (listLoadingText != null) listLoadingText.setTextColor(ContextCompat.getColor(themedContext, R.color.text_secondary));
        if (currentTime != null) currentTime.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        if (totalTime != null) totalTime.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnRefresh, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnMultiSelect, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnPlayPause, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnViewMode, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnSpeed, false);
        applyFilterChipsTheme(themedContext);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (compositeController != null && compositeController.isPlaying()) {
            compositeController.pause();
        }
        if (playerManager != null && playerManager.isPlaying()) {
            playerManager.pause();
        }
    }

    @Override
    public void onDestroyView() {
        scanGeneration.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        if (getView() != null) PlaybackImageMemory.clearViews(getView());
        if (videoList != null) {
            videoList.setAdapter(null);
            videoList.getRecycledViewPool().clear();
        }
        if (adapter != null) {
            adapter.release();
            adapter = null;
        }
        if (compositeController != null) {
            compositeController.release();
            compositeController = null;
        }
        if (playerManager != null) {
            playerManager.release();
            playerManager = null;
        }
        PlaybackImageMemory.clear(getContext());
        listLoadingView = null;
        listLoadingText = null;
        rootView = null;
        super.onDestroyView();
    }
}
