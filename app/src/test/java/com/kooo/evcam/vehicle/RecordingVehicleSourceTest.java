package com.kooo.evcam.vehicle;

import com.kooo.evcam.VhalSignalObserver;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class RecordingVehicleSourceTest {
    @Test public void threeCamerasShareBlindSpotStreamAndNeverStopItsOwner() {
        VhalSignalObserver existing = mock(VhalSignalObserver.class);
        try (MockedStatic<VhalSignalObserver> api = mockStatic(VhalSignalObserver.class);
             MockedConstruction<VhalSignalObserver> created = mockConstruction(VhalSignalObserver.class)) {
            api.when(() -> VhalSignalObserver.findRunningObserverExcept(any())).thenReturn(existing);
            try (RecordingVehicleSource.Lease a = RecordingVehicleSource.acquire();
                 RecordingVehicleSource.Lease b = RecordingVehicleSource.acquire();
                 RecordingVehicleSource.Lease c = RecordingVehicleSource.acquire()) {
                assertTrue(created.constructed().isEmpty());
                verify(existing, times(1)).requestPropertySnapshot();
                a.close();
                verify(existing, never()).setPropertyListener(null);
                assertSame(VehicleStatus.UNKNOWN, b.snapshot());
            }
            verify(existing, times(1)).setPropertyListener(null);
            verify(existing, never()).stop();
            verify(existing, never()).stopAsync();
        }
    }

    @Test public void privateStreamExistsOnlyWhileLeasedAndCanYieldToBlindSpot() {
        AtomicReference<Runnable> lifecycle = new AtomicReference<>();
        AtomicReference<VhalSignalObserver> current = new AtomicReference<>();
        try (MockedStatic<VhalSignalObserver> api = mockStatic(VhalSignalObserver.class);
             MockedConstruction<VhalSignalObserver> created = mockConstruction(VhalSignalObserver.class)) {
            api.when(() -> VhalSignalObserver.setObserverLifecycleListener(any())).thenAnswer(call -> {
                lifecycle.set(call.getArgument(0)); return null;
            });
            api.when(() -> VhalSignalObserver.findRunningObserverExcept(any())).thenAnswer(call -> current.get());
            try (RecordingVehicleSource.Lease a = RecordingVehicleSource.acquire()) {
                assertEquals(1, created.constructed().size());
                VhalSignalObserver privateStream = created.constructed().get(0);
                verify(privateStream).start();
                VhalSignalObserver blindSpot = mock(VhalSignalObserver.class);
                current.set(blindSpot);
                lifecycle.get().run();
                verify(privateStream).stopAsync();
                verify(blindSpot).requestPropertySnapshot();
            }
            verify(created.constructed().get(0), times(1)).stopAsync();
            assertNull(lifecycle.get());
        }
    }

    @Test public void privateSourceStopsOnlyAfterLastCameraAndCloseIsIdempotent() {
        try (MockedStatic<VhalSignalObserver> api = mockStatic(VhalSignalObserver.class);
             MockedConstruction<VhalSignalObserver> created = mockConstruction(VhalSignalObserver.class)) {
            RecordingVehicleSource.Lease a = RecordingVehicleSource.acquire();
            RecordingVehicleSource.Lease b = RecordingVehicleSource.acquire();
            VhalSignalObserver stream = created.constructed().get(0);
            try {
                a.close(); a.close();
                verify(stream, never()).stopAsync();
            } finally { a.close(); b.close(); }
            verify(stream, times(1)).stopAsync();
        }
    }
}
