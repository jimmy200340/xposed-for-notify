package com.amazfit.balance2fix;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * Modern libxposed API (101/102) entry point declared in META-INF/xposed/java_init.list.
 *
 * Strictly uses {@code io.github.libxposed.api.*} with ZERO references to legacy
 * {@code de.robv.android.xposed.*} classes to satisfy API 102 runtime enforcement.
 */
public class ModernXposedEntry extends XposedModule {

    private static final String TAG = "Balance2WeatherFix";

    public ModernXposedEntry() {
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        final String apiDesc = buildApiDescription();
        HookBridge.setBackend(new HookBridge.Backend() {
            @Override
            public void hookMethod(final Method method, final HookBridge.MethodHook callback) {
                hook(method).intercept(chain -> {
                    List<Object> argList = chain.getArgs();
                    Object[] origArgs = argList.toArray();
                    Object[] workArgs = Arrays.copyOf(origArgs, origArgs.length);

                    HookBridge.MethodHook.MethodHookParam hookParam = new HookBridge.MethodHook.MethodHookParam();
                    hookParam.method = method;
                    hookParam.thisObject = chain.getThisObject();
                    hookParam.args = workArgs;

                    try {
                        callback.beforeHookedMethod(hookParam);
                    } catch (Throwable hookErr) {
                        log(Log.ERROR, TAG, "beforeHookedMethod error in " + method, hookErr);
                        hookParam.resetBeforeError();
                    }

                    if (!hookParam.isReturnEarly()) {
                        boolean argsChanged = (hookParam.args != workArgs) || !sameArgRefs(origArgs, hookParam.args);
                        try {
                            Object result = argsChanged
                                    ? chain.proceed(hookParam.args != null ? hookParam.args : new Object[0])
                                    : chain.proceed();
                            hookParam.setOriginalResult(result);
                        } catch (Throwable origErr) {
                            hookParam.setOriginalThrowable(origErr);
                        }
                    }

                    Object savedResult = hookParam.getResult();
                    Throwable savedThrowable = hookParam.getThrowable();
                    try {
                        callback.afterHookedMethod(hookParam);
                    } catch (Throwable hookErr) {
                        log(Log.ERROR, TAG, "afterHookedMethod error in " + method, hookErr);
                        if (savedThrowable != null) {
                            hookParam.setOriginalThrowable(savedThrowable);
                        } else {
                            hookParam.setOriginalResult(savedResult);
                        }
                    }

                    if (hookParam.hasThrowable()) {
                        throw hookParam.getThrowable();
                    }
                    return hookParam.getResult();
                });
            }

            @Override
            public void log(int priority, String tag, String msg, Throwable tr) {
                if (tr != null) {
                    ModernXposedEntry.this.log(priority, tag, msg, tr);
                } else {
                    ModernXposedEntry.this.log(priority, tag, msg);
                }
            }

            @Override
            public SharedPreferences getModulePreferences(String prefsName) {
                if ((getFrameworkProperties() & PROP_CAP_REMOTE) != 0L) {
                    try {
                        return getRemotePreferences(prefsName);
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }

            @Override
            public String getApiDescription() {
                return apiDesc;
            }
        });

        log(Log.INFO, TAG, "Loaded via " + apiDesc + " in process " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (param == null) return;
        NotifyWeatherHook.initForPackage(param.getPackageName(), param.getClassLoader());
    }

    private String buildApiDescription() {
        try {
            return getFrameworkName() + " API " + getApiVersion();
        } catch (Throwable ignored) {
            return "libxposed API " + LIB_API;
        }
    }

    private static boolean sameArgRefs(Object[] a, Object[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }
}
