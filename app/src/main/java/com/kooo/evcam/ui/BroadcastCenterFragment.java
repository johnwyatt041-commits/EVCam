package com.kooo.evcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.external.ExternalBroadcastCatalog;
import com.kooo.evcam.external.ExternalBroadcastCatalog.BroadcastItem;
import com.kooo.evcam.external.ExternalBroadcastCatalog.Category;

import java.util.List;

/**
 * 6. 广播中心：展示所有外部可调用内置广播，支持分类筛选、关键词检索、一键复制与本机测试。
 */
public class BroadcastCenterFragment extends Fragment
        implements UiThemeController.ThemeAware, UiScaleController.ScaleAware {

    private TextView tvPackageName;
    private Button btnCopyPackageName;
    private Button btnCopyAll;
    private Button btnFilterAll;
    private Button btnFilterForeground;
    private Button btnFilterRecording;
    private Button btnFilterPreview;
    private Button btnFilterSystem;
    private EditText etSearch;
    private LinearLayout layoutItems;
    private TextView tvEmpty;

    @Nullable
    private Category activeCategory = null;
    private String searchKeyword = "";
    private String resolvedPackageName = ExternalBroadcastCatalog.DEFAULT_PACKAGE_NAME;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_broadcast_center, container, false);
        Context ctx = inflater.getContext();
        resolvedPackageName = ExternalBroadcastCatalog.resolvePackageName(ctx.getPackageName());

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }

        bindViews(view);
        wireListeners();
        refreshUi(ctx);
        SettingsFragment.configureEmbeddedToolbar(this, view, "广播中心");
        return view;
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) {
            return;
        }
        refreshUi(themedContext);
    }

    @Override
    public void onUiScaleApplied(float scaleFactor) {
        if (getContext() == null || layoutItems == null) {
            return;
        }
        UiScaleHelper.applyScaleToViewTree(layoutItems, scaleFactor);
    }

    private void bindViews(@NonNull View root) {
        tvPackageName = root.findViewById(R.id.tv_broadcast_package_name);
        btnCopyPackageName = root.findViewById(R.id.btn_copy_package_name);
        btnCopyAll = root.findViewById(R.id.btn_copy_all_broadcasts);
        btnFilterAll = root.findViewById(R.id.btn_broadcast_filter_all);
        btnFilterForeground = root.findViewById(R.id.btn_broadcast_filter_foreground);
        btnFilterRecording = root.findViewById(R.id.btn_broadcast_filter_recording);
        btnFilterPreview = root.findViewById(R.id.btn_broadcast_filter_preview);
        btnFilterSystem = root.findViewById(R.id.btn_broadcast_filter_system);
        etSearch = root.findViewById(R.id.et_broadcast_search);
        layoutItems = root.findViewById(R.id.layout_broadcast_items);
        tvEmpty = root.findViewById(R.id.tv_broadcast_empty);

        if (btnCopyPackageName != null) {
            btnCopyPackageName.setTag(R.id.ui_theme_text_role, "on_accent");
        }
    }

    private void wireListeners() {
        View.OnClickListener copyPackageListener = v -> copyTextToClipboard(
                "EVCam Package Name",
                resolvedPackageName,
                "已复制目标包名：" + resolvedPackageName);
        if (tvPackageName != null) {
            tvPackageName.setOnClickListener(copyPackageListener);
        }
        if (btnCopyPackageName != null) {
            btnCopyPackageName.setOnClickListener(copyPackageListener);
        }
        if (btnCopyAll != null) {
            btnCopyAll.setOnClickListener(v -> copyTextToClipboard(
                    "EVCam Broadcast Catalog",
                    ExternalBroadcastCatalog.formatFullCatalogExport(resolvedPackageName),
                    "已复制全部 " + ExternalBroadcastCatalog.getAllItems().size() + " 条内置广播清单"));
        }

        btnFilterAll.setOnClickListener(v -> selectCategory(null));
        btnFilterForeground.setOnClickListener(v -> selectCategory(Category.FOREGROUND));
        btnFilterRecording.setOnClickListener(v -> selectCategory(Category.RECORDING));
        btnFilterPreview.setOnClickListener(v -> selectCategory(Category.PREVIEW_MODE));
        btnFilterSystem.setOnClickListener(v -> selectCategory(Category.SYSTEM));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchKeyword = s != null ? s.toString().trim() : "";
                if (getContext() != null) {
                    renderCards(getContext());
                }
            }
        });
    }

    private void selectCategory(@Nullable Category category) {
        activeCategory = category;
        if (getContext() != null) {
            refreshFilterPills(getContext());
            renderCards(getContext());
        }
    }

    private void refreshUi(@NonNull Context ctx) {
        if (tvPackageName != null) {
            tvPackageName.setText("目标包名：" + resolvedPackageName + "（点击复制）");
            tvPackageName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            styleCodeBox(tvPackageName, ctx, 12, 8);
        }
        refreshFilterPills(ctx);
        renderCards(ctx);
    }

    private void refreshFilterPills(@NonNull Context ctx) {
        applyOptionPill(btnFilterAll, ctx, activeCategory == null);
        applyOptionPill(btnFilterForeground, ctx, activeCategory == Category.FOREGROUND);
        applyOptionPill(btnFilterRecording, ctx, activeCategory == Category.RECORDING);
        applyOptionPill(btnFilterPreview, ctx, activeCategory == Category.PREVIEW_MODE);
        applyOptionPill(btnFilterSystem, ctx, activeCategory == Category.SYSTEM);
    }

    private void renderCards(@NonNull Context ctx) {
        layoutItems.removeAllViews();
        List<BroadcastItem> filtered = ExternalBroadcastCatalog.filter(activeCategory, searchKeyword);
        if (filtered.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (BroadcastItem item : filtered) {
            layoutItems.addView(buildBroadcastCard(ctx, item, resolvedPackageName));
        }
    }

    @NonNull
    private View buildBroadcastCard(@NonNull Context ctx,
                                    @NonNull BroadcastItem item,
                                    @NonNull String packageName) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ui_card);
        int pad = dp(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(cardParams);

        // 标题行 + 分类标签 + 防抖标签
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(item.title);
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvCategoryBadge = buildBadge(
                ctx,
                item.category.getLabel(),
                R.color.ui_accent_soft,
                R.color.ui_option_pill_selected_stroke,
                R.color.ui_option_pill_selected_text);

        TextView tvDebounceBadge = buildBadge(
                ctx,
                item.debounceLabel,
                R.color.input_background,
                R.color.ui_border,
                R.color.text_secondary);
        LinearLayout.LayoutParams debounceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        debounceLp.setMarginStart(dp(ctx, 6));
        tvDebounceBadge.setLayoutParams(debounceLp);

        headerRow.addView(tvTitle);
        headerRow.addView(tvCategoryBadge);
        headerRow.addView(tvDebounceBadge);
        card.addView(headerRow);

        // Action 展示框（点击可直接复制 Action）
        TextView tvAction = new TextView(ctx);
        tvAction.setText(item.action);
        tvAction.setTypeface(Typeface.MONOSPACE);
        tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvAction.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvAction.setClickable(true);
        tvAction.setFocusable(true);
        styleCodeBox(tvAction, ctx, 10, 8);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(ctx, 8);
        tvAction.setLayoutParams(actionLp);
        tvAction.setOnClickListener(v -> copyTextToClipboard(
                "EVCam Broadcast Action",
                item.action,
                "已复制 Action：" + item.action));
        card.addView(tvAction);

        // 行为说明
        TextView tvDesc = new TextView(ctx);
        tvDesc.setText(item.description);
        tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(ctx, 8);
        tvDesc.setLayoutParams(descLp);
        card.addView(tvDesc);

        // 可选 Extra 说明
        if (!item.extrasHint.isEmpty()) {
            TextView tvExtras = new TextView(ctx);
            tvExtras.setText("参数说明：" + item.extrasHint);
            tvExtras.setTextColor(ContextCompat.getColor(ctx, R.color.button_accent));
            tvExtras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams extrasLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            extrasLp.topMargin = dp(ctx, 6);
            tvExtras.setLayoutParams(extrasLp);
            card.addView(tvExtras);
        }

        // 操作按钮行
        HorizontalScrollView actionsScroll = new HorizontalScrollView(ctx);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(ctx, 10);
        actionsScroll.setLayoutParams(scrollLp);

        LinearLayout actionsRow = new LinearLayout(ctx);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnCopyAction = buildActionButton(ctx, "复制 Action", true, 0);
        btnCopyAction.setOnClickListener(v -> copyTextToClipboard(
                "EVCam Broadcast Action",
                item.action,
                "已复制 Action：" + item.action));

        Button btnCopySnippet = buildActionButton(ctx, "复制配置", false, 8);
        btnCopySnippet.setOnClickListener(v -> copyTextToClipboard(
                "EVCam Broadcast Config",
                ExternalBroadcastCatalog.formatConfigSnippet(item, packageName),
                "已复制「" + item.title + "」完整配置"));

        Button btnCopyAdb = buildActionButton(ctx, "复制 ADB 命令", false, 8);
        btnCopyAdb.setOnClickListener(v -> copyTextToClipboard(
                "EVCam Broadcast ADB",
                ExternalBroadcastCatalog.formatAdbCommand(item, packageName),
                "已复制 ADB 广播命令"));

        Button btnTest = buildActionButton(ctx, "本机测试", false, 8);
        btnTest.setOnClickListener(v -> sendLocalTestBroadcast(item, packageName));

        actionsRow.addView(btnCopyAction);
        actionsRow.addView(btnCopySnippet);
        actionsRow.addView(btnCopyAdb);
        actionsRow.addView(btnTest);

        UiScaleHelper.applyScaleToViewTree(actionsRow, UiScaleController.getSavedScaleFactor(ctx));
        actionsScroll.addView(actionsRow);
        card.addView(actionsScroll);
        return card;
    }

    @NonNull
    private TextView buildBadge(@NonNull Context ctx,
                                @NonNull String text,
                                int bgColorRes,
                                int strokeColorRes,
                                int textColorRes) {
        TextView badge = new TextView(ctx);
        badge.setText(text);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTextColor(ContextCompat.getColor(ctx, textColorRes));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 6));
        bg.setColor(ContextCompat.getColor(ctx, bgColorRes));
        bg.setStroke(1, ContextCompat.getColor(ctx, strokeColorRes));
        badge.setBackground(bg);
        badge.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        return badge;
    }

    @NonNull
    private Button buildActionButton(@NonNull Context ctx,
                                     @NonNull String label,
                                     boolean accent,
                                     int marginStartDp) {
        Button button = new Button(ctx);
        button.setText(label);
        button.setAllCaps(false);
        button.setBackgroundTintList(ContextCompat.getColorStateList(
                ctx, accent ? R.color.button_accent : R.color.button_background));
        button.setTextColor(ContextCompat.getColor(
                ctx, accent ? R.color.ui_on_accent : R.color.button_text));
        if (accent) {
            button.setTag(R.id.ui_theme_text_role, "on_accent");
        }
        if (marginStartDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(ctx, marginStartDp));
            button.setLayoutParams(lp);
        }
        return button;
    }

    private void styleCodeBox(@NonNull TextView tv, @NonNull Context ctx, int padHDp, int padVDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setColor(ContextCompat.getColor(ctx, R.color.input_background));
        bg.setStroke(1, ContextCompat.getColor(ctx, R.color.ui_border));
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, padHDp), dp(ctx, padVDp), dp(ctx, padHDp), dp(ctx, padVDp));
    }

    private void sendLocalTestBroadcast(@NonNull BroadcastItem item, @NonNull String packageName) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(item.action);
        intent.setPackage(packageName);
        ctx.sendBroadcast(intent);
        Toast.makeText(ctx, "已发送广播：" + item.title, Toast.LENGTH_SHORT).show();
    }

    private void copyTextToClipboard(@NonNull String label,
                                     @NonNull String text,
                                     @NonNull String toastMessage) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(ctx, toastMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyOptionPill(@Nullable Button button, @NonNull Context context, boolean selected) {
        if (button == null) {
            return;
        }
        int fillColor = ContextCompat.getColor(context, selected ? R.color.ui_accent_soft : R.color.input_background);
        int strokeColor = ContextCompat.getColor(context, selected ? R.color.ui_option_pill_selected_stroke : R.color.ui_border);
        int textColor = ContextCompat.getColor(context, selected ? R.color.ui_option_pill_selected_text : R.color.text_primary);
        int strokeWidthPx = Math.max(1, dp(context, 1));
        float radiusPx = dp(context, 8);

        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setColor(fillColor);
        pill.setStroke(strokeWidthPx, strokeColor);
        pill.setCornerRadius(radiusPx);

        int pl = button.getPaddingLeft();
        int pt = button.getPaddingTop();
        int pr = button.getPaddingRight();
        int pb = button.getPaddingBottom();
        ViewCompat.setBackgroundTintList(button, null);
        button.setBackground(pill);
        button.setPadding(pl, pt, pr, pb);
        button.setTextColor(textColor);
        button.setTag(R.id.ui_theme_bg_role, null);
        button.setTag(R.id.ui_theme_text_role, null);
        button.setSelected(selected);
    }

    private static int dp(@NonNull Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }
}
