package com.kooo.evcam.transfer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class LanPathGuardAndNetworkTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void lanPathGuardRejectsTraversalNonMediaAndPrefixSpoofing() throws Exception {
        File mediaRoot = temp.newFolder("EVCam_Video");
        File siblingSpoof = temp.newFolder("EVCam_Video_evil");

        File validClip = new File(mediaRoot, "20261116_220000_combined.mp4");
        Files.write(validClip.toPath(), "mp4data".getBytes(StandardCharsets.UTF_8));
        File lockSidecar = new File(mediaRoot, "20261116_220000_combined.mp4.lock");
        Files.write(lockSidecar.toPath(), new byte[0]);
        File pending = new File(mediaRoot, "20261116_220100_front.mp4.pending");
        Files.write(pending.toPath(), "writing".getBytes(StandardCharsets.UTF_8));
        File secret = new File(siblingSpoof, "secret.mp4");
        Files.write(secret.toPath(), "secret".getBytes(StandardCharsets.UTF_8));

        assertEquals(validClip.getCanonicalFile(),
                LanPathGuard.resolveAuthorizedMediaFile(mediaRoot, "20261116_220000_combined.mp4"));

        assertNull(LanPathGuard.resolveAuthorizedMediaFile(mediaRoot, "../EVCam_Video_evil/secret.mp4"));
        assertNull(LanPathGuard.resolveAuthorizedMediaFile(mediaRoot, "/etc/passwd"));
        assertNull(LanPathGuard.resolveAuthorizedMediaFile(mediaRoot, "20261116_220000_combined.mp4.lock"));
        assertNull(LanPathGuard.resolveAuthorizedMediaFile(mediaRoot, "20261116_220100_front.mp4.pending"));
        assertFalse(LanPathGuard.isSafeAuthorizedRegularFile(mediaRoot, secret));
        assertFalse(LanPathGuard.isSafeAuthorizedRegularFile(mediaRoot, mediaRoot));
    }

    @Test
    public void rfc5987ContentDispositionEncodesUtf8Filenames() {
        String header = LanPathGuard.buildContentDispositionAttachment("行车录像 01.mp4");
        assertTrue(header.startsWith("attachment; filename=\""));
        assertTrue(header.contains("filename*=UTF-8''"));
        assertTrue(header.contains("%E8%A1%8C%E8%BD%A6%E5%BD%95%E5%83%8F%2001.mp4"));
    }

    @Test
    public void networkSelectorFiltersRfc1918AndRanksByInterfacePriority() throws Exception {
        assertTrue(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("10.0.0.1")));
        assertTrue(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("172.16.0.1")));
        assertTrue(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("172.31.255.254")));
        assertTrue(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("192.168.1.50")));

        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("172.15.0.1")));
        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("172.32.0.1")));
        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("169.254.1.1")));
        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("8.8.8.8")));
        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(InetAddress.getByName("127.0.0.1")));

        List<LanNetworkSelector.InterfaceSnapshot> snapshots = Arrays.asList(
                new LanNetworkSelector.InterfaceSnapshot("rmnet_data0",
                        Collections.singletonList(InetAddress.getByName("10.45.0.2"))),
                new LanNetworkSelector.InterfaceSnapshot("ap0",
                        Collections.singletonList(InetAddress.getByName("192.168.43.1"))),
                new LanNetworkSelector.InterfaceSnapshot("eth0",
                        Arrays.asList(InetAddress.getByName("169.254.9.9"), InetAddress.getByName("172.20.10.2"))),
                new LanNetworkSelector.InterfaceSnapshot("wlan0",
                        Collections.singletonList(InetAddress.getByName("192.168.1.100"))),
                new LanNetworkSelector.InterfaceSnapshot("en0",
                        Collections.singletonList(InetAddress.getByName("192.168.50.1")))
        );

        List<LanNetworkSelector.NetworkEndpoint> ranked = LanNetworkSelector.filterAndRank(snapshots);
        assertEquals(4, ranked.size());
        assertEquals("en0", ranked.get(0).interfaceName);
        assertEquals("wlan0", ranked.get(1).interfaceName);
        assertEquals("eth0", ranked.get(2).interfaceName);
        assertEquals("172.20.10.2", ranked.get(2).ip);
        assertEquals("ap0", ranked.get(3).interfaceName);
    }
}
