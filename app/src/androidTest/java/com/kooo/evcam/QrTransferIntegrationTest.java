package com.kooo.evcam;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.transfer.FileTransferServer;
import com.kooo.evcam.transfer.QrCodeGenerator;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class QrTransferIntegrationTest {
    @Test public void retainedQrRoundTripsAndServerDownloadsExactFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "qr-regression.mp4");
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Files.write(file.toPath(), bytes);
        FileTransferServer server = new FileTransferServer(context);
        Bitmap qr = null;
        try {
            server.addFile("sample", file);
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            qr = QrCodeGenerator.generateQRCode(base, 320, 320);
            assertNotNull(qr);
            int[] pixels = new int[320 * 320];
            qr.getPixels(pixels, 0, 320, 0, 0, 320, 320);
            assertEquals(base, new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(320, 320, pixels)))).getText());
            HttpURLConnection connection = (HttpURLConnection) new URL(base + "/download/sample").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try {
                assertEquals(200, connection.getResponseCode());
                try (java.io.InputStream input = connection.getInputStream();
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    assertArrayEquals(bytes, output.toByteArray());
                }
            } finally { connection.disconnect(); }
        } finally {
            server.stop();
            if (qr != null) qr.recycle();
            file.delete();
        }
    }
}
