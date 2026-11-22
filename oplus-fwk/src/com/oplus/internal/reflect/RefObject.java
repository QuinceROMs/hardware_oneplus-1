package com.oplus.internal.reflect;

import com.oplus.internal.reflect.internal.BaseField;
import com.oplus.utils.Alog;
import com.oplus.utils.TypeUtils;

import java.lang.reflect.Field;

public final class RefObject<T> extends BaseField {

    public RefObject(Class<?> cls, Field field) {
        super(cls, field);
    }

    @Deprecated
    public T get(Object obj) {
        try {
            return TypeUtils.cast(getObject(obj));
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
            return null;
        }
    }

    @Deprecated
    public void set(Object target, T value) {
        try {
            setObject(target, value);
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
        }
    }

    public T getWithDefault(Object obj, T defaultValue) {
        try {
            return TypeUtils.cast(getObject(obj));
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
            return defaultValue;
        }
    }

    public T getWithException(Object obj) throws IllegalAccessException {
        return TypeUtils.cast(getObject(obj));
    }

    public void setWithException(Object target, T value) throws IllegalAccessException {
        setObject(target, value);
    }
}
