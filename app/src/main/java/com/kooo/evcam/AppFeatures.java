package com.kooo.evcam;

/** This build keeps recording, blind-spot assistance and Feishu remote control. */
public final class AppFeatures {
    public static final boolean DINGTALK = false;
    public static final boolean TELEGRAM = false;
    public static final boolean HEARTBEAT_IMAGES = false;
    // ESP32 / Wi-Fi HUD output uses this path independently of Android displays.
    public static final boolean NETWORK_STREAMING = true;
    public static final boolean FISHEYE_CORRECTION = false;
    public static final boolean PREVIEW_CORRECTION = false;
    public static final boolean IMAGE_ADJUSTMENT = false;
    // Turn-signal blind-spot output may use a HUD or an EVCC virtual display.
    public static final boolean SECONDARY_DISPLAY = true;
    public static final boolean SUPERVISION = false;
    public static final boolean DOOR_LINKAGE = false;
    public static final boolean CUSTOM_KEYS = false;
    public static final boolean MOCK_SIGNALS = false;

    private AppFeatures() {}
}
