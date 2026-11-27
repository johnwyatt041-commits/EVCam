package com.kooo.evcam.external;

import com.kooo.evcam.KeepAliveReceiver;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalBroadcastCatalogTest {

    @Test
    public void catalogCoversAll13ExternalCommandActionsAndKeepAliveWithoutDuplicates() {
        List<ExternalBroadcastCatalog.BroadcastItem> items = ExternalBroadcastCatalog.getAllItems();
        assertEquals(14, items.size());

        Set<String> ids = new HashSet<>();
        Set<String> actions = new HashSet<>();
        for (ExternalBroadcastCatalog.BroadcastItem item : items) {
            assertTrue("Duplicate id: " + item.id, ids.add(item.id));
            assertTrue("Duplicate action: " + item.action, actions.add(item.action));
            assertFalse(item.title.isEmpty());
            assertFalse(item.description.isEmpty());
            assertFalse(item.debounceLabel.isEmpty());
            if (KeepAliveReceiver.ACTION_KEEP_ALIVE.equals(item.action)) {
                assertEquals(ExternalBroadcastCatalog.KEEP_ALIVE_RECEIVER, item.receiverClassName);
            } else {
                assertEquals(ExternalBroadcastCatalog.EXTERNAL_COMMAND_RECEIVER, item.receiverClassName);
                assertTrue("Expected supported external action: " + item.action,
                        ExternalCommandContract.isSupportedAction(item.action));
            }
        }

        String[] requiredExternalActions = {
                ExternalCommandContract.ACTION_TOGGLE_FOREGROUND,
                ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND,
                ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND,
                ExternalCommandContract.ACTION_TOGGLE_RECORDING,
                ExternalCommandContract.ACTION_START_RECORDING,
                ExternalCommandContract.ACTION_STOP_RECORDING,
                ExternalCommandContract.ACTION_TAKE_PHOTO,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_A,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_B,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_C,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_D,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_E,
                ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE
        };
        for (String action : requiredExternalActions) {
            assertTrue("Missing action in catalog: " + action, actions.contains(action));
        }
        assertTrue("Missing keep-alive action", actions.contains(KeepAliveReceiver.ACTION_KEEP_ALIVE));
    }

    @Test
    public void filterByCategoryAndKeywordMatchesExpectedItems() {
        assertEquals(14, ExternalBroadcastCatalog.filter(null, null).size());
        assertEquals(3, ExternalBroadcastCatalog.filter(ExternalBroadcastCatalog.Category.FOREGROUND, null).size());
        assertEquals(4, ExternalBroadcastCatalog.filter(ExternalBroadcastCatalog.Category.RECORDING, null).size());
        assertEquals(6, ExternalBroadcastCatalog.filter(ExternalBroadcastCatalog.Category.PREVIEW_MODE, null).size());
        assertEquals(1, ExternalBroadcastCatalog.filter(ExternalBroadcastCatalog.Category.SYSTEM, null).size());

        List<ExternalBroadcastCatalog.BroadcastItem> photoMatches =
                ExternalBroadcastCatalog.filter(null, "一键抓拍");
        assertEquals(1, photoMatches.size());
        assertEquals(ExternalCommandContract.ACTION_TAKE_PHOTO, photoMatches.get(0).action);

        List<ExternalBroadcastCatalog.BroadcastItem> categoryLabelMatches =
                ExternalBroadcastCatalog.filter(null, "录像与抓拍");
        assertEquals(4, categoryLabelMatches.size());

        List<ExternalBroadcastCatalog.BroadcastItem> actionMatches =
                ExternalBroadcastCatalog.filter(ExternalBroadcastCatalog.Category.PREVIEW_MODE, "open_preview_mode_e");
        assertEquals(1, actionMatches.size());
        assertEquals(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_E, actionMatches.get(0).action);

        List<ExternalBroadcastCatalog.BroadcastItem> extraMatches =
                ExternalBroadcastCatalog.filter(null, "toggle_background");
        assertEquals(1, extraMatches.size());
        assertEquals(ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE, extraMatches.get(0).action);

        assertTrue(ExternalBroadcastCatalog.filter(null, "not_existing_xyz").isEmpty());
    }

    @Test
    public void formatHelpersGenerateAccurateCopyablePayloads() {
        assertEquals(ExternalBroadcastCatalog.DEFAULT_PACKAGE_NAME,
                ExternalBroadcastCatalog.resolvePackageName(null));
        assertEquals(ExternalBroadcastCatalog.DEFAULT_PACKAGE_NAME,
                ExternalBroadcastCatalog.resolvePackageName("   "));
        assertEquals("com.kooo.evcam.custom",
                ExternalBroadcastCatalog.resolvePackageName(" com.kooo.evcam.custom "));

        ExternalBroadcastCatalog.BroadcastItem switchModeItem =
                ExternalBroadcastCatalog.filter(null, "SWITCH_PREVIEW_MODE").get(0);
        ExternalBroadcastCatalog.BroadcastItem toggleFgItem =
                ExternalBroadcastCatalog.filter(null, "TOGGLE_FOREGROUND").get(0);

        String adb = ExternalBroadcastCatalog.formatAdbCommand(switchModeItem, null);
        assertEquals("am broadcast -a com.kooo.evcam.action.SWITCH_PREVIEW_MODE -p com.kooo.evcam", adb);

        String switchSnippet = ExternalBroadcastCatalog.formatConfigSnippet(switchModeItem, "com.kooo.evcam");
        assertTrue(switchSnippet.contains("目标包名：com.kooo.evcam"));
        assertTrue(switchSnippet.contains("Action：com.kooo.evcam.action.SWITCH_PREVIEW_MODE"));
        assertTrue(switchSnippet.contains("可选参数："));
        assertTrue(switchSnippet.contains("ADB 命令：" + adb));

        String fgSnippet = ExternalBroadcastCatalog.formatConfigSnippet(toggleFgItem, "com.kooo.evcam");
        assertFalse(fgSnippet.contains("可选参数："));

        String fullExport = ExternalBroadcastCatalog.formatFullCatalogExport("com.kooo.evcam");
        assertTrue(fullExport.contains("共 14 条"));
        assertTrue(fullExport.contains("目标包名：com.kooo.evcam"));
        for (ExternalBroadcastCatalog.BroadcastItem item : ExternalBroadcastCatalog.getAllItems()) {
            assertTrue("Full export missing action: " + item.action, fullExport.contains(item.action));
        }
    }
}
