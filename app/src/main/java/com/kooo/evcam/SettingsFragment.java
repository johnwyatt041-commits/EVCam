package com.kooo.evcam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.kooo.evcam.ui.AppearanceSettingsFragment;
import com.kooo.evcam.ui.SettingsCatalog;
import com.kooo.evcam.ui.SettingsCatalog.Entry;
import com.kooo.evcam.ui.UiThemeController;
import com.kooo.evcam.ui.UiThemeController.ThemeMode;

import java.util.HashMap;
import java.util.Map;

/**
 * SC 风格精简设置目录页：
 * 仅展示已实装可进入项（1. 外观与交互、3. 记录仪、5. 补盲、11. 飞书远程、13. 系统与权限、14. 软件更新、15. Debug 日志）。
 */
public class SettingsFragment extends Fragment implements UiThemeController.ThemeAware {
    private static final String TAG = "SettingsFragment";

    private LinearLayout catalogContainer;
    private final Map<String, TextView> titleViews = new HashMap<>();
    private final Map<String, TextView> subtitleViews = new HashMap<>();
    private final Map<String, TextView> arrowViews = new HashMap<>();
    private final Map<String, View> cardViews = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        catalogContainer = view.findViewById(R.id.layout_settings_catalog);
        buildCatalogRows(inflater.getContext());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            refreshDynamicContent(getContext());
        }
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) return;
        refreshDynamicContent(themedContext);
    }

    private void buildCatalogRows(@NonNull Context context) {
        if (catalogContainer == null) return;
        catalogContainer.removeAllViews();
        titleViews.clear();
        subtitleViews.clear();
        arrowViews.clear();
        cardViews.clear();

        int cardGap = dp(context, 12);
        int paddingH = dp(context, 18);
        int paddingV = dp(context, 16);

        for (Entry entry : SettingsCatalog.getImplementedEntries()) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.ui_card);
            card.setPadding(paddingH, paddingV, paddingH, paddingV);
            card.setClickable(true);
            card.setFocusable(true);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.bottomMargin = cardGap;
            card.setLayoutParams(cardParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            colParams.setMarginEnd(dp(context, 12));
            textColumn.setLayoutParams(colParams);

            TextView titleView = new TextView(context);
            titleView.setText(entry.title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
            titleView.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            textColumn.addView(titleView);

            TextView subtitleView = new TextView(context);
            subtitleView.setText(resolveEntrySubtitle(context, entry));
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitleView.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subParams.topMargin = dp(context, 4);
            subtitleView.setLayoutParams(subParams);
            textColumn.addView(subtitleView);

            TextView arrowView = new TextView(context);
            arrowView.setText("进入 →");
            arrowView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            arrowView.setTextColor(ContextCompat.getColor(context, R.color.button_text));
            arrowView.setBackgroundResource(R.drawable.ui_card);
            arrowView.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.button_background)));
            arrowView.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));

            card.addView(textColumn);
            card.addView(arrowView);
            card.setOnClickListener(v -> openEntry(entry));

            catalogContainer.addView(card);
            cardViews.put(entry.id, card);
            titleViews.put(entry.id, titleView);
            subtitleViews.put(entry.id, subtitleView);
            arrowViews.put(entry.id, arrowView);
        }
    }

    private void refreshDynamicContent(@NonNull Context context) {
        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textSecondary = ContextCompat.getColor(context, R.color.text_secondary);
        int buttonBg = ContextCompat.getColor(context, R.color.button_background);
        int buttonText = ContextCompat.getColor(context, R.color.button_text);

        for (Entry entry : SettingsCatalog.getImplementedEntries()) {
            View card = cardViews.get(entry.id);
            if (card != null) {
                card.setBackground(ContextCompat.getDrawable(context, R.drawable.ui_card));
            }
            TextView titleView = titleViews.get(entry.id);
            if (titleView != null) {
                titleView.setTextColor(textPrimary);
            }
            TextView subtitleView = subtitleViews.get(entry.id);
            if (subtitleView != null) {
                subtitleView.setTextColor(textSecondary);
                subtitleView.setText(resolveEntrySubtitle(context, entry));
            }
            TextView arrowView = arrowViews.get(entry.id);
            if (arrowView != null) {
                arrowView.setTextColor(buttonText);
                arrowView.setBackgroundTintList(ColorStateList.valueOf(buttonBg));
            }
        }
    }

    @NonNull
    private String resolveEntrySubtitle(@NonNull Context context, @NonNull Entry entry) {
        if (SettingsCatalog.ID_APPEARANCE.equals(entry.id)) {
            ThemeMode mode = UiThemeController.savedThemeMode(context);
            AppConfig config = new AppConfig(context);
            String startLabel = AppearanceSettingsFragment.formatStartPageLabel(config.getDefaultStartPage());
            return "当前主题：" + mode.label + " · 默认启动页：" + startLabel
                    + (config.isConfirmDoorExitEnabled() ? " · 已开开门确认" : "");
        }
        if (SettingsCatalog.ID_UPDATE.equals(entry.id)) {
            try {
                String version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
                return "当前版本：v" + version + "（点击查看与检查更新）";
            } catch (PackageManager.NameNotFoundException e) {
                AppLog.w(TAG, "Cannot read versionName", e);
            }
        }
        return entry.description;
    }

    private void openEntry(@NonNull Entry entry) {
        if (getActivity() == null) return;
        Fragment target = createTargetFragment(entry.id);
        if (target == null) return;

        Bundle args = target.getArguments() != null ? new Bundle(target.getArguments()) : new Bundle();
        args.putString("ui_screen_title", entry.title);
        target.setArguments(args);

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, target);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    @Nullable
    private Fragment createTargetFragment(@NonNull String entryId) {
        switch (entryId) {
            case SettingsCatalog.ID_APPEARANCE:
                return new AppearanceSettingsFragment();
            case SettingsCatalog.ID_RECORDER:
                return new RecorderSettingsFragment();
            case SettingsCatalog.ID_BLIND_SPOT:
                return new BlindSpotSettingsFragment();
            case SettingsCatalog.ID_FEISHU:
                return new FeishuFragment();
            case SettingsCatalog.ID_SYSTEM_PERMISSIONS:
                return new PermissionSettingsFragment();
            case SettingsCatalog.ID_UPDATE:
                return new UpdateSettingsFragment();
            case SettingsCatalog.ID_DEBUG_LOG:
                return new DebugLogSettingsFragment();
            default:
                return null;
        }
    }

    private int dp(@NonNull Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
