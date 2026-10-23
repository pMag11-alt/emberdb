package dev.emberdb.engine;

import java.util.function.LongSupplier;

/** A clock tests can move forward, so TTLs can be tested without sleeping. */
public final class ManualClock implements LongSupplier {

    private long now = 1_700_000_000_000L;

    @Override
    public long getAsLong() {
        return now;
    }

    public void advance(long millis) {
        now += millis;
    }
}
