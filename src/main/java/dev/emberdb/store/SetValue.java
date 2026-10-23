package dev.emberdb.store;

import java.io.Serial;
import java.util.LinkedHashSet;

/** The set type: unique members, in insertion order so replies are deterministic. */
public final class SetValue extends LinkedHashSet<String> {

    @Serial
    private static final long serialVersionUID = 1L;
}
