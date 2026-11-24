package io.github.flysky701.opencage;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;

import io.github.flysky701.opencage.privacy.GuestPrivacy;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class GuestVpn {
    private GuestVpn() {}

    static boolean isGuestProcess(String host, String process) {
        String prefix = host + ":p";
        if (process == null || !process.startsWith(prefix) || process.length() == prefix.length()) return false;
        for (int i = prefix.length(); i < process.length(); i++) {
            char digit = process.charAt(i);
            if (digit < '0' || digit > '9') return false;
        }
        return true;
    }

    private static Network defaultVpn(ConnectivityManager cm) {
        if (cm == null) return null;
        GuestPrivacy.enterControlPlane();
        try {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? network : null;
        } finally {
            GuestPrivacy.exitControlPlane();
        }
    }

    static Network activeNetwork(Context context) {
        try {
            return defaultVpn(context.getSystemService(ConnectivityManager.class));
        } catch (SecurityException denied) {
            return null;
        }
    }

    static boolean fullTunnel(Network network, LinkProperties link) {
        if (network == null || link == null || link.getInterfaceName() == null || link.getDnsServers().isEmpty()) {
            return false;
        }
        boolean ipv4 = false, ipv6Blocked = false;
        for (LinkAddress address : link.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet6Address && !address.getAddress().isLinkLocalAddress()) {
                return false;
            }
        }
        for (RouteInfo route : link.getRoutes()) {
            if (Build.VERSION.SDK_INT >= 33 && route.getType() == RouteInfo.RTN_THROW) return false;
            boolean blocked = Build.VERSION.SDK_INT >= 33 && route.getType() == RouteInfo.RTN_UNREACHABLE;
            if (route.getDestination().getAddress() instanceof Inet6Address && !blocked
                    && !route.getDestination().getAddress().isLinkLocalAddress()) return false;
            if (route.getDestination().getPrefixLength() != 0) continue;
            if (!blocked && !link.getInterfaceName().equals(route.getInterface())) continue;
            ipv4 |= !blocked && route.getDestination().getAddress() instanceof Inet4Address;
            ipv6Blocked |= blocked && route.getDestination().getAddress() instanceof Inet6Address;
        }
        return ipv4 && (ipv6Blocked || ipv6Unreachable(network));
    }

    private static boolean ipv6Unreachable(Network network) {
        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(previous).permitNetwork().build());
        try (DatagramSocket socket = new DatagramSocket()) {
            network.bindSocket(socket);
            byte[] address = {0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(address), 9));
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof java.net.NoRouteToHostException) return true;
                if (t instanceof android.system.ErrnoException
                        && ((android.system.ErrnoException) t).errno == android.system.OsConstants.ENETUNREACH) {
                    return true;
                }
            }
        } finally {
            StrictMode.setThreadPolicy(previous);
        }
        return false;
    }

    static Network fullTunnelNetwork(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network vpn = defaultVpn(cm);
            if (vpn == null || cm == null) return null;
            GuestPrivacy.enterControlPlane();
            try {
                return fullTunnel(vpn, cm.getLinkProperties(vpn)) ? vpn : null;
            } finally {
                GuestPrivacy.exitControlPlane();
            }
        } catch (SecurityException denied) {
            return null;
        }
    }

    static void protect(Context base) {
        ConnectivityManager cm = base.getSystemService(ConnectivityManager.class);
        Network pinned = defaultVpn(cm);
        if (pinned == null || !cm.bindProcessToNetwork(pinned)) {
            stopGuest();
            return;
        }
        try {
            if (!ExitBinding.admitted(base, pinned)) stopGuest();
        } catch (IOException unavailable) {
            stopGuest();
        }
        // A lost bound network fails future default sockets and DNS. Never unbind to a fallback.
        GuestPrivacy.enterControlPlane();
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!pinned.equals(network)) stopGuest();
                }
                @Override public void onLost(Network network) {
                    if (pinned.equals(network)) stopGuest();
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (pinned.equals(network) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) stopGuest();
                }
            });
        } finally {
            GuestPrivacy.exitControlPlane();
        }
        if (!pinned.equals(defaultVpn(cm))) stopGuest();
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
            try {
                if (ExitBinding.configured(base)) ExitBinding.verify(base, pinned, true);
            } catch (IOException | SecurityException unavailable) {
                stopGuest();
            }
        }, ExitBinding.RECHECK_MS, ExitBinding.RECHECK_MS, TimeUnit.MILLISECONDS);
    }

    private static void stopGuest() {
        Process.killProcess(Process.myPid());
        throw new IllegalStateException("Guest VPN unavailable");
    }
}
