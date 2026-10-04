package com.dbagent.settings;

/** 한 키의 현재 유효값과 출처. 불변. */
public final class SettingValue {
    private final SettingKey key;
    private final String value;
    private final SettingSource source;

    public SettingValue(SettingKey key, String value, SettingSource source) {
        this.key = key;
        this.value = value;
        this.source = source;
    }

    public SettingKey key() { return key; }
    public String value() { return value; }
    public SettingSource source() { return source; }

    @Override
    public String toString() {
        return key.key() + "=" + (key.secret() ? "***" : value) + " (" + source.label() + ")";
    }
}
