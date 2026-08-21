package com.dbagent.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Java 8 has no java.util.Map.of(...); drop-in immutable-map builder used in its place. */
public final class Maps {

    private Maps() {
    }

    public static <K, V> Map<K, V> of() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> of(K k1, V v1) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        m.put(k5, v5);
        return Collections.unmodifiableMap(m);
    }
}
