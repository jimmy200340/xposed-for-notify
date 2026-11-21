package com.amazfit.balance2fix;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Framework-agnostic hooking & reflection bridge shared by both
 * modern libxposed API (101/102) and legacy Xposed API (82) entry points.
 *
 * IMPORTANT: This class must NOT reference {@code de.robv.android.xposed.*}
 * or {@code io.github.libxposed.*} so that libxposed API 102 runtime checks
 * never encounter legacy API references.
 */
public final class HookBridge {

    private static final String TAG = "Balance2WeatherFix";

    public interface Backend {
        void hookMethod(Method method, MethodHook hook);

        void log(int priority, String tag, String msg, Throwable tr);

        SharedPreferences getModulePreferences(String prefsName);

        String getApiDescription();
    }

    public static abstract class MethodHook {
        public static final class MethodHookParam {
            public Member method;
            public Object thisObject;
            public Object[] args;

            private Object result;
            private Throwable throwable;
            private boolean returnEarly;
            private boolean resultSet;

            public Object getResult() {
                return result;
            }

            public void setResult(Object result) {
                this.result = result;
                this.throwable = null;
                this.returnEarly = true;
                this.resultSet = true;
            }

            public Throwable getThrowable() {
                return throwable;
            }

            public void setThrowable(Throwable throwable) {
                this.throwable = throwable;
                this.result = null;
                this.returnEarly = true;
                this.resultSet = true;
            }

            public boolean hasThrowable() {
                return throwable != null;
            }

            boolean isReturnEarly() {
                return returnEarly;
            }

            boolean isResultSet() {
                return resultSet;
            }

            void resetBeforeError() {
                this.result = null;
                this.throwable = null;
                this.returnEarly = false;
                this.resultSet = false;
            }

            void setOriginalResult(Object result) {
                this.result = result;
                this.throwable = null;
                this.resultSet = false;
            }

            void setOriginalThrowable(Throwable throwable) {
                this.throwable = throwable;
                this.result = null;
                this.resultSet = false;
            }
        }

        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        }

        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        }
    }

    public static final class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) {
            super(cause);
        }
    }

    private static volatile Backend sBackend;

    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> FIELD_CACHE =
            new ConcurrentHashMap<>();

    private HookBridge() {
    }

    public static void setBackend(Backend backend) {
        sBackend = backend;
    }

    public static Backend getBackend() {
        return sBackend;
    }

    public static String getApiDescription() {
        Backend b = sBackend;
        return b != null ? b.getApiDescription() : "Unknown";
    }

    public static void logInfo(String tag, String msg) {
        Backend b = sBackend;
        if (b != null) {
            try {
                b.log(Log.INFO, tag, msg, null);
            } catch (Throwable ignored) {
            }
        }
        Log.i(tag, msg);
    }

    public static void logError(String tag, String msg, Throwable tr) {
        Backend b = sBackend;
        if (b != null) {
            try {
                b.log(Log.ERROR, tag, msg, tr);
            } catch (Throwable ignored) {
            }
        }
        Log.e(tag, msg, tr);
    }

    public static SharedPreferences getModulePreferences(String prefsName) {
        Backend b = sBackend;
        if (b == null) return null;
        try {
            return b.getModulePreferences(prefsName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty()) return null;
        try {
            ClassLoader cl = classLoader != null ? classLoader : HookBridge.class.getClassLoader();
            return Class.forName(className, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (clazz == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        Backend backend = sBackend;
        if (backend == null) {
            throw new IllegalStateException("HookBridge backend not initialized");
        }

        MethodHook callback = (MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] paramTypes = getParameterClasses(clazz.getClassLoader(), parameterTypesAndCallback);
        Method method = findMethodExact(clazz, methodName, paramTypes);
        backend.hookMethod(method, callback);
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + Arrays.toString(parameterTypes));
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        ConcurrentHashMap<String, Field> classFields = FIELD_CACHE.get(clazz);
        if (classFields == null) {
            classFields = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Field> existing = FIELD_CACHE.putIfAbsent(clazz, classFields);
            if (existing != null) classFields = existing;
        }
        Field cached = classFields.get(fieldName);
        if (cached != null) return cached;

        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                classFields.put(fieldName, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        try {
            findField(obj.getClass(), fieldName).setLong(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static double getDoubleField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getDouble(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setDoubleField(Object obj, String fieldName, double value) {
        try {
            findField(obj.getClass(), fieldName).setDouble(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static float getFloatField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getFloat(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        try {
            findField(obj.getClass(), fieldName).setFloat(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        Object[] safeArgs = args != null ? args : new Object[0];
        Method method = findMethodBestMatch(obj.getClass(), methodName, safeArgs, false);
        try {
            return method.invoke(obj, safeArgs);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Object[] safeArgs = args != null ? args : new Object[0];
        Method method = findMethodBestMatch(clazz, methodName, safeArgs, true);
        try {
            return method.invoke(null, safeArgs);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    private static Class<?>[] getParameterClasses(ClassLoader classLoader, Object[] parameterTypesAndCallback) {
        int count = parameterTypesAndCallback.length - 1;
        Class<?>[] parameterClasses = new Class<?>[count];
        for (int i = 0; i < count; i++) {
            Object type = parameterTypesAndCallback[i];
            if (type instanceof Class<?>) {
                parameterClasses[i] = (Class<?>) type;
            } else if (type instanceof String) {
                Class<?> resolved = findClassIfExists((String) type, classLoader);
                if (resolved == null) {
                    throw new RuntimeException("Class not found: " + type);
                }
                parameterClasses[i] = resolved;
            } else {
                throw new IllegalArgumentException("Parameter type must be Class or String: " + type);
            }
        }
        return parameterClasses;
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Object[] args, boolean staticOnly) {
        Class<?>[] argClasses = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argClasses[i] = args[i] != null ? args[i].getClass() : null;
        }

        Method bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        Class<?> cur = clazz;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (staticOnly && !Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] pTypes = m.getParameterTypes();
                if (pTypes.length != argClasses.length) continue;

                int score = computeMatchScore(pTypes, argClasses);
                if (score >= 0 && score < bestScore) {
                    bestScore = score;
                    bestMatch = m;
                    if (score == 0) {
                        bestMatch.setAccessible(true);
                        return bestMatch;
                    }
                }
            }
            cur = cur.getSuperclass();
        }

        if (bestMatch == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName + Arrays.toString(argClasses));
        }
        bestMatch.setAccessible(true);
        return bestMatch;
    }

    private static int computeMatchScore(Class<?>[] paramTypes, Class<?>[] argTypes) {
        int score = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> param = paramTypes[i];
            Class<?> arg = argTypes[i];
            if (arg == null) {
                if (param.isPrimitive()) return -1;
                score += 1;
            } else if (param == arg) {
                // exact match: +0
            } else if (param.isPrimitive()) {
                Class<?> unboxed = wrapperToPrimitive(arg);
                if (unboxed == null) return -1;
                if (unboxed == param) {
                    score += 1;
                } else if (isPrimitiveWidening(unboxed, param)) {
                    score += 3;
                } else {
                    return -1;
                }
            } else if (param.isAssignableFrom(arg)) {
                score += 2;
            } else {
                return -1;
            }
        }
        return score;
    }

    private static Class<?> wrapperToPrimitive(Class<?> wrapper) {
        if (wrapper == Boolean.class) return boolean.class;
        if (wrapper == Byte.class) return byte.class;
        if (wrapper == Short.class) return short.class;
        if (wrapper == Character.class) return char.class;
        if (wrapper == Integer.class) return int.class;
        if (wrapper == Long.class) return long.class;
        if (wrapper == Float.class) return float.class;
        if (wrapper == Double.class) return double.class;
        return null;
    }

    private static boolean isPrimitiveWidening(Class<?> from, Class<?> to) {
        if (from == byte.class) {
            return to == short.class || to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == short.class || from == char.class) {
            return to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == int.class) {
            return to == long.class || to == float.class || to == double.class;
        }
        if (from == long.class) {
            return to == float.class || to == double.class;
        }
        if (from == float.class) {
            return to == double.class;
        }
        return false;
    }
}
