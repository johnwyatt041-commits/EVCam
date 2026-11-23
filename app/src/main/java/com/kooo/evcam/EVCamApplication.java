package com.kooo.evcam;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.kooo.evcam.playback.PlaybackImageMemory;

import java.lang.ref.WeakReference;

public final class EVCamApplication extends Application {
    private static volatile WeakReference<Activity> sResumedUiActivity = new WeakReference<>(null);
    private int startedActivities;

    @Nullable
    public static Activity getResumedUiActivity() {
        Activity activity = sResumedUiActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        return activity;
    }

    public static boolean isAppInForeground() {
        return getResumedUiActivity() != null;
    }

    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { startedActivities++; }
            @Override public void onActivityStopped(Activity activity) {
                if (--startedActivities == 0 && !activity.isChangingConfigurations()) PlaybackImageMemory.clear(EVCamApplication.this);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {
                if (!(activity instanceof TransparentBootActivity)) {
                    sResumedUiActivity = new WeakReference<>(activity);
                }
            }
            @Override public void onActivityPaused(Activity activity) {
                if (sResumedUiActivity.get() == activity) {
                    sResumedUiActivity = new WeakReference<>(null);
                }
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {
                if (sResumedUiActivity.get() == activity) {
                    sResumedUiActivity = new WeakReference<>(null);
                }
            }
        });
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        PlaybackImageMemory.clear(this);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        PlaybackImageMemory.clear(this);
    }
}
