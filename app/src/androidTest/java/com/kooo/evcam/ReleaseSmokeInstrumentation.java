package com.kooo.evcam;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/** Platform-only instrumentation: the debug AndroidX runner cannot link a minified Kotlin runtime. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle results = new Bundle();
        Activity activity = null;
        try {
            String pkg = getTargetContext().getPackageName();
            activity = startActivitySync(new Intent().setClassName(pkg, pkg + ".MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            final Activity page = activity;
            waitForIdleSync();
            Class.forName("com.bumptech.glide.GeneratedAppGlideModuleImpl");
            Class.forName(pkg + ".VhalNative").getDeclaredMethod("decode", byte[].class);
            for (String id : new String[]{"ui_nav_playback", "ui_nav_photo", "ui_nav_feishu", "ui_nav_blind_spot", "ui_nav_settings", "ui_nav_recording"}) {
                int resource = page.getResources().getIdentifier(id, "id", pkg);
                if (resource == 0) throw new AssertionError("Missing destination " + id);
                runOnMainSync(() -> {
                    View view = page.findViewById(resource);
                    if (view == null) throw new AssertionError("Missing view " + id);
                    view.performClick();
                });
                waitForIdleSync();
            }
            for (String action : new String[]{"start_recording", "stop_recording"}) {
                runOnMainSync(() -> page.startActivity(new Intent().setClassName(pkg, pkg + ".MainActivity")
                        .putExtra("remote_action", action)));
                waitForIdleSync();
                Thread.sleep(1800);
                waitForIdleSync();
                if (page.getIntent().hasExtra("remote_action")) throw new AssertionError("Shared command ignored: " + action);
            }
            results.putString("stream", "OK (1 test)\nR8 launch, six pages, Glide discovery, JNI names and shared start/stop intents\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "FAILURES: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, results);
        } finally {
            if (activity != null) {
                final Activity page = activity;
                runOnMainSync(page::finish);
            }
        }
    }
}
