package com.oplus.internal.reflect;

import com.oplus.internal.reflect.internal.BaseField;
import com.oplus.utils.Alog;
import com.oplus.utils.TypeUtils;

import java.lang.reflect.Field;

public final class RefStaticObject<T> extends BaseField {

    public RefStaticObject(Class<?> cls, Field field) {
        super(cls, field);
    }

    @Deprecated
    public T get() {
        try {
            return TypeUtils.cast(getObject(null));
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
            return null;
        }
    }

    @Deprecated
    public void set(T value) {
        try {
            setObject(null, value);
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
        }
    }

    public T getWithDefault(T defaultValue) {
        try {
            return TypeUtils.cast(getObject(null));
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
            return defaultValue;
        }
    }

    public T getWithException() throws IllegalAccessException {
        return TypeUtils.cast(getObject(null));
    }

    public void setWithException(T value) throws IllegalAccessException {
        setObject(null, value);
    }
}
