package com.altair.probe;

/** 单个周期任务的排期。时间由调用方传入，便于确定性验证。 */
public final class Schedule {
    private long period;
    private long lastSuccess = -1;
    private long due;
    public int failures;

    public void configure(long newPeriod, long now) {
        if (newPeriod < 0) throw new IllegalArgumentException("周期不能为负");
        if (newPeriod == period) return;
        due = newPeriod == 0 ? 0 : (period == 0 || lastSuccess < 0 ? now : lastSuccess + newPeriod);
        period = newPeriod;
        failures = 0;
    }
    public boolean ready(long now) { return period > 0 && due <= now; }
    public long dueAt() { return due; }
    public void success(long now) {
        lastSuccess = now;
        due = now + period;
        failures = 0;
    }
    public void failure(long now) { due = now + 30_000L * ++failures; }
}
