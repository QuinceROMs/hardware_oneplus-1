package com.oplus.internal.reflect.internal;

import com.oplus.internal.reflect.MethodName;
import com.oplus.internal.reflect.MethodParams;
import com.oplus.internal.reflect.MethodReflectParams;
import com.oplus.internal.reflect.MethodSignature;
import com.oplus.utils.Alog;
import com.oplus.utils.TypeUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;

public abstract class BaseMethod {

    private final Method mMethod;

    public BaseMethod(Class<?> targetClass, Field mappingField) {
        mMethod = findTargetMethod(targetClass, getMethodNameFrom(mappingField),
                getMethodParameterTypesFrom(mappingField));
        if (mMethod != null) {
            mMethod.setAccessible(true);
        }
    }

    protected Object invoke(Object obj, Object... args)
            throws IllegalAccessException, InvocationTargetException {
        checkExist();
        return mMethod.invoke(obj, args);
    }

    private void checkExist() throws IllegalAccessException {
        if (mMethod == null) {
            throw new IllegalAccessException("Target method should not null");
        }
    }

    private String getMethodNameFrom(Field mappingField) {
        String methodName = "";
        if (mappingField.isAnnotationPresent(MethodName.class)) {
            methodName = mappingField.getAnnotation(MethodName.class).name();
        } else if (mappingField.isAnnotationPresent(MethodSignature.class)) {
            methodName = mappingField.getAnnotation(MethodSignature.class).name();
        }
        if ("".equals(methodName)) {
            return mappingField.getName();
        }
        return methodName;
    }

    private Class<?>[] getMethodParameterTypesFrom(Field mappingField) {
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

    private Method findTargetMethod(Class<?> targetClass, String name, Class<?>[] parameterTypes) {
        if (parameterTypes == null) {
            return findMethodForName(targetClass, name);
        }
        return getDeclaredMethod(targetClass, name, parameterTypes);
    }

    private Method findMethodForName(Class<?> targetClass, String name) {
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private Method getDeclaredMethod(Class<?> targetClass, String name, Class<?>[] parameterTypes) {
        try {
            return targetClass.getDeclaredMethod(name, parameterTypes);
        } catch (Exception e) {
            Alog.e("Failed getDeclaredMethod", e);
            try {
                return targetClass.getDeclaredMethod(name, fixParameterTypes(parameterTypes));
            } catch (Exception e2) {
                Alog.e("Failed getDeclaredMethod with fix list types", e2);
                return null;
            }
        }
    }

    private Class<?>[] fixParameterTypes(Class<?>[] parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == HashSet.class) {
                parameterTypes[i] = TypeUtils.classForName("android.util.ArraySet", null);
            }
        }
        return parameterTypes;
    }
}
