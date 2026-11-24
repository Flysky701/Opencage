package io.github.flysky701.opencage;

/** Pure decision rules: no missing evidence becomes a pass; test consent is not evidence. */
public final class CheckPolicy {
    public enum State { PASSED, FAILED, UNKNOWN }
    public static final long LIFETIME_MS = 5 * 60 * 1000;
    public static final int DEFAULT_REUSE_HOURS = 12;
    public static final int MAX_REUSE_HOURS = 24;

    private CheckPolicy() {}

    public static boolean allowsLaunch(State permissions, State vpn) {
        return permissions == State.PASSED && vpn == State.PASSED;
    }

    public static State mask(boolean complete, boolean outerPositive, boolean guestSignal) {
        if (guestSignal) return State.FAILED;
        if (!complete || !outerPositive) return State.UNKNOWN;
        return State.PASSED;
    }

    public static State timezone(boolean complete, boolean matches) {
        return !complete ? State.UNKNOWN : matches ? State.PASSED : State.FAILED;
    }

    public static State networkMetadata(boolean complete, boolean positiveControl, boolean emptyView) {
        if (!complete || !positiveControl) return State.UNKNOWN;
        return emptyView ? State.PASSED : State.FAILED;
    }

    public static boolean fresh(String expectedRequest, String reportRequest, long startedElapsed,
            long reportStartElapsed, boolean complete, long elapsedNow) {
        return expectedRequest != null && expectedRequest.equals(reportRequest) && complete
                && startedElapsed > 0 && reportStartElapsed >= startedElapsed
                && reportStartElapsed <= elapsedNow && elapsedNow - startedElapsed < 60000;
    }

    public static boolean reusable(String approvedRequest, String latestRequest, String approvedEnvironment,
            String environment, int approvedBoot, int boot, long approvedAt, long now, int hours) {
        return hours > 0 && hours <= MAX_REUSE_HOURS
                && approvedBoot >= 0 && approvedBoot == boot
                && approvedRequest != null && approvedRequest.equals(latestRequest)
                && approvedEnvironment != null && approvedEnvironment.equals(environment)
                && !approvedEnvironment.contains("|unavailable")
                && approvedAt > 0 && now >= approvedAt && now - approvedAt < hours * 60L * 60 * 1000;
    }
}
