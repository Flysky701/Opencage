package io.github.flysky701.opencage;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;

import io.github.flysky701.opencage.privacy.GuestRegionProfile;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Device-local expected public IPv4 and a short-lived observation for the selected VPN. */
final class ExitBinding {
    enum Status { UNBOUND, PENDING, VERIFIED }
    private static final String CONFIG = GuestRegionProfile.BINDING_FILE, PROOF = "cage-exit-proof.json";
    static final long RECHECK_MS = 45_000;
    private static final long PROOF_MS = 90_000;

    private ExitBinding() {}

    static String expected(Context host) throws IOException {
        return read(host, CONFIG).optString("ipv4");
    }

    static String label(Context host) {
        try {
            return expected(host).isEmpty() ? "未绑定" : "已绑定";
        } catch (IOException invalid) {
            return "需重新绑定";
        }
    }

    static Status status(Context host, Network vpn) {
        try {
            JSONObject config = read(host, CONFIG);
            if (config.optString("ipv4").isEmpty()) return Status.UNBOUND;
            return fresh(host, vpn, config, PROOF_MS) ? Status.VERIFIED : Status.PENDING;
        } catch (IOException | SecurityException invalid) {
            return Status.PENDING;
        }
    }

    static void configure(Context host, String requested) throws IOException {
        String address = requested.trim().isEmpty() ? "" : publicIpv4(requested);
        withLock(host, () -> {
            Network vpn = vpn(host);
            String observed = observed(vpn);
            if (!address.isEmpty() && !address.equals(observed)) {
                throw new IOException("当前出口与输入的 IP 不同。");
            }
            String profile = UUID.randomUUID().toString();
            JSONObject config = json("profile", profile, "ipv4", observed);
            try {
                updateRegion(vpn, observed, config);
            } catch (IOException unavailable) {
                // IPv4 binding remains useful if the optional region lookup is unavailable.
            }
            confirmNetwork(host, vpn);
            write(host, PROOF, proof(host, vpn, profile));
            write(host, CONFIG, config);
        });
    }

    static void refreshRegion(Context host) throws IOException {
        withLock(host, () -> {
            JSONObject config = read(host, CONFIG);
            String expected = config.optString("ipv4");
            if (expected.isEmpty()) throw new IOException("请先绑定出口 IPv4。");
            Network vpn = vpn(host);
            String observed = observed(vpn);
            if (!expected.equals(observed)) throw new IOException("当前出口与绑定 IP 不同。");
            updateRegion(vpn, observed, config);
            confirmNetwork(host, vpn);
            write(host, CONFIG, config);
        });
    }

    private static void updateRegion(Network vpn, String observed, JSONObject config) throws IOException {
        try {
            String endpoint = "https://ipwho.is/" + observed + "?fields=success,ip,country_code,timezone.id";
            JSONObject result = new JSONObject(getText(vpn, endpoint, 2048));
            if (!result.optBoolean("success") || !observed.equals(result.optString("ip"))) {
                throw new IOException("地区查询没有返回当前绑定的 IPv4。");
            }
            String region = result.getString("country_code");
            if (!Arrays.asList(Locale.getISOCountries()).contains(region)) {
                throw new IOException("出口地区响应无效。");
            }
            String zone = GuestRegionProfile.timezone(result.getJSONObject("timezone").getString("id"));
            String language = GuestRegionProfile.preferredLanguage(region);
            config.put("region", region).put("zone", zone).put("language", language);
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("出口地区或时区响应无效。");
        }
    }

    static void clear(Context host) throws IOException {
        withLock(host, () -> {
            write(host, CONFIG, new JSONObject());
            write(host, PROOF, new JSONObject());
        });
    }

    static void verifyLaunch(Context host) throws IOException {
        if (expected(host).isEmpty()) return;
        verify(host, vpn(host), false);
    }

    static boolean admitted(Context host, Network vpn) throws IOException {
        JSONObject config = read(host, CONFIG);
        return config.optString("ipv4").isEmpty() || fresh(host, vpn, config, PROOF_MS);
    }

    static boolean configured(Context host) throws IOException {
        return !expected(host).isEmpty();
    }

    static void verify(Context host, Network vpn, boolean reuseRecent) throws IOException {
        withLock(host, () -> {
            JSONObject config = read(host, CONFIG);
            String expected = config.optString("ipv4");
            if (expected.isEmpty()) return;
            confirmNetwork(host, vpn);
            if (reuseRecent && fresh(host, vpn, config, RECHECK_MS)) return;
            try {
                if (!expected.equals(observed(vpn))) {
                    throw new IOException("当前出口与绑定 IP 不同，请切回原出口。");
                }
                confirmNetwork(host, vpn);
                write(host, PROOF, proof(host, vpn, config.optString("profile")));
            } catch (IOException failed) {
                write(host, PROOF, new JSONObject());
                throw failed;
            }
        });
    }

    private static boolean fresh(Context host, Network vpn, JSONObject config, long limit) throws IOException {
        JSONObject value = read(host, PROOF);
        long age = SystemClock.elapsedRealtime() - value.optLong("elapsed", -PROOF_MS);
        int boot = boot(host);
        return vpn != null && boot >= 0 && boot == value.optInt("boot", -1)
                && age >= 0 && age < limit && vpn.getNetworkHandle() == value.optLong("network", -1)
                && !config.optString("profile").isEmpty() && config.optString("profile").equals(value.optString("profile"));
    }

    private static JSONObject proof(Context host, Network vpn, String profile) throws IOException {
        int boot = boot(host);
        if (boot < 0) throw new IOException("系统无法确认本次开机状态。");
        return json("profile", profile, "network", vpn.getNetworkHandle(), "boot", boot,
                "elapsed", SystemClock.elapsedRealtime());
    }

    private static int boot(Context host) {
        return Settings.Global.getInt(host.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static Network vpn(Context host) throws IOException {
        Network vpn = GuestVpn.activeNetwork(host);
        if (vpn == null) throw new IOException("请先连接覆盖 Open Cage 的 VPN。");
        return vpn;
    }

    private static void confirmNetwork(Context host, Network network) throws IOException {
        if (network == null || !network.equals(vpn(host))) throw new IOException("VPN 已变化，请重新核对。");
    }

    private static String observed(Network vpn) throws IOException {
        for (String endpoint : new String[]{"https://ipv4.icanhazip.com/", "https://api.ipify.org/"}) {
            try {
                return publicIpv4(getText(vpn, endpoint, 96));
            } catch (IllegalArgumentException | IOException unavailable) {
                // Try the fallback public IPv4 endpoint.
            }
        }
        throw new IOException("当前 VPN 未返回公网 IPv4。");
    }

    private static String getText(Network vpn, String endpoint, int byteLimit) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) vpn.openConnection(new URL(endpoint));
        connection.setConnectTimeout(3_500);
        connection.setReadTimeout(3_500);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("出口查询暂不可用。");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[64];
                int size;
                while ((size = in.read(bytes)) != -1) {
                    if (out.size() + size > byteLimit) throw new IOException("出口查询响应无效。");
                    out.write(bytes, 0, size);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String publicIpv4(String input) {
        String value = input.trim();
        if (!value.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) {
            throw new IllegalArgumentException("请输入公网 IPv4 地址。");
        }
        String[] parts = value.split("\\.");
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]), c = Integer.parseInt(parts[2]);
        if (a == 0 || a == 10 || a == 127 || a >= 224 || b > 255 || c > 255 || Integer.parseInt(parts[3]) > 255
                || a == 100 && b >= 64 && b <= 127 || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
                || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2))
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113) {
            throw new IllegalArgumentException("请输入公网 IPv4 地址。");
        }
        return value;
    }

    private interface LockedWork { void run() throws IOException; }

    private static synchronized void withLock(Context host, LockedWork work) throws IOException {
        File file = new File(host.getNoBackupFilesDir(), "cage-exit.lock");
        try (RandomAccessFile access = new RandomAccessFile(file, "rw"); FileLock lock = access.getChannel().lock()) {
            work.run();
        }
    }

    private static AtomicFile file(Context host, String name) {
        return new AtomicFile(new File(host.getNoBackupFilesDir(), name));
    }

    private static JSONObject read(Context host, String name) throws IOException {
        AtomicFile atomic = file(host, name);
        try {
            return new JSONObject(new String(atomic.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            if (!atomic.getBaseFile().exists()) return new JSONObject();
            throw new IOException("无法读取出口配置。");
        } catch (JSONException invalid) {
            throw new IOException("出口配置需要重新保存。");
        }
    }

    private static void write(Context host, String name, JSONObject value) throws IOException {
        AtomicFile atomic = file(host, name);
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException failed) {
            atomic.failWrite(stream);
            throw failed;
        }
    }

    private static JSONObject json(Object... entries) throws IOException {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
            return result;
        } catch (JSONException invalid) {
            throw new IOException("无法保存出口配置。");
        }
    }
}
