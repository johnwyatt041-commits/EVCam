package com.kooo.evcam.transfer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enumerates and ranks RFC1918 private IPv4 interfaces for LAN sharing.
 * Priority matches SmartCam / vehicle hotspot topology:
 * en0 (-1) > wlan/wifi/wlp (0) > eth/en* (1) > ap/softap/p2p (2) > usb/rndis/ncm (3)
 * > bridge/veth/tap/vlan (4) > other private LAN fallback (5).
 */
public final class LanNetworkSelector {

    private static final String TAG = "LanNetworkSelector";

    public static final int PRIORITY_FALLBACK = 5;
    public static final int PRIORITY_UNKNOWN = 99;

    private static final int MAX_DIAG_ITEMS = 6;

    public static final class NetworkEndpoint {
        public final String ip;
        public final String interfaceName;
        public final String description;
        public final int priority;

        public NetworkEndpoint(@NonNull String ip,
                               @NonNull String interfaceName,
                               @NonNull String description,
                               int priority) {
            this.ip = ip;
            this.interfaceName = interfaceName;
            this.description = description;
            this.priority = priority;
        }

        @NonNull
        @Override
        public String toString() {
            if (description.equalsIgnoreCase(interfaceName)) {
                return interfaceName + " (" + ip + ")";
            }
            return interfaceName + " · " + description + " (" + ip + ")";
        }
    }

    public static final class ScanResult {
        public final List<NetworkEndpoint> endpoints;
        public final String diagnosticSummary;

        public ScanResult(@NonNull List<NetworkEndpoint> endpoints,
                          @NonNull String diagnosticSummary) {
            this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
            this.diagnosticSummary = diagnosticSummary;
        }
    }

    private LanNetworkSelector() {}

    public static List<NetworkEndpoint> enumerateLocalEndpoints() {
        return scanLocalEndpoints(null).endpoints;
    }

    public static List<NetworkEndpoint> enumerateLocalEndpoints(@Nullable Context context) {
        return scanLocalEndpoints(context).endpoints;
    }

    public static ScanResult scanLocalEndpoints(@Nullable Context context) {
        List<InterfaceSnapshot> snapshots = new ArrayList<>();
        List<String> fullDiagNotes = new ArrayList<>();
        List<String> briefDiagNotes = new ArrayList<>();

        collectFromNetworkInterfaces(snapshots, fullDiagNotes, briefDiagNotes);
        if (context != null) {
            Context appCtx = context.getApplicationContext();
            Context safeContext = appCtx != null ? appCtx : context;
            collectFromConnectivityManager(safeContext, snapshots, fullDiagNotes, briefDiagNotes);
            collectFromWifiManagerFallback(safeContext, snapshots, fullDiagNotes, briefDiagNotes);
        }

        List<NetworkEndpoint> ranked = filterAndRank(snapshots);
        String fullDiag = fullDiagNotes.isEmpty() ? "none" : String.join("; ", fullDiagNotes);
        String briefDiag = buildBriefDiagnostic(briefDiagNotes, ranked.isEmpty());

        AppLog.i(TAG, "LAN NIC scan: endpoints=" + formatEndpointsForLog(ranked) + ", raw=[" + fullDiag + "]");
        return new ScanResult(ranked, briefDiag);
    }

    private static void collectFromNetworkInterfaces(List<InterfaceSnapshot> snapshots,
                                                     List<String> fullDiagNotes,
                                                     List<String> briefDiagNotes) {
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            AppLog.w(TAG, "NetworkInterface.getNetworkInterfaces() failed: " + e.getMessage());
            fullDiagNotes.add("ni-enum-err:" + e.getClass().getSimpleName());
            briefDiagNotes.add("系统网卡枚举受限");
            return;
        }
        if (interfaces == null) {
            fullDiagNotes.add("ni-enum-null");
            return;
        }

        while (interfaces.hasMoreElements()) {
            NetworkInterface iface;
            try {
                iface = interfaces.nextElement();
            } catch (Exception e) {
                AppLog.w(TAG, "NetworkInterface.nextElement() failed: " + e.getMessage());
                break;
            }
            if (iface == null) {
                continue;
            }
            String name = null;
            try {
                name = iface.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                if (isLoopbackSafe(iface, name)) {
                    continue;
                }
                boolean isUp = true;
                boolean upUnknown = false;
                try {
                    isUp = iface.isUp();
                } catch (Exception e) {
                    upUnknown = true;
                    AppLog.w(TAG, "iface.isUp() failed for " + name + ": " + e.getMessage());
                }
                if (!isUp) {
                    fullDiagNotes.add(name + ":down");
                    if (rankInterface(name) < PRIORITY_UNKNOWN) {
                        briefDiagNotes.add(name + "(未启用)");
                    }
                    continue;
                }

                List<InetAddress> addresses = Collections.list(iface.getInetAddresses());
                if (addresses.isEmpty()) {
                    fullDiagNotes.add(name + ":no-addr");
                    if (rankInterface(name) < PRIORITY_UNKNOWN) {
                        briefDiagNotes.add(name + "(无IP)");
                    }
                    continue;
                }

                snapshots.add(new InterfaceSnapshot(name, addresses));
                String diag = describeRawSnapshot(name, upUnknown ? "up?" : "up", addresses);
                fullDiagNotes.add(diag);
                briefDiagNotes.add(describeBriefSnapshot(name, addresses));
            } catch (Exception e) {
                String label = name != null ? name : "<unknown>";
                AppLog.w(TAG, "Inspecting interface " + label + " failed: " + e.getMessage());
                fullDiagNotes.add(label + ":err(" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private static boolean isLoopbackSafe(NetworkInterface iface, String name) {
        try {
            return iface.isLoopback();
        } catch (Exception e) {
            return "lo".equalsIgnoreCase(name);
        }
    }

    private static void collectFromConnectivityManager(@NonNull Context context,
                                                       List<InterfaceSnapshot> snapshots,
                                                       List<String> fullDiagNotes,
                                                       List<String> briefDiagNotes) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            Set<Network> networks = new LinkedHashSet<>();
            Network active = cm.getActiveNetwork();
            if (active != null) {
                networks.add(active);
            }
            @SuppressWarnings("deprecation")
            Network[] allNetworks = cm.getAllNetworks();
            if (allNetworks != null) {
                Collections.addAll(networks, allNetworks);
            }

            for (Network network : networks) {
                if (network == null) {
                    continue;
                }
                try {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    boolean isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    boolean isEthernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    boolean isCellularOnly = caps != null
                            && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            && !isWifi && !isEthernet;
                    boolean isVpnOnly = caps != null
                            && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            && !isWifi && !isEthernet;
                    if (isCellularOnly || isVpnOnly) {
                        continue;
                    }

                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp == null) {
                        continue;
                    }
                    List<LinkAddress> linkAddresses = lp.getLinkAddresses();
                    if (linkAddresses == null || linkAddresses.isEmpty()) {
                        continue;
                    }
                    List<InetAddress> addrs = new ArrayList<>(linkAddresses.size());
                    for (LinkAddress linkAddress : linkAddresses) {
                        if (linkAddress != null && linkAddress.getAddress() != null) {
                            addrs.add(linkAddress.getAddress());
                        }
                    }
                    if (addrs.isEmpty()) {
                        continue;
                    }

                    String ifaceName = lp.getInterfaceName();
                    if (ifaceName == null || ifaceName.trim().isEmpty()) {
                        if (isWifi) {
                            ifaceName = "wlan";
                        } else if (isEthernet) {
                            ifaceName = "eth";
                        } else {
                            ifaceName = "lan";
                        }
                    }
                    snapshots.add(new InterfaceSnapshot(ifaceName, addrs));
                    String diag = describeRawSnapshot("cm:" + ifaceName, "link", addrs);
                    fullDiagNotes.add(diag);
                    if (hasRfc1918Address(addrs)) {
                        briefDiagNotes.add(describeBriefSnapshot(ifaceName, addrs));
                    }
                } catch (Throwable inner) {
                    AppLog.w(TAG, "ConnectivityManager network inspection failed: " + inner.getMessage());
                }
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "ConnectivityManager enumeration failed: " + t.getMessage());
        }
    }

    @SuppressLint("WifiManagerLeak")
    @SuppressWarnings("deprecation")
    private static void collectFromWifiManagerFallback(@NonNull Context context,
                                                         List<InterfaceSnapshot> snapshots,
                                                         List<String> fullDiagNotes,
                                                         List<String> briefDiagNotes) {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) {
                return;
            }
            InetAddress addr = ipv4FromLittleEndianInt(info.getIpAddress());
            if (!isRfc1918PrivateIpv4(addr)) {
                return;
            }
            String ip = addr.getHostAddress();
            if (ip == null || ip.isEmpty() || containsIp(snapshots, ip)) {
                return;
            }
            snapshots.add(new InterfaceSnapshot("wlan0", Collections.singletonList(addr)));
            fullDiagNotes.add("wifi-fallback:wlan0=" + ip);
            briefDiagNotes.add("wlan0:" + ip);
        } catch (Throwable t) {
            AppLog.w(TAG, "WifiManager IP fallback failed: " + t.getMessage());
        }
    }

    @Nullable
    public static InetAddress ipv4FromLittleEndianInt(int ipInt) {
        if (ipInt == 0 || ipInt == -1) {
            return null;
        }
        byte[] bytes = new byte[] {
                (byte) (ipInt & 0xFF),
                (byte) ((ipInt >> 8) & 0xFF),
                (byte) ((ipInt >> 16) & 0xFF),
                (byte) ((ipInt >> 24) & 0xFF)
        };
        try {
            return InetAddress.getByAddress(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsIp(List<InterfaceSnapshot> snapshots, String targetIp) {
        for (InterfaceSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.addresses == null) {
                continue;
            }
            for (InetAddress address : snapshot.addresses) {
                if (isRfc1918PrivateIpv4(address) && targetIp.equals(address.getHostAddress())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRfc1918Address(List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (isRfc1918PrivateIpv4(address)) {
                return true;
            }
        }
        return false;
    }

    private static String describeRawSnapshot(String name, String state, List<InetAddress> addresses) {
        List<String> ipv4s = new ArrayList<>();
        int v6Count = 0;
        for (InetAddress addr : addresses) {
            if (addr instanceof Inet4Address) {
                String host = addr.getHostAddress();
                if (host != null) {
                    ipv4s.add(host);
                }
            } else if (addr != null) {
                v6Count++;
            }
        }
        return name + "(" + state + ",v4=" + ipv4s + ",v6=" + v6Count + ")";
    }

    private static String describeBriefSnapshot(String name, List<InetAddress> addresses) {
        if (rankInterface(name) >= PRIORITY_UNKNOWN) {
            return name + "(已排除)";
        }
        for (InetAddress addr : addresses) {
            if (isRfc1918PrivateIpv4(addr)) {
                return name + ":" + addr.getHostAddress();
            }
        }
        for (InetAddress addr : addresses) {
            if (addr instanceof Inet4Address) {
                return name + "(非局域网:" + addr.getHostAddress() + ")";
            }
        }
        return name + "(仅IPv6)";
    }

    private static String buildBriefDiagnostic(List<String> notes, boolean noEndpointsFound) {
        if (notes.isEmpty()) {
            return noEndpointsFound ? "系统未返回活动网卡" : "";
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(notes));
        int limit = Math.min(unique.size(), MAX_DIAG_ITEMS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(unique.get(i));
        }
        if (unique.size() > limit) {
            sb.append(" 等").append(unique.size()).append("个");
        }
        return sb.toString();
    }

    private static String formatEndpointsForLog(List<NetworkEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < endpoints.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            NetworkEndpoint ep = endpoints.get(i);
            sb.append(ep.interfaceName).append('=').append(ep.ip).append("(p=").append(ep.priority).append(')');
        }
        sb.append(']');
        return sb.toString();
    }

    public static List<NetworkEndpoint> filterAndRank(List<InterfaceSnapshot> snapshots) {
        Map<String, NetworkEndpoint> dedupedByIp = new LinkedHashMap<>();
        for (InterfaceSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.interfaceName == null || snapshot.addresses == null) {
                continue;
            }
            String normalizedName = snapshot.interfaceName.toLowerCase(Locale.ROOT);
            int priority = rankInterface(normalizedName);
            if (priority >= PRIORITY_UNKNOWN) {
                continue;
            }
            for (InetAddress address : snapshot.addresses) {
                if (!isRfc1918PrivateIpv4(address)) {
                    continue;
                }
                String ip = address.getHostAddress();
                if (ip == null || ip.isEmpty()) {
                    continue;
                }
                NetworkEndpoint candidate = new NetworkEndpoint(
                        ip, snapshot.interfaceName, describeInterface(normalizedName), priority);
                NetworkEndpoint existing = dedupedByIp.get(ip);
                if (existing == null || candidate.priority < existing.priority) {
                    dedupedByIp.put(ip, candidate);
                }
            }
        }

        List<NetworkEndpoint> sorted = new ArrayList<>(dedupedByIp.values());
        Collections.sort(sorted, Comparator
                .comparingInt((NetworkEndpoint e) -> e.priority)
                .thenComparing(e -> e.interfaceName.toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.ip));
        return sorted;
    }

    public static boolean isRfc1918PrivateIpv4(@Nullable InetAddress address) {
        if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length != 4) {
            return false;
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        if (b0 == 10) {
            return true;
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        return b0 == 192 && b1 == 168;
    }

    public static int rankInterface(@Nullable String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) {
            return PRIORITY_UNKNOWN;
        }
        String lower = interfaceName.toLowerCase(Locale.ROOT);
        if ("lo".equals(lower)
                || lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("pdp")
                || lower.startsWith("wwan") || lower.startsWith("seth_lte") || lower.startsWith("ims")
                || lower.startsWith("epdg")
                || lower.startsWith("tun") || lower.startsWith("docker") || lower.startsWith("dummy")
                || lower.startsWith("clat") || lower.startsWith("sit") || lower.startsWith("ip6tnl")
                || lower.startsWith("ip_vti") || lower.startsWith("ip6_vti") || lower.startsWith("gre")
                || lower.startsWith("erspan")) {
            return PRIORITY_UNKNOWN;
        }
        if ("en0".equals(lower)) {
            return -1;
        }
        if (lower.contains("wlan") || lower.contains("wifi") || lower.startsWith("wlp")) {
            return 0;
        }
        if ((!lower.startsWith("veth") && lower.contains("eth"))
                || lower.matches("en\\d+") || lower.startsWith("enp")
                || lower.startsWith("eno") || lower.startsWith("ens")) {
            return 1;
        }
        if ((!lower.startsWith("tap") && lower.contains("ap"))
                || lower.contains("softap") || lower.contains("p2p")) {
            return 2;
        }
        if (lower.contains("usb") || lower.contains("rndis") || lower.startsWith("ncm")) {
            return 3;
        }
        if (lower.startsWith("veth") || lower.startsWith("tap") || lower.startsWith("br")
                || lower.contains("bridge") || lower.startsWith("vlan")) {
            return 4;
        }
        return PRIORITY_FALLBACK;
    }

    public static String describeInterface(@Nullable String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) {
            return "局域网";
        }
        String lower = interfaceName.toLowerCase(Locale.ROOT);
        if ("en0".equals(lower) || lower.matches("en\\d+")
                || lower.startsWith("enp") || lower.startsWith("eno") || lower.startsWith("ens")) {
            return "车载有线/热点网口";
        }
        if (lower.contains("wlan") || lower.contains("wifi") || lower.startsWith("wlp")) {
            return "Wi-Fi";
        }
        if (lower.contains("tether")) {
            return "热点共享网口";
        }
        if (!lower.startsWith("veth") && lower.contains("eth")) {
            return "车载有线/热点网口";
        }
        if (lower.contains("p2p")) {
            return "Wi-Fi直连";
        }
        if ((!lower.startsWith("tap") && lower.contains("ap")) || lower.contains("softap")) {
            return "车机热点";
        }
        if (lower.contains("usb") || lower.contains("rndis") || lower.startsWith("ncm")) {
            return "USB网络";
        }
        if (lower.startsWith("veth") || lower.startsWith("tap") || lower.startsWith("br")
                || lower.contains("bridge") || lower.startsWith("vlan")) {
            return "桥接/虚拟网口";
        }
        return interfaceName;
    }

    public static final class InterfaceSnapshot {
        public final String interfaceName;
        public final List<InetAddress> addresses;

        public InterfaceSnapshot(@NonNull String interfaceName, @NonNull List<InetAddress> addresses) {
            this.interfaceName = interfaceName;
            this.addresses = addresses;
        }
    }
}
