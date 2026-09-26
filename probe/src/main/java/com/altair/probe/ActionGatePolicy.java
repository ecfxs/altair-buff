package com.altair.probe;

/** Pure conflict policy for preserving the user's saved value unless they explicitly choose otherwise. */
public final class ActionGatePolicy {
    private ActionGatePolicy() { }

    public static boolean hasConflict(boolean savedValue, boolean externalValue, boolean draftValue) {
        return externalValue != savedValue && draftValue == savedValue;
    }
}
