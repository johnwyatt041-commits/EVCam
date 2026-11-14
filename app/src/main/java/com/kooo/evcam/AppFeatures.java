package com.kooo.evcam;

/** This build keeps recording, blind-spot assistance and Feishu remote control. */
public final class AppFeatures {

    // ESP32 / Wi-Fi HUD output uses this path independently of Android displays.
    public static final boolean NETWORK_STREAMING = true;

    // Turn-signal blind-spot output may use a HUD or an EVCC virtual display.
    public static final boolean SECONDARY_DISPLAY = true;

    // Retained solely for the existing Wi-Fi HUD settings compatibility.
    public static final boolean FISHEYE_CORRECTION = false;

    private AppFeatures() {}
}
