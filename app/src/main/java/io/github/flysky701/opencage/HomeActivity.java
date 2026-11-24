package io.github.flysky701.opencage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.flysky701.opencage.privacy.GuestRegionProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static io.github.flysky701.opencage.CheckPolicy.State;
import static io.github.flysky701.opencage.SpaceApps.CLAUDE;
import static io.github.flysky701.opencage.SpaceApps.GMS;
import static io.github.flysky701.opencage.SpaceApps.PROBE;
import static io.github.flysky701.opencage.SpaceApps.STORE;

public final class HomeActivity extends Activity {
    private enum Page {
        SPACE("空间", "space"), CHECKS("启动检查", "shield"), SETTINGS("设置", "settings"),
        ADD("添加应用", "plus"), ACCOUNTS("Google 账户", "user"), EXIT("绑定出口 IP", "globe"),
        GUIDE("使用引导", "help");
        final String title, icon;
        Page(String title, String icon) { this.title = title; this.icon = icon; }
    }

    private interface Work { void run() throws Exception; }
    private static final class LaunchBlocked extends Exception {
        final String target;
        final Checks checks;

        LaunchBlocked(String target, Checks checks) {
            this.target = target;
            this.checks = checks;
        }
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int PICK_FILE = 20;
    private static final String CHECK_REUSE_HOURS = "checkReuseHours";
    private static final String ACCOUNT_TARGET = "@accounts", LISTING_TARGET = "@listing", MICROG_TARGET = "@microg";
    private static final String EMAIL_GUIDE = "推荐在空间内的 Claude 选择使用邮箱继续。使用官方邮件中的 Magic Link，"
            + "或将官方提供的验证码填回空间内原登录页。若同机点邮件链接会跳到外部浏览器，"
            + "可在另一台设备打开邮件，并在官方提供验证码时回原登录页输入。";
    private static final String GOOGLE_LIMIT = "Claude 的 Google 一键登录受限于底层引擎完整性校验，实验推进中。";

    private final ArrayList<Page> parents = new ArrayList<>();
    private final EnumMap<Page, Integer> positions = new EnumMap<>(Page.class);
    private Page page = Page.SPACE, renderedPage;
    private CageTheme theme;
    private LinearLayout body;
    private LinearLayout homeApps;
    private View homeSearchToggle;
    private TextView workLabel;
    private ScrollView scroll;
    private List<SpaceApps.App> installed = Collections.emptyList(), importable = Collections.emptyList();
    private List<String> googleAccounts = Collections.emptyList();
    private boolean packagesKnown, accountsKnown, busy, searchVisible, resumed;
    private Page pendingStorePage;
    private String launchingTarget;
    private boolean showChecks = true;
    private final HashSet<String> expandedChecks = new HashSet<>();
    private String homeQuery = "", importQuery = "", status = "", pendingTarget;
    private Checks live;
    private CheckReport report;
    private PreflightRunner runner;
    private boolean runningCheck, historical;
    private ConnectivityManager connectivity;
    private boolean observingNetwork;
    private boolean checkingNetwork, networkDirty, loadingPhoneApps, loadedPhoneApps;
    private final Runnable networkUpdate = this::readNetworkInBackground;
    private Uri pendingImport;
    private SpaceApps.ImportKind pendingImportKind;

    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { refreshNetwork(); }
        @Override public void onLost(Network network) { refreshNetwork(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { refreshNetwork(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties link) { refreshNetwork(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSplashScreen().setOnExitAnimationListener(android.window.SplashScreenView::remove);
        }
        if (state != null) {
            page = Page.valueOf(state.getString("page", Page.SPACE.name()));
            ArrayList<String> saved = state.getStringArrayList("parents");
            if (saved != null) for (String parent : saved) parents.add(Page.valueOf(parent));
            for (Page key : Page.values()) positions.put(key, state.getInt("scroll-" + key.name()));
            homeQuery = state.getString("homeQuery", "");
            importQuery = state.getString("importQuery", "");
            searchVisible = state.getBoolean("searchVisible");
            showChecks = state.getBoolean("showChecks");
            ArrayList<String> expanded = state.getStringArrayList("expandedChecks");
            if (expanded != null) expandedChecks.addAll(expanded);
            pendingTarget = state.getString("pendingTarget");
            String importUri = state.getString("pendingImport");
            if (importUri != null) {
                pendingImport = Uri.parse(importUri);
                pendingImportKind = SpaceApps.ImportKind.valueOf(state.getString("pendingImportKind"));
            }
        }
        live = Checks.pending();
        restoreReport();
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            try {
                connectivity.registerDefaultNetworkCallback(networkChanges, new Handler(Looper.getMainLooper()));
                observingNetwork = true;
            } catch (RuntimeException unavailable) {
                // The launcher and each guest process still recheck the current network.
            }
        }
        runJob(() -> BundledApps.prepare(getApplicationContext(), ignored -> {}), "");
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshNetwork();
        if (!busy && !runningCheck) runJob(() -> {}, "");
    }

    @Override protected void onPause() {
        resumed = false;
        pendingStorePage = null;
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        rememberScroll();
        state.putString("page", page.name());
        ArrayList<String> saved = new ArrayList<>();
        for (Page parent : parents) saved.add(parent.name());
        state.putStringArrayList("parents", saved);
        for (Page key : Page.values()) state.putInt("scroll-" + key.name(), positions.getOrDefault(key, 0));
        state.putString("homeQuery", homeQuery);
        state.putString("importQuery", importQuery);
        state.putBoolean("searchVisible", searchVisible);
        state.putBoolean("showChecks", showChecks);
        state.putStringArrayList("expandedChecks", new ArrayList<>(expandedChecks));
        state.putString("pendingTarget", pendingTarget);
        if (pendingImport != null) {
            state.putString("pendingImport", pendingImport.toString());
            state.putString("pendingImportKind", pendingImportKind.name());
        }
    }

    @Override protected void onDestroy() {
        if (runner != null) runner.cancel();
        if (observingNetwork) connectivity.unregisterNetworkCallback(networkChanges);
        main.removeCallbacks(networkUpdate);
        super.onDestroy();
    }

    private void runJob(Work work, String success) {
        runJob(work, success, true);
    }

    private void runJob(Work work, String success, boolean refreshApps) {
        if (busy) return;
        busy = true;
        boolean hadStatus = !status.isEmpty();
        status = "";
        Page origin = page;
        if (!hadStatus && renderedPage == Page.SPACE && page == Page.SPACE) {
            updateWorkLabel();
        } else render();
        WORKER.execute(() -> {
            String message = success;
            List<SpaceApps.App> snapshot = null;
            LaunchBlocked blocked = null;
            try {
                ((CandidateApplication) getApplication()).prepareHostServices();
                work.run();
            } catch (LaunchBlocked failure) {
                blocked = failure;
            } catch (SpaceApps.Problem failure) {
                message = failure.getMessage();
            } catch (Exception failure) {
                message = "操作未完成，请检查空间状态后重试。";
            }
            if (blocked == null && refreshApps) {
                try {
                    snapshot = SpaceApps.list(getApplicationContext());
                } catch (Exception unavailable) {
                    if (message.equals(success)) message = "暂时无法读取空间内的应用。";
                }
            }
            List<String> accountsSnapshot = blocked == null && refreshApps ? SpaceApps.googleAccounts() : null;
            String result = message;
            List<SpaceApps.App> guestResult = snapshot;
            LaunchBlocked rejected = blocked;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                busy = false;
                launchingTarget = null;
                if (rejected != null) {
                    if (rejected.checks.startedAt >= live.startedAt) live = rejected.checks;
                    if (page == origin) {
                        pendingTarget = rejected.target;
                        if (page != Page.CHECKS) openPage(Page.CHECKS);
                        else render();
                    } else render();
                    return;
                }
                status = result;
                if (page == Page.EXIT || page == Page.CHECKS || page == Page.SPACE || page == Page.GUIDE) refreshNetwork();
                if (refreshApps) {
                    packagesKnown = guestResult != null;
                    if (packagesKnown) installed = guestResult;
                    accountsKnown = accountsSnapshot != null;
                    if (accountsKnown) googleAccounts = accountsSnapshot;
                }
                if (beginPendingImport()) return;
                boolean openStore = pendingStorePage == page && packagesKnown;
                pendingStorePage = null;
                if (openStore) {
                    openStore();
                    return;
                }
                if (page == Page.SPACE && status.isEmpty()) {
                    updateWorkLabel();
                    appRows(homeApps, false);
                } else render();
            });
        });
    }

    private boolean has(String pkg) {
        for (SpaceApps.App app : installed) if (pkg.equals(app.pkg)) return true;
        return false;
    }

    private void refreshNetwork() {
        if (isDestroyed() || isFinishing()) return;
        networkDirty = true;
        if (!checkingNetwork) {
            main.removeCallbacks(networkUpdate);
            main.post(networkUpdate);
        }
    }

    private void readNetworkInBackground() {
        checkingNetwork = true;
        networkDirty = false;
        NETWORK.execute(() -> {
            Checks snapshot = Checks.read(getApplicationContext());
            main.post(() -> {
                checkingNetwork = false;
                if (isDestroyed() || isFinishing()) return;
                if (networkDirty) {
                    readNetworkInBackground();
                    return;
                }
                Checks before = live;
                if (snapshot.startedAt < live.startedAt) return;
                live = snapshot;
                if (before.vpn == State.PASSED && live.vpn != State.PASSED) invalidateConsent();
                if (live.sameStatus(before)) return;
                if (page == Page.SPACE) updateWorkLabel();
                if (page == Page.EXIT || page == Page.CHECKS || page == Page.SETTINGS || page == Page.GUIDE) render();
            });
        });
    }

    private void requestLaunch(String target) {
        if (runningCheck) {
            go(Page.CHECKS);
            return;
        }
        if (busy) {
            Toast.makeText(this, "空间正在准备，请稍后再打开。", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean setupAction = MICROG_TARGET.equals(target) || ACCOUNT_TARGET.equals(target);
        if (!setupAction && !admitted()) {
            startCheck(target);
            return;
        }
        launchNow(target);
    }

    private void launchNow(String target) {
        if (busy) {
            Toast.makeText(this, "空间正在准备，请稍后再打开。", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingTarget = null;
        launchingTarget = target;
        runJob(() -> {
            Context context = getApplicationContext();
            Checks current = Checks.read(context);
            if (!current.eligible()) throw new LaunchBlocked(target, current);
            try {
                ExitBinding.verifyLaunch(context);
            } catch (IOException failed) {
                throw new SpaceApps.Problem("绑定出口核对未通过。请在「绑定出口 IP」中重验。");
            }
            if (MICROG_TARGET.equals(target)) SpaceApps.setupMicrog(context);
            else if (ACCOUNT_TARGET.equals(target)) SpaceApps.accounts(context);
            else if (LISTING_TARGET.equals(target)) SpaceApps.openListing(context);
            else SpaceApps.launch(context, target);
        }, "", false);
    }

    private void openStore() {
        if (STORE.equals(launchingTarget) || LISTING_TARGET.equals(launchingTarget)) return;
        if (busy || !packagesKnown) {
            pendingStorePage = page;
            if (!busy) runJob(() -> {}, "");
            else updateWorkLabel();
            return;
        }
        if (has(STORE)) requestLaunch(STORE);
        else openPage(Page.ADD);
    }

    private void go(Page next) {
        pendingStorePage = null;
        hideKeyboard();
        parents.clear();
        pendingTarget = null;
        page = next;
        if (page == Page.CHECKS) refreshNetwork();
        render();
    }

    private void openPage(Page next) {
        if (page == next) return;
        pendingStorePage = null;
        hideKeyboard();
        parents.add(page);
        page = next;
        positions.remove(next);
        if (next == Page.EXIT) refreshNetwork();
        render();
    }

    private void closePage() {
        pendingStorePage = null;
        hideKeyboard();
        pendingTarget = null;
        page = parents.isEmpty() ? Page.SPACE : parents.remove(parents.size() - 1);
        render();
    }

    @Override public void onBackPressed() {
        if (!parents.isEmpty()) closePage();
        else if (page != Page.SPACE) go(Page.SPACE);
        else super.onBackPressed();
    }

    private void rememberScroll() {
        if (scroll != null && renderedPage != null) positions.put(renderedPage, scroll.getScrollY());
    }

    private void render() {
        rememberScroll();
        renderedPage = page;
        theme = new CageTheme(this);
        getWindow().setStatusBarColor(theme.paper);
        getWindow().setNavigationBarColor(theme.paper);
        getWindow().getDecorView().setSystemUiVisibility(theme.dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = column(), toolbar = row();
        root.setBackgroundColor(theme.paper);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        toolbar.setPadding(dp(20), dp(5), dp(20), dp(5));
        toolbar.setMinimumHeight(dp(58));
        if (!parents.isEmpty()) toolbar.addView(iconButton("back", "返回", this::closePage));
        else toolbar.addView(cageLogo(30), dp(30), dp(30));
        TextView title = text(parents.isEmpty() ? "Open Cage" : page.title, 17, theme.ink, true);
        title.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(title, weight());
        workLabel = text("", 12, theme.muted, false);
        if (page == Page.SPACE) {
            workLabel.setMinHeight(dp(48));
            workLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            workLabel.setOnClickListener(v -> go(Page.CHECKS));
        }
        toolbar.addView(workLabel);
        updateWorkLabel();
        root.addView(toolbar);
        divider(root);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        int margin = dp(getResources().getConfiguration().screenWidthDp < 360 ? 12 : 20);
        body.setPadding(margin, dp(page == Page.SPACE || page == Page.SETTINGS ? 24 : 16), margin, dp(18));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!status.isEmpty()) {
            note(body, status);
            gap(body, 10);
        }
        switch (page) {
            case CHECKS: checksPage(); break;
            case SETTINGS: settingsPage(); break;
            case ADD: addPage(); break;
            case ACCOUNTS: accountsPage(); break;
            case EXIT: exitPage(); break;
            case GUIDE: guidePage(); break;
            default: spacePage();
        }
        if (page == Page.GUIDE) {
            divider(root);
            guideActions(root);
        }
        if (parents.isEmpty()) {
            divider(root);
            LinearLayout navigation = row();
            navigation.setPadding(dp(9), dp(4), dp(9), dp(4));
            for (Page tab : new Page[]{Page.SPACE, Page.CHECKS, Page.SETTINGS}) nav(navigation, tab);
            root.addView(navigation);
        }
        int y = positions.getOrDefault(page, 0);
        ScrollView rendered = scroll;
        if (y != 0) rendered.post(() -> rendered.scrollTo(0, y));
    }

    private void nav(LinearLayout parent, Page tab) {
        boolean selected = page == tab;
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.setMinimumHeight(dp(55));
        cell.addView(icon(tab.icon, 21, selected ? theme.accent : theme.muted));
        gap(cell, 5);
        TextView caption = text(tab.title, 11, selected ? theme.ink : theme.muted, selected);
        caption.setGravity(Gravity.CENTER);
        cell.addView(caption);
        cell.setSelected(selected);
        cell.setContentDescription(tab.title + (selected ? "，当前页面" : ""));
        touch(cell, theme.paper, false, () -> go(tab));
        parent.addView(cell, weight());
    }

    private void spacePage() {
        body.addView(text("我的空间", 30, theme.ink, true));
        gap(body, 20);
        boolean stacked = getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout shortcuts = stacked ? column() : row();
        shortcuts.addView(shortcut("Google Play", "play", true, this::openStore), shortcutSize(stacked));
        shortcuts.addView(new View(this), stacked ? 1 : dp(10), stacked ? dp(10) : 1);
        shortcuts.addView(shortcut("Google 账户", "user", false, () -> openPage(Page.ACCOUNTS)),
                shortcutSize(stacked));
        body.addView(shortcuts);
        spaceList();
    }

    private void updateWorkLabel() {
        String label = busy ? (packagesKnown ? "正在处理…" : "正在准备空间…") : "";
        if (pendingStorePage == page) label = "准备后打开商店…";
        if (STORE.equals(launchingTarget) || LISTING_TARGET.equals(launchingTarget)) label = "正在打开商店…";
        if (label.isEmpty() && page == Page.SPACE) {
            if (live.startedAt < 0) label = "正在检查";
            else if (live.permissions == State.FAILED) label = "权限异常";
            else if (live.vpn == State.PASSED) label = "VPN 已覆盖";
            else if (live.vpn == State.FAILED) label = "VPN 未覆盖";
            else label = "VPN 待确认";
        }
        workLabel.setText(label);
        workLabel.setContentDescription(page == Page.SPACE ? "查看启动检查，" + label : label);
        workLabel.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View shortcut(String label, String symbol, boolean primary, Runnable action) {
        LinearLayout tile = column(), symbols = row();
        tile.setPadding(dp(16), dp(18), dp(16), dp(16));
        int ink = primary ? theme.buttonInk : theme.ink;
        symbols.addView(icon(symbol, 27, ink));
        symbols.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        symbols.addView(icon("diagonal", 15, ink));
        tile.addView(symbols);
        gap(tile, 20);
        tile.addView(text(label, 17, ink, true));
        tile.setContentDescription(label);
        touch(tile, primary ? theme.button : theme.chrome, false, action);
        return tile;
    }

    private LinearLayout.LayoutParams shortcutSize(boolean stacked) {
        return stacked ? new LinearLayout.LayoutParams(-1, -2) : weight();
    }

    private void spaceList() {
        gap(body, 26);
        LinearLayout header = row();
        header.addView(text("我的应用", 19, theme.ink, true), weight());
        homeSearchToggle = iconButton(searchVisible ? "close" : "search",
                searchVisible ? "关闭应用搜索" : "搜索应用", () -> {
                    hideKeyboard();
                    searchVisible = !searchVisible;
                    if (!searchVisible) homeQuery = "";
                    render();
                    if (searchVisible) {
                        EditText field = body.findViewWithTag("home-search");
                        InputMethodManager input = getSystemService(InputMethodManager.class);
                        field.requestFocus();
                        if (input != null) field.post(() -> input.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
                    }
                });
        header.addView(homeSearchToggle);
        boolean compact = getResources().getConfiguration().screenWidthDp < 330
                || getResources().getConfiguration().fontScale >= 1.3f;
        header.addView(compact ? iconButton("plus", "添加应用", () -> openPage(Page.ADD))
                : button("添加应用", "plus", false, () -> openPage(Page.ADD)));
        body.addView(header);
        gap(body, 14);
        LinearLayout apps = panel(body);
        homeApps = apps;
        if (searchVisible) {
            EditText field = search(homeQuery, value -> {
                homeQuery = value;
                appRows(apps, false);
            });
            field.setTag("home-search");
            insertSearch(field, apps);
        }
        appRows(apps, false);
    }

    private void addPage() {
        loadPhoneApps();
        LinearLayout box = panel(body);
        if (has(STORE)) {
            option(box, "play", "打开空间内的 Google Play", null, this::openStore);
            option(box, "arrow", "在商店安装 Claude", "请认准 Anthropic 发布的应用。",
                    () -> requestLaunch(LISTING_TARGET));
        } else {
            option(box, "play", "从手机导入 Google Play", "支持官方完整单 APK。",
                    () -> runJob(() -> SpaceApps.importInstalled(getApplicationContext(), STORE,
                            SpaceApps.ImportKind.STORE), "Google Play 已导入。"));
            option(box, "plus", "选择 Google Play APK", null, () -> pick(SpaceApps.ImportKind.STORE));
        }
        option(box, "plus", "导入 Claude 分包", "选择原签名、同版本的完整 ARM64 分包归档。",
                () -> pick(SpaceApps.ImportKind.CLAUDE));
        option(box, "plus", "选择其他 APK", "导入完整单 APK。", () -> pick(SpaceApps.ImportKind.APP));
        for (SpaceApps.App app : installed) {
            if (STORE.equals(app.pkg)) option(box, "trash", "移除 Google Play", null, () -> confirmRemoval(app));
        }
        gap(body, 20);
        body.addView(text("从手机添加应用", 18, theme.ink, true));
        gap(body, 10);
        LinearLayout list = panel(body);
        insertSearch(search(importQuery, value -> {
            importQuery = value;
            appRows(list, true);
        }), list);
        appRows(list, true);
    }

    private void loadPhoneApps() {
        if (loadedPhoneApps || loadingPhoneApps) return;
        loadingPhoneApps = true;
        WORKER.execute(() -> {
            List<SpaceApps.App> apps;
            try { apps = SpaceApps.importable(getApplicationContext()); }
            catch (RuntimeException unavailable) { apps = Collections.emptyList(); }
            List<SpaceApps.App> result = apps;
            main.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                loadingPhoneApps = false;
                loadedPhoneApps = true;
                importable = result;
                if (page == Page.ADD) render();
            });
        });
    }

    private void appRows(LinearLayout parent, boolean adding) {
        parent.removeAllViews();
        if (!adding) {
            boolean hasApps = packagesKnown && installed.stream().anyMatch(app -> showsInSpace(app));
            homeSearchToggle.setVisibility(hasApps || searchVisible ? View.VISIBLE : View.GONE);
        }
        if (!adding && !packagesKnown) {
            note(parent, busy ? "正在读取空间应用。" : "空间应用暂时无法读取。");
            return;
        }
        String query = adding ? importQuery : homeQuery;
        int count = 0;
        for (SpaceApps.App app : adding ? importable : installed) {
            if ((adding ? has(app.pkg) : !showsInSpace(app)) || !matches(app, query)) continue;
            if (count++ != 0) {
                if (adding) divider(parent);
                else appDivider(parent);
            }
            if (adding) {
                appRow(parent, app, () -> {
                    hideKeyboard();
                    runJob(() -> SpaceApps.importInstalled(getApplicationContext(), app.pkg, SpaceApps.ImportKind.APP),
                            "应用已导入。");
                }, null);
            } else appRow(parent, app, () -> requestLaunch(app.pkg), () -> confirmRemoval(app));
        }
        if (count == 0) {
            if (adding) note(parent, loadingPhoneApps ? "正在读取手机应用。" : "没有可添加的匹配应用。");
            else note(parent, query.trim().isEmpty() ? "空间里还没有应用。点「添加应用」开始。"
                    : "没有匹配的应用。");
        }
    }

    private static boolean showsInSpace(SpaceApps.App app) {
        return !GMS.equals(app.pkg) && !STORE.equals(app.pkg) && !PROBE.equals(app.pkg);
    }

    private static boolean matches(SpaceApps.App app, String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return app.label.toLowerCase(Locale.ROOT).contains(normalized)
                || app.pkg.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private void pick(SpaceApps.ImportKind kind) {
        if (busy) return;
        String type = kind == SpaceApps.ImportKind.CLAUDE ? "application/*"
                : "application/vnd.android.package-archive";
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_FILE + kind.ordinal());
        } catch (ActivityNotFoundException unavailable) {
            status = "手机上没有可用的文件选择器。";
            render();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        int kind = request - PICK_FILE;
        if (kind < 0 || kind >= SpaceApps.ImportKind.values().length || result != RESULT_OK || data == null) return;
        if (data.getData() == null) return;
        pendingImport = data.getData();
        pendingImportKind = SpaceApps.ImportKind.values()[kind];
        beginPendingImport();
    }

    private boolean beginPendingImport() {
        if (busy || pendingImport == null) return false;
        Uri uri = pendingImport;
        SpaceApps.ImportKind kind = pendingImportKind;
        pendingImport = null;
        runJob(() -> SpaceApps.importFile(getApplicationContext(), uri, kind), "应用已导入。");
        return true;
    }

    private void confirmRemoval(SpaceApps.App app) {
        if (busy) return;
        showDialog(dialog().setTitle("从空间移除 " + app.label + "？")
                .setMessage("这会删除该应用在当前空间的本地数据。手机上的原应用与数据会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, which) -> {
                    hideKeyboard();
                    runJob(() -> SpaceApps.remove(getApplicationContext(), app.pkg), "应用已从空间移除。");
                }), true);
    }

    private void accountsPage() {
        heading("Google 账户", "用于空间内的 Google Play。");
        LinearLayout signed = settingsSection("空间内已登录账户");
        if (!accountsKnown) {
            settingRow(signed, "user", busy ? "正在读取…" : "暂时无法读取账户", null, null);
        } else if (googleAccounts.isEmpty()) {
            settingRow(signed, "user", "尚未添加账户", null, null);
        } else {
            for (String name : googleAccounts) {
                settingRow(signed, "user", name, "空间账户", null);
            }
        }
        gap(body, 16);
        LinearLayout box = panel(body);
        option(box, "settings", "一键配置 microG", "准备内置组件，开启设备注册和 Google Play 结算，并打开 microG 设置。",
                () -> requestLaunch(MICROG_TARGET));
        option(box, "user", "添加 Google 账户", "在空间内完成商店账号设置。",
                () -> requestLaunch(ACCOUNT_TARGET));
        if (!has(STORE)) option(box, "play", "准备 Google Play", null, () -> openPage(Page.ADD));
        gap(body, 16);
        note(body, EMAIL_GUIDE);
        note(body, GOOGLE_LIMIT);
    }

    private void guidePage() {
        boolean bound = live.exit != ExitBinding.Status.UNBOUND;
        GuestRegionProfile profile = GuestRegionProfile.read(this);
        body.addView(cageLogo(56), dp(56), dp(56));
        gap(body, 24);
        body.addView(text(bound ? "空间已配置" : "设置你的空间", 29, theme.ink, true));
        gap(body, 10);
        body.addView(text(bound ? "回到首页即可添加应用或打开 Google Play。"
                : "连接 VPN 后可以绑定当前出口，获取建议的时区和语言。", 15, theme.muted, false));
        gap(body, 24);
        LinearLayout box = panel(body);
        settingRow(box, "globe", "网络出口", bound ? "已绑定 IPv4" : "待绑定", () -> openPage(Page.EXIT));
        settingRow(box, "clock", "时区", profile.zone, () -> editGuestRegion(false));
        settingRow(box, "user", "语言", regionLanguage(profile), () -> editGuestRegion(true));
        gap(body, 20);
        LinearLayout account = settingsSection("在空间内使用 Claude");
        option(account, "plus", "安装原版 Claude", "从空间商店安装，或导入官方分包归档。", () -> openPage(Page.ADD));
        option(account, "user", "使用邮箱登录", EMAIL_GUIDE, null);
        note(body, GOOGLE_LIMIT);
        note(body, "订阅由 Claude 内的 Google Play 面板处理，套餐状态以 Claude 内显示为准。");
    }

    private void guideActions(LinearLayout root) {
        LinearLayout actions = column();
        actions.setPadding(dp(20), dp(12), dp(20), dp(16));
        if (live.exit == ExitBinding.Status.UNBOUND) {
            View bind = button(busy ? "正在处理…" : "绑定当前出口", "globe", true, () -> bindExit(""));
            bind.setEnabled(!busy);
            actions.addView(bind);
            gap(actions, 8);
            actions.addView(button("先看首页", null, false, () -> go(Page.SPACE)));
        } else {
            actions.addView(button("进入首页", "arrow", true, () -> go(Page.SPACE)));
        }
        root.addView(actions);
    }

    private String vpnCoverage() {
        if (live.vpn == State.PASSED) return "已覆盖";
        if (live.vpn == State.FAILED) return "未覆盖";
        return "待确认";
    }

    private void exitPage() {
        heading("绑定出口 IP", "打开空间应用前自动核对已绑定的公网 IPv4。");
        LinearLayout box = panel(body);
        GuestRegionProfile profile = GuestRegionProfile.read(this);
        boolean bound = live.exit != ExitBinding.Status.UNBOUND;
        settingRow(box, "globe", "指定出口", ExitBinding.label(this), null);
        settingRow(box, "clock", "空间时区", profile.zone, () -> editGuestRegion(false));
        settingRow(box, "user", "空间语言", regionLanguage(profile), () -> editGuestRegion(true));
        if (bound) {
            String region = profile.region.isEmpty() ? "尚未确认" : new Locale("", profile.region).getDisplayCountry();
            settingRow(box, "globe", "出口地区", region, () -> runExitJob(() ->
                    ExitBinding.refreshRegion(getApplicationContext()), "出口地区已更新。"));
            settingRow(box, "refresh", "重验绑定出口", live.exit == ExitBinding.Status.VERIFIED
                    ? "最近核对一致" : "等待核对", () -> runExitJob(() ->
                    ExitBinding.verifyLaunch(getApplicationContext()), "出口核对一致。"));
        }
        gap(body, 18);
        body.addView(button("绑定当前 VPN 出口", "globe", true, () -> bindExit("")));
        gap(body, 10);
        body.addView(button("填写指定 IP", "settings", false, this::editExitBinding));
        if (bound) {
            gap(body, 10);
            body.addView(button("解除绑定", "close", false, () -> runExitJob(() ->
                    ExitBinding.clear(getApplicationContext()), "已解除出口绑定。")));
        }
        gap(body, 16);
        LinearLayout network = settingsSection("网络连接");
        settingRow(network, "globe", "当前 VPN", vpnCoverage(), this::vpnSettings);
        settingRow(network, "clock", "手机系统时区", live.timezone, null);
        note(body, "来宾连接到当前 VPN，VPN 断开或更换时会结束来宾进程。绑定后约每 45 秒复查公网 IPv4。");
    }

    private void editExitBinding() {
        if (busy) return;
        AlertDialog.Builder builder = dialog();
        DialogField field = new DialogField(builder.getContext(), "公网 IPv4");
        EditText input = field.input;
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        AlertDialog box = showDialog(builder.setTitle("指定出口 IP")
                .setMessage("填写当前 VPN 的公网 IPv4。")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("校验并绑定", null), false);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = input.getText().toString().trim();
            try {
                ExitBinding.publicIpv4(address);
            } catch (IllegalArgumentException invalid) {
                field.showError(invalid.getMessage());
                return;
            }
            box.dismiss();
            bindExit(address);
        });
    }

    private void bindExit(String address) {
        runExitJob(() -> ExitBinding.configure(getApplicationContext(), address), "出口 IPv4 已绑定。");
    }

    private String regionLanguage(GuestRegionProfile profile) {
        return profile.language.isEmpty() ? "跟随系统" : Locale.forLanguageTag(profile.language).getDisplayName();
    }

    private void editGuestRegion(boolean language) {
        if (busy) return;
        String title = language ? "空间语言" : "空间时区";
        AlertDialog.Builder builder = dialog();
        DialogField field = new DialogField(builder.getContext(), language ? "例如 zh-CN" : "例如 Asia/Tokyo");
        EditText input = field.input;
        input.setText(GuestRegionProfile.override(this, language));
        AlertDialog box = showDialog(builder.setTitle(title)
                .setMessage("留空采用出口建议。设置在来宾进程下次启动时生效。")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null), false);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            try {
                if (!value.isEmpty()) {
                    if (language) GuestRegionProfile.languageTag(value);
                    else GuestRegionProfile.timezone(value);
                }
            } catch (IllegalArgumentException invalid) {
                field.showError(invalid.getMessage());
                return;
            }
            box.dismiss();
            invalidateConsent();
            runJob(() -> GuestRegionProfile.setOverride(getApplicationContext(), value, language),
                    title + "已保存，下次启动来宾时生效。", false);
        });
    }

    private void runExitJob(Work work, String success) {
        invalidateConsent();
        runJob(() -> {
            try {
                work.run();
            } catch (IOException | SecurityException failed) {
                throw new SpaceApps.Problem("出口操作未完成，请检查 VPN 和期望 IPv4。");
            }
        }, success, false);
    }

    private void startCheck(String target) {
        if (runningCheck) {
            go(Page.CHECKS);
            return;
        }
        if (live.startedAt >= 0 && !live.eligible()) {
            pendingTarget = target;
            go(Page.CHECKS);
            return;
        }
        invalidateConsent();
        pendingTarget = target;
        report = null;
        historical = false;
        runningCheck = true;
        expandedChecks.clear();
        positions.put(Page.CHECKS, 0);
        page = Page.CHECKS;
        parents.clear();
        render();
        runner = new PreflightRunner();
        runner.run(this, value -> {
            if (isFinishing() || isDestroyed()) return;
            runningCheck = false;
            report = value;
            historical = false;
            saveReport(value);
            boolean passed = value.allPassed() && currentReport();
            if (passed) rememberApproval();
            if (passed && page == Page.CHECKS && target != null && resumed) {
                enter(false);
            } else {
                render();
            }
        });
    }

    private void saveReport(CheckReport value) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "cage-startup-check.json"))) {
            out.write(value.json().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void restoreReport() {
        File file = new File(getFilesDir(), "cage-startup-check.json");
        long size = file.length();
        if (size < 2 || size > 65536) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) size];
            int at = 0;
            while (at < data.length) {
                int n = in.read(data, at, data.length - at);
                if (n < 0) return;
                at += n;
            }
            JSONObject stored = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (stored.optInt("schema") != 1) return;
            CheckReport value = new CheckReport(stored.optString("request", null), null, 0, stored.getLong("createdAt"));
            value.location = stored.optString("location", "尚未确认出口");
            JSONArray rows = stored.getJSONArray("checks");
            if (rows.length() > 40) return;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject c = rows.getJSONObject(i);
                value.add(c.getString("id"), c.getString("title"),
                        State.valueOf(c.getString("state")), c.getString("detail"), c.optBoolean("advanced"));
            }
            report = value;
            historical = true;
        } catch (Exception ignored) {}
    }

    private boolean currentReport() {
        return report != null && !historical && report.error == null
                && SystemClock.elapsedRealtime() - report.createdElapsed < CheckPolicy.LIFETIME_MS
                && report.fingerprint != null && report.fingerprint.equals(Checks.fingerprint(this));
    }

    private SharedPreferences approval() {
        return getSharedPreferences("cage-launch-approval", MODE_PRIVATE);
    }

    private int bootCount() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private int checkReuseHours() {
        int value = getSharedPreferences("cage-android-ui", MODE_PRIVATE)
                .getInt(CHECK_REUSE_HOURS, CheckPolicy.DEFAULT_REUSE_HOURS);
        return value >= 0 && value <= CheckPolicy.MAX_REUSE_HOURS ? value : 0;
    }

    private void editCheckReuseHours() {
        AlertDialog.Builder builder = dialog();
        DialogField field = new DialogField(builder.getContext(), "小时");
        EditText input = field.input;
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(checkReuseHours()));
        input.setSelectAllOnFocus(true);
        AlertDialog box = showDialog(builder.setTitle("检查缓存")
                .setMessage("输入 0–" + CheckPolicy.MAX_REUSE_HOURS + " 小时。设为 0 后，每次打开应用都运行完整检查。")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null), false);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int hours;
            try {
                hours = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                hours = -1;
            }
            if (hours < 0 || hours > CheckPolicy.MAX_REUSE_HOURS) {
                field.showError("请输入 0–" + CheckPolicy.MAX_REUSE_HOURS + " 之间的整数。");
                return;
            }
            getSharedPreferences("cage-android-ui", MODE_PRIVATE).edit()
                    .putInt(CHECK_REUSE_HOURS, hours).apply();
            if (hours == 0) invalidateConsent();
            box.dismiss();
            render();
        });
    }

    private void invalidateConsent() {
        approval().edit().clear().apply();
    }

    private boolean admitted() {
        int hours = checkReuseHours();
        if (hours == 0 || report == null || report.request == null) return false;
        SharedPreferences saved = approval();
        return CheckPolicy.reusable(saved.getString("request", null), report.request,
                saved.getString("environment", null), Checks.fingerprint(this), saved.getInt("boot", -1),
                bootCount(), saved.getLong("approvedAt", 0), SystemClock.elapsedRealtime(), hours);
    }

    private void confirmCheck() {
        if (!currentReport()) {
            showDialog(dialog().setTitle("需要重新检查")
                    .setMessage("本轮检查已过期或环境已变化。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("重新检查", (d, w) -> startCheck(pendingTarget)), false);
            return;
        }
        if (report.allPassed()) {
            enter(true);
            return;
        }
        String summary = "通过 " + report.count(State.PASSED)
                + " · 未通过 " + report.count(State.FAILED)
                + " · 未确认 " + report.count(State.UNKNOWN);
        String title = pendingTarget == null ? "进入空间？" : "打开 " + targetLabel(pendingTarget) + "？";
        int hours = checkReuseHours();
        AlertDialog.Builder confirmation = dialog().setTitle(title).setNegativeButton("返回检查", null);
        android.content.DialogInterface.OnClickListener once = (d, w) -> {
            if (currentReport()) enter(false); else startCheck(pendingTarget);
        };
        if (hours == 0) {
            confirmation.setMessage(summary).setPositiveButton("仅本次", once);
        } else {
            confirmation.setMessage(summary + "\n\n选择「继续并记住」后，同一环境下 "
                            + hours + " 小时内免重复确认。")
                    .setNeutralButton("仅本次", once)
                    .setPositiveButton("继续并记住", (d, w) -> {
                        if (currentReport()) enter(true); else startCheck(pendingTarget);
                    });
        }
        showDialog(confirmation, false);
    }

    private void rememberApproval() {
        int boot = bootCount();
        if (checkReuseHours() == 0 || boot < 0) return;
        approval().edit()
                .putString("request", report.request)
                .putString("environment", report.fingerprint)
                .putInt("boot", boot)
                .putLong("approvedAt", SystemClock.elapsedRealtime())
                .apply();
    }

    private void enter(boolean remember) {
        if (remember) rememberApproval();
        String target = pendingTarget;
        pendingTarget = null;
        positions.put(Page.SPACE, 0);
        go(Page.SPACE);
        if (target != null) launchNow(target);
    }

    private void checksPage() {
        heading("启动检查", pendingTarget == null
                ? "在空间内实测 SIM、时区、WebView、VPN 屏蔽与网络路由。"
                : "即将打开 " + targetLabel(pendingTarget));
        if (live.vpn != State.PASSED) {
            body.addView(button("打开系统 VPN 设置", "globe", pendingTarget != null, this::vpnSettings));
            gap(body, 10);
        }
        if (runningCheck) {
            LinearLayout box = panel(body);
            box.setPadding(dp(18), dp(20), dp(18), dp(20));
            LinearLayout line = row();
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminateTintList(ColorStateList.valueOf(theme.ink));
            line.addView(progress, new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView title = text("正在空间内检查环境…", 16, theme.ink, true);
            title.setPadding(dp(12), 0, 0, 0);
            line.addView(title);
            box.addView(line);
            gap(box, 12);
            box.addView(text("检测组件可能短暂打开，完成后自动返回这里。", 13, theme.muted, false));
            gap(box, 16);
            box.addView(button("取消检查", "close", false, () -> {
                if (runner != null) runner.cancel();
                runningCheck = false;
                pendingTarget = null;
                render();
            }));
            return;
        }
        if (report == null) {
            LinearLayout sum = panel(body);
            sum.setPadding(dp(16), dp(18), dp(16), dp(18));
            sum.addView(icon("shield", 26, theme.ink));
            gap(sum, 12);
            sum.addView(text("尚未执行实测检查", 17, theme.ink, true));
            gap(sum, 7);
            sum.addView(text("启动内置检测组件在空间内实测 SIM、时区、WebView、VPN 屏蔽与出口路由，缺少证据的项保持未确认。",
                    13, theme.muted, false));
            gap(body, 16);
            body.addView(button("启动并检查", "shield", true, () -> startCheck(pendingTarget)));
            if (!has(GMS)) {
                gap(body, 8);
                body.addView(button("重试准备 microG", "refresh", false,
                        () -> runJob(() -> BundledApps.prepare(getApplicationContext(), ignored -> {}), "")));
            }
            return;
        }
        LinearLayout sum = panel(body);
        sum.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout top = row();
        top.addView(icon("shield", 24, theme.ink));
        TextView title = text(historical ? "上次检查记录"
                : report.error != null ? "检查未完成"
                : report.allPassed() ? "检查已通过" : "有项目需要确认", 17, theme.ink, true);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title);
        sum.addView(top);
        gap(sum, 14);
        LinearLayout totals = row();
        for (State state : State.values()) {
            LinearLayout cell = column();
            cell.setPadding(dp(4), dp(4), dp(8), dp(4));
            cell.addView(text(String.valueOf(report.count(state)), 22, stateColor(state), true));
            cell.addView(text(stateTitle(state), 11, theme.muted, false));
            totals.addView(cell, weight());
        }
        sum.addView(totals);
        gap(sum, 12);
        String timeStr = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(report.createdWall));
        sum.addView(text(timeStr + " · " + report.location, 11, theme.muted, false));
        gap(sum, 6);
        sum.addView(text(admitted() ? "检查缓存有效（同环境可复用 " + checkReuseHours() + " 小时）。"
                : historical ? "重新检查可获取当前环境实测结果。"
                : "手动继续不会把未解决项改成通过。", 11, theme.muted, false));

        gap(body, 18);
        LinearLayout mainBox = settingsSection("关键检查（点击查看详情）");
        for (CheckReport.Item item : report.items) if (!item.advanced) checkRow(mainBox, item);
        int extra = 0;
        for (CheckReport.Item item : report.items) if (item.advanced) extra++;
        if (extra > 0) {
            gap(body, 18);
            LinearLayout moreBox = settingsSection("其他边界 · " + extra + " 项");
            for (CheckReport.Item item : report.items) if (item.advanced) checkRow(moreBox, item);
        }

        gap(body, 14);
        if (historical || report.error != null) {
            body.addView(button("重新检查", "refresh", true, () -> startCheck(pendingTarget)));
        } else {
            LinearLayout actions = row();
            actions.addView(button("重检", "refresh", false, () -> startCheck(pendingTarget)), weight());
            actions.addView(new View(this), dp(10), 1);
            actions.addView(button(report.allPassed() ? "进入空间" : "确认后继续", "arrow", true, this::confirmCheck),
                    new LinearLayout.LayoutParams(0, -2, 2));
            body.addView(actions);
        }
        gap(body, 10);
        if (!has(GMS)) {
            body.addView(button("重试准备 microG", "refresh", false,
                    () -> runJob(() -> BundledApps.prepare(getApplicationContext(), ignored -> {}), "")));
            gap(body, 8);
        }
        body.addView(button("复制脱敏摘要", "copy", false, this::copyReport));
    }

    private void checkRow(LinearLayout parent, CheckReport.Item item) {
        if (parent.getChildCount() != 0) divider(parent);
        LinearLayout group = column(), header = row();
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.setMinimumHeight(dp(48));
        int color = stateColor(item.state);
        header.addView(icon(item.state == State.PASSED ? "check" : "info", 18, color));
        TextView title = text(item.title, 14, theme.ink, true);
        title.setPadding(dp(11), 0, dp(8), 0);
        header.addView(title, weight());
        header.addView(text(stateTitle(item.state), 11, color, false));
        View chevron = icon("down", 16, theme.muted);
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(16), dp(16));
        arrow.setMarginStart(dp(8));
        header.addView(chevron, arrow);
        group.addView(header);
        TextView detail = text(item.detail, 12, theme.muted, false);
        detail.setPadding(dp(43), 0, dp(14), dp(13));
        detail.setTextIsSelectable(true);
        group.addView(detail);
        Runnable update = () -> {
            boolean expanded = expandedChecks.contains(item.id);
            detail.setVisibility(expanded ? View.VISIBLE : View.GONE);
            chevron.setRotation(expanded ? 180 : 0);
            header.setContentDescription(item.title + "，" + stateTitle(item.state)
                    + (expanded ? "，收起详情" : "，展开详情"));
        };
        touch(header, theme.paper, false, () -> {
            if (!expandedChecks.remove(item.id)) expandedChecks.add(item.id);
            update.run();
        });
        update.run();
        parent.addView(group);
    }

    private void copyReport() {
        if (report == null) return;
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Open Cage 检查摘要", report.summary()));
            Toast.makeText(this, "已复制，不含账户、IP 与本机路径", Toast.LENGTH_SHORT).show();
        } catch (Exception unavailable) {
            Toast.makeText(this, "暂时无法复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void settingsPage() {
        body.addView(text("设置", 28, theme.ink, true));
        gap(body, 24);
        LinearLayout space = settingsSection("空间");
        settingRow(space, "space", "启动引导", null, () -> openPage(Page.GUIDE));
        settingRow(space, "globe", "绑定出口 IP", ExitBinding.label(this), () -> openPage(Page.EXIT));
        if (admitted()) {
            settingRow(space, "pause", "结束本次放行", null, () -> {
                invalidateConsent();
                render();
            });
        }
        gap(body, 23);

        SharedPreferences appearance = getSharedPreferences("cage-android-ui", MODE_PRIVATE);
        String[] modes = {"system", "light", "dark"}, names = {"跟随系统", "浅色", "深色"};
        String mode = appearance.getString("appearance", "system");
        int selected = "dark".equals(mode) ? 2 : "light".equals(mode) ? 1 : 0;
        settingRow(settingsSection("外观"), "theme", "主题", names[selected], () -> showDialog(dialog().setTitle("外观")
                .setSingleChoiceItems(names, selected, (d, index) -> {
                    appearance.edit().putString("appearance", modes[index]).apply();
                    d.dismiss();
                    render();
                }).setNegativeButton("取消", null), false));
        gap(body, 23);
        LinearLayout environment = settingsSection("环境与启动");
        GuestRegionProfile profile = GuestRegionProfile.read(this);
        settingRow(environment, "clock", "空间时区", profile.zone, () -> editGuestRegion(false));
        settingRow(environment, "user", "空间语言", regionLanguage(profile), () -> editGuestRegion(true));
        settingRow(environment, "sim", "SIM 屏蔽", "已配置", null);
        settingRow(environment, "shield", "VPN 状态屏蔽", "已配置", null);
        settingRow(environment, "globe", "网络连接", vpnCoverage(), () -> openPage(Page.EXIT));
        int hours = checkReuseHours();
        settingRow(environment, "clock", "检查缓存", hours == 0 ? "关闭" : hours + " 小时", this::editCheckReuseHours);
        settingRow(environment, "check", "启动方式", "后台核对", () -> showDialog(dialog().setTitle("后台核对")
                .setMessage("通过的检查可按设置在同一环境中复用。每次打开仍核对 VPN 和绑定出口。VPN 断开时会停止来宾应用。手机重启或环境变化后需重检。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("查看检查", (d, which) -> go(Page.CHECKS)), false));
        gap(body, 8);
        body.addView(text("已配置表示当前策略，实际生效情况以启动检查实测结果为准。", 11, theme.muted, false));
        gap(body, 23);
        LinearLayout about = settingsSection("关于");
        settingRow(about, "info", "关于 Open Cage", versionLabel(), () -> showInfo("关于 Open Cage",
                "独立的 Android 应用空间。账户、应用和登录数据保存在当前空间。\n\n版本 " + versionLabel()));
        settingRow(about, "shield", "适配范围与检查边界", null, () -> showInfo("适配范围与检查边界",
                "已接入 Java Binder、系统属性、ARM64 原生库、子进程 LD_PRELOAD 与 WebView 时区屏蔽，适用于 Claude 与内置检测组件。"
                        + "启动检查在空间内实测各项指标，并逐项标明通过、未通过和未确认。"));
        settingRow(about, "globe", "检查使用的服务", null, () -> showInfo("检查使用的服务",
                "通过当前 VPN 使用 icanhazip.com、ipify.org 核对公网 IPv4，并向 ipwho.is 查询已核对的出口地址、地区与时区。"));
        settingRow(about, "info", "第三方组件与许可", null, () -> {
            try {
                showInfo("第三方组件与许可", BundledApps.readAsset(this, "bundle/NOTICE.txt"));
            } catch (Exception unavailable) {
                Toast.makeText(this, "暂时无法读取许可说明", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private LinearLayout settingsSection(String title) {
        body.addView(text(title, 13, theme.ink, true));
        gap(body, 12);
        return panel(body);
    }

    private void settingRow(LinearLayout parent, String symbol, String title, String value, Runnable action) {
        if (parent.getChildCount() != 0) insetDivider(parent, 44);
        LinearLayout entry = row();
        entry.setMinimumHeight(dp(57));
        entry.setPadding(dp(14), dp(12), dp(14), dp(12));
        entry.addView(icon(symbol, 19, theme.muted));
        TextView name = text(title, 13, theme.ink, false);
        name.setPadding(dp(11), 0, dp(8), 0);
        entry.addView(name, weight());
        if (value != null) {
            TextView detail = text(value, 11, theme.muted, false);
            detail.setMaxWidth(dp(Math.min(150, getResources().getConfiguration().screenWidthDp / 3)));
            detail.setGravity(Gravity.END);
            entry.addView(detail);
        }
        if (action != null) {
            View arrow = icon("chevron", 14, theme.muted);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(14), dp(14));
            size.setMarginStart(dp(9));
            entry.addView(arrow, size);
            touch(entry, theme.paper, false, action);
        }
        parent.addView(entry);
    }

    private void showInfo(String title, String message) {
        showDialog(dialog().setTitle(title).setMessage(message).setPositiveButton("关闭", null), false);
    }

    private ImageView cageLogo(int size) {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.cage_toolbar);
        logo.setBackground(shape(0xfffaf9f6, Math.max(6, size / 5), false));
        logo.setClipToOutline(true);
        return logo;
    }

    private String versionLabel() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " · ARM64";
        } catch (PackageManager.NameNotFoundException unavailable) {
            return "ARM64";
        }
    }

    private String targetLabel(String target) {
        if (ACCOUNT_TARGET.equals(target)) return "Google 账户";
        if (MICROG_TARGET.equals(target)) return "microG 设置";
        if (LISTING_TARGET.equals(target) || STORE.equals(target)) return "Google Play";
        if (CLAUDE.equals(target)) return "Claude";
        for (SpaceApps.App app : installed) if (target.equals(app.pkg)) return app.label;
        return "空间应用";
    }

    private void vpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            status = "请从手机系统设置打开 VPN。";
            render();
        }
    }

    private static String stateTitle(State state) {
        switch (state) {
            case PASSED: return "通过";
            case FAILED: return "未通过";
            default: return "未确认";
        }
    }

    private int stateColor(State state) {
        switch (state) {
            case PASSED: return theme.success;
            case FAILED: return theme.danger;
            default: return theme.warning;
        }
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, theme.dark ? R.style.CageDialogDark : R.style.CageDialogLight);
    }

    private AlertDialog showDialog(AlertDialog.Builder builder, boolean destructive) {
        AlertDialog alert = builder.show();
        Window window = alert.getWindow();
        window.setBackgroundDrawable(shape(theme.paper, 24, true));
        window.setDimAmount(theme.dark ? .64f : .42f);
        int available = getResources().getDisplayMetrics().widthPixels - dp(32);
        window.setLayout(Math.min(available, dp(432)), WindowManager.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        TextView message = alert.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(theme.muted);
            message.setTextSize(15);
            message.setLineSpacing(dp(2), 1);
        }
        styleDialogButton(alert.getButton(AlertDialog.BUTTON_NEUTRAL), theme.chrome, theme.ink);
        styleDialogButton(alert.getButton(AlertDialog.BUTTON_NEGATIVE), theme.chrome, theme.ink);
        styleDialogButton(alert.getButton(AlertDialog.BUTTON_POSITIVE),
                destructive ? theme.danger : theme.button, theme.buttonInk);
        return alert;
    }

    private void styleDialogButton(Button button, int background, int foreground) {
        if (button == null || button.getVisibility() != View.VISIBLE) return;
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(16), dp(6), dp(16), dp(6));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x307F7F7F),
                shape(background, 14, false), null));
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
        margins.setMarginStart(dp(4));
        margins.setMarginEnd(dp(4));
        margins.topMargin = dp(4);
        margins.bottomMargin = dp(9);
        button.setLayoutParams(margins);
    }

    private final class DialogField extends LinearLayout {
        final EditText input;
        private final TextView error;

        DialogField(Context context, String hint) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(24), dp(6), dp(24), dp(8));
            input = new EditText(context);
            input.setSingleLine();
            input.setHint(hint);
            input.setTextColor(theme.ink);
            input.setHintTextColor(theme.muted);
            input.setTextSize(16);
            input.setMinimumHeight(dp(52));
            input.setPadding(dp(14), dp(10), dp(14), dp(10));
            input.setSelectAllOnFocus(true);
            input.setBackgroundTintList(null);
            input.setBackground(shape(theme.chrome, 12, true));
            addView(input, -1, -2);
            error = text("", 12, theme.danger, false);
            error.setPadding(dp(2), dp(6), dp(2), 0);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            error.setVisibility(GONE);
            addView(error, -1, -2);
            watchText(input, ignored -> error.setVisibility(GONE));
        }

        void showError(String message) {
            error.setText(message);
            error.setVisibility(VISIBLE);
        }
    }

    private void appRow(LinearLayout parent, SpaceApps.App app, Runnable action, Runnable remove) {
        LinearLayout entry = row(), main = row(), labels = column();
        main.setPadding(dp(15), dp(remove == null ? 10 : 14), dp(7), dp(remove == null ? 10 : 14));
        if (app.icon == null) main.addView(icon("space", 36, theme.muted));
        else {
            ImageView image = new ImageView(this);
            image.setImageDrawable(app.icon.getConstantState() == null
                    ? app.icon : app.icon.getConstantState().newDrawable());
            main.addView(image, dp(36), dp(36));
        }
        labels.setPadding(dp(12), 0, 0, 0);
        TextView name = text(app.label, 16, theme.ink, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        if (remove == null) {
            TextView pkg = text(app.pkg, 11, theme.muted, false);
            pkg.setMaxLines(1);
            pkg.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(pkg);
        }
        main.addView(labels, weight());
        if (remove == null) main.addView(icon("plus", 18, theme.muted));
        touch(main, theme.paper, false, action);
        main.setEnabled(!busy);
        entry.addView(main, weight());
        if (remove != null) {
            View actionButton = iconButton("trash", "移除 " + app.label, remove);
            actionButton.setEnabled(!busy);
            entry.addView(actionButton);
        }
        parent.addView(entry);
    }

    private void option(LinearLayout parent, String symbol, String title, String detail, Runnable action) {
        if (parent.getChildCount() != 0) divider(parent);
        LinearLayout entry = row(), labels = column();
        entry.setMinimumHeight(dp(49));
        entry.setPadding(dp(12), dp(11), dp(12), dp(11));
        entry.addView(icon(symbol, 20, theme.muted));
        labels.setPadding(dp(11), 0, dp(4), 0);
        labels.addView(text(title, 14, theme.ink, true));
        if (detail != null) {
            gap(labels, 4);
            labels.addView(text(detail, 12, theme.muted, false));
        }
        entry.addView(labels, weight());
        if (action != null) {
            entry.addView(icon("chevron", 16, theme.muted));
            touch(entry, theme.paper, false, action);
        }
        parent.addView(entry);
    }

    private void heading(String title, String detail) {
        body.addView(text(title, 25, theme.ink, true));
        if (detail != null) {
            gap(body, 7);
            body.addView(text(detail, 13, theme.muted, false));
        }
        gap(body, 15);
    }

    private void note(LinearLayout parent, String value) {
        TextView view = text(value, 13, theme.muted, false);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        parent.addView(view);
    }

    private EditText search(String value, Consumer<String> change) {
        EditText field = new EditText(this);
        field.setHint("搜索应用名或包名");
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(theme.ink);
        field.setHintTextColor(theme.muted);
        field.setPadding(dp(12), dp(9), dp(12), dp(9));
        field.setBackground(shape(theme.paper, true));
        field.setText(value);
        field.setSelection(value.length());
        watchText(field, change);
        return field;
    }

    private void watchText(EditText field, Consumer<String> change) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                change.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void insertSearch(EditText field, LinearLayout list) {
        body.addView(field, body.indexOfChild(list));
        body.addView(new View(this), body.indexOfChild(list), new LinearLayout.LayoutParams(1, dp(8)));
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (focused != null && input != null) input.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private View button(String label, String symbol, boolean primary, Runnable action) {
        LinearLayout button = row();
        button.setMinimumHeight(dp(44));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), dp(8), dp(9), dp(8));
        int ink = primary ? theme.buttonInk : theme.ink;
        if (symbol != null) {
            button.addView(icon(symbol, 17, ink));
            button.addView(new View(this), dp(6), 1);
        }
        button.addView(text(label, 13, ink, true));
        touch(button, primary ? theme.button : theme.chrome, !primary, action);
        return button;
    }

    private View iconButton(String symbol, String label, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.setMinimumWidth(dp(44));
        button.setMinimumHeight(dp(44));
        button.addView(new LineIcon(this, symbol, theme.ink),
                new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        button.setContentDescription(label);
        touch(button, theme.paper, false, action);
        return button;
    }

    private View icon(String symbol, int size, int color) {
        LineIcon view = new LineIcon(this, symbol, color);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout box = column();
        box.setBackground(shape(theme.paper, true));
        box.setPadding(dp(1), dp(1), dp(1), dp(1));
        box.setClipToOutline(true);
        parent.addView(box);
        return box;
    }

    private GradientDrawable shape(int fill, boolean border) {
        return shape(fill, 10, border);
    }

    private GradientDrawable shape(int fill, int radius, boolean border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (border) background.setStroke(dp(1), theme.line);
        return background;
    }

    private void touch(View view, int fill, boolean border, Runnable action) {
        int pressed = theme.dark ? 0x22ffffff : 0x15000000;
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed), shape(fill, border), null));
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(theme.line);
        parent.addView(line, -1, dp(1));
    }

    private void appDivider(LinearLayout parent) {
        insetDivider(parent, 66);
    }

    private void insetDivider(LinearLayout parent, int inset) {
        View line = new View(this);
        line.setBackgroundColor(theme.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMarginStart(dp(inset));
        params.setMarginEnd(dp(inset));
        parent.addView(line, params);
    }

    private void gap(LinearLayout parent, int value) { parent.addView(new View(this), 1, dp(value)); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
