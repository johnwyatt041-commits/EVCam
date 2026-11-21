package com.kooo.evcam.transfer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

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
import static org.mockito.Mockito.*;

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

    @Test
    public void networkSelectorRecognizesVehicleHotspotAndBridgeNamesAndExcludesCellularVpn() throws Exception {
        for (String excluded : Arrays.asList(
                "rmnet_data0", "ccmni0", "pdp0", "wwan0", "seth_lte0",
                "tun0", "docker0", "dummy0", "lo")) {
            assertEquals("Expected excluded priority for " + excluded,
                    LanNetworkSelector.PRIORITY_UNKNOWN, LanNetworkSelector.rankInterface(excluded));
        }

        List<LanNetworkSelector.InterfaceSnapshot> snapshots = Arrays.asList(
                new LanNetworkSelector.InterfaceSnapshot("rmnet_data0",
                        Collections.singletonList(InetAddress.getByName("10.10.0.1"))),
                new LanNetworkSelector.InterfaceSnapshot("seth_lte0",
                        Collections.singletonList(InetAddress.getByName("10.10.0.2"))),
                new LanNetworkSelector.InterfaceSnapshot("tun0",
                        Collections.singletonList(InetAddress.getByName("10.8.0.1"))),
                new LanNetworkSelector.InterfaceSnapshot("ecarx_lan0",
                        Collections.singletonList(InetAddress.getByName("192.168.250.10"))),
                new LanNetworkSelector.InterfaceSnapshot("veth0",
                        Collections.singletonList(InetAddress.getByName("192.168.200.1"))),
                new LanNetworkSelector.InterfaceSnapshot("tap0",
                        Collections.singletonList(InetAddress.getByName("192.168.190.1"))),
                new LanNetworkSelector.InterfaceSnapshot("br0",
                        Collections.singletonList(InetAddress.getByName("192.168.180.1"))),
                new LanNetworkSelector.InterfaceSnapshot("usb0",
                        Collections.singletonList(InetAddress.getByName("192.168.42.129"))),
                new LanNetworkSelector.InterfaceSnapshot("br_ap0",
                        Collections.singletonList(InetAddress.getByName("192.168.143.1"))),
                new LanNetworkSelector.InterfaceSnapshot("sap0",
                        Collections.singletonList(InetAddress.getByName("192.168.144.1"))),
                new LanNetworkSelector.InterfaceSnapshot("uap0",
                        Collections.singletonList(InetAddress.getByName("192.168.145.1"))),
                new LanNetworkSelector.InterfaceSnapshot("tether0",
                        Collections.singletonList(InetAddress.getByName("192.168.120.1"))),
                new LanNetworkSelector.InterfaceSnapshot("enp0s3",
                        Collections.singletonList(InetAddress.getByName("192.168.110.1"))),
                new LanNetworkSelector.InterfaceSnapshot("br_eth0",
                        Collections.singletonList(InetAddress.getByName("192.168.100.1"))),
                new LanNetworkSelector.InterfaceSnapshot("wlp1s0",
                        Collections.singletonList(InetAddress.getByName("192.168.30.1"))),
                new LanNetworkSelector.InterfaceSnapshot("swlan0",
                        Collections.singletonList(InetAddress.getByName("192.168.43.1"))),
                new LanNetworkSelector.InterfaceSnapshot("ap_br_wlan2",
                        Collections.singletonList(InetAddress.getByName("192.168.68.1"))),
                new LanNetworkSelector.InterfaceSnapshot("en0",
                        Collections.singletonList(InetAddress.getByName("192.168.50.1")))
        );

        List<LanNetworkSelector.NetworkEndpoint> ranked = LanNetworkSelector.filterAndRank(snapshots);
        assertEquals(15, ranked.size());
        assertEquals("en0", ranked.get(0).interfaceName);

        // Priority 0 (wlan/wifi/wlp) sorted alphabetically
        assertEquals("ap_br_wlan2", ranked.get(1).interfaceName);
        assertEquals("swlan0", ranked.get(2).interfaceName);
        assertEquals("wlp1s0", ranked.get(3).interfaceName);

        // Priority 1 (eth/en*/tether)
        assertEquals("br_eth0", ranked.get(4).interfaceName);
        assertEquals("enp0s3", ranked.get(5).interfaceName);
        assertEquals("tether0", ranked.get(6).interfaceName);

        // Priority 2 (ap/softap/p2p)
        assertEquals("br_ap0", ranked.get(7).interfaceName);
        assertEquals("sap0", ranked.get(8).interfaceName);
        assertEquals("uap0", ranked.get(9).interfaceName);

        // Priority 3 (usb/rndis/ncm)
        assertEquals("usb0", ranked.get(10).interfaceName);

        // Priority 4 (bridge/tap/veth)
        assertEquals("br0", ranked.get(11).interfaceName);
        assertEquals("tap0", ranked.get(12).interfaceName);
        assertEquals("veth0", ranked.get(13).interfaceName);

        // Priority 5 (fallback private LAN)
        assertEquals("ecarx_lan0", ranked.get(14).interfaceName);
        assertEquals(LanNetworkSelector.PRIORITY_FALLBACK, ranked.get(14).priority);
    }

    @Test
    public void endpointToStringIncludesRealInterfaceName() {
        LanNetworkSelector.NetworkEndpoint wifi = new LanNetworkSelector.NetworkEndpoint(
                "192.168.43.1", "swlan0", LanNetworkSelector.describeInterface("swlan0"), 0);
        assertTrue(wifi.toString().contains("swlan0"));
        assertTrue(wifi.toString().contains("192.168.43.1"));

        LanNetworkSelector.NetworkEndpoint fallback = new LanNetworkSelector.NetworkEndpoint(
                "192.168.250.10", "ecarx_lan0", LanNetworkSelector.describeInterface("ecarx_lan0"),
                LanNetworkSelector.PRIORITY_FALLBACK);
        assertEquals("ecarx_lan0 (192.168.250.10)", fallback.toString());
    }

    @Test
    public void ipv4FromLittleEndianIntConvertsAndRejectsInvalidValues() {
        assertNull(LanNetworkSelector.ipv4FromLittleEndianInt(0));
        assertNull(LanNetworkSelector.ipv4FromLittleEndianInt(-1));

        // 192.168.1.50 -> little-endian int (50<<24 | 1<<16 | 168<<8 | 192)
        int le192 = (50 << 24) | (1 << 16) | (168 << 8) | 192;
        InetAddress addr = LanNetworkSelector.ipv4FromLittleEndianInt(le192);
        assertNotNull(addr);
        assertEquals("192.168.1.50", addr.getHostAddress());
        assertTrue(LanNetworkSelector.isRfc1918PrivateIpv4(addr));

        // 127.0.0.1 -> valid InetAddress, rejected by RFC1918 filter
        int leLoopback = (1 << 24) | 127;
        InetAddress loopback = LanNetworkSelector.ipv4FromLittleEndianInt(leLoopback);
        assertNotNull(loopback);
        assertEquals("127.0.0.1", loopback.getHostAddress());
        assertFalse(LanNetworkSelector.isRfc1918PrivateIpv4(loopback));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void enumerateLocalEndpointsUsesConnectivityAndWifiFallbacksAndFiltersCellular() throws Exception {
        Context context = mock(Context.class);
        ConnectivityManager cm = mock(ConnectivityManager.class);
        WifiManager wifiManager = mock(WifiManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm);
        when(context.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifiManager);

        Network cellNet = mock(Network.class);
        NetworkCapabilities cellCaps = mock(NetworkCapabilities.class);
        when(cellCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true);
        when(cm.getNetworkCapabilities(cellNet)).thenReturn(cellCaps);
        LinkProperties cellLp = mock(LinkProperties.class);
        LinkAddress cellLa = mock(LinkAddress.class);
        when(cellLa.getAddress()).thenReturn(InetAddress.getByName("10.99.88.77"));
        when(cellLp.getInterfaceName()).thenReturn("rmnet_data0");
        when(cellLp.getLinkAddresses()).thenReturn(Collections.singletonList(cellLa));
        when(cm.getLinkProperties(cellNet)).thenReturn(cellLp);

        Network lanNet = mock(Network.class);
        NetworkCapabilities lanCaps = mock(NetworkCapabilities.class);
        when(lanCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).thenReturn(true);
        when(cm.getNetworkCapabilities(lanNet)).thenReturn(lanCaps);
        LinkProperties lanLp = mock(LinkProperties.class);
        LinkAddress lanLa = mock(LinkAddress.class);
        when(lanLa.getAddress()).thenReturn(InetAddress.getByName("172.16.254.2"));
        when(lanLp.getInterfaceName()).thenReturn("ecarx_bridge");
        when(lanLp.getLinkAddresses()).thenReturn(Collections.singletonList(lanLa));
        when(cm.getLinkProperties(lanNet)).thenReturn(lanLp);

        when(cm.getActiveNetwork()).thenReturn(lanNet);
        when(cm.getAllNetworks()).thenReturn(new Network[] { cellNet, lanNet });

        WifiInfo wifiInfo = mock(WifiInfo.class);
        // 192.168.188.66 in little-endian int
        int wifiIpInt = (66 << 24) | (188 << 16) | (168 << 8) | 192;
        when(wifiInfo.getIpAddress()).thenReturn(wifiIpInt);
        when(wifiManager.getConnectionInfo()).thenReturn(wifiInfo);

        List<LanNetworkSelector.NetworkEndpoint> endpoints = LanNetworkSelector.enumerateLocalEndpoints(context);

        boolean foundBridge = false;
        boolean foundWifi = false;
        boolean foundCell = false;
        for (LanNetworkSelector.NetworkEndpoint ep : endpoints) {
            if ("172.16.254.2".equals(ep.ip)) {
                foundBridge = true;
            }
            if ("192.168.188.66".equals(ep.ip)) {
                foundWifi = true;
            }
            if ("10.99.88.77".equals(ep.ip)) {
                foundCell = true;
            }
        }
        assertTrue("ConnectivityManager fallback endpoint missing", foundBridge);
        assertTrue("WifiManager fallback endpoint missing", foundWifi);
        assertFalse("Cellular endpoint must be excluded", foundCell);
    }
}
