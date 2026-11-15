package com.kooo.evcam.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class PreviewPresentationTest {

    @Test
    public void fromKeyNormalizesAndDefaultsToSidebar() {
        assertEquals(PreviewPresentation.SIDEBAR, PreviewPresentation.fromKey(null));
        assertEquals(PreviewPresentation.SIDEBAR, PreviewPresentation.fromKey(""));
        assertEquals(PreviewPresentation.SIDEBAR, PreviewPresentation.fromKey("unknown"));
        assertEquals(PreviewPresentation.SIDEBAR, PreviewPresentation.fromKey("SIDEBAR"));

        assertEquals(PreviewPresentation.FULLSCREEN, PreviewPresentation.fromKey("fullscreen"));
        assertEquals(PreviewPresentation.FULLSCREEN, PreviewPresentation.fromKey(" Fullscreen "));
        assertEquals(PreviewPresentation.WINDOW, PreviewPresentation.fromKey("window"));
        assertEquals(PreviewPresentation.WINDOW, PreviewPresentation.fromKey("WINDOW"));

        assertEquals("sidebar", PreviewPresentation.normalizeKey("bogus"));
        assertEquals("fullscreen", PreviewPresentation.normalizeKey("FULLSCREEN"));
        assertEquals("window", PreviewPresentation.normalizeKey("window"));
    }

    @Test
    public void metadataLabelsMatch42Spec() {
        assertEquals("sidebar", PreviewPresentation.SIDEBAR.getKey());
        assertEquals("保留侧边栏", PreviewPresentation.SIDEBAR.getDisplayName());
        assertEquals("fullscreen", PreviewPresentation.FULLSCREEN.getKey());
        assertEquals("全屏", PreviewPresentation.FULLSCREEN.getDisplayName());
        assertEquals("window", PreviewPresentation.WINDOW.getKey());
        assertEquals("独立悬浮窗", PreviewPresentation.WINDOW.getDisplayName());
    }
}
