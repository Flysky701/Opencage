package io.github.flysky701.opencage;

import io.github.flysky701.opencage.CheckPolicy.State;

public final class CheckPolicyTest {
    public static void main(String[] args) {
        int checked = 0;
        for (State permissions : State.values()) {
            for (State vpn : State.values()) {
                boolean expected = permissions == State.PASSED && vpn == State.PASSED;
                if (CheckPolicy.allowsLaunch(permissions, vpn) != expected) {
                    throw new AssertionError("permissions=" + permissions + ", vpn=" + vpn);
                }
                checked++;
            }
        }
        long hour = 60L * 60 * 1000;
        checkReuse(0, 1, "vpn", 4, false);
        checkReuse(1, hour - 1, "vpn", 4, true);
        checkReuse(1, hour, "vpn", 4, false);
        checkReuse(24, 24 * hour - 1, "vpn", 4, true);
        checkReuse(24, 24 * hour, "vpn", 4, false);
        checkReuse(25, 1, "vpn", 4, false);
        checkReuse(24, 1, "changed", 4, false);
        checkReuse(24, 1, "vpn", 5, false);
        System.out.println((checked + 8) + " CheckPolicy cases passed");
    }

    private static void checkReuse(int hours, long age, String environment, int boot, boolean expected) {
        if (CheckPolicy.reusable("request", "request", "vpn", environment, 4, boot, 1, 1 + age, hours) != expected) {
            throw new AssertionError("hours=" + hours + ", age=" + age + ", environment=" + environment + ", boot=" + boot);
        }
    }
}
