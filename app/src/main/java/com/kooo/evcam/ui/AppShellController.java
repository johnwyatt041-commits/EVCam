package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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

        applySystemBarInsets();

        fragments.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                              @NonNull View v, @Nullable android.os.Bundle s) {
                if (f.getId() == R.id.fragment_container) {
                    normalizeHeader(v);
                }
            }
        }, false);
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

        shell.findViewById(R.id.ui_edit_layout).setOnClickListener(v -> editLayout.run());
        back.setOnClickListener(v -> onBack.run());
        preview.setOnClickListener(v -> togglePreview.run());
        refreshTheme(activity, isActivityNight());
        updateHeader();
    }

    private void scaleNavDrawable(TextView item) {
        int iconSize = activity.getResources().getDimensionPixelSize(R.dimen.ui_nav_icon_size);
        Drawable[] icons = item.getCompoundDrawables();
        for (Drawable icon : icons) {
            if (icon != null) icon.setBounds(0, 0, iconSize, iconSize);
        }
        item.setCompoundDrawables(icons[0], icons[1], icons[2], icons[3]);
    }

    private void applySystemBarInsets() {
        View rail = shell.findViewById(R.id.ui_rail);
        View topBar = shell.findViewById(R.id.ui_top_bar);
        final int railTop = rail.getPaddingTop();
        final int railBottom = rail.getPaddingBottom();
        final int topBarHeight = topBar.getLayoutParams().height;
        final int topBarTop = topBar.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(shell, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            rail.setPadding(rail.getPaddingLeft(), railTop + sys.top, rail.getPaddingRight(), railBottom);
            if (topBar.getLayoutParams() != null && topBarHeight > 0) {
                topBar.getLayoutParams().height = topBarHeight + sys.top;
            }
            topBar.setPadding(topBar.getPaddingLeft(), topBarTop + sys.top, topBar.getPaddingRight(), topBar.getPaddingBottom());
            v.setPadding(sys.left, v.getPaddingTop(), sys.right, sys.bottom);
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.NONE)
                    .build();
        });
        ViewCompat.requestApplyInsets(shell);
    }

    public int getCurrentRoot() {
        return currentRoot;
    }

    public void showRoot(int destination, @Nullable Fragment fragment) {
        currentRoot = destination;
        boolean recording = destination == R.id.nav_recording;
        activity.findViewById(R.id.main).setVisibility(recording ? View.VISIBLE : View.GONE);
        View container = activity.findViewById(R.id.fragment_container);
        fragments.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (fragment != null) {
            FragmentTransaction transaction = fragments.beginTransaction();
            transaction.replace(R.id.fragment_container, fragment);
            transaction.commitAllowingStateLoss();
        }
        container.setVisibility(recording ? View.GONE : View.VISIBLE);
        updateHeader();
    }

    public void restore(int destination) {
        currentRoot = destination;
        for (int i = 0; i < destinations.length; i++) {
            if (destinations[i] == destination) {
                updateHeader();
                return;
            }
        }
        currentRoot = R.id.nav_recording;
        updateHeader();
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

    public void setRecordingState(String text, @ColorRes int color) {
        this.currentRecordingColor = color;
        TextView status = activity.findViewById(R.id.ui_recording_status);
        if (status != null) {
            status.setText(text);
            status.setTextColor(ContextCompat.getColor(activity, color));
        }
    }

    /** 原地刷新左栏、顶栏及系统配色，支持无重建切换深浅色。 */
    public void refreshTheme(@NonNull Context themedContext, boolean isNight) {
        shell.findViewById(R.id.ui_shell_root).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        shell.findViewById(R.id.ui_rail).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.nav_background));
        shell.findViewById(R.id.ui_rail_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        shell.findViewById(R.id.ui_rail_footer_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        shell.findViewById(R.id.ui_top_bar).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.menu_background));
        shell.findViewById(R.id.ui_topbar_divider).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        shell.findViewById(R.id.ui_content).setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));

        TextView brand = shell.findViewById(R.id.ui_rail_brand);
        if (brand != null) {
            brand.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
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
        repaintNavItem(shell.findViewById(R.id.ui_nav_exit), themedContext, navTextColors);

        if (back instanceof Button) {
            Button backBtn = (Button) back;
            ViewCompat.setBackgroundTintList(backBtn, ColorStateList.valueOf(ContextCompat.getColor(themedContext, R.color.button_background)));
            backBtn.setTextColor(ContextCompat.getColor(themedContext, R.color.button_text));
        }
        applyPreviewButtonColors(themedContext);
        applyEditButtonColors(themedContext);
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
        boolean secondary = currentRoot != R.id.nav_recording && fragments.getBackStackEntryCount() > 0;
        back.setVisibility(secondary ? View.VISIBLE : View.GONE);
        preview.setVisibility(currentRoot == R.id.nav_recording ? View.VISIBLE : View.GONE);
        shell.findViewById(R.id.ui_edit_layout).setVisibility(currentRoot == R.id.nav_recording
                && activity.findViewById(R.id.ui_preview_layout) != null ? View.VISIBLE : View.GONE);
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
