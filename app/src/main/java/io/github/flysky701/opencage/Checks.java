package io.github.flysky701.opencage;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.webkit.WebView;

import io.github.flysky701.opencage.privacy.GuestRegionProfile;
import io.github.flysky701.opencage.preflight.NetworkMetadata;
import io.github.flysky701.opencage.preflight.NetworkProbe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import static io.github.flysky701.opencage.CheckPolicy.State;

final class Checks {
    private static final String[] SIM_JAVA = {
            "simCountry", "simOperator", "simOperatorName",
            "networkCountry", "networkOperator", "networkOperatorName"
    };
    private static final String[] SIM_KEYS = {
            "gsm.sim.operator.iso-country", "gsm.sim.operator.alpha", "gsm.sim.operator.numeric",
            "gsm.operator.iso-country", "gsm.operator.alpha", "gsm.operator.numeric"
    };

    final State permissions;
    final State vpn;
    final State internet;
    final String timezone;
    final long startedAt;
    final ExitBinding.Status exit;

    private Checks(State permissions, State vpn, State internet, String timezone, long startedAt,
            ExitBinding.Status exit) {
        this.permissions = permissions;
        this.vpn = vpn;
        this.internet = internet;
        this.timezone = timezone;
        this.startedAt = startedAt;
        this.exit = exit;
    }

    boolean eligible() {
        return CheckPolicy.allowsLaunch(permissions, vpn);
    }

    boolean sameStatus(Checks previous) {
        return permissions == previous.permissions && vpn == previous.vpn
                && internet == previous.internet && timezone.equals(previous.timezone) && exit == previous.exit;
    }

    static Checks pending() {
        return new Checks(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, "", -1, ExitBinding.Status.UNBOUND);
    }

    static Checks read(Context context) {
        long startedAt = SystemClock.elapsedRealtime();
        State permissions = CandidateApplication.phoneAccessRestricted(context) ? State.PASSED : State.FAILED;
        State vpn = State.UNKNOWN;
        State internet = State.UNKNOWN;
        Network activeVpn = null;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager != null) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
                if (Objects.equals(network, manager.getActiveNetwork())) {
                    if (network == null) vpn = State.FAILED;
                    else if (caps != null) {
                        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? State.PASSED : State.FAILED;
                        if (vpn == State.PASSED) activeVpn = network;
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) internet = State.PASSED;
                    }
                }
            }
        } catch (SecurityException ignored) {}
        return new Checks(permissions, vpn, internet, TimeZone.getDefault().getID(), startedAt,
                ExitBinding.status(context, activeVpn));
    }

    static final class Outer {
        String zone = "Asia/Tokyo", language = "";
        boolean permissionsRestricted;
        boolean network, networkKnown, vpn, vpnKnown, sim, simKnown, tunnel;
        JSONObject egress, linkMetadata;
        boolean protectionReadable, protectionConfigured, activeVpn;
    }

    static Outer outer(Context context) {
        Outer out = new Outer();
        out.permissionsRestricted = CandidateApplication.phoneAccessRestricted(context);
        GuestRegionProfile profile = GuestRegionProfile.read(context);
        if (!profile.zone.isEmpty()) out.zone = profile.zone;
        out.language = profile.language;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager != null) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities cap = network == null ? null : manager.getNetworkCapabilities(network);
                out.networkKnown = true;
                out.network = cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                out.vpnKnown = true;
                out.vpn = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                out.activeVpn = out.vpn;
                out.linkMetadata = NetworkMetadata.link(network == null ? null : manager.getLinkProperties(network));
                for (Network item : manager.getAllNetworks()) {
                    NetworkCapabilities c = manager.getNetworkCapabilities(item);
                    if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.vpn = true;
                }
            }
        } catch (RuntimeException ignored) {}
        try {
            TelephonyManager phone = context.getSystemService(TelephonyManager.class);
            if (phone != null) {
                out.simKnown = true;
                out.sim = phone.getSimState() == TelephonyManager.SIM_STATE_READY
                        || !phone.getSimOperator().isEmpty() || !phone.getNetworkOperator().isEmpty();
            }
        } catch (RuntimeException ignored) {}
        try {
            Enumeration<NetworkInterface> list = NetworkInterface.getNetworkInterfaces();
            while (list != null && list.hasMoreElements()) {
                if (tunnel(list.nextElement().getName())) out.tunnel = true;
            }
        } catch (Exception ignored) {}
        boolean[] protection = protectionConfiguration(context);
        out.protectionReadable = protection[0];
        out.protectionConfigured = protection[1];
        Network vpnNet = GuestVpn.activeNetwork(context);
        out.egress = vpnNet != null ? NetworkProbe.measure(vpnNet) : new JSONObject();
        return out;
    }

    static String fingerprint(Context context) {
        GuestRegionProfile profile = GuestRegionProfile.read(context);
        StringBuilder out = new StringBuilder(Build.VERSION.SDK_INT + "|" + TimeZone.getDefault().getID()
                + "|" + Locale.getDefault().toLanguageTag());
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            out.append('|').append(active).append('|').append(caps == null ? "none" :
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) + ":"
                            + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            PackageInfo web = WebView.getCurrentWebViewPackage();
            LinkProperties link = active == null ? null : cm.getLinkProperties(active);
            if (link == null) out.append("|unavailable");
            else out.append('|').append(link.getDnsServers()).append('|').append(link.getRoutes());
            out.append('|').append(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
            out.append('|').append(web == null ? "none" : web.packageName + ":" + web.getLongVersionCode());
        } catch (Exception ignored) {
            out.append("|unavailable");
        }
        boolean[] protection = protectionConfiguration(context);
        out.append('|').append(protection[0]).append(':').append(protection[1]);
        out.append('|').append(profile.profileId).append('|').append(profile.zone).append('|').append(profile.language);
        return out.toString();
    }

    private static boolean[] protectionConfiguration(Context context) {
        try {
            String lockdown = Settings.Secure.getString(context.getContentResolver(), "always_on_vpn_lockdown");
            String owner = Settings.Secure.getString(context.getContentResolver(), "always_on_vpn_app");
            boolean enabled = "1".equals(lockdown) && owner != null && !owner.isEmpty();
            return new boolean[]{"0".equals(lockdown) || enabled, enabled};
        } catch (RuntimeException unavailable) {
            return new boolean[]{false, false};
        }
    }

    private static JSONObject object(JSONObject value, String key) {
        JSONObject result = value == null ? null : value.optJSONObject(key);
        return result == null ? new JSONObject() : result;
    }

    private static Object value(JSONObject read) {
        String status = read.optString("status");
        if ((!"OK".equals(status) && !"EMPTY".equals(status) && !"NULL".equals(status)) || !read.has("value")) return null;
        return read.opt("value");
    }

    private static boolean tunnel(String name) {
        return name != null && (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("wg")
                || name.startsWith("ppp") || name.startsWith("utun") || name.startsWith("ipsec"));
    }

    private static final class Measure {
        boolean complete = true, signal, matches = true;
        void empty(Object item) {
            if (!(item instanceof String)) complete = false;
            else if (!((String) item).isEmpty()) signal = true;
        }
        void equalsValue(Object item, Object expected) {
            if (item == null || item == JSONObject.NULL) complete = false;
            else if (!expected.equals(item)) matches = false;
        }
        void number(Object item, int expected) {
            if (!(item instanceof Number)) complete = false;
            else if (((Number) item).intValue() != expected) matches = false;
        }
        void network(JSONObject cap) {
            if (!(cap.opt("vpn") instanceof Boolean) || !(cap.opt("notVpn") instanceof Boolean)) complete = false;
            else if (cap.optBoolean("vpn") || !cap.optBoolean("notVpn")) signal = true;
        }
        void iface(Object name) {
            if (name != JSONObject.NULL && !(name instanceof String)) complete = false;
            else if (name instanceof String && tunnel((String) name)) signal = true;
        }
    }

    static void evaluate(CheckReport report, Outer outer, JSONObject guest) {
        report.add("permissions", "电话与短信读取权限",
                outer.permissionsRestricted ? State.PASSED : State.FAILED,
                outer.permissionsRestricted
                        ? "宿主未授予电话状态、号码与短信读取权限。"
                        : "宿主已授予受限制的电话或短信权限，空间暂停启动。");
        report.add("network", "网络连接",
                !outer.networkKnown ? State.UNKNOWN : outer.network ? State.PASSED : State.FAILED,
                outer.network ? "手机当前网络可用。" : "未确认手机已连接可用网络，请检查代理连接。");

        JSONObject nativeData = object(guest, "nativeInProcess"), child = object(guest, "nativeSubprocess");
        JSONObject nativeProps = object(nativeData, "properties"), childProps = object(child, "properties");
        JSONObject apis = object(nativeData, "propertyApis"), legacy = object(apis, "legacyRead"), callback = object(apis, "callback");

        Measure sim = new Measure();
        JSONObject javaSim = object(guest, "sim");
        for (String key : SIM_JAVA) sim.empty(value(object(javaSim, key)));
        Object state = value(object(javaSim, "defaultState"));
        if (!(state instanceof Number)) sim.complete = false;
        else if (((Number) state).intValue() != 1) sim.signal = true;
        JSONArray slots = javaSim.optJSONArray("slots");
        if (slots == null || slots.length() < 2) sim.complete = false;
        else for (int i = 0; i < slots.length(); i++) {
            Object slot = value(object(slots.optJSONObject(i), "state"));
            if (!(slot instanceof Number)) sim.complete = false;
            else if (((Number) slot).intValue() != 1) sim.signal = true;
        }
        for (String key : SIM_KEYS) {
            sim.empty(value(object(nativeProps, key)));
            sim.empty(value(object(childProps, key)));
            sim.empty(legacy.opt(key));
            sim.empty(callback.opt(key));
        }
        if (!"OK".equals(nativeData.optString("status")) || !apis.optBoolean("callbacksValid")) sim.complete = false;
        State simState = CheckPolicy.mask(sim.complete, outer.simKnown && outer.sim, sim.signal);
        report.add("sim_mask", "SIM 屏蔽", simState, simState == State.PASSED
                ? "手机有 SIM 信号；空间内已测系统、原生和子进程接口未显示运营商信息。"
                : sim.signal ? "空间内仍有接口能读取 SIM 信号。" : "缺少完整读数或手机端阳性对照，不能确认屏蔽有效。");

        Measure vpn = new Measure();
        JSONObject network = object(guest, "network");
        Object active = value(object(network, "active"));
        if (!(active instanceof JSONObject)) vpn.complete = false;
        else {
            Object cap = value(object((JSONObject) active, "capabilities"));
            if (cap instanceof JSONObject) vpn.network((JSONObject) cap); else vpn.complete = false;
            vpn.iface(value(object((JSONObject) active, "interface")));
        }
        Object all = value(object(network, "allNetworks"));
        if (!(all instanceof JSONArray)) vpn.complete = false;
        else for (int i = 0; i < ((JSONArray) all).length(); i++) {
            JSONObject row = ((JSONArray) all).optJSONObject(i);
            Object cap = value(object(row, "capabilities"));
            if (cap instanceof JSONObject) vpn.network((JSONObject) cap); else vpn.complete = false;
            vpn.iface(value(object(row, "interface")));
        }
        Object ifaces = value(object(network, "interfaces"));
        if (!(ifaces instanceof JSONArray)) vpn.complete = false;
        else for (int i = 0; i < ((JSONArray) ifaces).length(); i++) {
            JSONObject row = ((JSONArray) ifaces).optJSONObject(i);
            vpn.iface(row == null ? null : row.opt("name"));
        }
        Object nativeIfaces = value(object(nativeData, "interfaces"));
        if (!(nativeIfaces instanceof JSONArray)) vpn.complete = false;
        else for (int i = 0; i < ((JSONArray) nativeIfaces).length(); i++) vpn.iface(((JSONArray) nativeIfaces).opt(i));
        JSONObject events = object(guest, "networkCallback");
        JSONArray caps = events.optJSONArray("events");
        if (!"OK".equals(events.optString("status")) || caps == null || caps.length() == 0) vpn.complete = false;
        else for (int i = 0; i < caps.length(); i++) {
            JSONObject cap = caps.optJSONObject(i);
            if (cap == null) vpn.complete = false; else vpn.network(cap);
        }
        State vpnState = CheckPolicy.mask(vpn.complete, outer.vpnKnown && outer.vpn && outer.tunnel, vpn.signal);
        report.add("vpn_mask", "VPN 状态屏蔽", vpnState, vpnState == State.PASSED
                ? "手机 VPN 正在运行；空间内已测网络能力、回调和接口未显示 VPN。"
                : vpn.signal ? "空间内仍可观察到 VPN 状态或接口。" : "需要活动 VPN 和完整接口读数作对照。");

        JSONObject linkMetadata = active instanceof JSONObject ? object((JSONObject) active, "metadata") : new JSONObject();
        JSONArray linkEvents = events.optJSONArray("linkProperties");
        boolean metadataComplete = guest.optInt("networkMetadataVersion") == 1
                && NetworkMetadata.complete(outer.linkMetadata) && NetworkMetadata.complete(linkMetadata)
                && "OK".equals(events.optString("status")) && linkEvents != null && linkEvents.length() > 0;
        boolean metadataEmpty = NetworkMetadata.empty(linkMetadata);
        if (linkEvents != null) for (int i = 0; i < linkEvents.length(); i++) {
            JSONObject sample = object(linkEvents.optJSONObject(i), "metadata");
            metadataComplete &= NetworkMetadata.complete(sample);
            metadataEmpty &= NetworkMetadata.empty(sample);
        }
        boolean metadataPositive = outer.activeVpn && NetworkMetadata.hasPayload(outer.linkMetadata)
                && outer.linkMetadata.optBoolean("tunnelInterfaceName");
        State metadataState = CheckPolicy.networkMetadata(metadataComplete, metadataPositive, metadataEmpty);
        report.add("vpn_metadata", "VPN 网络信息", metadataState,
                metadataState == State.PASSED
                        ? "已测 VPN 链路及回调未携带地址、DNS、路由或代理信息；其他网络接口仍需单独验证。"
                        : metadataState == State.FAILED
                        ? "活动链路或回调仍含网络信息，需要核对过滤范围。"
                        : "缺少有效的 VPN 网络信息对照或完整读数，不能认定已隔离。", true);

        long sampled = guest.optLong("epochMillis", System.currentTimeMillis());
        int offset = TimeZone.getTimeZone(outer.zone).getOffset(sampled) / 60000;
        String signedOffset = String.format(Locale.ROOT, "%s%02d%02d",
                offset < 0 ? "-" : "+", Math.abs(offset) / 60, Math.abs(offset) % 60);
        Measure zone = new Measure();
        JSONObject time = object(guest, "timezone");
        for (String key : new String[]{"javaId", "icuId", "environmentTZ"}) {
            zone.equalsValue(value(object(time, key)), outer.zone);
        }
        for (String key : new String[]{"javaOffsetMinutes", "icuOffsetMinutes"}) {
            zone.number(value(object(time, key)), offset);
        }
        zone.equalsValue(value(object(nativeProps, "persist.sys.timezone")), outer.zone);
        zone.equalsValue(value(object(childProps, "persist.sys.timezone")), outer.zone);
        zone.equalsValue(legacy.opt("persist.sys.timezone"), outer.zone);
        zone.equalsValue(callback.opt("persist.sys.timezone"), outer.zone);
        Object date = value(object(child, "date"));
        if (!(date instanceof String)) zone.complete = false;
        else if (!((String) date).contains("|" + signedOffset + "|")) zone.matches = false;
        for (String key : new String[]{"initialTime", "afterTzsetTime"}) {
            Object reading = value(object(nativeData, key));
            if (!(reading instanceof JSONObject)) zone.complete = false;
            else zone.number(((JSONObject) reading).opt("offsetSeconds"), offset * 60);
        }
        report.add("guest_settings", "系统时区", CheckPolicy.timezone(zone.complete, zone.matches),
                zone.complete && zone.matches ? "各层时区与 " + outer.zone + " 一致。"
                        : "存在缺失或不一致的时区读数；目标为 " + outer.zone + "。");

        JSONObject web = object(guest, "webview");
        Object webValue = value(web);
        JSONObject page = webValue instanceof JSONObject ? (JSONObject) webValue : new JSONObject();
        JSONObject first = object(page, "firstScript");
        boolean complete = page.has("timezone") && first.has("timezone")
                && page.has("offsetMinutes") && first.has("offsetMinutes");
        boolean correct = outer.zone.equals(page.optString("timezone"))
                && outer.zone.equals(first.optString("timezone"))
                && page.optInt("offsetMinutes") == offset && first.optInt("offsetMinutes") == offset;
        report.add("browser_settings", "网页时区", CheckPolicy.timezone(complete, correct),
                !complete ? "本轮未取得完整网页读数。" : correct
                        ? "本轮首次脚本与加载后读数均符合 " + outer.zone + "。"
                        : "网页时区与 " + outer.zone + " 不一致。首次："
                                + first.optString("timezone", "未知") + "，加载后："
                                + page.optString("timezone", "未知") + "。");

        if (!outer.language.isEmpty()) {
            String actual = guest.optString("guestLanguage");
            report.add("language", "空间语言",
                    actual.isEmpty() ? State.UNKNOWN : outer.language.equalsIgnoreCase(actual) ? State.PASSED : State.FAILED,
                    "预期 " + outer.language + "，本轮读取 " + actual + "。");
        }

        JSONObject egress = object(guest, "egress");
        String ip = egress.optString("ip"), hostIp = outer.egress.optString("ip");
        boolean validIp = "OK".equals(egress.optString("status")) && NetworkProbe.publicIpv4(ip);
        report.add("proxy_transport", "空间联网", validIp ? State.PASSED : State.UNKNOWN,
                validIp ? "空间已通过固定 HTTPS 服务取得出口读数。" : "出口检测未完成或超时，请确认手机代理可用后重试。");

        String country = egress.optString("country"), locationZone = egress.optString("timezone");
        report.location = country.matches("[A-Z]{2}") ? "出口地区 " + country : "出口地区尚未确认";
        report.add("exit_region", "出口地区",
                !country.matches("[A-Z]{2}") ? State.UNKNOWN : "CN".equals(country) ? State.FAILED : State.PASSED,
                country.matches("[A-Z]{2}") ? "检测地区：" + country + "；当前规则为中国大陆以外。"
                        : "地区查询未取得有效结果，不能凭连接成功判断地区。");

        report.add("exit_consistency", "出口一致性",
                !validIp || !NetworkProbe.publicIpv4(hostIp) ? State.UNKNOWN
                        : ip.equals(hostIp) ? State.PASSED : State.FAILED,
                validIp && ip.equals(hostIp) ? "手机与空间在本轮测得相同公网出口。"
                        : "手机与空间出口不同，或有一侧未取得读数。");

        report.add("exit_timezone", "出口与时区匹配",
                locationZone.isEmpty() ? State.UNKNOWN
                        : outer.zone.equals(locationZone) ? State.PASSED : State.FAILED,
                locationZone.isEmpty() ? "未取得出口时区，无法核对。"
                        : "出口时区：" + locationZone + "；空间目标：" + outer.zone + "。");

        JSONObject guard = object(guest, "socketGuard");
        boolean pinned = guard.optBoolean("bound");
        State binding = !guard.has("bound") ? State.UNKNOWN : pinned ? State.PASSED : State.FAILED;
        report.add("fail_closed", "代理断线保护 · 网络绑定", binding,
                pinned ? "此次来宾进程的默认连接已绑定 VPN。隧道丢失时停止该进程。此项没有模拟代理上游故障。"
                        : "未确认来宾进程绑定到 VPN。", true);
        report.add("dns_transport", "DNS · 隧道解析",
                pinned && guard.optBoolean("resolver") ? State.PASSED : State.UNKNOWN,
                "检查绑定后的系统域名解析。代理上游实际使用的解析器需在代理端核对。", true);
        for (String key : new String[]{"udp4", "udp6"}) {
            String mark = guard.optString(key);
            State routeState = "VPN_MARK".equals(mark) ? State.PASSED
                    : "OTHER_MARK".equals(mark) ? State.FAILED : State.UNKNOWN;
            if ("udp6".equals(key)) {
                report.add(key, "公网 IPv6 阻断",
                        "NO_ROUTE".equals(mark) ? State.PASSED
                                : "REACHABLE".equals(mark) ? State.FAILED : State.UNKNOWN,
                        "NO_ROUTE".equals(mark) ? "来宾内核拒绝了 IPv6 测试套接字，未发送负载。"
                                : "未证实内核阻断 IPv6。", true);
                continue;
            }
            report.add(key, "UDP · IPv4 路由", routeState,
                    "VPN_MARK".equals(mark) ? "内核为测试套接字选择了当前 VPN。未发送 UDP 负载。"
                            : "NO_ROUTE".equals(mark) ? "内核拒绝建立此地址族的路由。"
                            : "未确认此套接字的 VPN 路由标记。", true);
        }
    }
}
