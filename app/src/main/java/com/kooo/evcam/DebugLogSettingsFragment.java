package com.kooo.evcam;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;

/**
 * 15. Debug 日志设置子页：
 * 提供 Debug 日志级别切换、保存本地日志文件以及一键上传诊断日志。
 */
public class DebugLogSettingsFragment extends Fragment {

    private SwitchMaterial debugSwitch;
    private Button saveLogsButton;
    private Button uploadLogsButton;
    private LinearLayout logButtonsLayout;
    private AppConfig appConfig;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_debug_log_settings, container, false);

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getActivity().getSupportFragmentManager().popBackStack();
                }
            });
        }

        debugSwitch = view.findViewById(R.id.switch_debug_to_info);
        saveLogsButton = view.findViewById(R.id.btn_save_logs);
        uploadLogsButton = view.findViewById(R.id.btn_upload_logs);
        logButtonsLayout = view.findViewById(R.id.layout_log_buttons);

        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
            boolean debugEnabled = AppLog.isDebugToInfoEnabled(getContext());
            debugSwitch.setChecked(debugEnabled);
            updateSaveLogsButtonVisibility(debugEnabled);
        }

        debugSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null) {
                AppLog.setDebugToInfoEnabled(getContext(), isChecked);
                updateSaveLogsButtonVisibility(isChecked);
                String message = isChecked ? "Debug logs will show as info" : "Debug logs will show as debug";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });

        saveLogsButton.setOnClickListener(v -> {
            if (getContext() != null) {
                File logFile = AppLog.saveLogsToFile(getContext());
                if (logFile != null) {
                    Toast.makeText(getContext(), "Logs saved to: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), "Failed to save logs", Toast.LENGTH_SHORT).show();
                }
            }
        });

        uploadLogsButton.setOnClickListener(v -> {
            if (getContext() != null && appConfig != null) {
                if (!appConfig.hasDeviceNickname()) {
                    showDeviceNicknameInputDialog();
                } else {
                    showUploadConfirmDialog(appConfig.getDeviceNickname());
                }
            }
        });

        SettingsFragment.configureEmbeddedToolbar(this, view, "Debug 日志");

        return view;
    }

    private void updateSaveLogsButtonVisibility(boolean visible) {
        if (logButtonsLayout != null) {
            logButtonsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void showDeviceNicknameInputDialog() {
        if (getContext() == null) return;

        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        inputEditText.setHint("例如：张三的银河E5");
        inputEditText.setPadding(48, 32, 48, 32);
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("设置设备识别名称")
                .setMessage("请输入一个便于识别的名称，用于区分不同用户的日志：")
                .setView(inputEditText)
                .setPositiveButton("确认", (dialog, which) -> {
                    String nickname = inputEditText.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        Toast.makeText(getContext(), "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showNicknameConfirmDialog(nickname);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNicknameConfirmDialog(String nickname) {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认设备名称")
                .setMessage("您输入的设备名称是：\n\n「" + nickname + "」\n\n确认使用此名称吗？")
                .setPositiveButton("确认", (dialog, which) -> {
                    if (appConfig != null) {
                        appConfig.setDeviceNickname(nickname);
                    }
                    showUploadConfirmDialog(nickname);
                })
                .setNegativeButton("重新输入", (dialog, which) -> showDeviceNicknameInputDialog())
                .show();
    }

    private void showUploadConfirmDialog(String nickname) {
        if (getContext() == null) return;

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);

        TextView nicknameLabel = new TextView(getContext());
        nicknameLabel.setText("上传身份：「" + nickname + "」");
        nicknameLabel.setTextSize(16);
        nicknameLabel.setPadding(0, 0, 0, 24);
        nicknameLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        layout.addView(nicknameLabel);

        TextView logTypeLabel = new TextView(getContext());
        logTypeLabel.setText("选择日志：");
        logTypeLabel.setTextSize(14);
        logTypeLabel.setPadding(0, 0, 0, 8);
        logTypeLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(logTypeLabel);

        RadioGroup logTypeGroup = new RadioGroup(getContext());
        logTypeGroup.setOrientation(RadioGroup.VERTICAL);
        logTypeGroup.setPadding(0, 0, 0, 16);

        RadioButton currentLogRadio = new RadioButton(getContext());
        currentLogRadio.setId(View.generateViewId());
        currentLogRadio.setText("本次运行日志");
        currentLogRadio.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        currentLogRadio.setChecked(true);
        logTypeGroup.addView(currentLogRadio);

        RadioButton previousLogRadio = new RadioButton(getContext());
        previousLogRadio.setId(View.generateViewId());
        boolean hasPrevious = AppLog.hasPreviousSessionLogs(getContext());
        if (hasPrevious) {
            String prevInfo = AppLog.getPreviousSessionLogInfo(getContext());
            previousLogRadio.setText("上次运行日志" + (prevInfo != null ? "\n  " + prevInfo : ""));
            previousLogRadio.setEnabled(true);
        } else {
            previousLogRadio.setText("上次运行日志（无可用日志）");
            previousLogRadio.setEnabled(false);
        }
        previousLogRadio.setTextColor(ContextCompat.getColor(getContext(),
                hasPrevious ? R.color.text_primary : R.color.text_secondary));
        logTypeGroup.addView(previousLogRadio);

        layout.addView(logTypeGroup);

        TextView descLabel = new TextView(getContext());
        descLabel.setText("问题描述：");
        descLabel.setTextSize(14);
        descLabel.setPadding(0, 0, 0, 8);
        descLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(descLabel);

        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputEditText.setMinLines(3);
        inputEditText.setMaxLines(6);
        inputEditText.setHint("请描述遇到的问题...");
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        layout.addView(inputEditText);

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("上传日志")
                .setView(layout)
                .setPositiveButton("上传", (dialog, which) -> {
                    String problemDesc = inputEditText.getText().toString().trim();
                    if (problemDesc.isEmpty()) {
                        problemDesc = "（用户未填写问题描述）";
                    }
                    boolean uploadPreviousSession = previousLogRadio.isChecked();
                    performLogUpload(nickname, problemDesc, uploadPreviousSession);
                })
                .setNeutralButton("修改名称", (dialog, which) -> showDeviceNicknameInputDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void performLogUpload(String deviceNickname, String problemDescription, boolean uploadPreviousSession) {
        if (getContext() == null) return;

        uploadLogsButton.setEnabled(false);
        uploadLogsButton.setText("上传中...");

        String logType = uploadPreviousSession ? "上次运行" : "本次运行";

        AppLog.uploadLogsToServer(getContext(), deviceNickname, problemDescription, uploadPreviousSession,
                new AppLog.UploadCallback() {
                    @Override
                    public void onSuccess() {
                        if (getActivity() != null && isAdded()) {
                            getActivity().runOnUiThread(() -> {
                                uploadLogsButton.setEnabled(true);
                                uploadLogsButton.setText("一键上传");
                                Toast.makeText(getContext(), "作者已收到" + logType + "日志", Toast.LENGTH_LONG).show();
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (getActivity() != null && isAdded()) {
                            getActivity().runOnUiThread(() -> {
                                uploadLogsButton.setEnabled(true);
                                uploadLogsButton.setText("一键上传");
                                Toast.makeText(getContext(), "上传失败: " + error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                });
    }
}
