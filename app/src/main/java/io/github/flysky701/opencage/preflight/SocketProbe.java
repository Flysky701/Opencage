package io.github.flysky701.opencage.preflight;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.ParcelFileDescriptor;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.json.JSONObject;

/** Inspect the kernel routing mark after UDP connect, without sending a payload. */
public final class SocketProbe {
    private SocketProbe() {}
    private static native int routeMark(int fd);
    private static native int ipv6Route();

    public static JSONObject measure(Context context) {
        JSONObject out = new JSONObject();
        try {
            Network network = context.getSystemService(ConnectivityManager.class).getBoundNetworkForProcess();
            out.put("bound", network != null);
            if (network == null) return out;
            try {
                out.put("resolver", InetAddress.getAllByName("example.com").length > 0);
            } catch (Exception unavailable) { out.put("resolver", false); }
            out.put("udp4", socket(network, "192.0.2.1"));
            try {
                System.loadLibrary("cage_socket_mark");
                int result=ipv6Route();
                out.put("udp6",result==0 ? "NO_ROUTE" : result==1 ? "REACHABLE" : "UNAVAILABLE");
            } catch (UnsatisfiedLinkError missing) { out.put("udp6","UNAVAILABLE"); }
        } catch (Exception unavailable) { /* Missing fields stay unverified. */ }
        return out;
    }

    private static String socket(Network network, String dest) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new java.net.InetSocketAddress(InetAddress.getByName(dest), 9));
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(socket)) {
                System.loadLibrary("cage_socket_mark");
                int mark = routeMark(pfd.getFd());
                if (mark < 0) return "UNAVAILABLE";
                long netId = network.getNetworkHandle() >>> 32;
                return (mark & 0xffff) == netId ? "VPN_MARK" : "OTHER_MARK";
            }
        } catch (java.net.NoRouteToHostException blocked) { return "NO_ROUTE"; }
        catch (Exception | UnsatisfiedLinkError unavailable) {
            for (Throwable t = unavailable; t != null; t = t.getCause())
                if (t instanceof android.system.ErrnoException
                        && ((android.system.ErrnoException)t).errno == android.system.OsConstants.ENETUNREACH) return "NO_ROUTE";
            return "UNAVAILABLE";
        }
    }
}
