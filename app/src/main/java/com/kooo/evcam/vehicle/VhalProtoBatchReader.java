package com.kooo.evcam.vehicle;

/**
 * 轻量 AOSP VehicleHalProto 批量消息解码器。
 * 统一服务于水印快照解码与 4 类核心车辆信号解析，避免重复实现 wire parser。
 */
public final class VhalProtoBatchReader {
    private static final int MAX_BATCH_BYTES = 2 * 1024 * 1024;

    /** 单条 VHAL 属性解析回调。 */
    public interface PropConsumer {
        void onProperty(
                int propertyId,
                int areaId,
                int availability,
                long timestamp,
                Integer intValue,
                Float floatValue
        );
    }

    private VhalProtoBatchReader() {
    }

    /** 解析 gRPC 批量属性字节流；截断或超长消息将被安全忽略。 */
    public static void parseBatch(byte[] bytes, PropConsumer consumer) {
        if (bytes == null || consumer == null || bytes.length == 0 || bytes.length > MAX_BATCH_BYTES) {
            return;
        }
        try {
            Reader batch = new Reader(bytes, 0, bytes.length);
            while (batch.more()) {
                int tag = batch.tag();
                if (tag == 10) {
                    Reader item = batch.message();
                    while (item.more()) {
                        int innerTag = item.tag();
                        if (innerTag == 10) {
                            readProperty(item.message(), consumer);
                        } else {
                            item.skip(innerTag);
                        }
                    }
                } else {
                    batch.skip(tag);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 截断或损坏帧不能覆盖已知真实状态
        }
    }

    private static void readProperty(Reader r, PropConsumer consumer) {
        int prop = 0;
        int area = 0;
        int availability = 0;
        Integer integer = null;
        Float number = null;
        long timestamp = 0;
        while (r.more()) {
            int tag = r.tag();
            switch (tag) {
                case 8:
                    prop = (int) r.varint();
                    break;
                case 24:
                    timestamp = r.varint();
                    break;
                case 32:
                    area = (int) r.varint();
                    break;
                case 80:
                    availability = (int) r.varint();
                    break;
                // AOSP VehicleHalProto.proto: repeated sint32 int32_values = 5 (zigzag encoded).
                // Verified against both EVCam libvhal_decoder.so (0xd70) and Smartcam libvhal_proto_parser.so.
                case 40: {
                    long v = r.varint();
                    if (integer == null) {
                        integer = (int) ((v >>> 1) ^ -(v & 1));
                    }
                    break;
                }
                case 42: {
                    Reader packed = r.message();
                    while (packed.more()) {
                        long v = packed.varint();
                        if (integer == null) {
                            integer = (int) ((v >>> 1) ^ -(v & 1));
                        }
                    }
                    break;
                }
                case 61: {
                    float v = Float.intBitsToFloat(r.fixed32());
                    if (number == null) {
                        number = v;
                    }
                    break;
                }
                case 58: {
                    Reader packed = r.message();
                    while (packed.more()) {
                        float v = Float.intBitsToFloat(packed.fixed32());
                        if (number == null) {
                            number = v;
                        }
                    }
                    break;
                }
                default:
                    r.skip(tag);
            }
        }
        consumer.onProperty(prop, area, availability, timestamp, integer, number);
    }

    private static final class Reader {
        final byte[] data;
        final int end;
        int pos;

        Reader(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() {
            long value = varint();
            if (value <= 0 || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException();
            }
            return (int) value;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IllegalArgumentException();
                }
                int b = data[pos++] & 255;
                if (shift == 63 && (b & 254) != 0) {
                    throw new IllegalArgumentException();
                }
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException();
        }

        int fixed32() {
            require(4);
            int value = (data[pos] & 255)
                    | (data[pos + 1] & 255) << 8
                    | (data[pos + 2] & 255) << 16
                    | (data[pos + 3] & 255) << 24;
            pos += 4;
            return value;
        }

        Reader message() {
            long size = varint();
            if (size < 0 || size > end - pos) {
                throw new IllegalArgumentException();
            }
            Reader child = new Reader(data, pos, pos + (int) size);
            pos = child.end;
            return child;
        }

        void require(int size) {
            if (size > end - pos) {
                throw new IllegalArgumentException();
            }
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0:
                    varint();
                    return;
                case 1:
                    require(8);
                    pos += 8;
                    return;
                case 2:
                    message();
                    return;
                case 5:
                    require(4);
                    pos += 4;
                    return;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }
}
