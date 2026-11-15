package com.kooo.evcam.vehicle;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一的 4 类核心车辆信号中心（档位 / 车速 / 4 车门 / 转向灯）。
 * 作为上层规则引擎与按需保活的唯一直出入口，避免多处重复解析 48 路冗余总线信号。
 */
public final class VehicleSignalHub {

    /** 信号快照变化监听器。 */
    public interface Listener {
        void onVehicleSignalChanged(VehicleSignalSnapshot snapshot);
    }

    private static final VehicleSignalHub INSTANCE = new VehicleSignalHub();

    private final CoreVehicleSignalDecoder decoder = new CoreVehicleSignalDecoder();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    VehicleSignalHub() {
    }

    public static VehicleSignalHub getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public VehicleSignalSnapshot getSnapshot() {
        return decoder.snapshot(System.currentTimeMillis());
    }

    public VehicleSignalSnapshot getSnapshot(long nowMs) {
        return decoder.snapshot(nowMs);
    }

    /** 消费 VHAL 原始字节流（只提取白名单中的 4 类核心信号）。 */
    public void onRawBatch(byte[] bytes) {
        onRawBatch(bytes, System.currentTimeMillis());
    }

    public void onRawBatch(byte[] bytes, long nowMs) {
        if (decoder.acceptRawBatch(bytes, nowMs)) {
            dispatchSnapshot(decoder.snapshot(nowMs));
        }
    }

    /** 消费 VhalNative 解码后的转向灯/四车门等白名单事件。 */
    public void onNativeEvents(int[] events) {
        onNativeEvents(events, System.currentTimeMillis());
    }

    public void onNativeEvents(int[] events, long nowMs) {
        if (decoder.acceptNativeEvents(events, nowMs)) {
            dispatchSnapshot(decoder.snapshot(nowMs));
        }
    }

    /** 从 CarSignalManager 等非 VHAL 转向灯源更新转向灯状态。 */
    public void onTurnSignalState(int leftState, int rightState) {
        long nowMs = System.currentTimeMillis();
        if (decoder.updateTurnSignal(leftState, rightState, nowMs)) {
            dispatchSnapshot(decoder.snapshot(nowMs));
        }
    }

    /** 清空全部信号为未知（信号服务断开或应用彻底退出时调用）。 */
    public void reset() {
        VehicleSignalSnapshot before = decoder.snapshot(System.currentTimeMillis());
        decoder.reset();
        if (!VehicleSignalSnapshot.UNKNOWN.equals(before)) {
            dispatchSnapshot(VehicleSignalSnapshot.UNKNOWN);
        }
    }

    private void dispatchSnapshot(VehicleSignalSnapshot snapshot) {
        for (Listener listener : listeners) {
            listener.onVehicleSignalChanged(snapshot);
        }
    }
}
