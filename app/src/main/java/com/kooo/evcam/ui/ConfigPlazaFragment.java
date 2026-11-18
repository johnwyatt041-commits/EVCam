package com.kooo.evcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.configplaza.ConfigPlazaRepository;
import com.kooo.evcam.configplaza.E5ConfigApplier;
import com.kooo.evcam.configplaza.E5ConfigSchema;
import com.kooo.evcam.configplaza.E5ConfigSection;
import com.kooo.evcam.configplaza.E5ConfigSnapshot;
import com.kooo.evcam.vehicle.VehicleAutomationCoordinator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 12. 银河 E5 离线配置广场（无云端依赖、白名单字段校验、分区导入导出与原子回滚）。
 */
public class ConfigPlazaFragment extends Fragment implements UiThemeController.ThemeAware {

    private enum FilterTab {
        ALL,
        BUILTIN,
        LOCAL
    }

    private ConfigPlazaRepository repository;
    private AppConfig appConfig;

    private TextView tvBaselineInfo;
    private TextView tvBackupStatus;
    private Button btnRestoreBackup;
    private Button btnFilterAll;
    private Button btnFilterBuiltin;
    private Button btnFilterLocal;
    private EditText etSearch;
    private LinearLayout layoutItems;
    private TextView tvEmpty;

    private FilterTab activeFilter = FilterTab.ALL;
    private String searchKeyword = "";
    @Nullable
    private E5ConfigSnapshot pendingExportSnapshot;

    private final ActivityResultLauncher<String[]> importJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || getContext() == null || repository == null) {
                    return;
                }
                E5ConfigSchema.ValidationResult result = repository.parseFromUri(uri);
                if (!result.valid || result.snapshot == null) {
                    showErrorDialog("配置包校验失败", result.errorMessage != null ? result.errorMessage : "未知格式错误");
                    return;
                }
                showApplyPreviewDialog(result.snapshot, true);
            });

    private final ActivityResultLauncher<String> exportJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null || getContext() == null || repository == null || pendingExportSnapshot == null) {
                    return;
                }
                boolean ok = repository.writeSnapshotToUri(uri, pendingExportSnapshot);
                Toast.makeText(getContext(), ok ? "配置已导出为 JSON 文件" : "导出 JSON 文件失败",
                        Toast.LENGTH_SHORT).show();
                pendingExportSnapshot = null;
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_config_plaza, container, false);
        Context ctx = inflater.getContext();
        repository = new ConfigPlazaRepository(ctx);
        appConfig = new AppConfig(ctx);

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }

        bindViews(view);
        wireListeners(view);
        refreshAllUi(ctx);
        SettingsFragment.configureEmbeddedToolbar(this, view, "配置广场");
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            refreshAllUi(getContext());
        }
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) {
            return;
        }
        refreshAllUi(themedContext);
    }

    private void bindViews(@NonNull View root) {
        tvBaselineInfo = root.findViewById(R.id.tv_plaza_baseline_info);
        tvBackupStatus = root.findViewById(R.id.tv_backup_status);
        btnRestoreBackup = root.findViewById(R.id.btn_restore_last_backup);
        btnFilterAll = root.findViewById(R.id.btn_filter_all);
        btnFilterBuiltin = root.findViewById(R.id.btn_filter_builtin);
        btnFilterLocal = root.findViewById(R.id.btn_filter_local);
        etSearch = root.findViewById(R.id.et_plaza_search);
        layoutItems = root.findViewById(R.id.layout_plaza_items);
        tvEmpty = root.findViewById(R.id.tv_plaza_empty);
    }

    private void wireListeners(@NonNull View root) {
        btnRestoreBackup.setOnClickListener(v -> confirmRestoreLastBackup());
        root.findViewById(R.id.btn_save_current_snapshot).setOnClickListener(v -> showExportOrSaveDialog(false, false));
        root.findViewById(R.id.btn_export_json_file).setOnClickListener(v -> showExportOrSaveDialog(false, true));
        root.findViewById(R.id.btn_copy_json_clipboard).setOnClickListener(v -> showExportOrSaveDialog(true, false));
        root.findViewById(R.id.btn_import_json_file).setOnClickListener(
                v -> importJsonLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}));
        root.findViewById(R.id.btn_import_json_paste).setOnClickListener(v -> showPasteImportDialog());

        btnFilterAll.setOnClickListener(v -> selectFilter(FilterTab.ALL));
        btnFilterBuiltin.setOnClickListener(v -> selectFilter(FilterTab.BUILTIN));
        btnFilterLocal.setOnClickListener(v -> selectFilter(FilterTab.LOCAL));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchKeyword = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                if (getContext() != null) {
                    renderCards(getContext());
                }
            }
        });
    }

    private void selectFilter(@NonNull FilterTab tab) {
        activeFilter = tab;
        if (getContext() != null) {
            refreshFilterPills(getContext());
            renderCards(getContext());
        }
    }

    private void refreshAllUi(@NonNull Context ctx) {
        boolean isE5 = repository.getTarget().isE5CarModel();
        if (isE5) {
            tvBaselineInfo.setText("当前车型：银河 E5（" + repository.getTarget().getCurrentCarModel()
                    + "） · 全离线白名单校验，零云端请求");
            tvBaselineInfo.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        } else {
            tvBaselineInfo.setText("提示：当前车型为「" + repository.getTarget().getCurrentCarModel()
                    + "」，配置广场仅支持导出银河 E5 配置，应用内置预设可切回 E5 基准");
            tvBaselineInfo.setTextColor(ContextCompat.getColor(ctx, R.color.ui_warning));
        }

        E5ConfigApplier.BackupRecord backup = repository.loadLastBackup();
        if (backup != null) {
            tvBackupStatus.setText("最近自动备份：" + backup.backupCreatedAt
                    + "（覆盖来源：「" + backup.appliedSourceName + "」）");
            btnRestoreBackup.setEnabled(true);
            btnRestoreBackup.setAlpha(1.0f);
        } else {
            tvBackupStatus.setText("尚无自动备份记录（每次应用配置前会自动保存当前 E5 配置快照）");
            btnRestoreBackup.setEnabled(false);
            btnRestoreBackup.setAlpha(0.5f);
        }

        refreshFilterPills(ctx);
        renderCards(ctx);
    }

    private void refreshFilterPills(@NonNull Context ctx) {
        applyOptionPill(btnFilterAll, ctx, activeFilter == FilterTab.ALL);
        applyOptionPill(btnFilterBuiltin, ctx, activeFilter == FilterTab.BUILTIN);
        applyOptionPill(btnFilterLocal, ctx, activeFilter == FilterTab.LOCAL);
    }

    private void renderCards(@NonNull Context ctx) {
        layoutItems.removeAllViews();

        List<E5ConfigSnapshot> all = new ArrayList<>();
        if (activeFilter == FilterTab.ALL || activeFilter == FilterTab.BUILTIN) {
            all.addAll(repository.loadBuiltinPresets());
        }
        if (activeFilter == FilterTab.ALL || activeFilter == FilterTab.LOCAL) {
            all.addAll(repository.loadLocalSnapshots());
        }

        List<E5ConfigSnapshot> filtered = new ArrayList<>();
        for (E5ConfigSnapshot item : all) {
            if (matchesKeyword(item)) {
                filtered.add(item);
            }
        }

        if (filtered.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        for (E5ConfigSnapshot snapshot : filtered) {
            layoutItems.addView(buildSnapshotCard(ctx, snapshot));
        }
    }

    private boolean matchesKeyword(@NonNull E5ConfigSnapshot item) {
        if (searchKeyword.isEmpty()) {
            return true;
        }
        String hay = (item.name + " " + item.description + " " + item.authorNote).toLowerCase(Locale.ROOT);
        if (hay.contains(searchKeyword)) {
            return true;
        }
        for (E5ConfigSection sec : item.getSectionSet()) {
            if (sec.getLabel().toLowerCase(Locale.ROOT).contains(searchKeyword)
                    || sec.getId().toLowerCase(Locale.ROOT).contains(searchKeyword)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private View buildSnapshotCard(@NonNull Context ctx, @NonNull E5ConfigSnapshot snapshot) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ui_card);
        int pad = dp(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(cardParams);

        // 标题行 + 来源标签
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(snapshot.name);
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean isBuiltin = E5ConfigSnapshot.SOURCE_BUILTIN.equals(snapshot.sourceType);
        TextView tvBadge = new TextView(ctx);
        tvBadge.setText(isBuiltin ? "官方 E5 预设" : "本机快照");
        tvBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvBadge.setTextColor(ContextCompat.getColor(ctx,
                isBuiltin ? R.color.ui_option_pill_selected_text : R.color.text_secondary));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(ctx, 6));
        badgeBg.setColor(ContextCompat.getColor(ctx, isBuiltin ? R.color.ui_accent_soft : R.color.input_background));
        badgeBg.setStroke(1, ContextCompat.getColor(ctx,
                isBuiltin ? R.color.ui_option_pill_selected_stroke : R.color.ui_border));
        tvBadge.setBackground(badgeBg);
        tvBadge.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));

        headerRow.addView(tvTitle);
        headerRow.addView(tvBadge);
        card.addView(headerRow);

        // 说明文字
        if (!snapshot.description.isEmpty()) {
            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(snapshot.description);
            tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descParams.topMargin = dp(ctx, 6);
            tvDesc.setLayoutParams(descParams);
            card.addView(tvDesc);
        }

        // 包含分区摘要 + 元信息
        StringBuilder secSummary = new StringBuilder("包含分区：");
        int idx = 0;
        for (E5ConfigSection sec : snapshot.getSectionSet()) {
            if (idx++ > 0) secSummary.append(" · ");
            secSummary.append(sec.getLabel());
        }
        if (!snapshot.createdAt.isEmpty()) {
            secSummary.append("  |  ").append(snapshot.createdAt);
        }
        TextView tvMeta = new TextView(ctx);
        tvMeta.setText(secSummary.toString());
        tvMeta.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(ctx, 6);
        tvMeta.setLayoutParams(metaParams);
        card.addView(tvMeta);

        // 操作按钮行（包入 HorizontalScrollView，避免大号缩放或长按钮截断）
        HorizontalScrollView actionsScroll = new HorizontalScrollView(ctx);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(ctx, 10);
        actionsScroll.setLayoutParams(scrollParams);

        LinearLayout actionsRow = new LinearLayout(ctx);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnApply = new Button(ctx);
        btnApply.setText("查看与分区应用");
        btnApply.setAllCaps(false);
        btnApply.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.button_accent));
        btnApply.setTextColor(ContextCompat.getColor(ctx, R.color.ui_on_accent));
        btnApply.setOnClickListener(v -> showApplyPreviewDialog(snapshot, false));

        Button btnExport = new Button(ctx);
        btnExport.setText("导出 JSON");
        btnExport.setAllCaps(false);
        btnExport.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.button_background));
        btnExport.setTextColor(ContextCompat.getColor(ctx, R.color.button_text));
        LinearLayout.LayoutParams expParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        expParams.setMarginStart(dp(ctx, 8));
        btnExport.setLayoutParams(expParams);
        btnExport.setOnClickListener(v -> launchExportSnapshot(snapshot));

        Button btnCopy = new Button(ctx);
        btnCopy.setText("复制");
        btnCopy.setAllCaps(false);
        btnCopy.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.button_background));
        btnCopy.setTextColor(ContextCompat.getColor(ctx, R.color.button_text));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.setMarginStart(dp(ctx, 8));
        btnCopy.setLayoutParams(copyParams);
        btnCopy.setOnClickListener(v -> copyJsonToClipboard(snapshot.toJsonString(true)));

        actionsRow.addView(btnApply);
        actionsRow.addView(btnExport);
        actionsRow.addView(btnCopy);

        if (!isBuiltin) {
            Button btnDelete = new Button(ctx);
            btnDelete.setText("删除");
            btnDelete.setAllCaps(false);
            btnDelete.setBackgroundTintList(ContextCompat.getColorStateList(ctx, R.color.button_background));
            btnDelete.setTextColor(ContextCompat.getColor(ctx, R.color.ui_danger));
            LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delParams.setMarginStart(dp(ctx, 8));
            btnDelete.setLayoutParams(delParams);
            btnDelete.setOnClickListener(v -> confirmDeleteSnapshot(snapshot));
            actionsRow.addView(btnDelete);
        }

        UiScaleHelper.applyScaleToViewTree(actionsRow, UiScaleController.getSavedScaleFactor(ctx));
        actionsScroll.addView(actionsRow);
        card.addView(actionsScroll);
        return card;
    }

    private void showExportOrSaveDialog(boolean copyClipboardOnly, boolean exportToFile) {
        if (getContext() == null) return;
        Context ctx = getContext();
        if (!repository.getTarget().isE5CarModel()) {
            showErrorDialog("仅支持银河 E5", "当前车型为「" + repository.getTarget().getCurrentCarModel()
                    + "」，配置广场仅支持导出银河 E5（galaxy_e5）配置。");
            return;
        }

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 18);
        container.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 8));

        EditText etName = new EditText(ctx);
        etName.setHint("配置名称（例如：E5 夜间通勤优化）");
        etName.setText("我的银河 E5 配置");
        etName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        etName.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        etName.setBackgroundResource(R.drawable.edit_text_background);
        etName.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        container.addView(etName);

        EditText etDesc = new EditText(ctx);
        etDesc.setHint("配置说明（选填）");
        etDesc.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        etDesc.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        etDesc.setBackgroundResource(R.drawable.edit_text_background);
        etDesc.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(ctx, 8);
        etDesc.setLayoutParams(descLp);
        container.addView(etDesc);

        TextView tvSecTitle = new TextView(ctx);
        tvSecTitle.setText("选择要包含的配置分区（已自动排除飞书凭据与私密路径）");
        tvSecTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvSecTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams secTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secTitleLp.topMargin = dp(ctx, 12);
        secTitleLp.bottomMargin = dp(ctx, 4);
        tvSecTitle.setLayoutParams(secTitleLp);
        container.addView(tvSecTitle);

        Map<E5ConfigSection, CheckBox> checks = new LinkedHashMap<>();
        for (E5ConfigSection sec : E5ConfigSection.values()) {
            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(true);
            cb.setText(sec.getLabel() + " — " + sec.getDescription());
            cb.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            container.addView(cb);
            checks.put(sec, cb);
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(container);

        String title = copyClipboardOnly ? "复制当前配置 JSON" : (exportToFile ? "导出配置 JSON 文件" : "保存当前配置为本机快照");
        String posText = copyClipboardOnly ? "复制 JSON" : (exportToFile ? "选择导出路径" : "保存快照");

        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(posText, (d, w) -> {
                    Set<E5ConfigSection> selected = collectCheckedSections(checks);
                    if (selected.isEmpty()) {
                        Toast.makeText(ctx, "请至少选择一个配置分区", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = etName.getText().toString().trim();
                    String desc = etDesc.getText().toString().trim();
                    if (copyClipboardOnly || exportToFile) {
                        E5ConfigApplier.ExportResult exp = repository.getApplier().exportCurrent(
                                "export_" + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date()),
                                name.isEmpty() ? "银河 E5 配置" : name,
                                desc,
                                "银河 E5 本地导出",
                                ConfigPlazaRepository.nowIso(),
                                selected
                        );
                        if (!exp.success || exp.snapshot == null) {
                            showErrorDialog("导出失败", exp.errorMessage != null ? exp.errorMessage : "校验未通过");
                            return;
                        }
                        if (copyClipboardOnly) {
                            copyJsonToClipboard(exp.snapshot.toJsonString(true));
                        } else {
                            launchExportSnapshot(exp.snapshot);
                        }
                    } else {
                        E5ConfigApplier.ExportResult res = repository.saveCurrentAsSnapshot(name, desc, selected);
                        if (!res.success) {
                            showErrorDialog("保存快照失败", res.errorMessage != null ? res.errorMessage : "未知错误");
                            return;
                        }
                        Toast.makeText(ctx, "已保存本机配置快照", Toast.LENGTH_SHORT).show();
                        refreshAllUi(ctx);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPasteImportDialog() {
        if (getContext() == null) return;
        Context ctx = getContext();

        EditText etJson = new EditText(ctx);
        etJson.setHint("请粘贴银河 E5 配置 JSON 内容（schemaVersion=1, vehicleModel=galaxy_e5）");
        etJson.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etJson.setMinLines(6);
        etJson.setMaxLines(12);
        etJson.setGravity(Gravity.TOP | Gravity.START);
        etJson.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        etJson.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        etJson.setBackgroundResource(R.drawable.edit_text_background);
        int pad = dp(ctx, 12);
        etJson.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("粘贴 JSON 导入 E5 配置")
                .setView(etJson)
                .setPositiveButton("校验并预览", (d, w) -> {
                    String raw = etJson.getText() != null ? etJson.getText().toString() : "";
                    E5ConfigSchema.ValidationResult result = E5ConfigSchema.parseAndValidate(raw);
                    if (!result.valid || result.snapshot == null) {
                        showErrorDialog("配置校验未通过",
                                result.errorMessage != null ? result.errorMessage : "JSON 格式非法");
                        return;
                    }
                    showApplyPreviewDialog(result.snapshot, true);
                })
                .setNeutralButton("从剪贴板读取", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    String pasted = "";
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                        if (cs != null) pasted = cs.toString();
                    }
                    E5ConfigSchema.ValidationResult result = E5ConfigSchema.parseAndValidate(pasted);
                    if (!result.valid || result.snapshot == null) {
                        showErrorDialog("剪贴板配置校验未通过",
                                result.errorMessage != null ? result.errorMessage : "剪贴板无有效 JSON");
                        return;
                    }
                    showApplyPreviewDialog(result.snapshot, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showApplyPreviewDialog(@NonNull E5ConfigSnapshot snapshot, boolean offerSaveToLocal) {
        if (getContext() == null) return;
        Context ctx = getContext();

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 8));

        TextView tvInfo = new TextView(ctx);
        StringBuilder info = new StringBuilder();
        info.append("适用车型：银河 E5（").append(snapshot.vehicleModel).append("）");
        if (!snapshot.authorNote.isEmpty()) {
            info.append("\n来源备注：").append(snapshot.authorNote);
        }
        if (!snapshot.description.isEmpty()) {
            info.append("\n说明：").append(snapshot.description);
        }
        tvInfo.setText(info.toString());
        tvInfo.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        container.addView(tvInfo);

        List<String> camWarnings = repository.checkCameraCompatibility(snapshot);
        if (!camWarnings.isEmpty()) {
            TextView tvWarn = new TextView(ctx);
            StringBuilder warnSb = new StringBuilder("摄像头兼容性提示：");
            for (String w : camWarnings) {
                warnSb.append("\n• ").append(w);
            }
            tvWarn.setText(warnSb.toString());
            tvWarn.setTextColor(ContextCompat.getColor(ctx, R.color.ui_warning));
            tvWarn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wLp.topMargin = dp(ctx, 8);
            tvWarn.setLayoutParams(wLp);
            container.addView(tvWarn);
        }

        TextView tvChoose = new TextView(ctx);
        tvChoose.setText("选择要应用的分区（应用前将自动原子备份当前配置）：");
        tvChoose.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvChoose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseLp.topMargin = dp(ctx, 12);
        chooseLp.bottomMargin = dp(ctx, 4);
        tvChoose.setLayoutParams(chooseLp);
        container.addView(tvChoose);

        Map<E5ConfigSection, CheckBox> checks = new LinkedHashMap<>();
        for (E5ConfigSection sec : E5ConfigSection.values()) {
            if (snapshot.getSection(sec) == null) {
                continue;
            }
            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(true);
            cb.setText(sec.getLabel() + " — " + sec.getDescription());
            cb.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            container.addView(cb);
            checks.put(sec, cb);
        }

        final CheckBox cbSaveCopy;
        if (offerSaveToLocal) {
            cbSaveCopy = new CheckBox(ctx);
            cbSaveCopy.setChecked(true);
            cbSaveCopy.setText("同时保存到本机「配置广场」快照列表");
            cbSaveCopy.setTextColor(ContextCompat.getColor(ctx, R.color.button_accent));
            cbSaveCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.topMargin = dp(ctx, 10);
            cbSaveCopy.setLayoutParams(saveLp);
            container.addView(cbSaveCopy);
        } else {
            cbSaveCopy = null;
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(container);

        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(snapshot.name)
                .setView(scroll)
                .setPositiveButton("确认应用", (d, w) -> {
                    Set<E5ConfigSection> selected = collectCheckedSections(checks);
                    if (selected.isEmpty()) {
                        Toast.makeText(ctx, "请至少选择一个要应用的分区", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (cbSaveCopy != null && cbSaveCopy.isChecked()) {
                        repository.saveSnapshotCopy(snapshot, snapshot.name, E5ConfigSnapshot.SOURCE_IMPORTED);
                    }
                    E5ConfigApplier.ApplyResult applyRes = repository.getApplier().applySnapshot(
                            snapshot, selected, ConfigPlazaRepository.nowIso());
                    if (!applyRes.success) {
                        showErrorDialog("应用失败", applyRes.errorMessage != null ? applyRes.errorMessage : "未知错误");
                        return;
                    }
                    syncRuntimeAfterApply(ctx, applyRes);
                    refreshAllUi(ctx);
                    showApplyResultNotice(ctx, snapshot.name, applyRes, false);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRestoreLastBackup() {
        if (getContext() == null) return;
        Context ctx = getContext();
        E5ConfigApplier.BackupRecord backup = repository.loadLastBackup();
        if (backup == null) {
            Toast.makeText(ctx, "暂无可恢复备份", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("恢复上次配置")
                .setMessage("将恢复到应用「" + backup.appliedSourceName + "」之前的配置备份：\n\n"
                        + "备份时间：" + backup.backupCreatedAt + "\n"
                        + "恢复分区数：" + backup.appliedSectionIds.size() + " 个\n\n"
                        + "确认立即恢复吗？")
                .setPositiveButton("确认恢复", (d, w) -> {
                    E5ConfigApplier.ApplyResult res = repository.getApplier().restoreLastBackup();
                    if (!res.success) {
                        showErrorDialog("恢复失败", res.errorMessage != null ? res.errorMessage : "未知错误");
                        return;
                    }
                    syncRuntimeAfterApply(ctx, res);
                    refreshAllUi(ctx);
                    showApplyResultNotice(ctx, "上次备份", res, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSnapshot(@NonNull E5ConfigSnapshot snapshot) {
        if (getContext() == null) return;
        Context ctx = getContext();
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("删除本地快照")
                .setMessage("确定删除本地配置快照「" + snapshot.name + "」？已应用到系统的设置不会受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    repository.deleteLocalSnapshot(snapshot.id);
                    refreshAllUi(ctx);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncRuntimeAfterApply(@NonNull Context ctx, @NonNull E5ConfigApplier.ApplyResult result) {
        appConfig.syncVehicleSignalCategoryFromConfig();
        BlindSpotService.update(ctx);
        VehicleAutomationCoordinator.getInstance().startOrReload(ctx);

        if (result.themeChanged && getActivity() instanceof UiThemeController.Host) {
            UiThemeController tc = ((UiThemeController.Host) getActivity()).getUiThemeController();
            if (tc != null) {
                tc.syncSystemBarsAndTree();
            }
        }
        if (result.appliedSections.contains(E5ConfigSection.APPEARANCE)
                && getActivity() instanceof UiScaleController.Host) {
            UiScaleController sc = ((UiScaleController.Host) getActivity()).getUiScaleController();
            if (sc != null) {
                sc.applySavedScaleInPlace();
            }
        }
        if (getActivity() instanceof PreviewModeController.Host) {
            PreviewModeController.Host host = (PreviewModeController.Host) getActivity();
            host.onDefaultPreviewModeChanged(PreviewMode.fromKey(appConfig.getPreviewMode()));
            host.onPreviewShortcutPreferencesChanged();
        }
    }

    private void showApplyResultNotice(@NonNull Context ctx,
                                       @NonNull String sourceName,
                                       @NonNull E5ConfigApplier.ApplyResult result,
                                       boolean isRestore) {
        String prefix = isRestore ? "已恢复到" : "已应用";
        String msg = prefix + "「" + sourceName + "」（" + result.appliedSections.size() + " 个分区）";
        if (result.cameraMappingChanged) {
            new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(isRestore ? "恢复完成" : "配置已应用")
                    .setMessage(msg + "\n\n检测到摄像头 ID 或布局映射已变更，建议返回记录仪或重启应用以使新摄像头连接生效。")
                    .setPositiveButton("知道了", null)
                    .show();
        } else {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }

    private void launchExportSnapshot(@NonNull E5ConfigSnapshot snapshot) {
        pendingExportSnapshot = snapshot;
        String safeName = snapshot.id.replaceAll("[^a-zA-Z0-9_-]", "");
        if (safeName.isEmpty()) {
            safeName = "e5_config";
        }
        exportJsonLauncher.launch("evcam-" + safeName + ".json");
    }

    private void copyJsonToClipboard(@NonNull String json) {
        if (getContext() == null) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("EVCam E5 Config JSON", json));
            Toast.makeText(getContext(), "已复制 E5 配置 JSON 到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void showErrorDialog(@NonNull String title, @NonNull String message) {
        if (getContext() == null) return;
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    @NonNull
    private static Set<E5ConfigSection> collectCheckedSections(@NonNull Map<E5ConfigSection, CheckBox> checks) {
        Set<E5ConfigSection> set = new LinkedHashSet<>();
        for (Map.Entry<E5ConfigSection, CheckBox> e : checks.entrySet()) {
            if (e.getValue() != null && e.getValue().isChecked()) {
                set.add(e.getKey());
            }
        }
        return set;
    }

    private void applyOptionPill(@Nullable Button button, @NonNull Context context, boolean selected) {
        if (button == null) return;
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
