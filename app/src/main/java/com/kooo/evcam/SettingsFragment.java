package com.kooo.evcam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.kooo.evcam.ui.AppearanceSettingsFragment;
import com.kooo.evcam.ui.SettingsCatalog;
import com.kooo.evcam.ui.SettingsCatalog.Entry;
import com.kooo.evcam.ui.UiThemeController;

import java.util.HashMap;
import java.util.Map;

/**
 * SC 风格双栏设置页（Master-Detail）：
 * 左侧分类栏固定展示已实装分类，右侧容器原地呈现当前选中分类的详情页与二级子页。
 */
public class SettingsFragment extends Fragment implements UiThemeController.ThemeAware {
    private static final String ARG_INITIAL_CATEGORY = "initial_category";
    private static final String STATE_ACTIVE_CATEGORY = "active_category";

    private String activeCategoryId = SettingsCatalog.ID_APPEARANCE;
    private LinearLayout catalogContainer;
    private View sidebarView;
    private View paneDivider;
    private View detailContainer;
    private TextView sidebarTitle;

    private final Map<String, View> rowViews = new HashMap<>();
    private final Map<String, ImageView> iconViews = new HashMap<>();
    private final Map<String, TextView> titleViews = new HashMap<>();
    private final Map<String, TextView> chevronViews = new HashMap<>();

    public static SettingsFragment newInstance(@Nullable String initialCategoryId) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_INITIAL_CATEGORY, resolveValidCategoryId(initialCategoryId));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            activeCategoryId = resolveValidCategoryId(savedInstanceState.getString(STATE_ACTIVE_CATEGORY));
        } else if (getArguments() != null) {
            activeCategoryId = resolveValidCategoryId(getArguments().getString(ARG_INITIAL_CATEGORY));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        sidebarView = view.findViewById(R.id.settings_sidebar);
        paneDivider = view.findViewById(R.id.settings_pane_divider);
        detailContainer = view.findViewById(R.id.settings_detail_container);
        sidebarTitle = view.findViewById(R.id.tv_settings_sidebar_title);
        catalogContainer = view.findViewById(R.id.layout_settings_catalog);

        buildCatalogSidebar(inflater.getContext());
        applySidebarTheme(inflater.getContext());

        if (savedInstanceState == null
                || getChildFragmentManager().findFragmentById(R.id.settings_detail_container) == null) {
            showCategoryDetail(activeCategoryId);
        }
        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ACTIVE_CATEGORY, activeCategoryId);
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) return;
        applySidebarTheme(themedContext);
    }

    /** 切换当前高亮分类并加载对应的详情 Fragment。 */
    public void selectCategory(@NonNull String categoryId) {
        String resolved = resolveValidCategoryId(categoryId);
        activeCategoryId = resolved;
        if (getContext() != null) {
            applySidebarTheme(getContext());
        }
        if (isAdded()) {
            showCategoryDetail(resolved);
        }
    }

    /** 返回当前右侧详情容器正在显示的设置页或其二级子页（若已附加）。 */
    @Nullable
    public Fragment getActiveDetailFragment() {
        if (!isAdded()) return null;
        return getChildFragmentManager().findFragmentById(R.id.settings_detail_container);
    }

    /** 处理硬件返回键：若右侧详情区存在二级子页返回栈，优先回退一级。 */
    public boolean handleBackPressed() {
        if (isAdded() && getChildFragmentManager().getBackStackEntryCount() > 0) {
            getChildFragmentManager().popBackStack();
            return true;
        }
        return false;
    }

    /** 将二级子页推入设置详情容器（若非双栏宿主则回退到主容器）。 */
    public static void openSubPage(@NonNull Fragment caller, @NonNull Fragment target) {
        Fragment parent = caller.getParentFragment();
        if (parent instanceof SettingsFragment && parent.isAdded()) {
            FragmentTransaction tx = parent.getChildFragmentManager().beginTransaction();
            tx.replace(R.id.settings_detail_container, target);
            tx.addToBackStack(null);
            tx.commitAllowingStateLoss();
            return;
        }
        if (caller.getActivity() != null) {
            FragmentTransaction tx = caller.getActivity().getSupportFragmentManager().beginTransaction();
            tx.replace(R.id.fragment_container, target);
            tx.addToBackStack(null);
            tx.commitAllowingStateLoss();
        }
    }

    /** 从二级子页返回上一级设置页。 */
    public static void popSubPage(@NonNull Fragment caller) {
        Fragment parent = caller.getParentFragment();
        if (parent instanceof SettingsFragment && parent.isAdded()) {
            parent.getChildFragmentManager().popBackStack();
            return;
        }
        if (caller.getActivity() != null) {
            caller.getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    /**
     * 当一级分类页内嵌于双栏设置右侧详情容器时，统一隐藏返回/菜单/主页按钮并采用 SC 大标题风格。
     */
    public static void configureEmbeddedToolbar(@NonNull Fragment fragment, @Nullable View root,
                                                @NonNull String cleanTitle) {
        if (root == null || !(fragment.getParentFragment() instanceof SettingsFragment)) {
            return;
        }
        View toolbar = root.findViewById(R.id.toolbar);
        if (toolbar == null) return;
        Context ctx = root.getContext();
        toolbar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.page_background));
        toolbar.setPadding(dpStatic(ctx, 20), dpStatic(ctx, 18), dpStatic(ctx, 20), dpStatic(ctx, 10));

        hideButton(root.findViewById(R.id.btn_back));
        hideButton(root.findViewById(R.id.btn_menu));
        hideButton(root.findViewById(R.id.btn_home));

        if (toolbar instanceof ViewGroup) {
            TextView heading = findFirstHeadingText((ViewGroup) toolbar);
            if (heading != null) {
                heading.setText(cleanTitle);
                heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                heading.setTypeface(null, Typeface.BOLD);
                heading.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            }
        }
    }

    /** 在双栏详情容器中显示的二级子页，保留返回按钮并隐藏多余的菜单/主页按钮。 */
    public static void configureSubPageToolbar(@NonNull Fragment fragment, @Nullable View root) {
        if (root == null || !(fragment.getParentFragment() instanceof SettingsFragment)) {
            return;
        }
        hideButton(root.findViewById(R.id.btn_menu));
        hideButton(root.findViewById(R.id.btn_home));
        View toolbar = root.findViewById(R.id.toolbar);
        if (toolbar != null) {
            Context ctx = root.getContext();
            toolbar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.page_background));
            toolbar.setPadding(dpStatic(ctx, 16), dpStatic(ctx, 14), dpStatic(ctx, 20), dpStatic(ctx, 10));
        }
    }

    private static void hideButton(@Nullable View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    @Nullable
    private static TextView findFirstHeadingText(@NonNull ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && !(child instanceof android.widget.Button)) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView nested = findFirstHeadingText((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void buildCatalogSidebar(@NonNull Context context) {
        if (catalogContainer == null) return;
        catalogContainer.removeAllViews();
        rowViews.clear();
        iconViews.clear();
        titleViews.clear();
        chevronViews.clear();

        int rowHeight = dpStatic(context, 46);
        int rowGap = dpStatic(context, 6);
        int paddingH = dpStatic(context, 12);
        int iconSize = dpStatic(context, 18);
        int iconGap = dpStatic(context, 10);

        for (Entry entry : SettingsCatalog.getImplementedEntries()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.ui_nav_item);
            row.setPadding(paddingH, 0, paddingH, 0);
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            rowParams.bottomMargin = rowGap;
            row.setLayoutParams(rowParams);

            ImageView icon = new ImageView(context);
            icon.setImageResource(entry.iconResId);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.setMarginEnd(iconGap);
            icon.setLayoutParams(iconParams);

            TextView titleView = new TextView(context);
            titleView.setText(entry.displayTitle);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleView.setMaxLines(1);
            titleView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(context);
            chevron.setText("›");
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);

            row.addView(icon);
            row.addView(titleView);
            row.addView(chevron);
            row.setOnClickListener(v -> selectCategory(entry.id));

            catalogContainer.addView(row);
            rowViews.put(entry.id, row);
            iconViews.put(entry.id, icon);
            titleViews.put(entry.id, titleView);
            chevronViews.put(entry.id, chevron);
        }
    }

    private void applySidebarTheme(@NonNull Context context) {
        if (sidebarView != null) {
            sidebarView.setBackgroundColor(ContextCompat.getColor(context, R.color.nav_background));
        }
        if (paneDivider != null) {
            paneDivider.setBackgroundColor(ContextCompat.getColor(context, R.color.ui_border));
        }
        if (detailContainer != null) {
            detailContainer.setBackgroundColor(ContextCompat.getColor(context, R.color.page_background));
        }
        if (sidebarTitle != null) {
            sidebarTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        }

        int selectedAccent = ContextCompat.getColor(context, R.color.nav_icon_tint_selected);
        int selectedText = ContextCompat.getColor(context, R.color.ui_option_pill_selected_text);
        int normalText = ContextCompat.getColor(context, R.color.text_primary);
        int normalIcon = ContextCompat.getColor(context, R.color.text_secondary);

        for (Entry entry : SettingsCatalog.getImplementedEntries()) {
            boolean selected = entry.id.equals(activeCategoryId);
            View row = rowViews.get(entry.id);
            if (row != null) {
                Drawable bg = ContextCompat.getDrawable(context, R.drawable.ui_nav_item);
                if (bg != null) {
                    row.setBackground(bg);
                }
                row.setSelected(selected);
            }
            ImageView icon = iconViews.get(entry.id);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(selected ? selectedAccent : normalIcon));
            }
            TextView titleView = titleViews.get(entry.id);
            if (titleView != null) {
                titleView.setTextColor(selected ? selectedText : normalText);
                titleView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            TextView chevron = chevronViews.get(entry.id);
            if (chevron != null) {
                chevron.setTextColor(selected ? selectedText : normalIcon);
            }
        }
    }

    private void showCategoryDetail(@NonNull String categoryId) {
        FragmentManager fm = getChildFragmentManager();
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        Fragment target = createTargetFragment(categoryId);
        if (target == null) return;
        FragmentTransaction transaction = fm.beginTransaction();
        transaction.replace(R.id.settings_detail_container, target);
        transaction.commitAllowingStateLoss();
    }

    @NonNull
    private static String resolveValidCategoryId(@Nullable String categoryId) {
        return SettingsCatalog.findById(categoryId) != null
                ? categoryId
                : SettingsCatalog.ID_APPEARANCE;
    }

    /** 从内置分栏子页面跳转到指定设置分类。 */
    public static boolean navigateToCategory(@NonNull Fragment caller, @NonNull String categoryId) {
        Fragment parent = caller.getParentFragment();
        if (parent instanceof SettingsFragment) {
            ((SettingsFragment) parent).selectCategory(categoryId);
            return true;
        }
        return false;
    }

    @Nullable
    private Fragment createTargetFragment(@NonNull String entryId) {
        switch (entryId) {
            case SettingsCatalog.ID_APPEARANCE:
                return new AppearanceSettingsFragment();
            case SettingsCatalog.ID_RECORDER:
                return new RecorderSettingsFragment();
            case SettingsCatalog.ID_SURROUND_360:
                return new com.kooo.evcam.ui.SurroundSettingsFragment();
            case SettingsCatalog.ID_BLIND_SPOT:
                return new BlindSpotSettingsFragment();
            case SettingsCatalog.ID_SIGNAL_PRESET:
                return new com.kooo.evcam.ui.VehicleSignalPresetFragment();
            case SettingsCatalog.ID_FEISHU:
                return new FeishuFragment();
            case SettingsCatalog.ID_CONFIG_PLAZA:
                return new com.kooo.evcam.ui.ConfigPlazaFragment();
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

    private static int dpStatic(@NonNull Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
