package com.oplus.internal.reflect.internal;

import com.oplus.utils.Alog;

import java.lang.reflect.Field;

public abstract class BaseField {

    private final Field mField;

    public BaseField(Class<?> targetCls, Field field) {
        mField = getField(targetCls, field);
        if (mField != null) {
            mField.setAccessible(true);
        }
    }

    private Field getField(Class<?> targetCls, Field targetField) {
        try {
            Field result = targetCls.getDeclaredField(targetField.getName());
            result.setAccessible(true);
            return result;
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
            return null;
        }
    }

    private void checkExist() throws IllegalAccessException {
        if (mField == null) {
            throw new IllegalAccessException("Target field should not null");
        }
    }

    protected boolean getBoolean(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getBoolean(target);
    }

    protected void setBoolean(Object target, boolean value) throws IllegalAccessException {
        checkExist();
        mField.setBoolean(target, value);
    }

    protected byte getByte(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getByte(target);
    }

    protected void setByte(Object target, byte value) throws IllegalAccessException {
        checkExist();
        mField.setByte(target, value);
    }

    protected char getChar(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getChar(target);
    }

    protected void setChar(Object target, char value) throws IllegalAccessException {
        checkExist();
        mField.setChar(target, value);
    }

    protected double getDouble(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getDouble(target);
    }

    protected void setDouble(Object target, double value) throws IllegalAccessException {
        checkExist();
        mField.setDouble(target, value);
    }

    protected float getFloat(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getFloat(target);
    }

    protected void setFloat(Object target, float value) throws IllegalAccessException {
        checkExist();
        mField.setFloat(target, value);
    }

    protected int getInt(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getInt(target);
    }

    protected void setInt(Object target, int value) throws IllegalAccessException {
        checkExist();
        mField.setInt(target, value);
    }

    protected long getLong(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getLong(target);
    }

    protected void setLong(Object target, long value) throws IllegalAccessException {
        checkExist();
        mField.setLong(target, value);
    }

    protected short getShort(Object target) throws IllegalAccessException {
        checkExist();
        return mField.getShort(target);
    }

    protected void setShort(Object target, short value) throws IllegalAccessException {
        checkExist();
        mField.setShort(target, value);
    }

    protected Object getObject(Object target) throws IllegalAccessException {
        checkExist();
        return mField.get(target);
    }

    protected void setObject(Object target, Object value) throws IllegalAccessException {
        checkExist();
        mField.set(target, value);
    }
}
