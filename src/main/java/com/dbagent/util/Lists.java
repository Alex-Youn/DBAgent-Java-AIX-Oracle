package com.dbagent.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Java 8 has no java.util.List.of(...); drop-in immutable-list builder used in its place. */
public final class Lists {

    private Lists() {
    }

    @SafeVarargs
    public static <T> List<T> of(T... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }
}
