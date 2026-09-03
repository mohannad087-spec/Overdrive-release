package com.overdrive.app.camera;

/**
 * Tracks which DiLink 4 camera client owns the panorama viewpoint.
 *
 * <p>The native AVC UI owns it while foreground. All other camera modes retain
 * their existing behavior by keeping this policy disabled for the session.
 */
final class Di4AvcViewpointPolicy {

    enum Action {
        NONE,
        YIELD,
        RESTORE
    }

    private boolean enabled;
    private boolean nativeAvcForeground;
    private boolean viewpointYielded;
    private long yieldTimestamp;

    static boolean isEnabledForCameraMode(String cameraMode) {
        return "dilink4".equalsIgnoreCase(cameraMode);
    }

    void beginSession(boolean enable) {
        enabled = enable;
        nativeAvcForeground = false;
        viewpointYielded = false;
        yieldTimestamp = 0L;
    }

    void endSession() {
        enabled = false;
        nativeAvcForeground = false;
        viewpointYielded = false;
        yieldTimestamp = 0L;
    }

    Action onNativeAvcForeground(Boolean foreground, boolean hasViewpointHolder) {
        if (!enabled || foreground == null || !hasViewpointHolder) return Action.NONE;

        nativeAvcForeground = foreground;
        if (nativeAvcForeground && !viewpointYielded) return Action.YIELD;
        if (!nativeAvcForeground && viewpointYielded) return Action.RESTORE;
        return Action.NONE;
    }

    boolean isYieldPending() {
        return enabled && nativeAvcForeground && !viewpointYielded;
    }

    boolean isRestorePending(boolean hasViewpointHolder) {
        return enabled && hasViewpointHolder && !nativeAvcForeground && viewpointYielded;
    }

    boolean isViewpointYielded() {
        return enabled && viewpointYielded;
    }

    void markViewpointYielded() {
        if (enabled && nativeAvcForeground) {
            viewpointYielded = true;
            yieldTimestamp = System.currentTimeMillis();
        }
    }

    void markViewpointRestored() {
        if (enabled && !nativeAvcForeground) {
            viewpointYielded = false;
            yieldTimestamp = 0L;
        }
    }

    /**
     * True once we've been yielded to the native AVC UI for longer than
     * {@code timeoutMs} without a foreground-loss transition clearing it.
     *
     * <p>Exists because the only signal that ends a yield is
     * {@link #onNativeAvcForeground}, which itself depends on a foreground
     * probe ({@code ActivityManager.getRunningTasks}/{@code
     * getRunningAppProcesses}) that some firmware/permission combinations
     * answer incorrectly or not at all. When that happens the probe never
     * reports {@code foreground=false} again, {@code viewpointYielded} stays
     * true forever, and the panorama viewpoint never gets re-asserted — the
     * camera view is then stuck showing whatever com.byd.avc last left it on.
     * A stale-yield timeout is the only way out when the primary signal is
     * unreliable.
     */
    boolean isStuckYielded(long nowMs, long timeoutMs) {
        return enabled && viewpointYielded && yieldTimestamp > 0
            && (nowMs - yieldTimestamp) > timeoutMs;
    }

    /**
     * Unconditionally clears the yield, regardless of what {@code
     * nativeAvcForeground} currently claims. Used by the stale-yield
     * watchdog to recover when the foreground signal itself can't be
     * trusted — a plain {@link #markViewpointRestored()} would refuse to
     * clear the flag while {@code nativeAvcForeground} is (possibly
     * incorrectly) still true.
     */
    void forceRestore() {
        nativeAvcForeground = false;
        viewpointYielded = false;
        yieldTimestamp = 0L;
    }
}
