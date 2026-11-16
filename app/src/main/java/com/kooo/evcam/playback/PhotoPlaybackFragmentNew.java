package com.kooo.evcam.playback;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.ui.UiThemeController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 图片回看页：按日期/小时分组、全部/今日/已锁定筛选、.lock 防误删保护、主题联动与统一分享入口。
 */
public class PhotoPlaybackFragmentNew extends Fragment implements UiThemeController.ThemeAware {
    private static final String TAG = "PhotoPlaybackFragmentNew";

    private View rootView;
    private RecyclerView photoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private View noSelectionHint;
    private Button btnMenu, btnRefresh, btnMultiSelect, btnHome;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect, btnShareSelected;
    private Button btnFilterAll, btnFilterToday, btnFilterLocked;
    private TextView selectedCount;
    private View toolbar, multiSelectToolbar, filterBar, listContainer, controlsLayout, previewContainer;

    private View multiViewLayout, singleViewLayout;
    private ImageView imageFront, imageBack, imageLeft, imageRight, imageSingle;
    private FrameLayout frameFront, frameBack, frameLeft, frameRight;
    private TextView labelFront, labelBack, labelLeft, labelRight, labelSingle;
    private TextView placeholderFront, placeholderBack, placeholderLeft, placeholderRight;
    private Button btnViewMode, btnLockToggle;

    private final List<DateSection<PhotoGroup>> dateSections = new ArrayList<>();
    private ExpandablePhotoGroupAdapter adapter;
    private PhotoGroup currentGroup;

    private int filterMode = PlaybackTimelineBuilder.FILTER_ALL;
    private boolean isMultiSelectMode = false;
    private boolean isSingleMode = false;
    private String currentSinglePosition = PhotoGroup.POSITION_FRONT;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_photo_playback_new, container, false);
        initViews(rootView);
        setupListeners();
        setupDoubleTapListeners();
        applyFilterChipsTheme(requireContext());
        updatePhotoList();
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

        photoList = view.findViewById(R.id.photo_list);
        emptyText = view.findViewById(R.id.empty_text);
        noSelectionHint = view.findViewById(R.id.no_selection_hint);

        multiViewLayout = view.findViewById(R.id.multi_view_layout);
        singleViewLayout = view.findViewById(R.id.single_view_layout);

        imageFront = view.findViewById(R.id.image_front);
        imageBack = view.findViewById(R.id.image_back);
        imageLeft = view.findViewById(R.id.image_left);
        imageRight = view.findViewById(R.id.image_right);
        imageSingle = view.findViewById(R.id.image_single);

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

        btnViewMode = view.findViewById(R.id.btn_view_mode);
        btnLockToggle = view.findViewById(R.id.btn_lock_toggle);

        adapter = new ExpandablePhotoGroupAdapter(getContext(), dateSections);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return adapter.isFullSpanPosition(position) ? 2 : 1;
                }
            });
            photoList.setLayoutManager(gridLayoutManager);
        } else {
            photoList.setLayoutManager(new LinearLayoutManager(getContext()));
        }
        photoList.setAdapter(adapter);

        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
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
        btnRefresh.setOnClickListener(v -> updatePhotoList());
        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());
        btnSelectAll.setOnClickListener(v -> selectAll());
        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());
        btnShareSelected.setOnClickListener(v -> shareSelected());

        btnFilterAll.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_ALL));
        btnFilterToday.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_TODAY));
        btnFilterLocked.setOnClickListener(v -> switchFilterMode(PlaybackTimelineBuilder.FILTER_LOCKED));

        adapter.setOnItemClickListener((group, position) -> loadPhotoGroup(group));
        adapter.setOnItemSelectedListener(group -> updateSelectedCount());
        adapter.setOnItemLongClickListener((group, position) -> {
            if (adapter.isMultiSelectMode()) {
                shareSelected();
            } else {
                showSingleGroupActionsDialog(group);
            }
        });

        btnViewMode.setOnClickListener(v -> cycleViewMode());
        if (btnLockToggle != null) {
            btnLockToggle.setOnClickListener(v -> {
                if (currentGroup != null) {
                    toggleLockForPhotoGroup(currentGroup);
                }
            });
        }
    }

    private void switchFilterMode(int newMode) {
        if (filterMode == newMode) return;
        filterMode = newMode;
        if (getContext() != null) {
            applyFilterChipsTheme(getContext());
        }
        updatePhotoList();
    }

    private void setupDoubleTapListeners() {
        setupDoubleTap(frameFront, PhotoGroup.POSITION_FRONT, "前");
        setupDoubleTap(frameBack, PhotoGroup.POSITION_BACK, "后");
        setupDoubleTap(frameLeft, PhotoGroup.POSITION_LEFT, "左");
        setupDoubleTap(frameRight, PhotoGroup.POSITION_RIGHT, "右");

        if (singleViewLayout != null) {
            GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
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
                if (!isSingleMode && currentGroup != null && currentGroup.hasPhoto(position)) {
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
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.VISIBLE);
        labelSingle.setText(label);
        btnViewMode.setText(label + "摄");

        if (currentGroup != null) {
            loadImage(currentGroup.getPhotoFile(position), imageSingle);
        }
    }

    private void switchToMultiMode() {
        isSingleMode = false;
        multiViewLayout.setVisibility(View.VISIBLE);
        singleViewLayout.setVisibility(View.GONE);
        btnViewMode.setText("多路");
    }

    private void cycleViewMode() {
        if (currentGroup == null) return;
        List<String> availablePositions = new ArrayList<>();
        availablePositions.add("multi");
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_FRONT)) availablePositions.add(PhotoGroup.POSITION_FRONT);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_BACK)) availablePositions.add(PhotoGroup.POSITION_BACK);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_LEFT)) availablePositions.add(PhotoGroup.POSITION_LEFT);
        if (currentGroup.hasPhoto(PhotoGroup.POSITION_RIGHT)) availablePositions.add(PhotoGroup.POSITION_RIGHT);

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

    private String getPositionLabel(String position) {
        switch (position) {
            case PhotoGroup.POSITION_FRONT: return "前";
            case PhotoGroup.POSITION_BACK: return "后";
            case PhotoGroup.POSITION_LEFT: return "左";
            case PhotoGroup.POSITION_RIGHT: return "右";
            default: return "";
        }
    }

    private void loadPhotoGroup(PhotoGroup group) {
        this.currentGroup = group;
        noSelectionHint.setVisibility(View.GONE);

        if (isSingleMode && !group.hasPhoto(currentSinglePosition)) {
            isSingleMode = false;
            btnViewMode.setText("多路");
        }
        if (isSingleMode) {
            multiViewLayout.setVisibility(View.GONE);
            singleViewLayout.setVisibility(View.VISIBLE);
            loadImage(group.getPhotoFile(currentSinglePosition), imageSingle);
        } else {
            multiViewLayout.setVisibility(View.VISIBLE);
            singleViewLayout.setVisibility(View.GONE);
        }

        controlsLayout.setVisibility(View.VISIBLE);
        currentDatetime.setText(group.getFormattedDateTime() + (group.isLocked() ? " · 已锁定" : ""));
        updateLockButtonState();
        updatePhotoDisplay(group);
    }

    private void updatePhotoDisplay(PhotoGroup group) {
        boolean hasFront = group.hasPhoto(PhotoGroup.POSITION_FRONT);
        boolean hasBack = group.hasPhoto(PhotoGroup.POSITION_BACK);
        boolean hasLeft = group.hasPhoto(PhotoGroup.POSITION_LEFT);
        boolean hasRight = group.hasPhoto(PhotoGroup.POSITION_RIGHT);

        imageFront.setVisibility(hasFront ? View.VISIBLE : View.GONE);
        placeholderFront.setVisibility(hasFront ? View.GONE : View.VISIBLE);
        if (hasFront) loadImage(group.getFrontPhoto(), imageFront);

        imageBack.setVisibility(hasBack ? View.VISIBLE : View.GONE);
        placeholderBack.setVisibility(hasBack ? View.GONE : View.VISIBLE);
        if (hasBack) loadImage(group.getBackPhoto(), imageBack);

        imageLeft.setVisibility(hasLeft ? View.VISIBLE : View.GONE);
        placeholderLeft.setVisibility(hasLeft ? View.GONE : View.VISIBLE);
        if (hasLeft) loadImage(group.getLeftPhoto(), imageLeft);

        imageRight.setVisibility(hasRight ? View.VISIBLE : View.GONE);
        placeholderRight.setVisibility(hasRight ? View.GONE : View.VISIBLE);
        if (hasRight) loadImage(group.getRightPhoto(), imageRight);
    }

    private void loadImage(File photoFile, ImageView imageView) {
        if (photoFile == null || !photoFile.exists() || getContext() == null) return;
        RequestOptions options = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(photoFile.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);
        Glide.with(getContext()).load(photoFile).apply(options).into(imageView);
    }

    private void updatePhotoList() {
        dateSections.clear();
        File saveDir = StorageHelper.getPhotoDir(getContext());
        List<DateSection<PhotoGroup>> scanned = PlaybackTimelineBuilder.scanPhotoDirectory(
                saveDir, filterMode, System.currentTimeMillis());
        dateSections.addAll(scanned);

        if (dateSections.isEmpty()) {
            showEmptyState();
        } else {
            photoList.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
        }

        adapter.buildFlattenedList();
        adapter.syncSelectionByGroup(currentGroup);
        adapter.notifyDataSetChanged();
    }

    private void showEmptyState() {
        photoList.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        if (filterMode == PlaybackTimelineBuilder.FILTER_LOCKED) {
            emptyText.setText("暂无已锁定照片");
        } else if (filterMode == PlaybackTimelineBuilder.FILTER_TODAY) {
            emptyText.setText("今日暂无照片文件");
        } else {
            emptyText.setText("暂无照片文件");
        }
    }

    private void toggleLockForPhotoGroup(@NonNull PhotoGroup group) {
        boolean targetLock = !group.isLocked();
        boolean allOk = true;
        for (File file : group.getShareableFiles()) {
            if (!VideoLockStore.setLocked(file, targetLock)) {
                allOk = false;
            }
        }
        if (!allOk) {
            Toast.makeText(getContext(), "锁定状态更新失败", Toast.LENGTH_SHORT).show();
            return;
        }
        group.setLocked(targetLock);
        Toast.makeText(getContext(),
                targetLock ? "已锁定该组照片（自动清理时将保留）" : "已解除锁定",
                Toast.LENGTH_SHORT).show();
        if (group == currentGroup) {
            currentDatetime.setText(group.getFormattedDateTime() + (group.isLocked() ? " · 已锁定" : ""));
            updateLockButtonState();
        }
        if (filterMode == PlaybackTimelineBuilder.FILTER_LOCKED && !targetLock) {
            updatePhotoList();
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

    private void showSingleGroupActionsDialog(@NonNull PhotoGroup group) {
        if (getContext() == null) return;
        String lockAction = group.isLocked() ? "解除锁定" : "锁定照片（防清理与误删）";
        String[] items = {lockAction, "分享此组照片（扫码 / 系统）", "删除此组照片"};
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(group.getFormattedDateTime())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        toggleLockForPhotoGroup(group);
                    } else if (which == 1) {
                        showPhotoShareDialog(group);
                    } else if (which == 2) {
                        confirmDeleteSingleGroup(group);
                    }
                })
                .show();
    }

    private void confirmDeleteSingleGroup(@NonNull PhotoGroup group) {
        if (getContext() == null) return;
        if (group.isLocked()) {
            new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("照片已锁定保护")
                    .setMessage("该组照片已锁定，请先解除锁定后再删除。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage("确定要删除 " + group.getFormattedDateTime() + " 的全部照片吗？")
                .setPositiveButton("删除", (d, w) -> {
                    if (group == currentGroup) {
                        resetPreviewToEmpty();
                    }
                    int deleted = group.deleteAll();
                    Toast.makeText(getContext(), "已删除 " + deleted + " 张照片", Toast.LENGTH_SHORT).show();
                    updatePhotoList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetPreviewToEmpty() {
        currentGroup = null;
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
        controlsLayout.setVisibility(View.GONE);
        currentDatetime.setText("");
        updateLockButtonState();
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
        Set<PhotoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty() || getContext() == null) return;

        List<PhotoGroup> unlocked = new ArrayList<>();
        int lockedCount = 0;
        for (PhotoGroup group : selectedGroups) {
            if (group.isLocked()) {
                lockedCount++;
            } else {
                unlocked.add(group);
            }
        }
        if (unlocked.isEmpty()) {
            new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("无法直接删除")
                    .setMessage("所选 " + lockedCount + " 组照片均已锁定保护，请先解除锁定后再删除。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String msg = lockedCount > 0
                ? "已选中 " + selectedGroups.size() + " 组，其中 " + lockedCount
                + " 组已锁定将被跳过，仅删除其余 " + unlocked.size() + " 组未锁定照片。"
                : "确定要删除选中的 " + unlocked.size() + " 组照片吗？（包含全部摄像头照片）";
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage(msg)
                .setPositiveButton(lockedCount > 0 ? "仅删未锁定项" : "删除", (dialog, which) -> {
                    int deletedCount = 0;
                    for (PhotoGroup group : unlocked) {
                        if (group == currentGroup) {
                            resetPreviewToEmpty();
                        }
                        deletedCount += group.deleteAll();
                    }
                    adapter.clearSelection();
                    exitMultiSelectMode();
                    updatePhotoList();
                    if (getContext() != null) {
                        Toast.makeText(getContext(), "已删除 " + deletedCount + " 张照片", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void shareSelected() {
        Set<PhotoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            Toast.makeText(getContext(), "请先选择要分享的图片", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> allPhotoFiles = new ArrayList<>();
        for (PhotoGroup group : selectedGroups) {
            allPhotoFiles.addAll(group.getShareableFiles());
        }
        PlaybackThemeAndShareHelper.showShareOptionsDialog(
                this,
                "分享选中的图片",
                "已选择 " + selectedGroups.size() + " 组图片，共 " + allPhotoFiles.size() + " 个文件",
                allPhotoFiles,
                "image/jpeg",
                "分享图片",
                "来自 EVCam 的图片分享",
                "分享图片到");
    }

    private void showPhotoShareDialog(PhotoGroup group) {
        List<File> photoFiles = group.getShareableFiles();
        PlaybackThemeAndShareHelper.showShareOptionsDialog(
                this,
                "分享图片",
                "共 " + photoFiles.size() + " 个图片文件",
                photoFiles,
                "image/jpeg",
                "分享图片",
                "来自 EVCam 的图片分享",
                "分享图片到");
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
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnRefresh, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnMultiSelect, false);
        PlaybackThemeAndShareHelper.styleToolbarButton(themedContext, btnViewMode, false);
        applyFilterChipsTheme(themedContext);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onDestroyView() {
        if (getView() != null) PlaybackImageMemory.clearViews(getView());
        if (photoList != null) {
            photoList.setAdapter(null);
            photoList.getRecycledViewPool().clear();
        }
        PlaybackImageMemory.clear(requireContext());
        adapter = null;
        imageFront = imageBack = imageLeft = imageRight = imageSingle = null;
        rootView = null;
        super.onDestroyView();
    }
}
