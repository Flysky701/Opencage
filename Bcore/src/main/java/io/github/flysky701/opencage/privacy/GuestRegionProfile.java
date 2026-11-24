package io.github.flysky701.opencage.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.util.ULocale;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.IllformedLocaleException;
import java.util.Locale;

/** Local settings shared by the launcher and the guest processes via atomic files. */
public final class GuestRegionProfile {
    public static final String BINDING_FILE = "cage-exit-config.json";
    private static final String OVERRIDE_FILE = "cage-region-override.json";
    private static final String FALLBACK_ZONE = "Asia/Tokyo";
    public final String region;
    public final String language;
    public final String zone;
    public final String exitZone;
    public final String profileId;

    private GuestRegionProfile(String region, String language, String zone, String exitZone, String profileId) {
        this.region = region;
        this.language = language;
        this.zone = zone;
        this.exitZone = exitZone;
        this.profileId = profileId;
    }

    public static GuestRegionProfile read(Context host) {
        JSONObject manual = readOverrides(host);
        JSONObject automatic = readJson(host, BINDING_FILE);
        String language = manual.optString("language", "");
        String zone = manual.optString("zone", "");
        String exitZone = automatic.optString("zone", "");
        return new GuestRegionProfile(
                automatic.optString("region", ""),
                language.isEmpty() ? automatic.optString("language", "") : language,
                !zone.isEmpty() ? zone : exitZone.isEmpty() ? FALLBACK_ZONE : exitZone,
                exitZone,
                automatic.optString("profile", ""));
    }

    public static String override(Context host, boolean language) {
        return readOverrides(host).optString(language ? "language" : "zone", "");
    }

    public static void setOverride(Context host, String value, boolean language) throws IOException {
        String normalized = value.trim();
        if (!normalized.isEmpty()) normalized = language ? languageTag(normalized) : timezone(normalized);
        JSONObject current = readOverrides(host);
        try {
            current.put(language ? "language" : "zone", normalized);
        } catch (Exception invalid) {
            throw new IOException("空间地区设置未保存。");
        }
        writeJson(host, OVERRIDE_FILE, current);
        options(host).edit().putString(language ? "language" : "zone", normalized).commit();
    }

    public static String timezone(String value) {
        if (!ZoneId.getAvailableZoneIds().contains(value)) {
            throw new IllegalArgumentException("请输入有效的时区，例如 Asia/Tokyo。");
        }
        return value;
    }

    public static String languageTag(String value) {
        try {
            Locale locale = new Locale.Builder().setLanguageTag(value).build();
            if (!locale.getLanguage().isEmpty() && !"und".equals(locale.getLanguage())) return locale.toLanguageTag();
        } catch (IllformedLocaleException invalid) {
            // Return the same explanation for an invalid or unspecified language tag.
        }
        throw new IllegalArgumentException("请输入有效的语言标签，例如 ja-JP。");
    }

    public static String preferredLanguage(String region) {
        return languageTag(ULocale.addLikelySubtags(new ULocale("und_" + region)).toLanguageTag());
    }

    private static JSONObject readOverrides(Context host) {
        File overrideFile = new File(host.getNoBackupFilesDir(), OVERRIDE_FILE);
        if (overrideFile.isFile()) return readJson(host, OVERRIDE_FILE);
        SharedPreferences legacy = options(host);
        JSONObject fallback = new JSONObject();
        try {
            fallback.put("language", legacy.getString("language", ""));
            fallback.put("zone", legacy.getString("zone", ""));
        } catch (Exception ignored) {}
        return fallback;
    }

    private static JSONObject readJson(Context host, String name) {
        AtomicFile file = new AtomicFile(new File(host.getNoBackupFilesDir(), name));
        try {
            return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void writeJson(Context host, String name, JSONObject value) throws IOException {
        AtomicFile atomic = new AtomicFile(new File(host.getNoBackupFilesDir(), name));
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException failed) {
            atomic.failWrite(stream);
            throw failed;
        }
    }

    private static SharedPreferences options(Context host) {
        return host.getSharedPreferences("cage-guest-region", Context.MODE_PRIVATE);
    }
}
