package com.kooo.evcam.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.kooo.evcam.R;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * SC 风格精简左栏外壳（4+2 结构）：
 * 主导航保留记录仪（默认）/ 回看 / 补盲 / 飞书，左下角固定设置与退出，
 * 替代旧 DrawerLayout / NavigationView，并移除顶栏外观按钮。
 */
public final class AppShellController {

    private final FragmentActivity activity;
    private final FragmentManager fragments;
    private final View shell;
    private final TextView title;
    private final View back;
    private final Button preview;

    private final int[] destinations = {
            R.id.nav_recording,
            R.id.nav_playback,
            R.id.nav_secondary_display,
            R.id.nav_feishu,
            R.id.nav_settings
    };
    private final int[] buttons = {
            R.id.ui_nav_recording,
            R.id.ui_nav_playback,
            R.id.ui_nav_blind_spot,
            R.id.ui_nav_feishu,
            R.id.ui_nav_settings
    };
    private final String[] names = {"记录仪", "回看", "补盲", "飞书", "设置"};

    private int currentRoot = R.id.nav_recording;
    private boolean previewEnabled;
    private boolean layoutEditing;
    private boolean fullscreenPreview;
    private boolean floatingWindowActive;
    private int lastSysTopInset;
    private float currentUiScale = 1.0f;
    private PreviewMode activePreviewMode = PreviewMode.MODE_B;
    @Nullable
    private Consumer<Boolean> fullscreenListener;
    @Nullable
    private Runnable recordToggleListener;
    @ColorRes
    private int currentRecordingColor = R.color.text_primary;

    public AppShellController(FragmentActivity activity) {
        this.activity = activity;
        fragments = activity.getSupportFragmentManager();

        ViewGroup root = activity.findViewById(android.R.id.content);
        View existing = root.getChildAt(0);
        if (existing != null) {
            root.removeView(existing);
        }

        shell = LayoutInflater.from(activity).inflate(R.layout.ui_app_shell, root, false);
        root.addView(shell);
        if (existing != null) {
            ((ViewGroup) shell.findViewById(R.id.ui_content)).addView(existing);
        }

        title = shell.findViewById(R.id.ui_page_title);
        back = shell.findViewById(R.id.ui_back);
        preview = shell.findViewById(R.id.ui_preview_toggle);

        TextView versionView = shell.findViewById(R.id.ui_rail_version);
        if (versionView != null) {
            versionView.setText(resolveDisplayVersion(activity));
        }

        applySystemBarInsets();

        fragments.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                              @NonNull View v, @Nullable android.os.Bundle s) {
                if (f.getId() == R.id.fragment_container) {
                    normalizeHeader(v);
                }
                float scale = UiScaleController.getSavedScaleFactor(activity);
                UiScaleHelper.applyScaleToViewTree(v, scale);
                if (f instanceof UiScaleController.ScaleAware) {
                    ((UiScaleController.ScaleAware) f).onUiScaleApplied(scale);
                }
            }
        }, true);
        fragments.addOnBackStackChangedListener(this::updateHeader);
    }

    public void bind(IntConsumer navigate, Runnable onExit, Runnable onBack,
                     Runnable togglePreview, Runnable editLayout) {
        for (int i = 0; i < buttons.length; i++) {
            final int id = destinations[i];
            TextView item = shell.findViewById(buttons[i]);
            scaleNavDrawable(item);
            item.setOnClickListener(v -> navigate.accept(id));
        }

        TextView exitItem = shell.findViewById(R.id.ui_nav_exit);
        if (exitItem != null) {
            scaleNavDrawable(exitItem);
            exitItem.setOnClickListener(v -> onExit.run());
        }

        TextView recordToggle = shell.findViewById(R.id.ui_nav_record_toggle);
        if (recordToggle != null) {
            scaleNavDrawable(recordToggle);
            recordToggle.setOnClickListener(v -> {
                if (recordToggleListener != null) {
                    recordToggleListener.run();
                }
            });
        }

        shell.findViewById(R.id.ui_edit_layout).setOnClickListener(v -> editLayout.run());
        back.setOnClickListener(v -> onBack.run());
        preview.setOnClickListener(v -> togglePreview.run());
        refreshTheme(activity, isActivityNight());
        applyUiScale(UiScaleController.getSavedScaleFactor(activity));
        updateHeader();
    }

    /** 绑定最左栏顶部「开始录制 / 停止录制」操作。 */
    public void bindRecordToggle(@Nullable Runnable onToggleRecording) {
        this.recordToggleListener = onToggleRecording;
    }

    public void bindPreviewModeControls(Consumer<PreviewMode> onModeSelect,
                                        Runnable onToggleFullscreen,
                                        @Nullable Consumer<Boolean> onFullscreenChanged) {
        this.fullscreenListener = onFullscreenChanged;
        View btnA = shell.findViewById(R.id.ui_mode_a);
        if (btnA != null) {
            btnA.setOnClickListener(v -> onModeSelect.accept(PreviewMode.MODE_A));
        }
        View btnB = shell.findViewById(R.id.ui_mode_b);
        if (btnB != null) {
            btnB.setOnClickListener(v -> onModeSelect.accept(PreviewMode.MODE_B));
        }
        View btnC = shell.findViewById(R.id.ui_mode_c);
        if (btnC != null) {
            btnC.setOnClickListener(v -> onModeSelect.accept(PreviewMode.MODE_C));
        }
        View btnD = shell.findViewById(R.id.ui_mode_d);
        if (btnD != null) {
            btnD.setOnClickListener(v -> onModeSelect.accept(PreviewMode.MODE_D));
        }
        View btnFullscreen = shell.findViewById(R.id.ui_fullscreen_toggle);
        if (btnFullscreen != null) {
            btnFullscreen.setOnClickListener(v -> onToggleFullscreen.run());
        }
        applyPreviewModeColors(activity);
        updateHeader();
    }

    /** 绑定 4.2 顶栏"小窗"快捷按钮。 */
    public void bindFloatingWindowAction(@Nullable Runnable onFloatingWindow) {
        View btnFloating = shell.findViewById(R.id.ui_floating_window_toggle);
        if (btnFloating != null) {
            btnFloating.setOnClickListener(v -> {
                if (onFloatingWindow != null) {
                    onFloatingWindow.run();
                }
            });
        }
        applyPreviewModeColors(activity);
    }

    public void setFloatingWindowActive(boolean active) {
        this.floatingWindowActive = active;
        applyPreviewModeColors(activity);
    }

    private void scaleNavDrawable(@Nullable TextView item) {
        if (item == null) return;
        int iconSize = UiScaleHelper.dimPx(activity, R.dimen.ui_nav_icon_size, currentUiScale);
        Drawable[] icons = item.getCompoundDrawables();
        for (Drawable icon : icons) {
            if (icon != null) icon.setBounds(0, 0, iconSize, iconSize);
        }
        item.setCompoundDrawables(icons[0], icons[1], icons[2], icons[3]);
    }

    private void applySystemBarInsets() {
        View rail = shell.findViewById(R.id.ui_rail);
        View topBar = shell.findViewById(R.id.ui_top_bar);

        ViewCompat.setOnApplyWindowInsetsListener(shell, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            lastSysTopInset = sys.top;
            applyShellBarInsets(rail, topBar);
            v.setPadding(sys.left, v.getPaddingTop(), sys.right, sys.bottom);
            applyContentTopInset();
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.NONE)
                    .build();
        });
        ViewCompat.requestApplyInsets(shell);
    }

    private void applyShellBarInsets(@Nullable View rail, @Nullable View topBar) {
        if (rail != null) {
            int railPad = UiScaleHelper.dimPx(activity, R.dimen.ui_rail_padding, currentUiScale);
            rail.setPadding(railPad, railPad + lastSysTopInset, railPad, railPad);
        }
        if (topBar != null) {
            int barPadH = UiScaleHelper.dimPx(activity, R.dimen.ui_page_padding, currentUiScale);
            topBar.setPadding(barPadH, lastSysTopInset, barPadH, topBar.getPaddingBottom());
            ViewGroup.LayoutParams lp = topBar.getLayoutParams();
            int targetH = UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_height, currentUiScale) + lastSysTopInset;
            if (lp != null && lp.height != targetH) {
                lp.height = targetH;
                topBar.setLayoutParams(lp);
            }
        }
    }

    /**
     * 原地应用菜单与按钮缩放倍率：重算左栏宽度/条目/图标/字号、顶栏高度/按钮/字号，
     * 以及主界面操作按钮与角落快捷胶囊尺寸，不重建 Activity。
     */
    public void applyUiScale(float scaleFactor) {
        this.currentUiScale = UiScaleHelper.normalizeScale(scaleFactor);
        float s = this.currentUiScale;
        float density = activity.getResources().getDisplayMetrics().density;

        View rail = shell.findViewById(R.id.ui_rail);
        View topBar = shell.findViewById(R.id.ui_top_bar);
        if (rail != null && rail.getLayoutParams() != null) {
            int targetRailW = UiScaleHelper.dimPx(activity, R.dimen.ui_rail_width, s);
            if (rail.getLayoutParams().width != targetRailW) {
                rail.getLayoutParams().width = targetRailW;
                rail.requestLayout();
            }
        }
        applyShellBarInsets(rail, topBar);

        TextView railBrand = shell.findViewById(R.id.ui_rail_brand);
        if (railBrand != null) {
            UiScaleHelper.applyTextViewMetrics(
                    railBrand,
                    UiScaleHelper.dimFloat(activity, R.dimen.ui_nav_text_size, s),
                    UiScaleHelper.dimPx(activity, R.dimen.ui_rail_brand_height, s),
                    0,
                    0,
                    railBrand.getPaddingLeft(),
                    railBrand.getPaddingTop());
        }

        applyRailNavItemMetrics(shell.findViewById(R.id.ui_nav_record_toggle), false,
                UiScaleHelper.dimPx(activity, R.dimen.ui_nav_gap, s));
        for (int i = 0; i < buttons.length; i++) {
            int navId = buttons[i];
            boolean isFooter = (navId == R.id.ui_nav_settings);
            int marginBottom = (navId == R.id.ui_nav_feishu)
                    ? 0
                    : UiScaleHelper.dimPx(activity, R.dimen.ui_nav_gap, s);
            applyRailNavItemMetrics(shell.findViewById(navId), isFooter, marginBottom);
        }
        applyRailNavItemMetrics(shell.findViewById(R.id.ui_nav_exit), true,
                Math.round(4f * density * s));

        TextView versionView = shell.findViewById(R.id.ui_rail_version);
        if (versionView != null) {
            versionView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    UiScaleHelper.dimFloat(activity, R.dimen.ui_rail_version_text_size, s));
        }

        if (topBar != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    UiScaleHelper.dimFloat(activity, R.dimen.ui_top_bar_title_text_size, s));
            setMarginEnd(title, UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_group_gap, s));

            TextView barStatus = topBar.findViewById(R.id.ui_recording_status);
            if (barStatus != null) {
                barStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        UiScaleHelper.dimFloat(activity, R.dimen.ui_top_bar_status_text_size, s));
                setMarginEnd(barStatus, UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_group_gap, s));
            }

            int groupGap = UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_group_gap, s);
            int btnGap = UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_button_gap, s);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_back), groupGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_edit_layout), groupGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_mode_a), btnGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_mode_b), btnGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_mode_c), btnGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_mode_d), btnGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_fullscreen_toggle), btnGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_floating_window_toggle), groupGap);
            applyTopBarButtonMetrics(topBar.findViewById(R.id.ui_preview_toggle), -1);
        }

        View content = shell.findViewById(R.id.ui_content);
        if (content != null) {
            View mainView = content.findViewById(R.id.main);
            if (mainView != null) {
                int mainPadH = UiScaleHelper.dimPx(activity, R.dimen.ui_page_padding, s);
                mainView.setPadding(mainPadH, mainView.getPaddingTop(), mainPadH, mainView.getPaddingBottom());
            }
            int actionHeight = UiScaleHelper.dimPx(activity, R.dimen.ui_main_action_button_height, s);
            int actionPadH = UiScaleHelper.dimPx(activity, R.dimen.ui_button_padding_h, s);
            float actionTextPx = UiScaleHelper.dimFloat(activity, R.dimen.ui_button_text_size, s);

            Button btnTakePhoto = content.findViewById(R.id.btn_take_photo);
            if (btnTakePhoto != null) {
                int minW = UiScaleHelper.dimPx(activity, R.dimen.ui_button_min_width, s);
                UiScaleHelper.applyTextViewMetrics(btnTakePhoto, actionTextPx, actionHeight, actionHeight, minW, actionPadH, 0);
                setMarginEnd(btnTakePhoto, Math.round(10f * density * s));
            }
            Button btnStartRecord = content.findViewById(R.id.btn_start_record);
            if (btnStartRecord != null) {
                int minW = UiScaleHelper.dimPx(activity, R.dimen.ui_main_action_button_min_width, s);
                UiScaleHelper.applyTextViewMetrics(btnStartRecord, actionTextPx, actionHeight, actionHeight, minW, actionPadH, 0);
            }

            View cornerGroup = content.findViewById(R.id.ui_shortcut_corner_nav_group);
            if (cornerGroup != null) {
                int groupH = UiScaleHelper.dimPx(activity, R.dimen.ui_corner_shortcut_height, s);
                int groupPadH = Math.round(6f * density * s);
                cornerGroup.setPadding(groupPadH, cornerGroup.getPaddingTop(), groupPadH, cornerGroup.getPaddingBottom());
                ViewGroup.LayoutParams glp = cornerGroup.getLayoutParams();
                if (glp != null && glp.height != groupH) {
                    glp.height = groupH;
                    cornerGroup.setLayoutParams(glp);
                }
                int itemH = UiScaleHelper.dimPx(activity, R.dimen.ui_corner_shortcut_item_height, s);
                int itemPadH = Math.round(10f * density * s);
                float pillTextPx = UiScaleHelper.dimFloat(activity, R.dimen.ui_pill_text_size, s);
                UiScaleHelper.applyTextViewMetrics(cornerGroup.findViewById(R.id.btn_shortcut_back),
                        pillTextPx, itemH, itemH, 0, itemPadH, 0);
                UiScaleHelper.applyTextViewMetrics(cornerGroup.findViewById(R.id.btn_shortcut_settings),
                        pillTextPx, itemH, itemH, 0, itemPadH, 0);
            }
        }
    }

    private void applyRailNavItemMetrics(@Nullable TextView item, boolean isFooter, int marginBottomPx) {
        if (item == null) return;
        float s = currentUiScale;
        int minH = UiScaleHelper.dimPx(activity,
                isFooter ? R.dimen.ui_nav_footer_height : R.dimen.ui_nav_height, s);
        int padH = UiScaleHelper.dimPx(activity, R.dimen.ui_nav_padding, s);
        int padV = UiScaleHelper.dimPx(activity, R.dimen.ui_nav_padding_v, s);
        float textPx = UiScaleHelper.dimFloat(activity, R.dimen.ui_nav_text_size, s);
        UiScaleHelper.applyTextViewMetrics(item, textPx, ViewGroup.LayoutParams.WRAP_CONTENT, minH, 0, padH, padV);
        item.setCompoundDrawablePadding(UiScaleHelper.dimPx(activity, R.dimen.ui_nav_icon_gap, s));
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            if (mlp.bottomMargin != marginBottomPx) {
                mlp.bottomMargin = marginBottomPx;
                item.setLayoutParams(mlp);
            }
        }
        scaleNavDrawable(item);
    }

    private void applyTopBarButtonMetrics(@Nullable View view, int marginEndPx) {
        if (!(view instanceof TextView)) return;
        float s = currentUiScale;
        int btnH = UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_button_height, s);
        int padH = UiScaleHelper.dimPx(activity, R.dimen.ui_top_bar_button_padding_h, s);
        float textPx = UiScaleHelper.dimFloat(activity, R.dimen.ui_top_bar_button_text_size, s);
        UiScaleHelper.applyTextViewMetrics((TextView) view, textPx, btnH, btnH, 0, padH, 0);
        if (marginEndPx >= 0) {
            setMarginEnd(view, marginEndPx);
        }
    }

    private static void setMarginEnd(@Nullable View view, int marginEndPx) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            if (mlp.getMarginEnd() != marginEndPx) {
                mlp.setMarginEnd(marginEndPx);
                view.setLayoutParams(mlp);
            }
        }
    }

    private void applyContentTopInset() {
        View content = shell.findViewById(R.id.ui_content);
        if (content == null) return;
        boolean topBarHidden = (currentRoot != R.id.nav_recording) || fullscreenPreview;
        int topPadding = topBarHidden ? lastSysTopInset : 0;
        if (content.getPaddingTop() != topPadding) {
            content.setPadding(content.getPaddingLeft(), topPadding,
                    content.getPaddingRight(), content.getPaddingBottom());
        }
    }

    public boolean isFullscreenPreview() {
        return fullscreenPreview;
    }

    public void setFullscreenPreview(boolean fullscreen) {
        boolean target = fullscreen
                && currentRoot == R.id.nav_recording
                && activity.findViewById(R.id.ui_preview_layout) != null;
        if (fullscreenPreview == target) return;
        fullscreenPreview = target;
        int railVisibility = target ? View.GONE : View.VISIBLE;
        shell.findViewById(R.id.ui_rail).setVisibility(railVisibility);
        shell.findViewById(R.id.ui_rail_divider).setVisibility(railVisibility);
        updateTopBarVisibility();
        applyContentTopInset();
        applyPreviewModeColors(activity);
        if (fullscreenListener != null) {
            fullscreenListener.accept(fullscreenPreview);
        }
    }

    private void updateTopBarVisibility() {
        boolean showTopBar = (currentRoot == R.id.nav_recording) && !fullscreenPreview;
        int topBarVisibility = showTopBar ? View.VISIBLE : View.GONE;
        View topBar = shell.findViewById(R.id.ui_top_bar);
        if (topBar != null && topBar.getVisibility() != topBarVisibility) {
            topBar.setVisibility(topBarVisibility);
        }
        View divider = shell.findViewById(R.id.ui_topbar_divider);
        if (divider != null && divider.getVisibility() != topBarVisibility) {
            divider.setVisibility(topBarVisibility);
        }
    }

    public int getCurrentRoot() {
        return currentRoot;
    }

    public void showRoot(int destination, @Nullable Fragment fragment) {
        currentRoot = destination;
        if (destination != R.id.nav_recording && fullscreenPreview) {
            setFullscreenPreview(false);
        }
        applyRootContainerVisibility();
        fragments.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (fragment != null) {
            FragmentTransaction transaction = fragments.beginTransaction();
            transaction.replace(R.id.fragment_container, fragment);
            transaction.commitAllowingStateLoss();
        }
        updateHeader();
    }

    public void restore(int destination) {
        if (destination == R.id.nav_secondary_display || destination == R.id.nav_feishu) {
            currentRoot = R.id.nav_settings;
            applyRootContainerVisibility();
            updateHeader();
            return;
        }
        currentRoot = destination;
        for (int i = 0; i < destinations.length; i++) {
            if (destinations[i] == destination) {
                applyRootContainerVisibility();
                updateHeader();
                return;
            }
        }
        currentRoot = R.id.nav_recording;
        applyRootContainerVisibility();
        updateHeader();
    }

    private void applyRootContainerVisibility() {
        boolean recording = currentRoot == R.id.nav_recording;
        View mainView = activity.findViewById(R.id.main);
        if (mainView != null) {
            mainView.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
        View container = activity.findViewById(R.id.fragment_container);
        if (container != null) {
            container.setVisibility(recording ? View.GONE : View.VISIBLE);
        }
    }

    public void setPreviewEnabled(boolean enabled) {
        this.previewEnabled = enabled;
        preview.setText(enabled ? "预览：开" : "预览：关");
        applyPreviewButtonColors(activity);
    }

    public void setLayoutEditing(boolean editing) {
        this.layoutEditing = editing;
        Button edit = shell.findViewById(R.id.ui_edit_layout);
        edit.setText(editing ? "编辑中" : "编辑布局");
        applyEditButtonColors(activity);
    }

    public void setPreviewModeUi(PreviewMode mode) {
        this.activePreviewMode = (mode != null) ? mode : PreviewMode.MODE_B;
        applyPreviewModeColors(activity);
        updateHeader();
    }

    public void setRecordingState(String text, @ColorRes int color) {
        this.currentRecordingColor = color;
        int resolvedColor = ContextCompat.getColor(activity, color);
        TextView topStatus = shell.findViewById(R.id.ui_recording_status);
        if (topStatus != null) {
            topStatus.setText(text);
            topStatus.setTextColor(resolvedColor);
        }
        View content = shell.findViewById(R.id.ui_content);
        if (content != null) {
            TextView contentStatus = content.findViewById(R.id.ui_recording_status);
            if (contentStatus != null) {
                contentStatus.setText(text);
                contentStatus.setTextColor(resolvedColor);
            }
        }
        updateRecordToggleUi(activity);
    }

    /** 原地刷新左栏、顶栏及系统配色，支持无重建切换深浅色。 */
    public void refreshTheme(@NonNull Context themedContext, boolean isNight) {
        shell.findViewById(R.id.ui_shell_root).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        shell.findViewById(R.id.ui_rail).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.nav_background));
        shell.findViewById(R.id.ui_rail_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        View recordDivider = shell.findViewById(R.id.ui_rail_record_divider);
        if (recordDivider != null) {
            recordDivider.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        }
        shell.findViewById(R.id.ui_rail_footer_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        shell.findViewById(R.id.ui_top_bar).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.menu_background));
        shell.findViewById(R.id.ui_topbar_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        shell.findViewById(R.id.ui_content).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));

        TextView brand = shell.findViewById(R.id.ui_rail_brand);
        if (brand != null) {
            brand.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        }
        TextView versionView = shell.findViewById(R.id.ui_rail_version);
        if (versionView != null) {
            versionView.setTextColor(ContextCompat.getColor(themedContext, R.color.text_secondary));
        }
        title.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));

        TextView status = shell.findViewById(R.id.ui_recording_status);
        if (status != null) {
            status.setTextColor(ContextCompat.getColor(themedContext, currentRecordingColor));
        }

        ColorStateList navTextColors = AppCompatResources.getColorStateList(themedContext, R.color.ui_nav_text);
        for (int buttonId : buttons) {
            repaintNavItem(shell.findViewById(buttonId), themedContext, navTextColors);
        }
        repaintDangerNavItem(shell.findViewById(R.id.ui_nav_exit), themedContext);
        updateRecordToggleUi(themedContext);

        if (back instanceof Button) {
            Button backBtn = (Button) back;
            ViewCompat.setBackgroundTintList(backBtn, ColorStateList.valueOf(ContextCompat.getColor(themedContext, R.color.button_background)));
            backBtn.setTextColor(ContextCompat.getColor(themedContext, R.color.button_text));
        }
        applyPreviewButtonColors(themedContext);
        applyEditButtonColors(themedContext);
        applyPreviewModeColors(themedContext);
    }

    private void repaintDangerNavItem(@Nullable TextView item, Context themedContext) {
        if (item == null) return;
        Drawable itemBg = ContextCompat.getDrawable(themedContext, R.drawable.ui_nav_item);
        if (itemBg != null) {
            item.setBackground(itemBg);
        }
        int dangerColor = ContextCompat.getColor(themedContext, R.color.ui_danger);
        item.setTextColor(dangerColor);
        item.setCompoundDrawableTintList(ColorStateList.valueOf(dangerColor));
    }

    private void updateRecordToggleUi(@NonNull Context themedContext) {
        TextView recordToggle = shell.findViewById(R.id.ui_nav_record_toggle);
        if (recordToggle == null) return;
        Drawable itemBg = ContextCompat.getDrawable(themedContext, R.drawable.ui_nav_item);
        if (itemBg != null) {
            recordToggle.setBackground(itemBg);
        }
        String label;
        @ColorRes int colorRes;
        if (currentRecordingColor == R.color.ui_danger) {
            label = "停止录制";
            colorRes = R.color.ui_danger;
        } else if (currentRecordingColor == R.color.ui_warning) {
            label = "准备中…";
            colorRes = R.color.ui_warning;
        } else {
            label = "开始录制";
            colorRes = R.color.text_secondary;
        }
        int resolved = ContextCompat.getColor(themedContext, colorRes);
        recordToggle.setText(label);
        recordToggle.setTextColor(resolved);
        recordToggle.setCompoundDrawableTintList(ColorStateList.valueOf(resolved));
    }

    @NonNull
    private static String resolveDisplayVersion(@NonNull Context context) {
        try {
            String raw = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            if (raw != null && !raw.trim().isEmpty()) {
                int dash = raw.indexOf('-');
                String base = dash > 0 ? raw.substring(0, dash) : raw.trim();
                return base.startsWith("V") || base.startsWith("v")
                        ? "V" + base.substring(1)
                        : "V" + base;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "V1.6.6";
    }

    private void repaintNavItem(@Nullable TextView item, Context themedContext, @Nullable ColorStateList navTextColors) {
        if (item == null) return;
        Drawable itemBg = ContextCompat.getDrawable(themedContext, R.drawable.ui_nav_item);
        if (itemBg != null) {
            item.setBackground(itemBg);
        }
        if (navTextColors != null) {
            item.setTextColor(navTextColors);
            item.setCompoundDrawableTintList(navTextColors);
        }
    }

    private void applyPreviewButtonColors(Context themedContext) {
        int bgColor = ContextCompat.getColor(themedContext, previewEnabled ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(themedContext, previewEnabled ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(preview, ColorStateList.valueOf(bgColor));
        preview.setTextColor(textColor);
        preview.setTag(R.id.ui_theme_text_role, previewEnabled ? "on_accent" : null);
    }

    private void applyEditButtonColors(Context themedContext) {
        Button edit = shell.findViewById(R.id.ui_edit_layout);
        if (edit == null) return;
        int bgColor = ContextCompat.getColor(themedContext, layoutEditing ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(themedContext, layoutEditing ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(edit, ColorStateList.valueOf(bgColor));
        edit.setTextColor(textColor);
        edit.setTag(R.id.ui_theme_text_role, layoutEditing ? "on_accent" : null);
    }

    private void applyPreviewModeColors(Context themedContext) {
        styleShellToggle(shell.findViewById(R.id.ui_mode_a), themedContext,
                activePreviewMode == PreviewMode.MODE_A);
        styleShellToggle(shell.findViewById(R.id.ui_mode_b), themedContext,
                activePreviewMode == PreviewMode.MODE_B);
        styleShellToggle(shell.findViewById(R.id.ui_mode_c), themedContext,
                activePreviewMode == PreviewMode.MODE_C);
        styleShellToggle(shell.findViewById(R.id.ui_mode_d), themedContext,
                activePreviewMode == PreviewMode.MODE_D);
        Button fullscreenBtn = shell.findViewById(R.id.ui_fullscreen_toggle);
        if (fullscreenBtn != null) {
            fullscreenBtn.setText(fullscreenPreview ? "退出全屏" : "全屏");
            styleShellToggle(fullscreenBtn, themedContext, fullscreenPreview);
        }
        styleShellToggle(shell.findViewById(R.id.ui_floating_window_toggle), themedContext, floatingWindowActive);
    }

    private void styleShellToggle(@Nullable Button button, Context themedContext, boolean active) {
        if (button == null) return;
        int bgColor = ContextCompat.getColor(themedContext, active ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(themedContext, active ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(bgColor));
        button.setTextColor(textColor);
        button.setTag(R.id.ui_theme_text_role, active ? "on_accent" : null);
    }

    private boolean isActivityNight() {
        return (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private String rootName() {
        for (int i = 0; i < destinations.length; i++) {
            if (destinations[i] == currentRoot) return names[i];
        }
        return "EVCam";
    }

    public void updateHeader() {
        for (int i = 0; i < buttons.length; i++) {
            shell.findViewById(buttons[i]).setSelected(destinations[i] == currentRoot);
        }
        updateTopBarVisibility();
        applyContentTopInset();
        boolean secondary = currentRoot != R.id.nav_recording && fragments.getBackStackEntryCount() > 0;
        back.setVisibility(secondary ? View.VISIBLE : View.GONE);
        boolean hasEditablePreview = currentRoot == R.id.nav_recording
                && activity.findViewById(R.id.ui_preview_layout) != null;
        preview.setVisibility(currentRoot == R.id.nav_recording ? View.VISIBLE : View.GONE);
        shell.findViewById(R.id.ui_edit_layout).setVisibility(
                (hasEditablePreview && activePreviewMode != PreviewMode.MODE_A) ? View.VISIBLE : View.GONE);
        View modeControls = shell.findViewById(R.id.ui_preview_mode_controls);
        if (modeControls != null) {
            modeControls.setVisibility(hasEditablePreview ? View.VISIBLE : View.GONE);
        }
        Fragment current = currentRoot == R.id.nav_recording ? null : fragments.findFragmentById(R.id.fragment_container);
        Object custom = null;
        if (current != null) {
            if (current.getArguments() != null) {
                custom = current.getArguments().getCharSequence("ui_screen_title");
            }
            if (custom == null && current.getView() != null) {
                custom = current.getView().getTag(R.id.ui_screen_title);
            }
        }
        title.setText(secondary && custom instanceof CharSequence ? (CharSequence) custom : rootName());
    }

    private void normalizeHeader(View root) {
        View toolbar = root.findViewById(R.id.toolbar);
        if (toolbar == null) return;
        CharSequence heading = findToolbarTitle(toolbar);
        if (toolbar instanceof ViewGroup) {
            boolean nested = currentRoot != R.id.nav_recording && fragments.getBackStackEntryCount() > 0;
            ViewGroup bar = (ViewGroup) toolbar;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                int id = child.getId();
                if (id == R.id.btn_menu || id == R.id.btn_home || id == R.id.btn_back) {
                    child.setVisibility(View.GONE);
                } else if (nested && child instanceof TextView && heading != null && heading.equals(((TextView) child).getText())) {
                    child.setVisibility(View.GONE);
                }
            }
            if (hasVisibleChildren(bar)) {
                bar.setPadding(bar.getPaddingLeft(), 8, bar.getPaddingRight(), bar.getPaddingBottom());
                if (nested) root.setTag(R.id.ui_screen_title, heading);
            } else {
                bar.setVisibility(View.GONE);
                if (heading != null) root.setTag(R.id.ui_screen_title, heading);
            }
        }
        updateHeader();
    }

    private CharSequence findToolbarTitle(View view) {
        if (view instanceof TextView && !(view instanceof Button)) return ((TextView) view).getText();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CharSequence found = findToolbarTitle(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasVisibleChildren(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (bar.getChildAt(i).getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }
}
