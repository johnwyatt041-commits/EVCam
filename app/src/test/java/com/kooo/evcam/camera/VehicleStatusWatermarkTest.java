package com.kooo.evcam.camera;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleStatus;

import org.junit.Test;

import static org.junit.Assert.*;

public class VehicleStatusWatermarkTest {

    @Test
    public void fourModuleFormattingRespectsSwitchesCustomLengthLimitAndUnknownFallback() {
        String longText = "浙A·1234567890123456789012345678901234567890EXTRA";
        VehicleStatusWatermark.WatermarkOptions opts = new VehicleStatusWatermark.WatermarkOptions(
                true,
                true,
                longText,
                true,
                true,
                true,
                VehicleStatusWatermark.Position.TOP_LEFT,
                VehicleStatusWatermark.Style.CARD
        );

        assertEquals(VehicleStatusWatermark.MAX_CUSTOM_TEXT_LENGTH, opts.customText.length());
        assertTrue(opts.hasAnyEnabled());
        assertTrue(opts.needsVehicleSource());

        String line1Unknown = VehicleStatusWatermark.formatFirstLine(
                opts, "2026-11-16 14:00:00", VehicleStatus.UNKNOWN);
        assertTrue(line1Unknown.contains("2026-11-16 14:00:00"));
        assertTrue(line1Unknown.contains(opts.customText));
        assertTrue(line1Unknown.contains("车速 -- km/h"));

        String line2Unknown = VehicleStatusWatermark.formatSecondLine(
                opts, VehicleStatus.UNKNOWN, VehicleSignalSnapshot.Gear.UNKNOWN);
        assertTrue(line2Unknown.contains("档位 未知"));
        assertFalse(line2Unknown.contains("刹车"));
        assertTrue(line2Unknown.contains("左 未知"));
        assertTrue(line2Unknown.contains("右 未知"));

        VehicleStatus active = new VehicleStatus(68, 1, 1, 0);
        String line1Active = VehicleStatusWatermark.formatFirstLine(opts, "2026-11-16 14:00:01", active);
        String line2Active = VehicleStatusWatermark.formatSecondLine(opts, active, VehicleSignalSnapshot.Gear.DRIVE);
        assertTrue(line1Active.contains("车速 68 km/h"));
        assertTrue(line2Active.contains("档位 D"));
        assertFalse(line2Active.contains("刹车"));
        assertTrue(line2Active.contains("左 开"));
        assertTrue(line2Active.contains("右 关"));
    }

    @Test
    public void gearAndTurnSignalSwitchesWorkIndependentlyWithoutBrake() {
        VehicleStatus active = new VehicleStatus(52, 1, 1, 0);

        VehicleStatusWatermark.WatermarkOptions gearOnly = new VehicleStatusWatermark.WatermarkOptions(
                false, false, "", false, true, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.CARD);
        assertTrue(gearOnly.hasAnyEnabled());
        assertTrue(gearOnly.needsVehicleSource());
        assertEquals("", VehicleStatusWatermark.formatFirstLine(gearOnly, "2026-11-18 19:00:00", active));
        assertEquals("档位 D", VehicleStatusWatermark.formatSecondLine(gearOnly, active, VehicleSignalSnapshot.Gear.DRIVE));

        VehicleStatusWatermark.WatermarkOptions turnOnly = new VehicleStatusWatermark.WatermarkOptions(
                false, false, "", false, false, true,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.CARD);
        assertTrue(turnOnly.hasAnyEnabled());
        assertTrue(turnOnly.needsVehicleSource());
        assertEquals("", VehicleStatusWatermark.formatFirstLine(turnOnly, "2026-11-18 19:00:00", active));
        assertEquals("左 开   右 关", VehicleStatusWatermark.formatSecondLine(turnOnly, active, VehicleSignalSnapshot.Gear.DRIVE));

        // Brake transitions must not invalidate cacheKey since brake is excluded from watermark.
        String kBrakeOff = VehicleStatusWatermark.buildCacheKey(
                turnOnly, 1000L, new VehicleStatus(52, 0, 1, 0), VehicleSignalSnapshot.Gear.DRIVE);
        String kBrakeOn = VehicleStatusWatermark.buildCacheKey(
                turnOnly, 1000L, new VehicleStatus(52, 1, 1, 0), VehicleSignalSnapshot.Gear.DRIVE);
        assertEquals(kBrakeOff, kBrakeOn);

        String kGearChanged = VehicleStatusWatermark.buildCacheKey(
                turnOnly, 1000L, active, VehicleSignalSnapshot.Gear.REVERSE);
        assertEquals(kBrakeOff, kGearChanged);
        String kTurnChanged = VehicleStatusWatermark.buildCacheKey(
                turnOnly, 1000L, new VehicleStatus(52, 0, 0, 1), VehicleSignalSnapshot.Gear.DRIVE);
        assertNotEquals(kBrakeOff, kTurnChanged);
    }

    @Test
    public void disabledModulesReportNoActiveWatermarkAndNoVehicleLeaseNeed() {
        VehicleStatusWatermark.WatermarkOptions dateOnly = new VehicleStatusWatermark.WatermarkOptions(
                true, false, "", false, false, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.TEXT);
        assertTrue(dateOnly.hasAnyEnabled());
        assertFalse(dateOnly.needsVehicleSource());
        assertEquals("", VehicleStatusWatermark.formatSecondLine(
                dateOnly, VehicleStatus.UNKNOWN, VehicleSignalSnapshot.Gear.DRIVE));

        VehicleStatusWatermark.WatermarkOptions off = VehicleStatusWatermark.WatermarkOptions.disabled();
        assertFalse(off.hasAnyEnabled());
        assertFalse(off.needsVehicleSource());
    }

    @Test
    public void cornerPositionsMapToCorrectGlQuadrants() {
        float[] tl = VehicleStatusWatermark.computeQuadVertices(
                VehicleStatusWatermark.Position.TOP_LEFT, 1280, 800, 640, 80);
        float[] tr = VehicleStatusWatermark.computeQuadVertices(
                VehicleStatusWatermark.Position.TOP_RIGHT, 1280, 800, 640, 80);
        float[] bl = VehicleStatusWatermark.computeQuadVertices(
                VehicleStatusWatermark.Position.BOTTOM_LEFT, 1280, 800, 640, 80);
        float[] br = VehicleStatusWatermark.computeQuadVertices(
                VehicleStatusWatermark.Position.BOTTOM_RIGHT, 1280, 800, 640, 80);

        // TOP_LEFT: left<0, bottom>0
        assertTrue(tl[0] < 0f && tl[1] > 0f);
        // TOP_RIGHT: right>0, bottom>0
        assertTrue(tr[2] > 0f && tr[1] > 0f);
        // BOTTOM_LEFT: left<0, top<0
        assertTrue(bl[0] < 0f && bl[5] < 0f);
        // BOTTOM_RIGHT: right>0, top<0
        assertTrue(br[2] > 0f && br[5] < 0f);
    }

    @Test
    public void cacheKeyOnlyChangesWhenSecondOrStateOrOptionsChange() {
        VehicleStatusWatermark.WatermarkOptions opts = new VehicleStatusWatermark.WatermarkOptions(
                true, false, "", true, true, true,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.CARD);

        VehicleStatus s1 = new VehicleStatus(40, 0, 0, 0);
        String k1 = VehicleStatusWatermark.buildCacheKey(opts, 1000L, s1, VehicleSignalSnapshot.Gear.DRIVE);
        String k2 = VehicleStatusWatermark.buildCacheKey(opts, 1000L, new VehicleStatus(40, 0, 0, 0), VehicleSignalSnapshot.Gear.DRIVE);
        assertEquals(k1, k2);

        String kNextSec = VehicleStatusWatermark.buildCacheKey(opts, 1001L, s1, VehicleSignalSnapshot.Gear.DRIVE);
        assertNotEquals(k1, kNextSec);

        String kNextSpeed = VehicleStatusWatermark.buildCacheKey(opts, 1000L, new VehicleStatus(41, 0, 0, 0), VehicleSignalSnapshot.Gear.DRIVE);
        assertNotEquals(k1, kNextSpeed);
    }

    @Test
    public void customSoundValidationEnforces20MbLimitAndAudioExtensions() {
        assertFalse(RecordingSoundPlayer.isValidCustomSoundSize(0));
        assertFalse(RecordingSoundPlayer.isValidCustomSoundSize(-1));
        assertTrue(RecordingSoundPlayer.isValidCustomSoundSize(1024));
        assertTrue(RecordingSoundPlayer.isValidCustomSoundSize(RecordingSoundPlayer.MAX_CUSTOM_SOUND_BYTES));
        assertFalse(RecordingSoundPlayer.isValidCustomSoundSize(RecordingSoundPlayer.MAX_CUSTOM_SOUND_BYTES + 1));

        assertTrue(RecordingSoundPlayer.isSupportedAudioFileName("start_chime.MP3"));
        assertTrue(RecordingSoundPlayer.isSupportedAudioFileName("beep.wav"));
        assertTrue(RecordingSoundPlayer.isSupportedAudioFileName("alert.m4a"));
        assertFalse(RecordingSoundPlayer.isSupportedAudioFileName("video.mp4"));
        assertFalse(RecordingSoundPlayer.isSupportedAudioFileName(null));
    }
}
