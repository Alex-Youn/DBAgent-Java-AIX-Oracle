package com.dbagent.util;

/** Java 8 replacements for String.isBlank/strip/stripTrailing (Java 11+) and HexFormat (Java 17+). */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String strip(String s) {
        return s == null ? null : s.trim();
    }

    public static String stripTrailing(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
