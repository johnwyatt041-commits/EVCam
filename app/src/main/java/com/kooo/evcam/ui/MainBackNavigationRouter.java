package com.kooo.evcam.ui;

import androidx.annotation.NonNull;

/**
 * 主界面返回导航优先级路由，供 {@link androidx.activity.OnBackPressedCallback} 与顶栏返回按钮共享。
 */
public final class MainBackNavigationRouter {

    public enum Action {
        FINISH_LAYOUT_EDIT,
        EXIT_FULLSCREEN_PREVIEW,
        POP_SETTINGS_SUBPAGE,
        POP_FRAGMENT_BACK_STACK,
        RETURN_TO_RECORDING,
        MOVE_TO_BACKGROUND
    }

    public interface Host {
        boolean isLayoutEditing();
        void finishLayoutEditing();
        boolean isFullscreenPreview();
        void exitFullscreenPreview();
        boolean handleSettingsSubPageBack();
        int getFragmentBackStackEntryCount();
        void popFragmentBackStack();
        boolean isFragmentContainerVisible();
        void returnToRecordingInterface();
        void moveToBackground();
    }

    private MainBackNavigationRouter() {}

    @NonNull
    public static Action dispatch(@NonNull Host host) {
        if (host.isLayoutEditing()) {
            host.finishLayoutEditing();
            return Action.FINISH_LAYOUT_EDIT;
        }
        if (host.isFullscreenPreview()) {
            host.exitFullscreenPreview();
            return Action.EXIT_FULLSCREEN_PREVIEW;
        }
        if (host.handleSettingsSubPageBack()) {
            return Action.POP_SETTINGS_SUBPAGE;
        }
        if (host.getFragmentBackStackEntryCount() > 0) {
            host.popFragmentBackStack();
            return Action.POP_FRAGMENT_BACK_STACK;
        }
        if (host.isFragmentContainerVisible()) {
            host.returnToRecordingInterface();
            return Action.RETURN_TO_RECORDING;
        }
        host.moveToBackground();
        return Action.MOVE_TO_BACKGROUND;
    }
}
