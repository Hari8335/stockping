package dev.stockping.demand.domain;

import java.util.Set;

public enum WaitlistStatus {
    WAITING, NOTIFICATION_QUEUED, NOTIFIED, FULFILLED, EXPIRED, CANCELLED, OPTED_OUT;
    public static final Set<WaitlistStatus> ACTIVE = Set.of(WAITING, NOTIFICATION_QUEUED);
    public boolean isActive() { return ACTIVE.contains(this); }
}
