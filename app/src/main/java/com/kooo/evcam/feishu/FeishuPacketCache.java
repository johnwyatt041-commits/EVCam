package com.kooo.evcam.feishu;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounds incomplete WebSocket messages and assembles each complete message only once. */
final class FeishuPacketCache {
    private static final int MAX_PARTS = 128;
    private static final int MAX_MESSAGE_BYTES = 512 * 1024;
    private final LinkedHashMap<String, Message> messages = new LinkedHashMap<>();
    private final int maxMessages;
    private final int maxBytes;
    private final long ttlMs;
    private final LongSupplier clock;
    private int bytes;

    private static final class Message {
        final byte[][] parts;
        int received;
        int bytes;
        long updatedAt;
        Message(int total) { parts = new byte[total][]; }
    }

    FeishuPacketCache() {
        this(32, 2 * 1024 * 1024, 120000, () -> System.nanoTime() / 1_000_000);
    }

    FeishuPacketCache(int maxMessages, int maxBytes, long ttlMs, LongSupplier clock) {
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    synchronized byte[] add(String id, int total, int sequence, byte[] data) {
        prune();
        if (id == null || total < 1 || total > MAX_PARTS || sequence < 0 || sequence >= total
                || data == null || data.length > MAX_MESSAGE_BYTES) return null;
        Message message = messages.get(id);
        if (message != null && message.parts.length != total) {
            remove(id);
            message = null;
        }
        if (message == null) {
            message = new Message(total);
            messages.put(id, message);
        }
        byte[] previous = message.parts[sequence];
        if (previous == null) message.received++;
        int increase = data.length - (previous == null ? 0 : previous.length);
        message.parts[sequence] = data;
        message.bytes += increase;
        message.updatedAt = clock.getAsLong();
        bytes += increase;
        if (message.bytes > MAX_MESSAGE_BYTES) {
            remove(id);
            return null;
        }
        while (messages.size() > maxMessages || bytes > maxBytes) {
            remove(messages.keySet().iterator().next());
        }
        if (!messages.containsKey(id) || message.received != total) return null;
        byte[] combined = new byte[message.bytes];
        int offset = 0;
        for (byte[] part : message.parts) {
            System.arraycopy(part, 0, combined, offset, part.length);
            offset += part.length;
        }
        remove(id);
        return combined;
    }

    synchronized void prune() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Message>> iterator = messages.entrySet().iterator();
        while (iterator.hasNext()) {
            Message message = iterator.next().getValue();
            if (now - message.updatedAt >= ttlMs) {
                bytes -= message.bytes;
                iterator.remove();
            }
        }
    }

    private void remove(String id) {
        Message message = messages.remove(id);
        if (message != null) bytes -= message.bytes;
    }

    synchronized void clear() { messages.clear(); bytes = 0; }
    synchronized int retainedBytes() { return bytes; }
}
