package com.kooo.evcam.vehicle;

/**
 * 线程安全的 4 类核心信号状态解码器（档位 / 车速 / 4 车门 / 转向灯）。
 *
 * 所有超出 4 类核心清单的车辆属性在解码入口直接丢弃，不进入状态存储或监听分发。
 */
public final class CoreVehicleSignalDecoder {
    private static final long CONTINUOUS_EXPIRE_MS = 3000L;

    private VehicleSignalSnapshot.Gear gear = VehicleSignalSnapshot.Gear.UNKNOWN;
    private VehicleSignalSnapshot.Gear gearSelection = VehicleSignalSnapshot.Gear.UNKNOWN;
    private VehicleSignalSnapshot.Gear currentGear = VehicleSignalSnapshot.Gear.UNKNOWN;
    private int speedKmh = -1;
    private int doorFl = -1;
    private int doorFr = -1;
    private int doorRl = -1;
    private int doorRr = -1;
    private int leftTurn = -1;
    private int rightTurn = -1;

    private long speedUpdatedMs;
    private long lastUpdatedMs;

    private long gearSelectionTimestamp;
    private long currentGearTimestamp;
    private long gearUpdateSeq;
    private long gearSelectionSeq;
    private long currentGearSeq;
    private long speedTimestamp;
    private long turnTimestamp;
    private long doorFlTimestamp;
    private long doorFrTimestamp;
    private long doorRlTimestamp;
    private long doorRrTimestamp;

    private VehicleSignalSnapshot cachedSnapshot = VehicleSignalSnapshot.UNKNOWN;

    /** 重置全部 4 类信号为未知（断连或应用彻底退出时调用）。 */
    public synchronized void reset() {
        gear = VehicleSignalSnapshot.Gear.UNKNOWN;
        gearSelection = VehicleSignalSnapshot.Gear.UNKNOWN;
        currentGear = VehicleSignalSnapshot.Gear.UNKNOWN;
        speedKmh = -1;
        doorFl = -1;
        doorFr = -1;
        doorRl = -1;
        doorRr = -1;
        leftTurn = -1;
        rightTurn = -1;
        speedUpdatedMs = 0L;
        lastUpdatedMs = 0L;
        gearSelectionTimestamp = currentGearTimestamp = speedTimestamp = turnTimestamp = 0L;
        gearUpdateSeq = gearSelectionSeq = currentGearSeq = 0L;
        doorFlTimestamp = doorFrTimestamp = doorRlTimestamp = doorRrTimestamp = 0L;
        cachedSnapshot = VehicleSignalSnapshot.UNKNOWN;
    }

    /** 返回当前快照，自动让过期的连续车速退回未知（-1）。 */
    public synchronized VehicleSignalSnapshot snapshot(long nowMs) {
        if (speedKmh >= 0 && nowMs - speedUpdatedMs > CONTINUOUS_EXPIRE_MS) {
            speedKmh = -1;
            refreshSnapshotLocked(nowMs);
        }
        return cachedSnapshot;
    }

    /** 消费原始 gRPC 批量属性帧，仅处理 4 类核心信号。 */
    public synchronized boolean acceptRawBatch(byte[] bytes, long nowMs) {
        final boolean[] changed = {false};
        VhalProtoBatchReader.parseBatch(bytes, (prop, area, availability, timestamp, intVal, floatVal) -> {
            VehicleSignalCategory category = VehicleSignalCategory.classifyPropertyId(prop);
            if (category == null) {
                return;
            }
            switch (category) {
                case GEAR:
                    // Accept all area IDs for gear (zoned VHAL properties may use non-zero areaId).
                    if (applyGear(prop, timestamp, availability, intVal)) {
                        changed[0] = true;
                    }
                    break;
                case SPEED:
                    if (area == 0 && applySpeed(timestamp, availability, floatVal, nowMs)) {
                        changed[0] = true;
                    }
                    break;
                case TURN_SIGNALS:
                    if (area == 0 && applyTurn(timestamp, availability, intVal)) {
                        changed[0] = true;
                    }
                    break;
                case DOORS:
                    if (applyDoorAreas(area, timestamp, availability, intVal)) {
                        changed[0] = true;
                    }
                    break;
            }
        });
        if (changed[0]) {
            refreshSnapshotLocked(nowMs);
        }
        return changed[0];
    }

    /**
     * 消费 VhalNative.decode 输出事件三元组。
     * 转向灯与 4 车门事件均映射到统一 4 类状态；其他无关事件直接忽略。
     */
    public synchronized boolean acceptNativeEvents(int[] events, long nowMs) {
        if (events == null || events.length < 1) {
            return false;
        }
        boolean changed = false;
        int numEvents = events[0];
        for (int i = 0; i < numEvents; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) {
                break;
            }
            int type = events[offset];
            int p1 = events[offset + 1];
            VehicleSignalCategory category = VehicleSignalCategory.classifyNativeEvent(type);
            if (category == null) {
                continue;
            }
            if (category == VehicleSignalCategory.TURN_SIGNALS) {
                if (applyNativeTurnDirection(p1)) {
                    changed = true;
                }
            } else if (category == VehicleSignalCategory.DOORS) {
                int openState = type == VehicleSignalCategory.NATIVE_EVT_DOOR_OPEN ? 1 : 0;
                if (setDoorByNativePosition(p1, openState)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            refreshSnapshotLocked(nowMs);
        }
        return changed;
    }

    /** 从 CarSignalManager 等外部源同步转向灯状态到统一快照。 */
    public synchronized boolean updateTurnSignal(int leftState, int rightState, long nowMs) {
        int normalizedLeft = normalizeBinaryState(leftState);
        int normalizedRight = normalizeBinaryState(rightState);
        if (leftTurn == normalizedLeft && rightTurn == normalizedRight) {
            return false;
        }
        leftTurn = normalizedLeft;
        rightTurn = normalizedRight;
        refreshSnapshotLocked(nowMs);
        return true;
    }

    private boolean applyGear(int propertyId, long timestamp, int availability, Integer intVal) {
        VehicleSignalSnapshot.Gear parsed = (availability == 0 && intVal != null)
                ? VehicleSignalSnapshot.Gear.fromVhalValue(intVal)
                : VehicleSignalSnapshot.Gear.UNKNOWN;

        // Track GEAR_SELECTION (0x11400400) and CURRENT_GEAR (0x11400401) separately so an
        // unimplemented/UNKNOWN sibling property cannot clobber a valid gear reading.
        boolean isCurrentGearProp = propertyId == VehicleSignalCategory.getEffectiveCurrentGearPropId()
                && propertyId != VehicleSignalCategory.getEffectiveGearSelectionPropId();
        long seq = ++gearUpdateSeq;
        if (isCurrentGearProp) {
            if (timestamp > 0 && timestamp < currentGearTimestamp) {
                return false;
            }
            currentGearTimestamp = timestamp;
            currentGearSeq = seq;
            currentGear = parsed;
        } else {
            if (timestamp > 0 && timestamp < gearSelectionTimestamp) {
                return false;
            }
            gearSelectionTimestamp = timestamp;
            gearSelectionSeq = seq;
            gearSelection = parsed;
        }

        VehicleSignalSnapshot.Gear next = resolveEffectiveGear(
                gearSelection, gearSelectionTimestamp, gearSelectionSeq,
                currentGear, currentGearTimestamp, currentGearSeq
        );
        if (next == gear) {
            return false;
        }
        gear = next;
        return true;
    }

    private static VehicleSignalSnapshot.Gear resolveEffectiveGear(
            VehicleSignalSnapshot.Gear selection,
            long selectionTs,
            long selectionSeq,
            VehicleSignalSnapshot.Gear current,
            long currentTs,
            long currentSeq) {
        boolean selectionKnown = isKnownGear(selection);
        boolean currentKnown = isKnownGear(current);
        if (currentKnown && selectionKnown) {
            if (currentTs != selectionTs && (currentTs > 0 || selectionTs > 0)) {
                return currentTs > selectionTs ? current : selection;
            }
            return currentSeq >= selectionSeq ? current : selection;
        }
        if (currentKnown) {
            return current;
        }
        if (selectionKnown) {
            return selection;
        }
        return current != VehicleSignalSnapshot.Gear.UNKNOWN ? current : selection;
    }

    private static boolean isKnownGear(VehicleSignalSnapshot.Gear value) {
        return value != VehicleSignalSnapshot.Gear.UNKNOWN
                && value != VehicleSignalSnapshot.Gear.OTHER;
    }

    private boolean applySpeed(long timestamp, int availability, Float floatVal, long nowMs) {
        if (timestamp > 0 && timestamp < speedTimestamp) {
            return false;
        }
        speedTimestamp = timestamp;
        speedUpdatedMs = nowMs;
        int next = (availability == 0 && floatVal != null && Float.isFinite(floatVal) && Math.abs(floatVal) <= 120f)
                ? Math.round(Math.abs(floatVal) * 3.6f)
                : -1;
        if (next == speedKmh) {
            return false;
        }
        speedKmh = next;
        return true;
    }

    private boolean applyTurn(long timestamp, int availability, Integer intVal) {
        if (timestamp > 0 && timestamp < turnTimestamp) {
            return false;
        }
        turnTimestamp = timestamp;
        boolean known = availability == 0 && intVal != null && intVal >= 0 && intVal <= 2;
        int nextLeft = known ? (intVal == 2 ? 1 : 0) : -1;
        int nextRight = known ? (intVal == 1 ? 1 : 0) : -1;
        if (nextLeft == leftTurn && nextRight == rightTurn) {
            return false;
        }
        leftTurn = nextLeft;
        rightTurn = nextRight;
        return true;
    }

    private boolean applyNativeTurnDirection(int direction) {
        int nextLeft;
        int nextRight;
        if (direction == VehicleSignalCategory.NATIVE_DIR_LEFT) {
            nextLeft = 1;
            nextRight = 0;
        } else if (direction == VehicleSignalCategory.NATIVE_DIR_RIGHT) {
            nextLeft = 0;
            nextRight = 1;
        } else if (direction == VehicleSignalCategory.NATIVE_DIR_NONE) {
            nextLeft = 0;
            nextRight = 0;
        } else {
            return false;
        }
        if (nextLeft == leftTurn && nextRight == rightTurn) {
            return false;
        }
        leftTurn = nextLeft;
        rightTurn = nextRight;
        return true;
    }

    private boolean applyDoorAreas(int areaId, long timestamp, int availability, Integer intVal) {
        if (areaId <= 0) {
            return false;
        }
        int nextState = (availability == 0 && intVal != null) ? (intVal > 0 ? 1 : 0) : -1;
        boolean changed = false;
        if ((areaId & VehicleSignalCategory.AREA_DOOR_ROW_1_LEFT) != 0 && !(timestamp > 0 && timestamp < doorFlTimestamp)) {
            doorFlTimestamp = timestamp;
            if (doorFl != nextState) {
                doorFl = nextState;
                changed = true;
            }
        }
        if ((areaId & VehicleSignalCategory.AREA_DOOR_ROW_1_RIGHT) != 0 && !(timestamp > 0 && timestamp < doorFrTimestamp)) {
            doorFrTimestamp = timestamp;
            if (doorFr != nextState) {
                doorFr = nextState;
                changed = true;
            }
        }
        if ((areaId & VehicleSignalCategory.AREA_DOOR_ROW_2_LEFT) != 0 && !(timestamp > 0 && timestamp < doorRlTimestamp)) {
            doorRlTimestamp = timestamp;
            if (doorRl != nextState) {
                doorRl = nextState;
                changed = true;
            }
        }
        if ((areaId & VehicleSignalCategory.AREA_DOOR_ROW_2_RIGHT) != 0 && !(timestamp > 0 && timestamp < doorRrTimestamp)) {
            doorRrTimestamp = timestamp;
            if (doorRr != nextState) {
                doorRr = nextState;
                changed = true;
            }
        }
        return changed;
    }

    private boolean setDoorByNativePosition(int doorPos, int openState) {
        switch (doorPos) {
            case VehicleSignalCategory.NATIVE_DOOR_FL:
                if (doorFl == openState) return false;
                doorFl = openState;
                return true;
            case VehicleSignalCategory.NATIVE_DOOR_FR:
                if (doorFr == openState) return false;
                doorFr = openState;
                return true;
            case VehicleSignalCategory.NATIVE_DOOR_RL:
                if (doorRl == openState) return false;
                doorRl = openState;
                return true;
            case VehicleSignalCategory.NATIVE_DOOR_RR:
                if (doorRr == openState) return false;
                doorRr = openState;
                return true;
            default:
                return false;
        }
    }

    private static int normalizeBinaryState(int value) {
        if (value < 0) return -1;
        return value > 0 ? 1 : 0;
    }

    private void refreshSnapshotLocked(long nowMs) {
        lastUpdatedMs = nowMs;
        VehicleSignalSnapshot next = new VehicleSignalSnapshot(
                gear, speedKmh, doorFl, doorFr, doorRl, doorRr, leftTurn, rightTurn, lastUpdatedMs);
        if (!next.hasSameSignals(cachedSnapshot) || cachedSnapshot == VehicleSignalSnapshot.UNKNOWN) {
            cachedSnapshot = next;
        }
    }
}
