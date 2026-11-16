package com.kooo.evcam.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates and ranks RFC1918 private IPv4 interfaces for LAN sharing.
 * Priority matches vehicle/host hotspot topology: en0 > wlan/wifi > eth/en* > ap/softap/p2p > usb.
 */
public final class LanNetworkSelector {

    public static final int PRIORITY_UNKNOWN = 99;

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
            return description + " (" + ip + ")";
        }
    }

    private LanNetworkSelector() {}

    public static List<NetworkEndpoint> enumerateLocalEndpoints() {
        List<InterfaceSnapshot> snapshots = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                List<InetAddress> addresses = Collections.list(iface.getInetAddresses());
                snapshots.add(new InterfaceSnapshot(iface.getName(), addresses));
            }
        } catch (Exception ignored) {
        }
        return filterAndRank(snapshots);
    }

    public static List<NetworkEndpoint> filterAndRank(List<InterfaceSnapshot> snapshots) {
        Map<String, NetworkEndpoint> dedupedByIp = new LinkedHashMap<>();
        for (InterfaceSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.interfaceName == null) {
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
        if (lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("pdp")
                || lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("veth")
                || lower.startsWith("docker") || "lo".equals(lower)) {
            return PRIORITY_UNKNOWN;
        }
        if ("en0".equals(lower)) {
            return -1;
        }
        if (lower.startsWith("wlan") || lower.contains("wifi")) {
            return 0;
        }
        if (lower.startsWith("eth") || lower.matches("en\\d+")) {
            return 1;
        }
        if (lower.startsWith("ap") || lower.contains("softap") || lower.startsWith("p2p")) {
            return 2;
        }
        if (lower.startsWith("usb") || lower.startsWith("rndis")) {
            return 3;
        }
        return PRIORITY_UNKNOWN;
    }

    public static String describeInterface(@Nullable String interfaceName) {
        if (interfaceName == null) {
            return "局域网";
        }
        String lower = interfaceName.toLowerCase(Locale.ROOT);
        if ("en0".equals(lower) || lower.startsWith("eth") || lower.matches("en\\d+")) {
            return "车载有线/热点网口";
        }
        if (lower.startsWith("wlan") || lower.contains("wifi")) {
            return "Wi-Fi";
        }
        if (lower.startsWith("ap") || lower.contains("softap")) {
            return "车机热点";
        }
        if (lower.startsWith("p2p")) {
            return "Wi-Fi直连";
        }
        if (lower.startsWith("usb") || lower.startsWith("rndis")) {
            return "USB网络";
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
