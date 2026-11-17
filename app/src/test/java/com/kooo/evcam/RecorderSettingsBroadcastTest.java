package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;

import com.kooo.evcam.service.RecordingFloatingService;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

/**
 * 悬浮录制按钮大小广播包名限定测试，防止回退为隐式广播。
 */
public class RecorderSettingsBroadcastTest {

    @Test
    public void buttonSizeBroadcastSetsPackageAndExtrasBeforeSend() {
        Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn("com.kooo.evcam");

        try (MockedConstruction<Intent> intents = mockConstruction(Intent.class, (mock, ctx) ->
                assertEquals(RecordingFloatingService.ACTION_UPDATE_SIZE, ctx.arguments().get(0)))) {
            RecorderSettingsFragment.sendRecordingFloatingSizeBroadcast(context, 64, -1);

            assertEquals(1, intents.constructed().size());
            Intent builtIntent = intents.constructed().get(0);
            InOrder order = inOrder(builtIntent, context);
            order.verify(builtIntent).setPackage("com.kooo.evcam");
            order.verify(builtIntent).putExtra(RecordingFloatingService.EXTRA_BUTTON_SIZE, 64);
            order.verify(builtIntent).putExtra(RecordingFloatingService.EXTRA_TEXT_SIZE, -1);
            order.verify(context).sendBroadcast(builtIntent);
        }
    }

    @Test
    public void textSizeBroadcastSetsPackageAndExtrasBeforeSend() {
        Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn("com.kooo.evcam");

        try (MockedConstruction<Intent> intents = mockConstruction(Intent.class, (mock, ctx) ->
                assertEquals(RecordingFloatingService.ACTION_UPDATE_SIZE, ctx.arguments().get(0)))) {
            RecorderSettingsFragment.sendRecordingFloatingSizeBroadcast(context, -1, 18);

            assertEquals(1, intents.constructed().size());
            Intent builtIntent = intents.constructed().get(0);
            verify(builtIntent).setPackage("com.kooo.evcam");
            verify(builtIntent).putExtra(RecordingFloatingService.EXTRA_BUTTON_SIZE, -1);
            verify(builtIntent).putExtra(RecordingFloatingService.EXTRA_TEXT_SIZE, 18);
            verify(context).sendBroadcast(builtIntent);
        }
    }
}
