package com.kooo.evcam.camera;

import android.media.*;
import android.os.Build;
import com.kooo.evcam.AppLog;
import java.io.IOException;
import java.util.*;

/** Validates the actual size/rate and tries real configuration, preferring hardware encoders. */
final class RecordingEncoderSelector {
    static final class Result {
        final MediaCodec codec;
        final String mime;
        final int bitrate;
        Result(MediaCodec codec, String mime, int bitrate) {
            this.codec = codec; this.mime = mime; this.bitrate = bitrate;
        }
    }

    static Result create(int width, int height, int fps, int avcBitrate, int hevcBitrate,
                         boolean h264Only, int keySeconds, boolean hardwareOnly, boolean e5Compatibility)
            throws IOException {
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        String[] types = h264Only ? new String[]{MediaFormat.MIMETYPE_VIDEO_AVC}
                : new String[]{MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC};
        Throwable last = null;
        // Prefer any hardware codec over a software HEVC encoder.
        for (boolean software : new boolean[]{false, true}) for (String mime : types) {
            if (software && hardwareOnly) continue;
            for (MediaCodecInfo info : codecs) {
                if (!info.isEncoder() || isSoftware(info) != software) continue;
                if (!Arrays.asList(info.getSupportedTypes()).contains(mime)) continue;
                MediaCodecInfo.CodecCapabilities caps;
                MediaCodecInfo.VideoCapabilities video;
                try {
                    caps = info.getCapabilitiesForType(mime);
                    video = caps.getVideoCapabilities();
                    if (!e5Compatibility && (video == null || !video.areSizeAndRateSupported(width, height, fps))) continue;
                    if (e5Compatibility && video != null && !video.areSizeAndRateSupported(width,height,fps)) {
                        AppLog.w("RecordingEncoder", "E5 capability report differs; trying actual configure: " + info.getName());
                    }
                    boolean surface = false;
                    for (int color : caps.colorFormats) if (color ==
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                    if (!surface) continue;
                } catch (Exception e) { last = e; continue; }
                for (int attempt=0; attempt<2; attempt++) {
                    boolean realtime = !e5Compatibility && attempt==0;
                    MediaCodec codec = null;
                    try {
                        codec = MediaCodec.createByCodecName(info.getName());
                        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                        int requestedBitrate = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? hevcBitrate : avcBitrate;
                        int clampedBitrate = video == null ? Math.max(1,requestedBitrate)
                                : video.getBitrateRange().clamp(Math.max(1, requestedBitrate));
                        format.setInteger(MediaFormat.KEY_BIT_RATE, clampedBitrate);
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keySeconds);
                        if (!e5Compatibility && Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                        if (e5Compatibility && !h264Only && attempt==0) {
                            if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                                format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                                format.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4);
                            } else format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                        }
                        if (realtime) {
                            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                            format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps);
                            MediaCodecInfo.EncoderCapabilities encoderCaps = caps.getEncoderCapabilities();
                            if (encoderCaps != null && encoderCaps.isBitrateModeSupported(
                                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                            }
                        }
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        AppLog.i("RecordingEncoder", "Configured " + info.getName() + ", software="
                                + software + ", " + width + "x" + height + "@" + fps + ", mime=" + mime
                                + ", bitrate=" + clampedBitrate + ", realtime=" + realtime + ", e5Compatibility=" + e5Compatibility
                                + ", declaredMaxInstances=" + caps.getMaxSupportedInstances());
                        return new Result(codec, mime, clampedBitrate);
                    } catch (Exception e) {
                        last = e;
                        if (codec != null) try { codec.release(); } catch (Exception ignored) {}
                    }
                }
            }
        }
        throw new IOException("No encoder can configure " + width + "x" + height + "@" + fps, last);
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        String name = info.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.")
                || (Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly());
    }
}
