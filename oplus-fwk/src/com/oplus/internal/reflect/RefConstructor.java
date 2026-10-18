package com.oplus.internal.reflect;

import com.oplus.internal.reflect.internal.MethodVisitor;
import com.oplus.utils.Alog;
import com.oplus.utils.TypeUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public final class RefConstructor<T> {

    private final Constructor<?> mConstructor;

    public RefConstructor(Class<?> targetClass, Field mappingField) {
        mConstructor = getConstructor(targetClass, getParameterTypesFrom(mappingField));
        if (mConstructor != null) {
            mConstructor.setAccessible(true);
        }
    }

    @Deprecated
    public T newInstance(Object... params) {
        try {
            return newInstanceWithException(params);
        } catch (ReflectiveOperationException e) {
            Alog.e("Failed newInstance", e);
            return null;
        }
    }

    public T newInstanceWithException(Object... params)
            throws IllegalAccessException, InstantiationException, InvocationTargetException {
        checkExist();
        return TypeUtils.cast(mConstructor.newInstance(params));
    }

    private void checkExist() throws IllegalAccessException {
        if (mConstructor == null) {
            throw new IllegalAccessException("Target constructor should not null");
        }
    }

    private Constructor<?> getConstructor(Class<?> targetClass, Class<?>[] parameterTypes) {
        try {
            return targetClass.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            Alog.e("Failed get constructor, targetClass=" + targetClass, e);
            return null;
        }
    }

    private Class<?>[] getParameterTypesFrom(Field mappingField) {
        if (mappingField.isAnnotationPresent(MethodName.class)) {
            return mappingField.getAnnotation(MethodName.class).params();
        }
        if (mappingField.isAnnotationPresent(MethodSignature.class)) {
            return MethodVisitor.visitParameterTypes(
                    mappingField.getAnnotation(MethodSignature.class));
        }
        if (mappingField.isAnnotationPresent(MethodParams.class)) {
            return mappingField.getAnnotation(MethodParams.class).value();
        }
        if (mappingField.isAnnotationPresent(MethodReflectParams.class)) {
            return MethodVisitor.visitParameterTypes(
                    mappingField.getAnnotation(MethodReflectParams.class));
        }
        return null;
    }
}
