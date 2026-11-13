package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FileTransferManagerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void queuedTaskWakesWorkerAndTransfersSmallRecordingWithoutPolling() throws Exception {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Constructor<FileTransferManager> constructor = FileTransferManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        FileTransferManager manager = constructor.newInstance(context);
        Field running = FileTransferManager.class.getDeclaredField("isRunning");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(manager)).set(true);
        Handler worker = mock(Handler.class);
        Field handler = FileTransferManager.class.getDeclaredField("transferHandler");
        handler.setAccessible(true);
        handler.set(manager, worker);
        File source = files.newFile("short.mp4");
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        Files.write(source.toPath(), content);
        File target = new File(files.getRoot(), "copied.mp4");
        FileTransferManager.TransferCallback callback = mock(FileTransferManager.TransferCallback.class);

        manager.addTransferTask(source, target, callback);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(worker).post(task.capture());
        task.getValue().run();

        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
        assertFalse(source.exists());
        verify(callback).onTransferComplete(source, target);
        verify(worker, never()).postDelayed(any(Runnable.class), anyLong());
        assertEquals(0, manager.getQueueSize());
    }
}
