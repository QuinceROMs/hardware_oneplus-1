package com.oplus.internal.reflect;

import com.oplus.utils.Alog;
import com.oplus.utils.TypeUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.HashMap;

public final class RefClass {

    private static final HashMap<Class<?>, Constructor<?>> REF_TYPES = new HashMap<>();

    static {
        try {
            Class<?>[] refClasses = {RefObject.class, RefMethod.class, RefStaticMethod.class,
                    RefInt.class, RefLong.class, RefFloat.class, RefDouble.class, RefBoolean.class,
                    RefByte.class, RefChar.class, RefShort.class, RefConstructor.class,
                    RefStaticInt.class, RefStaticByte.class, RefStaticChar.class, RefStaticDouble.class,
                    RefStaticFloat.class, RefStaticLong.class, RefStaticObject.class,
                    RefStaticShort.class, RefStaticBoolean.class};
            for (Class<?> clazz : refClasses) {
                REF_TYPES.put(clazz, clazz.getConstructor(Class.class, Field.class));
            }
        } catch (Exception e) {
            Alog.e(e.getMessage(), e);
        }
    }

    public static Class<?> load(Class<?> mappingClass, String targetClassName) {
        ClassLoader classLoader = TypeUtils.checkMappingClass(mappingClass).getClassLoader();
        return load(mappingClass, targetClassName, classLoader);
    }

    public static Class<?> load(Class<?> mappingClass, String targetClassName,
            ClassLoader classLoader) {
        Class<?> targetClass = TypeUtils.classForName(targetClassName, classLoader);
        return load(mappingClass, targetClass);
    }

    public static Class<?> load(Class<?> mappingClass, Class<?> targetClass) {
        if (mappingClass == null || targetClass == null) {
            return null;
        }
        Alog.d("Load mappingClass=" + mappingClass.getName()
                + ", targetClass=" + targetClass.getName());
        for (Field mappingField : mappingClass.getDeclaredFields()) {
            if (!Modifier.isStatic(mappingField.getModifiers())) {
                continue;
            }
            try {
                mappingField.setAccessible(true);
                Constructor<?> refConstructor = REF_TYPES.get(mappingField.getType());
                if (refConstructor != null) {
                    mappingField.set(null, refConstructor.newInstance(targetClass, mappingField));
                }
            } catch (IllegalAccessException | InstantiationException
                    | InvocationTargetException e) {
                Alog.e("Failed load, mappingClass=" + mappingClass.getName()
                        + ", targetClass=" + targetClass.getName(), e);
            }
        }
        return targetClass;
    }
}
