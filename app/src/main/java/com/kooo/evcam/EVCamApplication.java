package com.kooo.evcam;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import com.kooo.evcam.playback.PlaybackImageMemory;

public final class EVCamApplication extends Application {
    private int startedActivities;

    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { startedActivities++; }
            @Override public void onActivityStopped(Activity activity) {
                if (--startedActivities == 0 && !activity.isChangingConfigurations()) PlaybackImageMemory.clear(EVCamApplication.this);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
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
