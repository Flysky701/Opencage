package io.github.flysky701.opencage.privacy.internal;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import io.github.flysky701.opencage.privacy.GuestRegionProfile;
import java.util.Locale;
import java.util.TimeZone;
import top.niunaijun.blackbox.BlackBoxCore;

/** Supplies the active space region/timezone to the privacy stack; VPN pinning runs in CandidateApplication. */
final class GuestNetwork {
    private GuestNetwork() {}

    static String zone() {
        Context host = BlackBoxCore.getContext();
        if (host == null) return Profile.ZONE;
        String value = GuestRegionProfile.read(host).zone;
        return value == null || value.isEmpty() ? Profile.ZONE : value;
    }

    static void applyRegion(Context guest) {
        Context host = BlackBoxCore.getContext();
        if (host == null || guest == null) return;
        GuestRegionProfile profile = GuestRegionProfile.read(host);
        if (profile.language != null && !profile.language.isEmpty()) {
            LocaleList locales = LocaleList.forLanguageTags(profile.language);
            Resources res = guest.getResources();
            Configuration config = new Configuration(res.getConfiguration());
            if (!config.getLocales().equals(locales)) {
                config.setLocales(locales);
                res.updateConfiguration(config, res.getDisplayMetrics());
            }
            LocaleList.setDefault(locales);
            if (!locales.isEmpty()) Locale.setDefault(locales.get(0));
        }
        String id = zone();
        TimeZone.setDefault(TimeZone.getTimeZone(id));
        System.setProperty("user.timezone", id);
        GuestPrivacy.setIcuZone(android.icu.util.TimeZone.getTimeZone(id));
        try { Os.setenv("TZ", id, true); }
        catch (ErrnoException unavailable) { Log.w("CagePrivacy", "Guest TZ env unavailable"); }
    }

    static void require() {}
    static void rethrowIfBlocked() {}
}
