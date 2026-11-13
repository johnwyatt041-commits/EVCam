package com.kooo.evcam.vehicle;

import android.os.SystemClock;
import com.kooo.evcam.VhalSignalObserver;

/** One shared source for all recording cameras, leased only while the overlay is active. */
public final class RecordingVehicleSource {
    private static RecordingVehicleSource instance;
    private final VehicleStatusDecoder decoder = new VehicleStatusDecoder();
    private int users;
    private boolean switching;
    private VhalSignalObserver attached;
    private VhalSignalObserver owned;

    private RecordingVehicleSource() {}

    public static synchronized Lease acquire() {
        if (instance == null) instance = new RecordingVehicleSource();
        instance.retain();
        return new Lease(instance);
    }

    private synchronized void retain() {
        if (++users == 1) {
            VhalSignalObserver.setObserverLifecycleListener(this::refresh);
            refresh();
        }
    }

    private synchronized void refresh() {
        if (users == 0 || switching) return;
        switching = true;
        try {
            VhalSignalObserver candidate = VhalSignalObserver.findRunningObserverExcept(owned);
            if (candidate == null) {
                if (owned == null) owned = new VhalSignalObserver(null);
                candidate = owned;
            }
            if (candidate == attached) return;
            if (attached != null) attached.setPropertyListener(null);
            decoder.reset();
            attached = candidate;
            final VhalSignalObserver selected = candidate;
            candidate.setPropertyListener(new VhalSignalObserver.PropertyListener() {
                @Override public void onBatch(byte[] bytes) {
                    synchronized (RecordingVehicleSource.this) {
                        if (attached == selected && users > 0) decoder.accept(bytes, SystemClock.elapsedRealtime());
                    }
                }
                @Override public void onConnectionChanged(boolean connected) {
                    synchronized (RecordingVehicleSource.this) {
                        if (attached == selected) decoder.reset();
                    }
                }
            });
            if (candidate == owned) {
                owned.start();
            } else {
                if (owned != null) {
                    VhalSignalObserver previous = owned;
                    owned = null;
                    // Never wait for a network thread while holding the source monitor.
                    previous.stopAsync();
                }
                candidate.requestPropertySnapshot();
            }
        } finally {
            switching = false;
        }
    }

    private void release() {
        VhalSignalObserver toStop = null;
        synchronized (this) {
            if (--users != 0) return;
            VhalSignalObserver.setObserverLifecycleListener(null);
            if (attached != null) attached.setPropertyListener(null);
            attached = null;
            decoder.reset();
            toStop = owned;
            owned = null;
        }
        // Shutdown is bounded by VhalSignalObserver, and does not stall the encoder thread.
        if (toStop != null) toStop.stopAsync();
    }

    public static final class Lease implements AutoCloseable {
        private RecordingVehicleSource source;
        private Lease(RecordingVehicleSource source) { this.source = source; }
        public VehicleStatus snapshot() {
            RecordingVehicleSource value = source;
            return value == null ? VehicleStatus.UNKNOWN : value.decoder.snapshot(SystemClock.elapsedRealtime());
        }
        @Override public void close() {
            RecordingVehicleSource value = source;
            source = null;
            if (value != null) value.release();
        }
    }
}
