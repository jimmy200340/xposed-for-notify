package com.amazfit.balance2fix;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed API (82+) entry point declared in assets/xposed_init.
 *
 * Isolated in its own class so modern libxposed API 101/102 frameworks never
 * load or touch {@code de.robv.android.xposed.*}.
 */
public class LegacyXposedEntry implements IXposedHookLoadPackage {

    private static final String MODULE_PACKAGE = "com.amazfit.balance2fix";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !NotifyWeatherHook.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        final String apiDesc = buildApiDescription();
        HookBridge.setBackend(new HookBridge.Backend() {
            @Override
            public void hookMethod(Method method, final HookBridge.MethodHook callback) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam xp) throws Throwable {
                        HookBridge.MethodHook.MethodHookParam p = new HookBridge.MethodHook.MethodHookParam();
                        p.method = xp.method;
                        p.thisObject = xp.thisObject;
                        p.args = xp.args;
                        callback.beforeHookedMethod(p);
                        if (p.args != xp.args) {
                            xp.args = p.args;
                        }
                        if (p.isReturnEarly()) {
                            if (p.hasThrowable()) {
                                xp.setThrowable(p.getThrowable());
                            } else {
                                xp.setResult(p.getResult());
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam xp) throws Throwable {
                        HookBridge.MethodHook.MethodHookParam p = new HookBridge.MethodHook.MethodHookParam();
                        p.method = xp.method;
                        p.thisObject = xp.thisObject;
                        p.args = xp.args;
                        if (xp.hasThrowable()) {
                            p.setOriginalThrowable(xp.getThrowable());
                        } else {
                            p.setOriginalResult(xp.getResult());
                        }
                        callback.afterHookedMethod(p);
                        if (p.isResultSet()) {
                            if (p.hasThrowable()) {
                                xp.setThrowable(p.getThrowable());
                            } else {
                                xp.setResult(p.getResult());
                            }
                        }
                    }
                });
            }

            @Override
            public void log(int priority, String tag, String msg, Throwable tr) {
                String prefix = (priority >= Log.ERROR) ? ("[" + tag + "] ERROR: ") : ("[" + tag + "] ");
                XposedBridge.log(prefix + msg);
                if (tr != null) {
                    XposedBridge.log(tr);
                }
            }

            @Override
            public SharedPreferences getModulePreferences(String prefsName) {
                try {
                    XSharedPreferences xsp = new XSharedPreferences(MODULE_PACKAGE, prefsName);
                    xsp.reload();
                    return xsp;
                } catch (Throwable ignored) {
                    return null;
                }
            }

            @Override
            public String getApiDescription() {
                return apiDesc;
            }
        });

        ApplicationInfo appInfo = null;
        try {
            appInfo = (ApplicationInfo) lpparam.getClass().getField("appInfo").get(lpparam);
        } catch (Throwable ignored) {
        }
        NotifyWeatherHook.initForPackage(lpparam.packageName, lpparam.classLoader, appInfo);
    }

    private static String buildApiDescription() {
        try {
            int ver = XposedBridge.getXposedVersion();
            if (ver > 0) {
                return "Xposed API " + ver;
            }
        } catch (Throwable ignored) {
        }
        return "Xposed API 82";
    }
}
