package com.kooo.evcam.playback;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.kooo.evcam.R;
import com.kooo.evcam.transfer.QrTransferDialog;
import com.kooo.evcam.ui.UiThemeController;

/**
 * 合并「回看」统一入口：顶部切换视频回看 / 照片回看两个列表，并集成 Wi-Fi 浏览器全库分享入口。
 */
public class PlaybackHubFragment extends Fragment implements UiThemeController.ThemeAware {

    public static final int TAB_VIDEO = 0;
    public static final int TAB_PHOTO = 1;
    private static final String ARG_INITIAL_TAB = "initial_tab";
    private static final String STATE_ACTIVE_TAB = "active_tab";

    private int activeTab = TAB_VIDEO;
    private Button btnTabVideo;
    private Button btnTabPhoto;
    private Button btnLanShare;
    private View rootView;

    public static PlaybackHubFragment newInstance(int initialTab) {
        PlaybackHubFragment fragment = new PlaybackHubFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INITIAL_TAB, initialTab == TAB_PHOTO ? TAB_PHOTO : TAB_VIDEO);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt(STATE_ACTIVE_TAB, TAB_VIDEO);
        } else if (getArguments() != null) {
            activeTab = getArguments().getInt(ARG_INITIAL_TAB, TAB_VIDEO);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_playback_hub, container, false);
        btnTabVideo = rootView.findViewById(R.id.btn_tab_video);
        btnTabPhoto = rootView.findViewById(R.id.btn_tab_photo);
        btnLanShare = rootView.findViewById(R.id.btn_lan_share);

        getChildFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                      @NonNull View v, @Nullable Bundle s) {
                        if (f.getId() == R.id.playback_tab_container) {
                            normalizeChildToolbar(v);
                        }
                    }
                }, false);

        btnTabVideo.setOnClickListener(v -> switchTab(TAB_VIDEO, true));
        btnTabPhoto.setOnClickListener(v -> switchTab(TAB_PHOTO, true));
        if (btnLanShare != null) {
            btnLanShare.setOnClickListener(v -> openLibraryLanShare());
        }

        applyTabButtonTheme(requireContext());
        if (savedInstanceState == null || getChildFragmentManager().findFragmentById(R.id.playback_tab_container) == null) {
            switchTab(activeTab, false);
        }
        return rootView;
    }

    private void openLibraryLanShare() {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !isAdded()) {
            return;
        }
        QrTransferDialog.showLibraryShare(activity);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ACTIVE_TAB, activeTab);
    }

    public void switchTab(int tab, boolean clearMemoryFirst) {
        int normalized = tab == TAB_PHOTO ? TAB_PHOTO : TAB_VIDEO;
        if (clearMemoryFirst && normalized == activeTab
                && getChildFragmentManager().findFragmentById(R.id.playback_tab_container) != null) {
            return;
        }
        activeTab = normalized;
        if (getContext() != null) {
            applyTabButtonTheme(requireContext());
            if (clearMemoryFirst) {
                PlaybackImageMemory.clear(requireContext());
            }
        }
        Fragment child = activeTab == TAB_PHOTO ? new PhotoPlaybackFragmentNew() : new PlaybackFragmentNew();
        getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.playback_tab_container, child)
                .commitAllowingStateLoss();
    }

    public int getActiveTab() {
        return activeTab;
    }

    @Override
    public void onDestroyView() {
        PlaybackImageMemory.clear(getContext());
        rootView = null;
        btnTabVideo = null;
        btnTabPhoto = null;
        btnLanShare = null;
        super.onDestroyView();
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (rootView == null || themedContext == null) return;
        rootView.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        View tabBar = rootView.findViewById(R.id.playback_tab_bar);
        if (tabBar != null) {
            tabBar.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.menu_background));
        }
        View divider = rootView.findViewById(R.id.playback_tab_divider);
        if (divider != null) {
            divider.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.ui_border));
        }
        View tabContainer = rootView.findViewById(R.id.playback_tab_container);
        if (tabContainer != null) {
            tabContainer.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.page_background));
        }
        applyTabButtonTheme(themedContext);
    }

    private void applyTabButtonTheme(@NonNull Context context) {
        styleTab(btnTabVideo, activeTab == TAB_VIDEO, context);
        styleTab(btnTabPhoto, activeTab == TAB_PHOTO, context);
        if (btnLanShare != null) {
            ViewCompat.setBackgroundTintList(btnLanShare,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_background)));
            btnLanShare.setTextColor(ContextCompat.getColor(context, R.color.button_text));
        }
    }

    private void styleTab(@Nullable Button button, boolean selected, @NonNull Context context) {
        if (button == null) return;
        int bgRes = selected ? R.color.button_accent : R.color.button_background;
        int textRes = selected ? R.color.ui_on_accent : R.color.button_text;
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(ContextCompat.getColor(context, bgRes)));
        button.setTextColor(ContextCompat.getColor(context, textRes));
        button.setTag(R.id.ui_theme_text_role, selected ? "on_accent" : null);
    }

    private void normalizeChildToolbar(View childRoot) {
        View toolbar = childRoot.findViewById(R.id.toolbar);
        if (toolbar == null) return;
        toolbar.setPadding(toolbar.getPaddingLeft(), 8, toolbar.getPaddingRight(), 8);
        View btnMenu = childRoot.findViewById(R.id.btn_menu);
        if (btnMenu != null) btnMenu.setVisibility(View.GONE);
        View btnHome = childRoot.findViewById(R.id.btn_home);
        if (btnHome != null) btnHome.setVisibility(View.GONE);
        View title = childRoot.findViewById(R.id.toolbar_title);
        if (title != null) title.setVisibility(View.GONE);
    }
}
