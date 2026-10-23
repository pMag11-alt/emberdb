package dev.emberdb.store;

import java.io.Serial;
import java.util.LinkedHashMap;

/** The hash type: field → value, in insertion order so replies are deterministic. */
public final class HashValue extends LinkedHashMap<String, String> {

    @Serial
    private static final long serialVersionUID = 1L;
}
