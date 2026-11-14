package com.kooo.evcam.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.kooo.evcam.R;
import java.util.function.IntConsumer;

/** One navigation surface and one back hierarchy, independent of camera ownership. */
public final class AppShellController {
    private final FragmentActivity activity;
    private final FragmentManager fragments;
    private final View shell;
    private final TextView title;
    private final View back;
    private final Button preview;
    private final int[] destinations = {R.id.nav_recording, R.id.nav_playback,
            R.id.nav_photo_playback, R.id.nav_secondary_display, R.id.nav_feishu, R.id.nav_settings};
    private final int[] buttons = {R.id.ui_nav_recording, R.id.ui_nav_playback, R.id.ui_nav_photo,
            R.id.ui_nav_blind_spot, R.id.ui_nav_feishu, R.id.ui_nav_settings};
    private final String[] names = {"录制", "视频回放", "照片", "转向补盲", "飞书远程", "设置"};
    private int currentRoot = R.id.nav_recording;

    public AppShellController(FragmentActivity activity) {
        this.activity = activity;
        fragments = activity.getSupportFragmentManager();
        DrawerLayout drawer = activity.findViewById(R.id.drawer_layout);
        View content = drawer.getChildAt(0);
        drawer.removeView(content);
        shell = activity.getLayoutInflater().inflate(R.layout.ui_app_shell, drawer, false);
        ((FrameLayout) shell.findViewById(R.id.ui_content)).addView(content,
                new FrameLayout.LayoutParams(-1, -1));
        drawer.addView(shell, 0);
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        title = shell.findViewById(R.id.ui_page_title);
        back = shell.findViewById(R.id.ui_back);
        preview = shell.findViewById(R.id.ui_preview_toggle);
        ViewCompat.setOnApplyWindowInsetsListener(shell, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        fragments.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                    @NonNull View view, Bundle savedInstanceState) {
                normalizeHeader(view);
                updateHeader();
            }
        }, false);
        fragments.addOnBackStackChangedListener(this::updateHeader);
    }

    public void bind(IntConsumer navigate, Runnable onBack, Runnable togglePreview, Runnable chooseTheme, Runnable editLayout) {
        for (int i = 0; i < buttons.length; i++) {
            final int id = destinations[i];
            TextView item = shell.findViewById(buttons[i]);
            int iconSize = activity.getResources().getDimensionPixelSize(R.dimen.ui_nav_icon_size);
            android.graphics.drawable.Drawable[] icons = item.getCompoundDrawables();
            for (android.graphics.drawable.Drawable icon : icons) {
                if (icon != null) icon.setBounds(0, 0, iconSize, iconSize);
            }
            item.setCompoundDrawables(icons[0], icons[1], icons[2], icons[3]);
            item.setCompoundDrawableTintList(ContextCompat.getColorStateList(activity, R.color.ui_nav_text));
            item.setOnClickListener(v -> navigate.accept(id));
        }
        shell.findViewById(R.id.ui_edit_layout).setOnClickListener(v -> editLayout.run());
        back.setOnClickListener(v -> onBack.run());
        preview.setOnClickListener(v -> togglePreview.run());
        shell.findViewById(R.id.ui_theme).setOnClickListener(v -> chooseTheme.run());
        updateHeader();
    }

    public int getCurrentRoot() { return currentRoot; }

    public void restore(int destination) {
        currentRoot = destination;
        boolean recording = destination == R.id.nav_recording;
        activity.findViewById(R.id.main).setVisibility(recording ? View.VISIBLE : View.GONE);
        activity.findViewById(R.id.fragment_container).setVisibility(recording ? View.GONE : View.VISIBLE);
        updateHeader();
    }

    public void showRoot(int destination, Fragment next) {
        if (fragments.isStateSaved()) return;
        Fragment active = fragments.findFragmentById(R.id.fragment_container);
        boolean atRoot = fragments.getBackStackEntryCount() == 0;
        if (destination == currentRoot && atRoot && ((next == null && active == null)
                || (next != null && active != null && next.getClass() == active.getClass()))) {
            updateHeader();
            return;
        }
        fragments.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        currentRoot = destination;
        active = fragments.findFragmentById(R.id.fragment_container);
        androidx.fragment.app.FragmentTransaction transaction = fragments.beginTransaction().setReorderingAllowed(true);
        if (next != null) transaction.replace(R.id.fragment_container, next);
        else if (active != null) transaction.remove(active);
        transaction.commitNow();
        restore(destination);
    }

    public void setPreviewEnabled(boolean enabled) {
        preview.setText(enabled ? "关闭预览" : "开启预览");
        preview.setContentDescription(enabled ? "关闭主界面实时预览" : "开启主界面实时预览");
    }

    public void setLayoutEditing(boolean editing) {
        Button edit = shell.findViewById(R.id.ui_edit_layout);
        edit.setEnabled(!editing);
        edit.setText(editing ? "编辑中" : "编辑布局");
    }

    public void setRecordingState(String text, int color) {
        TextView status = activity.findViewById(R.id.ui_recording_status);
        if (status != null) { status.setText(text); status.setTextColor(ContextCompat.getColor(activity, color)); }
    }

    private String rootName() {
        for (int i = 0; i < destinations.length; i++) if (destinations[i] == currentRoot) return names[i];
        return "EVCam";
    }

    private void updateHeader() {
        for (int i = 0; i < buttons.length; i++) shell.findViewById(buttons[i]).setSelected(destinations[i] == currentRoot);
        boolean nested = fragments.getBackStackEntryCount() > 0;
        back.setVisibility(nested ? View.VISIBLE : View.GONE);
        preview.setVisibility(currentRoot == R.id.nav_recording ? View.VISIBLE : View.GONE);
        shell.findViewById(R.id.ui_edit_layout).setVisibility(currentRoot == R.id.nav_recording
                && activity.findViewById(R.id.ui_preview_layout) != null ? View.VISIBLE : View.GONE);
        String text = rootName();
        Fragment active = fragments.findFragmentById(R.id.fragment_container);
        if (active != null && active.getView() != null) {
            Object pageTitle = active.getView().getTag(R.id.ui_screen_title);
            if (nested && pageTitle instanceof String && !((String) pageTitle).isEmpty()) text = (String) pageTitle;
        }
        title.setText(text);
    }

    /** Keep page actions such as refresh; remove duplicate navigation and headings. */
    private void normalizeHeader(View root) {
        View toolbar = root.findViewById(R.id.toolbar);
        if (!(toolbar instanceof ViewGroup)) {
            View childBack = root.findViewById(R.id.btn_back);
            if (childBack != null && childBack.getParent() instanceof ViewGroup) toolbar = (View) childBack.getParent();
        }
        if (!(toolbar instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) toolbar;
        int remaining = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            int id = child.getId();
            if (id == R.id.btn_menu || id == R.id.btn_home || id == R.id.btn_back) {
                child.setVisibility(View.GONE);
            } else if (child instanceof TextView && !(child instanceof Button)
                    && id != R.id.current_datetime && root.getTag(R.id.ui_screen_title) == null) {
                root.setTag(R.id.ui_screen_title, ((TextView) child).getText().toString());
                child.setVisibility(View.GONE);
            } else if (child.getVisibility() != View.GONE) remaining++;
        }
        if (remaining == 0) group.setVisibility(View.GONE);
        else { group.setPadding(16, 0, 16, 8); }
    }
}
