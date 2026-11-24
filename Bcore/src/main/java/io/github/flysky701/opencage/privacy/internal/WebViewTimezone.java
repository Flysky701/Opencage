package io.github.flysky701.opencage.privacy.internal;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Configures Chromium's real timezone path; first-script coverage is provider-specific.
 * Modern Chromium (Android 13+ / WebView 156/158+) receives the native monitor update
 * asynchronously in isolated renderers, so installing before engine startup does not
 * guarantee its first script sees the zone.
 * Modern startup controller and TimeZoneMonitor ContextUtils fields are discovered
 * structurally at runtime without modifying global flag files, system broadcasts,
 * debugging endpoints, page scripts, or reports.
 */
public final class WebViewTimezone {
    public interface Scope { boolean isCurrentGuest(String packageName); }

    private static final String PROVIDER = "com.android.webview";
    private static final String PHONE_PROVIDER = "com.google.android.webview";
    private static final String FACTORY = "com.android.webview.chromium.WebViewChromiumFactoryProvider";
    private static final String TIMEZONE_MONITOR = "org.chromium.device.time_zone_monitor.TimeZoneMonitor";
    private static final String TIMEZONE_MONITOR_DESC = "Lorg/chromium/device/time_zone_monitor/TimeZoneMonitor;";
    private static final String CONTEXT_DESC = "Landroid/content/Context;";
    private static String status = "NOT_PREPARED";
    private static String stage = "none";
    private static String errorClass;
    private static String owner, zone;
    private static boolean attempted, providerTouched, initializedProvider, prepared, stopped;
    private static boolean engineWasNotStarted, suffixWasConfigured;
    private static String profileName;
    private static MonitorContext monitorContext;
    private static final AtomicInteger monitorNotifications = new AtomicInteger();
    private static volatile String monitorError;

    private WebViewTimezone() {}

    private static boolean warmed;
    /** Start the default renderer while the guest is still initializing. No page script or remote URL. */
    static synchronized void warmRenderer(Context context) {
        if (warmed || !prepared || stopped || monitorContext == null
                || Looper.myLooper() != Looper.getMainLooper()) return;
        warmed = true;
        try {
            WebView view = new WebView(context);
            Handler main = new Handler(Looper.getMainLooper());
            Runnable release = new Runnable() {
                private boolean closed;
                public void run() {
                    if (!closed) { closed = true; view.destroy(); }
                }
            };
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) { main.post(release); }
                @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                    main.post(release);
                    return true;
                }
            });
            view.loadData("<!doctype html><title></title>", "text/html", "UTF-8");
            main.postDelayed(release, 5000);
        } catch (RuntimeException unavailable) {
            android.util.Log.i("CagePrivacy", "WebView warmup unavailable");
        }
    }

    /**
     * Call synchronously after publishing the guest's InitialApplication and before providers.
     * Earlier calls defer, and an already initialized foreign/unknown provider is not modified.
     * A true result is configuration evidence, not a replacement for first-script verification.
     */
    public static synchronized boolean prepare(Context guest, String packageName,
            String zoneId, Scope scope) {
        if (guest == null || scope == null || !selected(packageName)
                || !scope.isCurrentGuest(packageName)) return false;
        if (stopped) {
            status = "FRESH_PROCESS_REQUIRED";
            return false;
        }
        if (prepared) {
            if (!packageName.equals(owner) || !zone.equals(zoneId)) {
                status = "FRESH_PROCESS_REQUIRED";
                return false;
            }
            try {
                stage = "monitorContextRead";
                boolean retained = monitorContext.isAttached() && monitorContext.active;
                status = retained ? (monitorNotifications.get() == 0 ? "ARMED_BEFORE_ENGINE"
                    : "MONITOR_NOTIFIED_RUNTIME_UNVERIFIED") : "MONITOR_CONTEXT_CHANGED";
                return retained;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                errorClass = errorType(e);
                status = "MONITOR_CONTEXT_READ_ERROR";
                return false;
            }
        }
        if (attempted) return false;
        if (Build.VERSION.SDK_INT < 33) {
            status = "UNSUPPORTED_ANDROID_VERSION";
            return false;
        }
        if (zoneId == null || !java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zoneId)) {
            status = "UNSUPPORTED_ZONE";
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            status = "DEFER_MAIN_THREAD";
            return false;
        }
        if (!zoneId.equals(TimeZone.getDefault().getID())) {
            status = "DEFER_JAVA_TIMEZONE";
            return false;
        }

        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            stage = "application";
            Application application = (Application) Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null);
            if (application == null || application.getApplicationInfo() == null
                    || !packageName.equals(application.getApplicationInfo().packageName)
                    || !packageName.equals(application.getPackageName())) {
                status = "DEFER_GUEST_APPLICATION";
                return false;
            }
            ClassLoader guestLoader = application.getClassLoader();
            if (guestLoader == null || guest.getClassLoader() != guestLoader) {
                status = "DEFER_GUEST_CLASS_LOADER";
                return false;
            }
            stage = "providerSelection";
            PackageInfo currentWebView = WebView.getCurrentWebViewPackage();
            if (!supportsProvider(currentWebView)) {
                status = "UNSUPPORTED_WEBVIEW_PROVIDER";
                return false;
            }
            profileName = "android" + Build.VERSION.SDK_INT + "-chromium-" + packageVersionLabel(currentWebView);

            stage = "providerLock";
            Class<?> androidFactory = Class.forName("android.webkit.WebViewFactory");
            Object providerLock = field(androidFactory, "sProviderLock").get(null);
            if (providerLock == null) throw new IllegalStateException("Missing provider lock");
            synchronized (providerLock) {
                if (field(androidFactory, "sProviderInstance").get(null) != null) {
                    status = "EXISTING_PROVIDER_NOT_COVERED";
                    attempted = true;
                    return false;
                }
                Object suffix = field(androidFactory, "sDataDirectorySuffix").get(null);
                suffixWasConfigured = suffix instanceof String && !((String) suffix).isEmpty();
                if (!suffixWasConfigured) {
                    status = "DEFER_DATA_DIRECTORY_SUFFIX";
                    return false;
                }
                if (!scope.isCurrentGuest(packageName)) return false;

                attempted = true;
                owner = packageName;
                zone = zoneId;
                // WebViewDelegate reads the now-published guest Application. Use its loader
                // for any context-loader lookup during provider construction as well.
                Thread.currentThread().setContextClassLoader(guestLoader);
                Method getProvider = method(androidFactory, "getProvider");
                providerTouched = true;
                stage = "getProvider";
                Object provider = getProvider.invoke(null);
                initializedProvider = true;
                if (provider == null || field(androidFactory, "sProviderInstance").get(null) != provider)
                    throw new IllegalStateException("Provider identity changed");
                PackageInfo loaded = (PackageInfo) method(androidFactory, "getLoadedPackageInfo").invoke(null);
                if (!sameWebViewPackage(currentWebView, loaded))
                    throw new IllegalStateException("Provider version changed");

                stage = "engineState";
                ClassLoader providerLoader = provider.getClass().getClassLoader();
                Class<?> factoryClass = Class.forName(FACTORY, false, providerLoader);
                if (!factoryClass.isInstance(provider)) throw new IllegalStateException("Unexpected provider");
                EngineState engine = discoverModernEngineState(provider);
                synchronized (engine.lock) {
                    if (!engine.isIdle()) {
                        status = "ENGINE_ALREADY_STARTING_NOT_COVERED";
                        return false;
                    }
                    engineWasNotStarted = true;
                    stage = "monitorContext";
                    monitorContext = new MonitorContext(loaded, providerLoader, packageName, zoneId, scope);
                    monitorContext.attach();
                    if (!engine.isIdle() || !scope.isCurrentGuest(packageName)) {
                        monitorContext.deactivate();
                        status = "ENGINE_CHANGED_DURING_MONITOR_PREPARE";
                        return false;
                    }
                    prepared = true;
                    status = "ARMED_BEFORE_ENGINE";
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            errorClass = errorType(e);
            status = "PREPARE_ERROR";
            if (monitorContext != null) {
                try { monitorContext.deactivate(); }
                catch (ReflectiveOperationException | RuntimeException restoreError) {
                    status = "RESTORE_ERROR_FRESH_PROCESS_REQUIRED";
                    errorClass = errorType(restoreError);
                }
            }
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    /**
     * Stops the scoped monitor, then rejects reuse of this process.
     * Existing renderers and the provider may retain the original guest state.
     */
    public static synchronized void stopAndRestore() {
        if (!providerTouched) return;
        stopped = true;
        prepared = false;
        stage = "restore";
        try {
            if (monitorContext != null) monitorContext.deactivate();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            status = "RESTORE_ERROR_FRESH_PROCESS_REQUIRED";
            errorClass = errorType(e);
            throw new IllegalStateException("Cannot restore scoped WebView monitor; fresh process required", e);
        }
        status = "FRESH_PROCESS_REQUIRED";
        throw new IllegalStateException("Scoped WebView retains guest state; fresh process required before changing guest");
    }

    /** Call before reusing a cached application and keep the current state. */
    public static synchronized void assertProcessReusable(String packageName) {
        if (providerTouched && (stopped || owner == null || !owner.equals(packageName))) {
            stopped = true;
            status = "FRESH_PROCESS_REQUIRED";
            throw new IllegalStateException("Scoped WebView process cannot be rebound to another guest");
        }
    }

    public static synchronized boolean isBlocked() { return stopped; }

    public static synchronized Bundle diagnostics() {
        Bundle out = new Bundle();
        out.putString("status", "ARMED_BEFORE_ENGINE".equals(status) && monitorNotifications.get() > 0
            ? "MONITOR_NOTIFIED_RUNTIME_UNVERIFIED" : status);
        out.putString("stage", stage);
        out.putBoolean("preparedBeforeEngine", prepared);
        out.putBoolean("engineWasNotStarted", engineWasNotStarted);
        out.putBoolean("nativeCommandLine", false);
        out.putBoolean("suffixWasConfigured", suffixWasConfigured);
        out.putBoolean("providerInitializedByHelper", initializedProvider);
        out.putBoolean("commandLineRestored", false);
        out.putBoolean("requiresFreshProcessOnGuestChange", providerTouched || stopped);
        out.putBoolean("firstScriptRuntimeVerified", false);
        out.putString("providerProfile", profileName == null ? "none" : profileName);
        // The Android13+ Java callback updates the browser monitor, not each renderer.
        // A notification count or a separate probe's reading must never certify a
        // subsequently created renderer belonging to the target application.
        if (profileName != null) {
            out.putBoolean("rendererInitialZoneGuaranteed", false);
            out.putBoolean("monitorNotificationAcknowledgedByRenderer", false);
            out.putString("firstScriptCoverage", "ISOLATED_RENDERER_STARTUP_UNCOVERED");
        }
        out.putInt("monitorNotifications", monitorNotifications.get());
        out.putString("strategy", monitorContext == null ? "none" : "native-monitor");
        out.putBoolean("monitorContextRestored", monitorContext != null && monitorContext.restored);
        if (monitorError != null) out.putString("monitorError", monitorError);
        if (errorClass != null) out.putString("errorClass", errorClass);
        return out;
    }

    private static boolean selected(String packageName) {
        return "com.anthropic.claude".equals(packageName)
            || "io.github.flysky701.opencage.preflightprobe".equals(packageName)
            || "io.github.flysky701.opencage.probe".equals(packageName);
    }

    private static boolean supportsProvider(PackageInfo info) {
        return info != null && info.packageName != null
            && (info.packageName.startsWith(PHONE_PROVIDER) || PROVIDER.equals(info.packageName));
    }

    private static String packageVersionLabel(PackageInfo info) {
        return info.versionName != null && !info.versionName.isEmpty()
            ? info.versionName
            : String.valueOf(info.getLongVersionCode());
    }

    private static boolean sameWebViewPackage(PackageInfo expected, PackageInfo actual) {
        return expected != null && actual != null
            && expected.packageName != null && expected.packageName.equals(actual.packageName)
            && expected.getLongVersionCode() == actual.getLongVersionCode();
    }

    /**
     * Locates Chromium's modern StartupController singleton structurally from provider fields,
     * independent of R8-obfuscated class/field names across WebView 156/158+.
     */
    private static EngineState discoverModernEngineState(Object provider) throws IllegalAccessException {
        IdentityHashMap<Object, Boolean> providerObjects = new IdentityHashMap<>();
        List<Object> roots = new ArrayList<>();
        collectNonPlatformFieldValues(provider, providerObjects, roots);

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        List<Object> candidates = new ArrayList<>();
        for (Object root : roots) {
            if (visited.put(root, Boolean.TRUE) == null) candidates.add(root);
            List<Object> nested = new ArrayList<>();
            collectNonPlatformFieldValues(root, null, nested);
            for (Object child : nested) {
                if (visited.put(child, Boolean.TRUE) == null) candidates.add(child);
            }
        }

        List<EngineState> matches = new ArrayList<>();
        for (Object candidate : candidates) {
            EngineState match = matchStartupController(candidate, providerObjects);
            if (match != null) matches.add(match);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Unexpected startup controller candidates: " + matches.size());
        }
        return matches.get(0);
    }

    private static void collectNonPlatformFieldValues(Object ownerObj,
            IdentityHashMap<Object, Boolean> outSet, List<Object> outList) throws IllegalAccessException {
        if (ownerObj == null) return;
        for (Field f : allDeclaredFields(ownerObj.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t.isPrimitive() || t.isArray()) continue;
            String n = t.getName();
            if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.")) continue;
            f.setAccessible(true);
            Object v = f.get(ownerObj);
            if (v != null) {
                if (outSet != null) outSet.put(v, Boolean.TRUE);
                outList.add(v);
            }
        }
    }

    private static EngineState matchStartupController(Object candidate,
            IdentityHashMap<Object, Boolean> providerObjects) throws IllegalAccessException {
        Class<?> type = candidate.getClass();
        List<Field> fields = allDeclaredFields(type);
        boolean hasSelfSingleton = false;
        boolean hasDelegateRef = false;
        Field latchField = null;
        int latchCount = 0;
        Field lockField = null;
        int lockCount = 0;
        Field runtimeErrField = null;
        int runtimeErrCount = 0;
        Field fatalErrField = null;
        int fatalErrCount = 0;
        List<Field> counterFields = new ArrayList<>();

        for (Field f : fields) {
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) {
                if (f.getType() == type && f.get(null) == candidate) {
                    hasSelfSingleton = true;
                }
                continue;
            }
            Class<?> ft = f.getType();
            if (ft == CountDownLatch.class) {
                latchCount++;
                latchField = f;
            } else if (ft == AtomicInteger.class) {
                if (!(f.get(candidate) instanceof AtomicInteger)) return null;
                counterFields.add(f);
            } else if (ft == Object.class) {
                lockCount++;
                lockField = f;
            } else if (ft == RuntimeException.class) {
                runtimeErrCount++;
                runtimeErrField = f;
            } else if (ft == Error.class) {
                fatalErrCount++;
                fatalErrField = f;
            } else if (!ft.isPrimitive() && !ft.isArray()) {
                Object val = f.get(candidate);
                if (val != null && val != candidate && providerObjects.containsKey(val)) {
                    hasDelegateRef = true;
                }
            }
        }
        if (!hasSelfSingleton || !hasDelegateRef || latchCount != 1 || counterFields.isEmpty()
                || lockCount != 1 || runtimeErrCount != 1 || fatalErrCount != 1) {
            return null;
        }
        Object lock = lockField.get(candidate);
        if (lock == null || lock.getClass() != Object.class) return null;
        if (!(latchField.get(candidate) instanceof CountDownLatch)) return null;
        return new EngineState(candidate, lock, counterFields.toArray(new Field[0]),
                latchField, runtimeErrField, fatalErrField);
    }

    private static List<Field> allDeclaredFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Collections.addAll(out, c.getDeclaredFields());
        }
        return out;
    }

    private static final class EngineState {
        final Object controller, lock;
        final Field[] counters;
        final Field latch, runtimeError, fatalError;

        EngineState(Object controller, Object lock, Field[] counters, Field latch,
                Field runtimeError, Field fatalError) {
            if (lock == null || lock.getClass() != Object.class)
                throw new IllegalStateException("Unexpected engine lock");
            this.controller = controller;
            this.lock = lock;
            this.counters = counters;
            this.latch = latch;
            this.runtimeError = runtimeError;
            this.fatalError = fatalError;
        }

        boolean isIdle() throws IllegalAccessException {
            for (Field counter : counters) {
                Object val = counter.get(controller);
                if (!(val instanceof AtomicInteger) || ((AtomicInteger) val).get() != 0) return false;
            }
            Object completion = latch.get(controller);
            return completion instanceof CountDownLatch
                && ((CountDownLatch) completion).getCount() == 1
                && runtimeError.get(controller) == null
                && fatalError.get(controller) == null;
        }
    }

    /**
     * Modern WebView removed time-zone-for-testing. Its native monitor receives an explicit
     * zone through TimeZoneMonitor's BroadcastReceiver, so intercept ContextUtils' receiver
     * registration without altering JavaScript globals or pages.
     */
    private static final class MonitorContext extends ContextWrapper {
        private interface RegisterCall { Intent run(BroadcastReceiver wrapped); }

        final Field contextField, receiverOwner, monitorReceiver, nativePointer;
        final Class<?> receiverType, monitorType;
        final String packageName, zoneId;
        final Scope scope;
        final Map<BroadcastReceiver, MonitorReceiver> receivers = new IdentityHashMap<>();
        volatile boolean active = true, restored;

        MonitorContext(PackageInfo loaded, ClassLoader loader, String packageName,
                String zoneId, Scope scope) throws ReflectiveOperationException {
            this(loaded, loader, packageName, zoneId, scope,
                findContextUtilsField(loaded, loader, packageName));
        }

        private MonitorContext(PackageInfo loaded, ClassLoader loader, String packageName,
                String zoneId, Scope scope, Field contextField) throws ReflectiveOperationException {
            super((Context) contextField.get(null));
            this.contextField = contextField;
            this.packageName = packageName;
            this.zoneId = zoneId;
            this.scope = scope;
            Context base = getBaseContext();
            if (base == null || !packageName.equals(base.getPackageName())
                    || base.getApplicationInfo() == null
                    || !packageName.equals(base.getApplicationInfo().packageName))
                throw new IllegalStateException("Unexpected Chromium context owner");

            monitorType = Class.forName(TIMEZONE_MONITOR, false, loader);
            monitorReceiver = uniqueInstanceFieldByAssignableType(monitorType, BroadcastReceiver.class);
            nativePointer = uniqueInstanceFieldByExactType(monitorType, long.class);
            receiverType = monitorReceiver.getType();
            if (receiverType == BroadcastReceiver.class || !BroadcastReceiver.class.isAssignableFrom(receiverType))
                throw new IllegalStateException("Unexpected monitor receiver");
            receiverOwner = uniqueInstanceFieldByExactType(receiverType, monitorType);
            instanceMethod(receiverType, "onReceive", void.class, Context.class, Intent.class);
            instanceMethod(monitorType, "stop", void.class);
        }

        void attach() throws IllegalAccessException {
            if (contextField.get(null) != getBaseContext())
                throw new IllegalStateException("Chromium context changed");
            contextField.set(null, this);
            if (!isAttached()) throw new IllegalStateException("Chromium context write failed");
        }

        boolean isAttached() throws IllegalAccessException { return contextField.get(null) == this; }

        synchronized void deactivate() throws IllegalAccessException {
            active = false;
            restoreIfUnused();
        }

        private synchronized void restoreIfUnused() throws IllegalAccessException {
            // Keep unregisterReceiver's identity translation until Chromium unregisters
            // its final monitor. Native ICU/renderer state still requires a fresh process.
            if (!active && receivers.isEmpty() && isAttached()) {
                contextField.set(null, getBaseContext());
                restored = contextField.get(null) == getBaseContext();
                if (!restored) throw new IllegalStateException("Chromium context restore failed");
            }
        }

        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            if (receiver == null || receiver.getClass() != receiverType)
                return super.registerReceiver(receiver, filter);
            return registerTargetMonitorReceiver(receiver, filter, w -> super.registerReceiver(w, filter));
        }

        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            if (receiver == null || receiver.getClass() != receiverType)
                return super.registerReceiver(receiver, filter, flags);
            return registerTargetMonitorReceiver(receiver, filter, w -> super.registerReceiver(w, filter, flags));
        }

        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler) {
            if (receiver == null || receiver.getClass() != receiverType)
                return super.registerReceiver(receiver, filter, permission, scheduler);
            return registerTargetMonitorReceiver(receiver, filter,
                w -> super.registerReceiver(w, filter, permission, scheduler));
        }

        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String permission, Handler scheduler, int flags) {
            if (receiver == null || receiver.getClass() != receiverType)
                return super.registerReceiver(receiver, filter, permission, scheduler, flags);
            return registerTargetMonitorReceiver(receiver, filter,
                w -> super.registerReceiver(w, filter, permission, scheduler, flags));
        }

        private Intent registerTargetMonitorReceiver(BroadcastReceiver receiver, IntentFilter filter,
                RegisterCall call) {
            if (filter == null || filter.countActions() != 1
                    || !filter.hasAction(Intent.ACTION_TIMEZONE_CHANGED))
                throw new IllegalStateException("Unexpected monitor filter");
            final MonitorReceiver forward;
            try {
                Object monitor = receiverOwner.get(receiver);
                if (!monitorType.isInstance(monitor) || monitorReceiver.get(monitor) != receiver
                        || nativePointer.getLong(monitor) == 0)
                    throw new IllegalStateException("Unexpected native monitor identity");
                synchronized (this) {
                    if (receivers.containsKey(receiver))
                        throw new IllegalStateException("Duplicate monitor registration");
                    forward = new MonitorReceiver(receiver, monitor, nativePointer.getLong(monitor));
                    receivers.put(receiver, forward);
                }
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot inspect native monitor", error);
            }
            final Intent sticky;
            try {
                sticky = call.run(forward);
            } catch (RuntimeException | Error error) {
                synchronized (this) { receivers.remove(receiver); }
                throw error;
            }
            // C++ calls getInstance after its TimeZoneMonitor base constructor has set
            // timezone_ and constructed the empty client set. This same-thread callback
            // only updates that base state; it does not use the not-yet-assigned impl_.
            // Runtime first-script timing remains explicitly unverified in diagnostics.
            try {
                forward.deliver(this, new Intent(Intent.ACTION_TIMEZONE_CHANGED)
                    .putExtra(Intent.EXTRA_TIMEZONE, zoneId), true);
            } catch (RuntimeException | LinkageError error) {
                monitorError = errorType(error);
            }
            return sticky;
        }

        @Override public void unregisterReceiver(BroadcastReceiver receiver) {
            final MonitorReceiver forward;
            synchronized (this) { forward = receivers.get(receiver); }
            super.unregisterReceiver(forward == null ? receiver : forward);
            synchronized (this) { if (forward != null) receivers.remove(receiver); }
            try { restoreIfUnused(); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }

        private final class MonitorReceiver extends BroadcastReceiver {
            final BroadcastReceiver original;
            final Object monitor;
            final long pointer;
            boolean delivering;
            MonitorReceiver(BroadcastReceiver original, Object monitor, long pointer) {
                this.original = original;
                this.monitor = monitor;
                this.pointer = pointer;
            }
            @Override public void onReceive(Context context, Intent intent) {
                deliver(context, intent, false);
            }
            synchronized void deliver(Context context, Intent intent, boolean initial) {
                if (delivering) {
                    monitorError = "REENTRANT_MONITOR_CALLBACK";
                    return;
                }
                try {
                    if (nativePointer.getLong(monitor) != pointer
                            || monitorReceiver.get(monitor) != original) {
                        monitorError = "MONITOR_STOPPED_OR_CHANGED";
                        return;
                    }
                    boolean scoped = active && scope.isCurrentGuest(packageName) && isAttached();
                    if (initial && !scoped) return;
                    Intent forwarded = intent;
                    // Preserve invalid/unrelated intents and all metadata. Only the
                    // explicit zone in this monitor's valid timezone event is scoped.
                    if (scoped && intent != null && Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                            && intent.getStringExtra(Intent.EXTRA_TIMEZONE) != null)
                        forwarded = new Intent(intent).putExtra(Intent.EXTRA_TIMEZONE, zoneId);
                    delivering = true;
                    original.onReceive(context, forwarded);
                    if (scoped && forwarded != intent) monitorNotifications.incrementAndGet();
                } catch (IllegalAccessException error) {
                    monitorError = errorType(error);
                    throw new IllegalStateException("Native monitor identity read failed", error);
                } finally {
                    delivering = false;
                }
            }
        }
    }

    /**
     * Parses TimeZoneMonitor.stop/getInstance Dalvik bytecode in the loaded WebView APK to locate
     * ContextUtils.sApplicationContext (static android.content.Context read via sget-object).
     */
    private static Field findContextUtilsField(PackageInfo loaded, ClassLoader loader,
            String packageName) throws ReflectiveOperationException {
        if (loaded == null || loaded.applicationInfo == null)
            throw new IllegalStateException("Missing loaded WebView package info");
        List<String> apkPaths = new ArrayList<>();
        ApplicationInfo appInfo = loaded.applicationInfo;
        if (appInfo.sourceDir != null && !appInfo.sourceDir.isEmpty()) apkPaths.add(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) {
            for (String split : appInfo.splitSourceDirs) {
                if (split != null && !split.isEmpty() && !apkPaths.contains(split)) apkPaths.add(split);
            }
        }
        Set<String> candidateRefs = new LinkedHashSet<>();
        for (String apkPath : apkPaths) {
            try (ZipFile zip = new ZipFile(apkPath)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("classes") && name.endsWith(".dex") && !name.contains("/")) {
                        byte[] dex = readEntryBytes(zip, entry);
                        collectTimeZoneMonitorContextRefs(dex, candidateRefs);
                        if (!candidateRefs.isEmpty()) break;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot inspect WebView APK DEX", e);
            }
            if (!candidateRefs.isEmpty()) break;
        }
        for (String ref : candidateRefs) {
            int sep = ref.indexOf('#');
            String classDesc = ref.substring(0, sep);
            String fieldName = ref.substring(sep + 1);
            if (!classDesc.startsWith("L") || !classDesc.endsWith(";")) continue;
            String className = classDesc.substring(1, classDesc.length() - 1).replace('/', '.');
            try {
                Class<?> cls = Class.forName(className, false, loader);
                Field f = typedField(cls, fieldName, Context.class, true);
                Object val = f.get(null);
                if (val instanceof Context && packageName.equals(((Context) val).getPackageName())) {
                    return f;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        throw new IllegalStateException("Cannot locate Chromium ContextUtils application context");
    }

    private static byte[] readEntryBytes(ZipFile zip, ZipEntry entry) throws IOException {
        int initCap = entry.getSize() > 0 && entry.getSize() < Integer.MAX_VALUE
            ? (int) entry.getSize() : 65536;
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream(initCap)) {
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) >= 0;) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void collectTimeZoneMonitorContextRefs(byte[] dex, Set<String> outRefs) {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return;
        int stringIdsSize = readIntLE(dex, 56);
        int stringIdsOff = readIntLE(dex, 60);
        int typeIdsSize = readIntLE(dex, 64);
        int typeIdsOff = readIntLE(dex, 68);
        int fieldIdsOff = readIntLE(dex, 84);
        int methodIdsOff = readIntLE(dex, 92);
        int classDefsSize = readIntLE(dex, 96);
        int classDefsOff = readIntLE(dex, 100);

        int classDataOff = -1;
        for (int i = 0; i < classDefsSize; i++) {
            int defBase = classDefsOff + i * 32;
            int classTypeIdx = readIntLE(dex, defBase);
            String desc = dexTypeDescriptor(dex, typeIdsOff, stringIdsOff, classTypeIdx);
            if (TIMEZONE_MONITOR_DESC.equals(desc)) {
                classDataOff = readIntLE(dex, defBase + 24);
                break;
            }
        }
        if (classDataOff <= 0 || classDataOff >= dex.length) return;

        int[] pos = new int[]{classDataOff};
        int staticFieldsSize = readUleb128(dex, pos);
        int instanceFieldsSize = readUleb128(dex, pos);
        int directMethodsSize = readUleb128(dex, pos);
        int virtualMethodsSize = readUleb128(dex, pos);
        for (int i = 0; i < staticFieldsSize + instanceFieldsSize; i++) {
            readUleb128(dex, pos);
            readUleb128(dex, pos);
        }
        scanEncodedMethods(dex, pos, directMethodsSize, methodIdsOff, fieldIdsOff, typeIdsOff,
            stringIdsOff, stringIdsSize, typeIdsSize, outRefs);
        scanEncodedMethods(dex, pos, virtualMethodsSize, methodIdsOff, fieldIdsOff, typeIdsOff,
            stringIdsOff, stringIdsSize, typeIdsSize, outRefs);
    }

    private static void scanEncodedMethods(byte[] dex, int[] pos, int count, int methodIdsOff,
            int fieldIdsOff, int typeIdsOff, int stringIdsOff, int stringIdsSize,
            int typeIdsSize, Set<String> outRefs) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            methodIdx += readUleb128(dex, pos);
            readUleb128(dex, pos); // accessFlags
            int codeOff = readUleb128(dex, pos);
            if (codeOff <= 0 || codeOff + 16 > dex.length) continue;
            int nameIdx = readIntLE(dex, methodIdsOff + methodIdx * 8 + 4);
            String mName = nameIdx >= 0 && nameIdx < stringIdsSize ? dexString(dex, stringIdsOff, nameIdx) : null;
            if (!"stop".equals(mName) && !"getInstance".equals(mName)) continue;

            int insnsSize = readIntLE(dex, codeOff + 12);
            int insnsStart = codeOff + 16;
            if (insnsStart + insnsSize * 2 > dex.length) continue;
            int pc = 0;
            while (pc < insnsSize) {
                int unit = readU16LE(dex, insnsStart + pc * 2);
                int op = unit & 0xFF;
                if (op == 0x62 && pc + 1 < insnsSize) {
                    int fieldIdx = readU16LE(dex, insnsStart + (pc + 1) * 2);
                    int fBase = fieldIdsOff + fieldIdx * 8;
                    if (fBase + 8 <= dex.length) {
                        int fClassIdx = readU16LE(dex, fBase);
                        int fTypeIdx = readU16LE(dex, fBase + 2);
                        int fNameIdx = readIntLE(dex, fBase + 4);
                        if (fTypeIdx < typeIdsSize && fClassIdx < typeIdsSize && fNameIdx < stringIdsSize) {
                            String fType = dexTypeDescriptor(dex, typeIdsOff, stringIdsOff, fTypeIdx);
                            if (CONTEXT_DESC.equals(fType)) {
                                String fClass = dexTypeDescriptor(dex, typeIdsOff, stringIdsOff, fClassIdx);
                                String fName = dexString(dex, stringIdsOff, fNameIdx);
                                outRefs.add(fClass + "#" + fName);
                            }
                        }
                    }
                }
                int width = dalvikInsnWidth(dex, insnsStart, pc, insnsSize);
                if (width <= 0) break;
                pc += width;
            }
        }
    }

    private static int dalvikInsnWidth(byte[] dex, int insnsStart, int pc, int insnsSize) {
        int off = insnsStart + pc * 2;
        int unit = readU16LE(dex, off);
        if (unit == 0x0100 && pc + 1 < insnsSize) {
            return 4 + readU16LE(dex, off + 2) * 2;
        }
        if (unit == 0x0200 && pc + 1 < insnsSize) {
            return 2 + readU16LE(dex, off + 2) * 4;
        }
        if (unit == 0x0300 && pc + 3 < insnsSize) {
            int width = readU16LE(dex, off + 2);
            long size = readIntLE(dex, off + 4) & 0xFFFFFFFFL;
            return (int) ((size * width + 1L) / 2L) + 4;
        }
        int op = unit & 0xFF;
        if (op == 0x18) return 5;
        if (op == 0xfa || op == 0xfb) return 4;
        if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17 || op == 0x1b
                || (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c)
                || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)
                || op == 0xfc || op == 0xfd) {
            return 3;
        }
        if (op == 0x02 || op == 0x05 || op == 0x08 || op == 0x13 || op == 0x15 || op == 0x16
                || op == 0x19 || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20
                || op == 0x22 || op == 0x23 || op == 0x29
                || (op >= 0x2d && op <= 0x3d) || (op >= 0x44 && op <= 0x6d)
                || (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) {
            return 2;
        }
        return 1;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readUleb128(byte[] b, int[] pos) {
        int res = 0, shift = 0;
        while (true) {
            int cur = b[pos[0]++] & 0xFF;
            res |= (cur & 0x7F) << shift;
            if ((cur & 0x80) == 0) return res;
            shift += 7;
        }
    }

    private static String dexString(byte[] dex, int stringIdsOff, int idx) {
        int off = readIntLE(dex, stringIdsOff + idx * 4);
        int[] pos = new int[]{off};
        readUleb128(dex, pos); // utf16 size
        int start = pos[0], end = start;
        while (end < dex.length && dex[end] != 0) end++;
        return new String(dex, start, end - start, StandardCharsets.UTF_8);
    }

    private static String dexTypeDescriptor(byte[] dex, int typeIdsOff, int stringIdsOff, int typeIdx) {
        int strIdx = readIntLE(dex, typeIdsOff + typeIdx * 4);
        return dexString(dex, stringIdsOff, strIdx);
    }

    private static Field uniqueInstanceFieldByExactType(Class<?> ownerType, Class<?> fieldType) {
        Field found = null;
        for (Field f : allDeclaredFields(ownerType)) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == fieldType) {
                if (found != null) throw new IllegalStateException("Ambiguous field of type " + fieldType.getName());
                f.setAccessible(true);
                found = f;
            }
        }
        if (found == null) throw new IllegalStateException("Missing field of type " + fieldType.getName());
        return found;
    }

    private static Field uniqueInstanceFieldByAssignableType(Class<?> ownerType, Class<?> superType) {
        Field found = null;
        for (Field f : allDeclaredFields(ownerType)) {
            if (!Modifier.isStatic(f.getModifiers()) && superType.isAssignableFrom(f.getType())) {
                if (found != null) throw new IllegalStateException("Ambiguous field assignable to " + superType.getName());
                f.setAccessible(true);
                found = f;
            }
        }
        if (found == null) throw new IllegalStateException("Missing field assignable to " + superType.getName());
        return found;
    }

    private static Field typedField(Class<?> type, String name, Class<?> valueType, boolean isStatic)
            throws ReflectiveOperationException {
        Field result = field(type, name);
        if (result.getType() != valueType || Modifier.isStatic(result.getModifiers()) != isStatic)
            throw new IllegalStateException("Unexpected field shape");
        return result;
    }

    private static Method instanceMethod(Class<?> type, String name, Class<?> returns,
            Class<?>... parameters) throws ReflectiveOperationException {
        Method result = method(type, name, parameters);
        if (result.getReturnType() != returns || Modifier.isStatic(result.getModifiers()))
            throw new IllegalStateException("Unexpected instance method shape");
        return result;
    }

    private static String errorType(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null)
            error = error.getCause();
        return error.getClass().getSimpleName();
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }
}
