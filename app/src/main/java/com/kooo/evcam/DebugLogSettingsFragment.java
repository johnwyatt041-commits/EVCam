package com.kooo.evcam;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;

/**
 * 15. Debug 日志设置子页：
 * 提供 Debug 级别切换、会话摘要、本地导出（本次/上次）、实时 Logcat 查看与连贯性自检。
 * 已移除原硬编码上游 Gotify 服务器上传逻辑，全部保持本地诊断闭环。
 */
public class DebugLogSettingsFragment extends Fragment {

    private SwitchMaterial debugSwitch;
    private Button saveLogsButton;
    private Button savePrevLogsButton;
    private Button verifyCoherenceButton;
    private Button openLogcatButton;
    private TextView currentSummaryText;
    private TextView previousSummaryText;
    private TextView coherenceStatusText;

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
        savePrevLogsButton = view.findViewById(R.id.btn_save_prev_logs);
        verifyCoherenceButton = view.findViewById(R.id.btn_verify_coherence);
        openLogcatButton = view.findViewById(R.id.btn_open_logcat);
        currentSummaryText = view.findViewById(R.id.tv_current_session_summary);
        previousSummaryText = view.findViewById(R.id.tv_previous_session_summary);
        coherenceStatusText = view.findViewById(R.id.tv_coherence_status);

        if (getContext() != null) {
            boolean debugEnabled = AppLog.isDebugToInfoEnabled(getContext());
            debugSwitch.setChecked(debugEnabled);
            refreshSessionSummaries();
        }

        debugSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null) {
                return;
            }
            AppLog.setDebugToInfoEnabled(getContext(), isChecked);
            String message = isChecked
                    ? "已开启：Debug 日志将以 INFO 级别记录并持久化"
                    : "已关闭：恢复默认日志级别";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            refreshSessionSummaries();
        });

        saveLogsButton.setOnClickListener(v -> exportLogs(false));
        savePrevLogsButton.setOnClickListener(v -> exportLogs(true));

        verifyCoherenceButton.setOnClickListener(v -> {
            if (getContext() == null) {
                return;
            }
            SessionLogStore.CoherenceReport report = AppLog.runCoherenceCheck(getContext());
            if (coherenceStatusText != null) {
                coherenceStatusText.setText("连贯性校验：" + report.detail);
                coherenceStatusText.setTextColor(ContextCompat.getColor(
                        getContext(), report.passed ? R.color.ui_success : R.color.ui_danger));
            }
            refreshSessionSummaries();
            Toast.makeText(getContext(),
                    report.passed ? "日志连贯性校验通过" : "日志连贯性校验异常，请查看详情",
                    Toast.LENGTH_SHORT).show();
        });

        openLogcatButton.setOnClickListener(v -> {
            if (getContext() == null) {
                return;
            }
            startActivity(new Intent(getContext(), LogcatViewerActivity.class));
        });

        SettingsFragment.configureEmbeddedToolbar(this, view, "15. Debug 日志");
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSessionSummaries();
    }

    private void exportLogs(boolean previousSession) {
        if (getContext() == null) {
            return;
        }
        File logFile = previousSession
                ? AppLog.savePreviousLogsToFile(getContext())
                : AppLog.saveLogsToFile(getContext());
        if (logFile != null) {
            Toast.makeText(getContext(),
                    "日志已导出至：" + logFile.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(),
                    previousSession ? "上次会话暂无可用日志" : "当前会话日志导出失败或为空",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSessionSummaries() {
        if (getContext() == null) {
            return;
        }
        SessionLogStore.SessionSummary current = AppLog.getCurrentSessionSummary(getContext());
        SessionLogStore.SessionSummary previous = AppLog.getPreviousSessionSummary(getContext());
        if (currentSummaryText != null) {
            currentSummaryText.setText("本次会话：" + current.formatDisplay());
        }
        if (previousSummaryText != null) {
            previousSummaryText.setText("上次会话：" + previous.formatDisplay());
        }
        if (savePrevLogsButton != null) {
            savePrevLogsButton.setEnabled(previous.exists && previous.lineCount > 0);
        }
    }
}
