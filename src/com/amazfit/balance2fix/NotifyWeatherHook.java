package com.amazfit.balance2fix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-API Xposed/LSPosed Module (libxposed API 101/102 + legacy Xposed API 82)
 * for Notify for Amazfit (com.mc.amazfit1) v24.1.1 / v24.0.3
 * Target Device: Amazfit Balance 2 (Zepp OS 6 / 3.5+)
 *
 * NOTE: This class intentionally has ZERO references to {@code de.robv.android.xposed.*}
 * or {@code io.github.libxposed.*}; all hooking, logging and preferences go through
 * {@link HookBridge}, wired by {@link ModernXposedEntry} or {@link LegacyXposedEntry}.
 */
public class NotifyWeatherHook {

    private static final String TAG = "Balance2WeatherFix";
    static final String TARGET_PACKAGE = "com.mc.amazfit1";
    private static final AtomicBoolean sHooksInitialized = new AtomicBoolean(false);
    private static final String ACTION_TRIGGER_SYNC = "com.amazfit.balance2fix.TRIGGER_SYNC";
    private static final String ACTION_DUMP_GATT = "com.amazfit.balance2fix.DUMP_GATT";
    private static final String ACTION_GATT_RESULT = "com.amazfit.balance2fix.GATT_RESULT";
    private static final String ACTION_PAIR_UNLOCK_CHAR = "com.amazfit.balance2fix.PAIR_UNLOCK_CHAR";
    private static final String ACTION_AGPS_INTERNAL_PROGRESS = "com.amazfit.balance2fix.AGPS_INTERNAL_PROGRESS";

    // Module 4-Switch Configuration States (Synced from MainActivity & persisted in SharedPreferences)
    private static volatile boolean sEnableWeatherFix = true;
    private static volatile boolean sEnableAgpsFix = true;
    private static volatile boolean sEnableBtUnlock = true;
    private static volatile boolean sEnableVoipCallFix = true;
    private static volatile int sAgpsFormatMode = 2; // 2 = EPO (0x8C/0x8D/0x8E, verified Balance 2 bitmask 0x000F1C00), 3 = 7d LTO (0x8B), 4 = 2d LTO
    private static volatile long sAgpsLastUpdateMs = 0L;
    private static volatile long sAgpsExpireMs = 0L;
    private static volatile boolean sConfigReceiverRegistered = false;
    private static volatile java.lang.ref.WeakReference<android.app.Activity> sCurrentBandSettingsActivity = null;
    private static volatile android.app.ProgressDialog sCustomAgpsProgressDialog = null;
    private static volatile int sCurrentAgpsTotalBytes = 0;
    private static volatile boolean sAgpsTransferActive = false;
    private static volatile long sAgpsTransferStartMs = 0L;
    private static volatile boolean sLoggedAgpsBleParams = false;
    private static volatile int sLastAgpsInstallStatus = -1;
    private static volatile long sLastAgpsFlashProgressMs = 0L;
    private static volatile long sWatchAgpsCapMask = 0L;
    private static volatile long sLastProgressBroadcastMs = 0L;
    private static volatile long sLastCompletedAgpsTransferMs = 0L;

    private static final String PREF_MOD_WEATHER = "balance2_mod_enable_weather";
    private static final String PREF_MOD_AGPS = "balance2_mod_enable_agps";
    private static final String PREF_MOD_BT_UNLOCK = "balance2_mod_enable_bt_unlock";
    private static final String PREF_MOD_VOIP_CALL = "balance2_mod_enable_voip_call";
    private static final String PREF_MOD_AGPS_FORMAT = "balance2_mod_agps_format";
    private static final String PREF_AGPS_MIGRATED_EPO_V1 = "balance2_agps_migrated_epo_v1";
    private static final String PREF_AGPS_LAST_UPDATE_MS = "balance2_agps_last_update_ms";
    private static final String PREF_AGPS_EXPIRE_MS = "balance2_agps_expire_ms";
    private static final String NOTIFY_AGPS_TIME_KEY = "22d907dc-6606-4aac-ba8d-d6219402818e";

    // Notify v24.1.1 internal broadcast action UUIDs
    private static final String ACTION_NOTIFY_DO_AGPS_UUID = "fc53784d-e0be-463a-924d-bd6c1d8a11a3";
    private static final String ACTION_NOTIFY_FW_PROGRESS_UUID = "2bc128ac-7a29-4a68-aad1-f9684058b77f";
    private static final String ACTION_NOTIFY_FW_DONE_UUID = "d288b5ef-4b71-4432-9c49-ec641bf0c788";
    private static final String ACTION_NOTIFY_FW_KO_UUID = "2c1cdc19-8e2f-46c2-9d53-05832e1c0240";
    private static final String ACTION_NOTIFY_WEATHER_UPDATED_UUID = "1a49176a-7315-4ddb-8e0a-20c4faa6ebb9";
    private static final String ACTION_NOTIFY_WEATHER_FAILED_UUID = "d6ff1901-69d0-471d-ab45-d710f44ed1ae";

    // Cached real city & coordinates from external provider (Breezy Weather / Gadgetbridge) or prefs
    private static volatile String sRealCityName = null;
    private static volatile Double sRealLatitude = null;
    private static volatile Double sRealLongitude = null;
    private static volatile String sActiveWatchLocationKey = "notify";

    // Cached application context
    private static volatile Context sAppContext = null;
    private static volatile boolean sDebugReceiverRegistered = false;

    // Cached reflected Endpoint 14 classes & method names
    private static volatile Class<?> sEndpoint14Class = null;
    private static volatile Class<?> sSettingsClass = null;
    private static volatile Class<?> sCityClass = null;
    private static volatile Class<?> sProtocolClass = null;
    private static volatile String sQueryProtocolMethodName = "u";
    private static volatile String sQuerySettingsMethodName = "x";
    private static volatile String sQueryCitiesMethodName = "w";
    private static volatile String sWriteSettingsMethodName = "A";
    private static volatile String sFallbackWriteMethodName = "B";
    private static volatile String sVerifyDefaultMethodName = "C";
    private static volatile String sCheckGnssUnavailMethodName = "D";
    private static volatile String sLatchUpdateMethodName = "E";
    private static volatile String sSendRawBleMethodName = "F";

    // Cached hourly forecast list (A in Lre8/Loe8) to prevent Notify from clearing it
    private static volatile ArrayList<?> sCachedHourlyForecastList = null;

    // Sync state & throttling
    private static volatile long sLastSyncTimestamp = 0L;
    private static volatile boolean sIsCustomSyncRunning = false;
    private static volatile long sLastAgpsSyncTimestamp = 0L;
    private static final long AGPS_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 hours
    private static volatile long sLastHttpProxyTimeMs = 0L;
    private static final java.util.concurrent.atomic.AtomicInteger sActiveHttpProxyCount = new java.util.concurrent.atomic.AtomicInteger(0);

    // ModelXHelper UUID keys
    private static final String KEY_GNSS_ACTIVE_FLAG = "6de38e8f-0c3c-445b-90fb-1a40d4b91572";
    private static final String KEY_GNSS_CITY_NAME = "55b48dd9-d16f-4b02-b971-cdc155fc6b7e";
    private static final String KEY_GNSS_CITY_NAME_LEGACY = "42b8fc03-b8bc-4f4c-ad1f-c86fdba3a5c6";
    private static final String KEY_GNSS_LOCATION_KEY = "ee7ca29f-0509-4d04-b50d-54c09456e830";

    private static void logInfo(String msg) {
        HookBridge.logInfo(TAG, msg);
    }

    private static void logError(String msg, Throwable t) {
        HookBridge.logError(TAG, msg, t);
    }

    private static Class<?> findClassSafe(ClassLoader cl, String... names) {
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            Class<?> c = HookBridge.findClassIfExists(name, cl);
            if (c != null) return c;
        }
        return null;
    }

    private static boolean hasMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null) return false;
        try {
            clazz.getDeclaredMethod(name, paramTypes);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasMethodWithReturn(Class<?> clazz, String name, Class<?> returnType, Class<?>... paramTypes) {
        if (clazz == null) return false;
        try {
            Method m = clazz.getDeclaredMethod(name, paramTypes);
            return returnType == null || returnType.isAssignableFrom(m.getReturnType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> resolveWeatherDataClass(ClassLoader cl) {
        Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
        if (userPrefsClass != null) {
            for (String mName : new String[]{"D7", "w7"}) {
                try {
                    Method m = userPrefsClass.getDeclaredMethod(mName);
                    Class<?> ret = m.getReturnType();
                    if (!ret.isPrimitive() && ret != String.class) {
                        return ret;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return findClassSafe(cl, "oe8", "re8");
    }

    private static Object callUserPrefsWeatherData(Object prefs, ClassLoader cl) {
        if (prefs == null) return null;
        Class<?> c = prefs.getClass();
        for (String mName : new String[]{"D7", "w7"}) {
            try {
                Method m = c.getDeclaredMethod(mName);
                if (!m.getReturnType().isPrimitive() && m.getReturnType() != String.class) {
                    m.setAccessible(true);
                    return m.invoke(prefs);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String callUserPrefsCity(Object prefs) {
        if (prefs == null) return null;
        Class<?> c = prefs.getClass();
        // v24.1.1: H7() returns weather city; v24.0.3: A7()
        boolean isV2411 = hasMethodWithReturn(c, "G7", double.class) && hasMethodWithReturn(c, "I7", double.class);
        String[] candidates = isV2411 ? new String[]{"H7", "A7"} : new String[]{"A7", "H7"};
        for (String mName : candidates) {
            if (hasMethodWithReturn(c, mName, String.class)) {
                try {
                    return (String) HookBridge.callMethod(prefs, mName);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static double[] callUserPrefsLatLon(Object prefs) {
        if (prefs == null) return null;
        Class<?> c = prefs.getClass();
        // v24.1.1: G7() / I7(); v24.0.3: o6() / p6()
        String[][] pairs = new String[][]{{"G7", "I7"}, {"o6", "p6"}};
        for (String[] pair : pairs) {
            if (hasMethodWithReturn(c, pair[0], double.class) && hasMethodWithReturn(c, pair[1], double.class)) {
                try {
                    double lat = (Double) HookBridge.callMethod(prefs, pair[0]);
                    double lon = (Double) HookBridge.callMethod(prefs, pair[1]);
                    if (Math.abs(lat) > 0.001 || Math.abs(lon) > 0.001) {
                        return new double[]{lat, lon};
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static void initForPackage(String packageName, ClassLoader cl) {
        if (!TARGET_PACKAGE.equals(packageName) || cl == null) {
            return;
        }
        if (!sHooksInitialized.compareAndSet(false, true)) {
            return;
        }
        new NotifyWeatherHook().installHooks(packageName, cl);
    }

    private void installHooks(String packageName, final ClassLoader cl) {
        logInfo("Initializing NotifyBalance2Fix (v4.2 [" + HookBridge.getApiDescription()
                + "] - Notify v24.1.1/v24.0.3 Weather + ZeppOS 6 AGPS + HyperOS Unlock + VoIP) for " + packageName);

        loadModuleConfigFromPrefs(null, cl);

        hookApplicationContext(cl);
        hookGenericWeatherReceiver(cl);
        hookModelXHelperCircularLoop(cl);
        hookKl5PlaceModel(cl);
        hookBleWeatherEndpoint14(cl);
        hookPlaceJsonBuilder(cl);
        hookBleHttpProxy(cl);
        hookWeatherActivityUI(cl);
        hookBroadcastFailureOverride(cl);
        hookAgpsFixPipeline(cl);
        hookBandSettingsUnlockUI(cl);
        hookVoipCallPipeline(cl);

        logInfo("All hooks installed for " + packageName);
    }

    // =========================================================================
    // 0. Application Context, 4-Switch Config Sync & Debug Trigger Receiver
    // =========================================================================
    private static boolean isWeatherFixEnabled(ClassLoader cl) {
        return sEnableWeatherFix;
    }

    private static boolean isAgpsFixEnabled(ClassLoader cl) {
        return sEnableAgpsFix;
    }

    private static boolean isBtUnlockEnabled(ClassLoader cl) {
        return sEnableBtUnlock;
    }

    private static boolean isVoipCallFixEnabled(ClassLoader cl) {
        return sEnableVoipCallFix;
    }

    private static void loadModuleConfigFromPrefs(Context ctx, ClassLoader cl) {
        try {
            // 1. Try reading from Notify's own SharedPreferences (persisted via ACTION_UPDATE_CONFIG)
            SharedPreferences sp = getNotifySharedPrefs(cl);
            if (sp != null) {
                if (sp.contains(PREF_MOD_WEATHER)) sEnableWeatherFix = sp.getBoolean(PREF_MOD_WEATHER, true);
                if (sp.contains(PREF_MOD_AGPS)) sEnableAgpsFix = sp.getBoolean(PREF_MOD_AGPS, true);
                if (sp.contains(PREF_MOD_BT_UNLOCK)) sEnableBtUnlock = sp.getBoolean(PREF_MOD_BT_UNLOCK, true);
                if (sp.contains(PREF_MOD_VOIP_CALL)) sEnableVoipCallFix = sp.getBoolean(PREF_MOD_VOIP_CALL, true);
                if (sp.contains(PREF_MOD_AGPS_FORMAT)) {
                    sAgpsFormatMode = sp.getInt(PREF_MOD_AGPS_FORMAT, 2);
                }
                if (!sp.getBoolean(PREF_AGPS_MIGRATED_EPO_V1, false)) {
                    if (sAgpsFormatMode == 3 || sAgpsFormatMode == 4) {
                        sAgpsFormatMode = 2;
                    }
                    sp.edit()
                            .putInt(PREF_MOD_AGPS_FORMAT, sAgpsFormatMode)
                            .putBoolean(PREF_AGPS_MIGRATED_EPO_V1, true)
                            .apply();
                }
                long savedUpd = sp.getLong(PREF_AGPS_LAST_UPDATE_MS, 0L);
                if (savedUpd > sAgpsLastUpdateMs) sAgpsLastUpdateMs = savedUpd;
                long savedExp = sp.getLong(PREF_AGPS_EXPIRE_MS, 0L);
                if (savedExp > sAgpsExpireMs) sAgpsExpireMs = savedExp;
            }
            // 2. Also check module preferences via HookBridge (libxposed RemotePreferences or legacy XSharedPreferences)
            try {
                SharedPreferences xsp = HookBridge.getModulePreferences(MainActivity.PREFS_NAME);
                if (xsp != null) {
                    if (xsp.contains(MainActivity.KEY_WEATHER_FIX)) {
                        sEnableWeatherFix = xsp.getBoolean(MainActivity.KEY_WEATHER_FIX, sEnableWeatherFix);
                    }
                    if (xsp.contains(MainActivity.KEY_AGPS_FIX)) {
                        sEnableAgpsFix = xsp.getBoolean(MainActivity.KEY_AGPS_FIX, sEnableAgpsFix);
                    }
                    if (xsp.contains(MainActivity.KEY_BT_UNLOCK)) {
                        sEnableBtUnlock = xsp.getBoolean(MainActivity.KEY_BT_UNLOCK, sEnableBtUnlock);
                    }
                    if (xsp.contains(MainActivity.KEY_VOIP_CALL_FIX)) {
                        sEnableVoipCallFix = xsp.getBoolean(MainActivity.KEY_VOIP_CALL_FIX, sEnableVoipCallFix);
                    }
                }
            } catch (Throwable ignored) {
            }
            // 3. Also check Notify's Lyx7/Lux7 AGPS timestamp ("22d907dc-6606-4aac-ba8d-d6219402818e")
            Context appCtx = ctx != null ? ctx.getApplicationContext() : sAppContext;
            if (appCtx != null && cl != null) {
                try {
                    Class<?> ux7Class = findClassSafe(cl, "yx7", "ux7");
                    if (ux7Class != null && hasMethod(ux7Class, "e", Context.class, String.class)) {
                        long notifyAgpsTime = (Long) HookBridge.callStaticMethod(ux7Class, "e", appCtx, NOTIFY_AGPS_TIME_KEY);
                        if (notifyAgpsTime > sAgpsLastUpdateMs) {
                            sAgpsLastUpdateMs = notifyAgpsTime;
                            if (sAgpsExpireMs <= 0L) {
                                sAgpsExpireMs = notifyAgpsTime + 7L * 24L * 3600L * 1000L;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveModuleConfigToNotifyPrefs(ClassLoader cl) {
        try {
            SharedPreferences sp = getNotifySharedPrefs(cl);
            if (sp != null) {
                SharedPreferences.Editor ed = sp.edit();
                ed.putBoolean(PREF_MOD_WEATHER, sEnableWeatherFix);
                ed.putBoolean(PREF_MOD_AGPS, sEnableAgpsFix);
                ed.putBoolean(PREF_MOD_BT_UNLOCK, sEnableBtUnlock);
                ed.putBoolean(PREF_MOD_VOIP_CALL, sEnableVoipCallFix);
                ed.putInt(PREF_MOD_AGPS_FORMAT, sAgpsFormatMode);
                if (sAgpsLastUpdateMs > 0L) ed.putLong(PREF_AGPS_LAST_UPDATE_MS, sAgpsLastUpdateMs);
                if (sAgpsExpireMs > 0L) ed.putLong(PREF_AGPS_EXPIRE_MS, sAgpsExpireMs);
                ed.apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void broadcastStatusToModuleApp(Context ctx, String progressMsg) {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return;
        try {
            Intent report = new Intent(MainActivity.ACTION_STATUS_REPORT);
            report.setPackage("com.amazfit.balance2fix");
            report.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            report.putExtra("hook_active", true);
            report.putExtra("xposed_api", HookBridge.getApiDescription());
            try {
                String verName = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
                if (verName != null) {
                    report.putExtra("notify_version", verName);
                }
            } catch (Throwable ignored) {
            }
            report.putExtra(MainActivity.KEY_AGPS_LAST_UPDATE, sAgpsLastUpdateMs);
            report.putExtra(MainActivity.KEY_AGPS_EXPIRE, sAgpsExpireMs);
            if (progressMsg != null) {
                report.putExtra("agps_progress_msg", progressMsg);
            }
            ctx.sendBroadcast(report);
        } catch (Throwable ignored) {
        }
    }

    private void hookApplicationContext(final ClassLoader cl) {
        try {
            Class<?> appClass = findClassSafe(cl, "com.mc.miband1.App", "android.app.Application");
            if (appClass != null) {
                HookBridge.findAndHookMethod(appClass, "onCreate", new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Context) {
                            Context ctx = ((Context) param.thisObject).getApplicationContext();
                            if (ctx != null) {
                                sAppContext = ctx;
                                loadModuleConfigFromPrefs(ctx, cl);
                                registerConfigReceiverIfNeeded(ctx, cl);
                                registerDebugReceiverIfNeeded(ctx, cl);
                                registerVoipAudioAndHfpMonitorIfNeeded(ctx, cl);
                                loadRealLocationFromPrefs(cl);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            logError("hookApplicationContext failed", t);
        }
    }

    private static synchronized void registerConfigReceiverIfNeeded(final Context ctx, final ClassLoader cl) {
        if (sConfigReceiverRegistered || ctx == null) return;
        try {
            BroadcastReceiver configReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (MainActivity.ACTION_UPDATE_CONFIG.equals(action)) {
                        sEnableWeatherFix = intent.getBooleanExtra(MainActivity.KEY_WEATHER_FIX, sEnableWeatherFix);
                        sEnableAgpsFix = intent.getBooleanExtra(MainActivity.KEY_AGPS_FIX, sEnableAgpsFix);
                        sEnableBtUnlock = intent.getBooleanExtra(MainActivity.KEY_BT_UNLOCK, sEnableBtUnlock);
                        sEnableVoipCallFix = intent.getBooleanExtra(MainActivity.KEY_VOIP_CALL_FIX, sEnableVoipCallFix);
                        if (intent.hasExtra(MainActivity.KEY_AGPS_FORMAT)) {
                            int reqMode = intent.getIntExtra(MainActivity.KEY_AGPS_FORMAT, sAgpsFormatMode);
                            if (sWatchAgpsCapMask != 0L
                                    && ((sWatchAgpsCapMask & (1L << 9)) == 0L)
                                    && ((sWatchAgpsCapMask & (0x7L << 10)) != 0L)
                                    && (reqMode == 3 || reqMode == 4)) {
                                reqMode = 2;
                            }
                            sAgpsFormatMode = reqMode;
                        }
                        saveModuleConfigToNotifyPrefs(cl);
                        logInfo("[ConfigSync] Updated switches -> weather=" + sEnableWeatherFix
                                + ", agps=" + sEnableAgpsFix + ", btUnlock=" + sEnableBtUnlock
                                + ", agpsFormat=" + sAgpsFormatMode);
                        refreshBandSettingsRowsOnUiThread(cl);
                        broadcastStatusToModuleApp(ctx, null);
                    } else if (MainActivity.ACTION_REQUEST_STATUS.equals(action)) {
                        loadModuleConfigFromPrefs(ctx, cl);
                        broadcastStatusToModuleApp(ctx, null);
                    } else if ("com.amazfit.balance2fix.OPEN_BAND_SETTINGS".equals(action)) {
                        try {
                            String proc = (Build.VERSION.SDK_INT >= 28) ? android.app.Application.getProcessName() : "";
                            if (proc == null || !proc.endsWith(":NotifyService")) {
                                Class<?> bsClass = findClassSafe(cl, "com.mc.miband1.ui.settings.BandSettingsActivity");
                                if (bsClass != null) {
                                    Intent bsIntent = new Intent(ctx, bsClass);
                                    bsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    ctx.startActivity(bsIntent);
                                }
                            }
                        } catch (Throwable t) {
                            logError("Failed to open BandSettingsActivity from broadcast", t);
                        }
                    } else if (ACTION_AGPS_INTERNAL_PROGRESS.equals(action)) {
                        long upd = intent.getLongExtra(PREF_AGPS_LAST_UPDATE_MS, 0L);
                        long exp = intent.getLongExtra(PREF_AGPS_EXPIRE_MS, 0L);
                        if (upd > 0L) sAgpsLastUpdateMs = upd;
                        if (exp > 0L) sAgpsExpireMs = exp;
                        final int pct = intent.getIntExtra("pct", -1);
                        final String msg = intent.getStringExtra("msg");
                        final boolean done = intent.getBooleanExtra("done", false);
                        final boolean success = intent.getBooleanExtra("success", true);
                        updateCustomAgpsProgressDialogOnUiThread(cl, pct, msg, done, success);
                        refreshBandSettingsRowsOnUiThread(cl);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(MainActivity.ACTION_UPDATE_CONFIG);
            filter.addAction(MainActivity.ACTION_REQUEST_STATUS);
            filter.addAction("com.amazfit.balance2fix.OPEN_BAND_SETTINGS");
            filter.addAction(ACTION_AGPS_INTERNAL_PROGRESS);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(configReceiver, filter);
            }
            sConfigReceiverRegistered = true;
            broadcastStatusToModuleApp(ctx, null);
        } catch (Throwable t) {
            logError("registerConfigReceiverIfNeeded failed", t);
        }
    }

    private static synchronized void registerDebugReceiverIfNeeded(final Context ctx, final ClassLoader cl) {
        if (sDebugReceiverRegistered || ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String procName = android.app.Application.getProcessName();
                if (procName != null && !procName.endsWith(":NotifyService")) {
                    sDebugReceiverRegistered = true;
                    logInfo("Skipping service broadcast receiver in non-service process: " + procName);
                    return;
                }
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent != null ? intent.getAction() : "";
                    logInfo("Received broadcast in :NotifyService: " + action);
                    if (ACTION_DUMP_GATT.equals(action)) {
                        new Thread(() -> dumpWatchGattAndMiBleStatus(ctx, cl, true), "Balance2Fix-DumpGatt").start();
                        return;
                    }
                    if (ACTION_PAIR_UNLOCK_CHAR.equals(action)) {
                        new Thread(() -> triggerWatchPairCharacteristic(ctx, cl), "Balance2Fix-PairChar").start();
                        return;
                    }
                    if ("com.amazfit.balance2fix.QUERY_AGPS_0A".equals(action)) {
                        queryWatchAgpsConfigOnEndpoint0A(cl);
                        return;
                    }
                    if (MainActivity.ACTION_ONE_CLICK_AGPS.equals(action)) {
                        final int mode = intent.getIntExtra(MainActivity.KEY_AGPS_FORMAT, sAgpsFormatMode);
                        sAgpsFormatMode = mode;
                        saveModuleConfigToNotifyPrefs(cl);
                        new Thread(() -> runOneClickAgpsUpdateInService(ctx, cl, mode), "Balance2Fix-OneClickAgps").start();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            dumpWatchGattAndMiBleStatus(ctx, cl, false);
                            loadRealLocationFromPrefs(cl);
                            if (isWeatherFixEnabled(cl)) {
                                // Trigger Weather Sync via Lif8.l(ctx, true) [v24.1.1] or Llf8/Lif8.k(ctx, true) [v24.0.3]
                                Class<?> syncClass = findClassSafe(cl, "if8", "lf8");
                                if (syncClass != null) {
                                    String triggerMethod = hasMethod(syncClass, "l", Context.class, boolean.class) ? "l"
                                            : (hasMethod(syncClass, "k", Context.class, boolean.class) ? "k" : null);
                                    if (triggerMethod != null) {
                                        for (Field f : syncClass.getDeclaredFields()) {
                                            if (f.getType() == syncClass) {
                                                f.setAccessible(true);
                                                Object singleton = f.get(null);
                                                if (singleton != null) {
                                                    logInfo("Triggering WeatherSync singleton (" + syncClass.getName() + ")." + triggerMethod + "(ctx, true)...");
                                                    HookBridge.callMethod(singleton, triggerMethod, ctx, true);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (isAgpsFixEnabled(cl)) {
                                sLastAgpsSyncTimestamp = 0L;
                                triggerAgpsUpdate(ctx, cl, true);
                            }
                        } catch (Throwable t) {
                            logError("ADB trigger sync failed", t);
                        }
                    }, "Balance2Fix-TriggerSync").start();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_TRIGGER_SYNC);
            filter.addAction(ACTION_DUMP_GATT);
            filter.addAction(ACTION_PAIR_UNLOCK_CHAR);
            filter.addAction("com.amazfit.balance2fix.QUERY_AGPS_0A");
            filter.addAction(MainActivity.ACTION_ONE_CLICK_AGPS);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            sDebugReceiverRegistered = true;
            logInfo("Registered service broadcast receiver for SYNC, GATT, UNLOCK & ONE_CLICK_AGPS");
        } catch (Throwable t) {
            logError("registerDebugReceiverIfNeeded failed", t);
        }
    }

    // =========================================================================
    // 1. Hook GenericWeatherReceiver & UserPreferences Weather Data (Lre8 / Loe8)
    // =========================================================================
    private void hookGenericWeatherReceiver(final ClassLoader cl) {
        try {
            Class<?> receiverClass = findClassSafe(cl,
                    "com.mc.miband1.GenericWeatherReceiver",
                    "com.mc.miband1.weather.GenericWeatherReceiver");
            if (receiverClass != null) {
                if (hasMethod(receiverClass, "h", JSONObject.class)) {
                    HookBridge.findAndHookMethod(receiverClass, "h", JSONObject.class, new HookBridge.MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isWeatherFixEnabled(cl)) return;
                            try {
                                JSONObject obj = (JSONObject) param.args[0];
                                if (obj != null) {
                                    String loc = obj.optString("location", "").trim();
                                    if (!loc.isEmpty()) {
                                        sRealCityName = loc;
                                    }
                                    if (obj.has("latitude") && !obj.isNull("latitude")) {
                                        double lat = obj.optDouble("latitude", 0.0);
                                        if (Math.abs(lat) > 0.001) sRealLatitude = lat;
                                    }
                                    if (obj.has("longitude") && !obj.isNull("longitude")) {
                                        double lon = obj.optDouble("longitude", 0.0);
                                        if (Math.abs(lon) > 0.001) sRealLongitude = lon;
                                    }
                                    logInfo("[GenericWeatherReceiver.h] Parsed external weather JSON -> location='"
                                            + sRealCityName + "', lat=" + sRealLatitude + ", lon=" + sRealLongitude);
                                    if (sAppContext != null && sRealCityName != null && !sRealCityName.isEmpty()) {
                                        saveRealLocationToPrefs(sAppContext, cl, sRealCityName, sRealLatitude, sRealLongitude);
                                    }
                                }
                            } catch (Throwable t) {
                                logError("Failed in GenericWeatherReceiver.h hook", t);
                            }
                        }
                    });
                }

                HookBridge.findAndHookMethod(receiverClass, "onReceive", Context.class, Intent.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context ctx = (Context) param.args[0];
                        Intent intent = (Intent) param.args[1];
                        if (ctx != null && sAppContext == null) {
                            sAppContext = ctx.getApplicationContext();
                            loadModuleConfigFromPrefs(sAppContext, cl);
                            registerConfigReceiverIfNeeded(sAppContext, cl);
                            registerDebugReceiverIfNeeded(sAppContext, cl);
                        }
                        if (!isWeatherFixEnabled(cl)) return;
                        if (intent == null) return;
                        String weatherJson = intent.getStringExtra("WeatherJson");
                        if (weatherJson != null && !weatherJson.isEmpty()) {
                            try {
                                JSONObject obj = new JSONObject(weatherJson);
                                if (obj.has("location") && !obj.isNull("location")) {
                                    String loc = obj.optString("location", "").trim();
                                    if (!loc.isEmpty()) {
                                        sRealCityName = loc;
                                        logInfo("[GenericWeatherReceiver] Captured external location name: " + sRealCityName);
                                    }
                                }
                                if (obj.has("latitude") && !obj.isNull("latitude")) {
                                    double lat = obj.getDouble("latitude");
                                    if (Math.abs(lat) > 0.001) sRealLatitude = lat;
                                }
                                if (obj.has("longitude") && !obj.isNull("longitude")) {
                                    double lon = obj.getDouble("longitude");
                                    if (Math.abs(lon) > 0.001) sRealLongitude = lon;
                                }
                            } catch (Throwable t) {
                                logError("Failed parsing WeatherJson in GenericWeatherReceiver", t);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        Context ctx = (Context) param.args[0];
                        if (ctx != null && sRealCityName != null && !sRealCityName.isEmpty()) {
                            saveRealLocationToPrefs(ctx, cl, sRealCityName, sRealLatitude, sRealLongitude);
                            if (isAgpsFixEnabled(cl)) {
                                triggerAgpsUpdate(ctx, cl, false);
                            }
                        }
                    }
                });
                logInfo("Hooked GenericWeatherReceiver (" + receiverClass.getName() + ").onReceive & .h");
            }

            // Hook UserPreferences.Vu/Du(Loe8/Lre8) and D7/w7() to dynamically extract new weather location & preserve hourly forecasts (field A)
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            Class<?> weatherDataClass = resolveWeatherDataClass(cl);
            if (userPrefsClass != null && weatherDataClass != null) {
                String setWeatherMethod = hasMethod(userPrefsClass, "Vu", weatherDataClass) ? "Vu"
                        : (hasMethod(userPrefsClass, "Du", weatherDataClass) ? "Du" : null);
                String getWeatherMethod = hasMethodWithReturn(userPrefsClass, "D7", weatherDataClass) ? "D7"
                        : (hasMethodWithReturn(userPrefsClass, "w7", weatherDataClass) ? "w7" : null);

                if (setWeatherMethod != null) {
                    HookBridge.findAndHookMethod(userPrefsClass, setWeatherMethod, weatherDataClass, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object weatherData = param.args[0];
                            if (weatherData == null) return;
                            try {
                                String inCity = (String) HookBridge.getObjectField(weatherData, "i");
                                double inLat = HookBridge.getDoubleField(weatherData, "q");
                                double inLon = HookBridge.getDoubleField(weatherData, "w");
                                if (inCity != null && !inCity.trim().isEmpty()
                                        && !"Hengke Village".equalsIgnoreCase(inCity.trim())) {
                                    sRealCityName = inCity.trim();
                                    if (Math.abs(inLat) > 0.001 || Math.abs(inLon) > 0.001) {
                                        sRealLatitude = inLat;
                                        sRealLongitude = inLon;
                                    }
                                    saveRealLocationToPrefs(sAppContext, cl, sRealCityName, sRealLatitude, sRealLongitude);
                                    logInfo("[UserPreferences." + setWeatherMethod + "] Extracted fresh weather location: city='" + sRealCityName
                                            + "', lat=" + sRealLatitude + ", lon=" + sRealLongitude);
                                } else {
                                    String realCity = getRealCityName(cl);
                                    if (realCity != null && !realCity.isEmpty()) {
                                        HookBridge.setObjectField(weatherData, "i", realCity);
                                    }
                                    if (sRealLatitude != null && sRealLongitude != null) {
                                        HookBridge.setDoubleField(weatherData, "q", sRealLatitude);
                                        HookBridge.setDoubleField(weatherData, "w", sRealLongitude);
                                    }
                                }
                                ArrayList<?> hourlyList = (ArrayList<?>) HookBridge.getObjectField(weatherData, "A");
                                if (hourlyList != null && !hourlyList.isEmpty()) {
                                    sCachedHourlyForecastList = new ArrayList<>(hourlyList);
                                    logInfo("[UserPreferences." + setWeatherMethod + "] Cached " + sCachedHourlyForecastList.size() + " hourly forecast entries");
                                } else if (sCachedHourlyForecastList != null && !sCachedHourlyForecastList.isEmpty()) {
                                    HookBridge.setObjectField(weatherData, "A", new ArrayList<>(sCachedHourlyForecastList));
                                    logInfo("[UserPreferences." + setWeatherMethod + "] Restored " + sCachedHourlyForecastList.size() + " hourly forecast entries into empty A");
                                }
                                sanitizeWeatherDataFeelsLike(weatherData);
                            } catch (Throwable t) {
                                logError("Error in UserPreferences." + setWeatherMethod + " hook", t);
                            }
                        }
                    });
                }

                if (getWeatherMethod != null) {
                    HookBridge.findAndHookMethod(userPrefsClass, getWeatherMethod, new HookBridge.MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object weatherData = param.getResult();
                            if (weatherData == null) return;
                            try {
                                String inCity = (String) HookBridge.getObjectField(weatherData, "i");
                                double inLat = HookBridge.getDoubleField(weatherData, "q");
                                double inLon = HookBridge.getDoubleField(weatherData, "w");
                                if (inCity != null && !inCity.trim().isEmpty()
                                        && !"Hengke Village".equalsIgnoreCase(inCity.trim())) {
                                    sRealCityName = inCity.trim();
                                    if (Math.abs(inLat) > 0.001 || Math.abs(inLon) > 0.001) {
                                        sRealLatitude = inLat;
                                        sRealLongitude = inLon;
                                    }
                                } else {
                                    String realCity = getRealCityName(cl);
                                    if (realCity != null && !realCity.isEmpty()) {
                                        HookBridge.setObjectField(weatherData, "i", realCity);
                                    }
                                    if (sRealLatitude != null && sRealLongitude != null) {
                                        HookBridge.setDoubleField(weatherData, "q", sRealLatitude);
                                        HookBridge.setDoubleField(weatherData, "w", sRealLongitude);
                                    }
                                }
                                ArrayList<?> hourlyList = (ArrayList<?>) HookBridge.getObjectField(weatherData, "A");
                                if ((hourlyList == null || hourlyList.isEmpty()) && sCachedHourlyForecastList != null && !sCachedHourlyForecastList.isEmpty()) {
                                    HookBridge.setObjectField(weatherData, "A", new ArrayList<>(sCachedHourlyForecastList));
                                }
                                sanitizeWeatherDataFeelsLike(weatherData);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                logInfo("Hooked UserPreferences." + setWeatherMethod + " & " + getWeatherMethod + " for weatherDataClass=" + weatherDataClass.getName());
            }

            // Hook WeatherSync singleton (Lif8.j/l in v24.1.1, Llf8/Lif8.i/k in v24.0.3)
            for (String syncClassName : new String[]{"if8", "lf8"}) {
                Class<?> syncCand = findClassSafe(cl, syncClassName);
                String syncTriggerMethod = hasMethod(syncCand, "l", Context.class, boolean.class) ? "l"
                        : (hasMethod(syncCand, "k", Context.class, boolean.class) ? "k" : null);
                if (syncCand != null && syncTriggerMethod != null) {
                    // Prevent Endpoint 14 .o() from triggering a re-entrant weather sync mid-BLE-HTTP-transfer!
                    String needSyncMethod = (userPrefsClass != null && hasMethodWithReturn(syncCand, "j", boolean.class, userPrefsClass)) ? "j"
                            : ((userPrefsClass != null && hasMethodWithReturn(syncCand, "i", boolean.class, userPrefsClass)) ? "i" : null);
                    if (needSyncMethod != null) {
                        HookBridge.findAndHookMethod(syncCand, needSyncMethod, userPrefsClass, new HookBridge.MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(false);
                            }
                        });
                    }
                    final String mSync = syncTriggerMethod;
                    HookBridge.findAndHookMethod(syncCand, syncTriggerMethod, Context.class, boolean.class, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            if (ctx != null && sAppContext == null) {
                                sAppContext = ctx.getApplicationContext();
                                registerDebugReceiverIfNeeded(sAppContext, cl);
                            }
                            loadRealLocationFromPrefs(cl);
                            logInfo("[WeatherSync." + mSync + "] Starting weather sync (force=" + param.args[1] + "), realCity=" + sRealCityName
                                    + ", lat=" + sRealLatitude + ", lon=" + sRealLongitude);
                            if (ctx != null) {
                                triggerAgpsUpdate(ctx, cl, false);
                            }
                        }
                    });
                    logInfo("Hooked WeatherSync class: " + syncCand.getName() + "." + syncTriggerMethod + "(Context, boolean) & ." + needSyncMethod + "(UserPreferences)");
                    break;
                }
            }
        } catch (Throwable t) {
            logError("hookGenericWeatherReceiver failed", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sanitizeWeatherDataFeelsLike(Object weatherData) {
        if (weatherData == null) return;
        try {
            Object currentCond = HookBridge.getObjectField(weatherData, "y"); // Lu51
            if (currentCond != null) {
                fixU51FeelsLike(currentCond);
            }
            ArrayList dailyList = (ArrayList) HookBridge.getObjectField(weatherData, "z");
            if (dailyList != null) {
                while (dailyList.size() > 7) {
                    dailyList.remove(dailyList.size() - 1);
                }
                for (Object item : dailyList) {
                    fixU51FeelsLike(item);
                }
            }
            ArrayList hourlyList = (ArrayList) HookBridge.getObjectField(weatherData, "A"); // ArrayList<Lzo2>
            if (hourlyList != null && !hourlyList.isEmpty()) {
                Calendar nowCal = Calendar.getInstance();
                nowCal.set(Calendar.MINUTE, 0);
                nowCal.set(Calendar.SECOND, 0);
                nowCal.set(Calendar.MILLISECOND, 0);
                long currentHourSec = nowCal.getTimeInMillis() / 1000L;

                ArrayList trimmedHourly = new ArrayList(24);
                for (Object hItem : hourlyList) {
                    if (hItem == null) continue;
                    int epochSec = HookBridge.getIntField(hItem, "f");
                    if (epochSec == 0 || epochSec >= currentHourSec) {
                        trimmedHourly.add(hItem);
                        if (trimmedHourly.size() >= 24) {
                            break;
                        }
                    }
                }
                if (trimmedHourly.isEmpty()) {
                    for (int i = Math.max(0, hourlyList.size() - 24); i < hourlyList.size(); i++) {
                        Object hItem = hourlyList.get(i);
                        if (hItem != null) trimmedHourly.add(hItem);
                    }
                }
                if (hourlyList.size() != trimmedHourly.size()) {
                    hourlyList.clear();
                    hourlyList.addAll(trimmedHourly);
                }

                float firstTemp = Float.NaN;
                boolean allIdentical = true;
                for (Object hItem : hourlyList) {
                    if (hItem == null) continue;
                    float temp = HookBridge.getFloatField(hItem, "i");
                    float feels = HookBridge.getFloatField(hItem, "E");
                    float hum = HookBridge.getFloatField(hItem, "w");
                    float wind = HookBridge.getFloatField(hItem, "x");
                    if (Float.isNaN(firstTemp)) {
                        firstTemp = temp;
                    } else if (Math.abs(temp - firstTemp) > 0.05f) {
                        allIdentical = false;
                    }
                    if (Math.abs(feels) < 0.01f && Math.abs(temp) > 3.0f) {
                        double computed = computeApparentTemperature(
                                temp,
                                hum > 0 ? hum : 65.0,
                                wind >= 0 ? wind : 5.0
                        );
                        HookBridge.setFloatField(hItem, "E", (float) computed);
                    }
                }
                if (allIdentical && hourlyList.size() > 1 && currentCond != null) {
                    float minT = HookBridge.getFloatField(currentCond, "i");
                    float maxT = HookBridge.getFloatField(currentCond, "q");
                    if (maxT > minT + 0.5f) {
                        double meanT = (minT + maxT) / 2.0;
                        double amp = (maxT - minT) / 2.0;
                        for (Object hItem : hourlyList) {
                            if (hItem == null) continue;
                            int epochSec = HookBridge.getIntField(hItem, "f");
                            Calendar cal = Calendar.getInstance();
                            if (epochSec > 0) cal.setTimeInMillis(epochSec * 1000L);
                            int hour = cal.get(Calendar.HOUR_OF_DAY);
                            double rad = ((hour - 14.0) / 24.0) * 2.0 * Math.PI;
                            float synthTemp = (float) (Math.round((meanT + amp * Math.cos(rad)) * 10.0) / 10.0);
                            float hum = HookBridge.getFloatField(hItem, "w");
                            float wind = HookBridge.getFloatField(hItem, "x");
                            HookBridge.setFloatField(hItem, "i", synthTemp);
                            HookBridge.setFloatField(hItem, "E", (float) computeApparentTemperature(
                                    synthTemp,
                                    hum > 0 ? hum : 65.0,
                                    wind >= 0 ? wind : 5.0
                            ));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fixU51FeelsLike(Object u51) {
        if (u51 == null) return;
        try {
            float curT = HookBridge.getFloatField(u51, "w");
            float minT = HookBridge.getFloatField(u51, "i");
            float maxT = HookBridge.getFloatField(u51, "q");
            double baseT = curT != 0 ? curT : ((minT != 0 && maxT != 0) ? (minT + maxT) / 2.0 : (maxT != 0 ? maxT : minT));
            float feels = HookBridge.getFloatField(u51, "L");
            float hum = HookBridge.getFloatField(u51, "B");
            float wind = HookBridge.getFloatField(u51, "C");
            if (Math.abs(feels) < 0.01f && Math.abs(baseT) > 3.0) {
                double computed = computeApparentTemperature(
                        baseT,
                        hum > 0 ? hum : 65.0,
                        wind >= 0 ? wind : 5.0
                );
                HookBridge.setFloatField(u51, "L", (float) computed);
            }
        } catch (Throwable ignored) {
        }
    }

    public static double computeApparentTemperature(double tempC, double humidityPct, double windKmh) {
        double rh = Math.max(0.0, Math.min(100.0, humidityPct));
        double wsMs = Math.max(0.0, windKmh) / 3.6;
        // Australian Apparent Temperature formula
        double e = (rh / 100.0) * 6.105 * Math.exp((17.27 * tempC) / (237.7 + tempC));
        double at = tempC + 0.33 * e - 0.70 * wsMs - 4.00;
        return Math.round(at * 10.0) / 10.0;
    }

    // =========================================================================
    // 2. Hook ModelXHelper Circular GNSS Cache Loop
    // =========================================================================
    private void hookModelXHelperCircularLoop(final ClassLoader cl) {
        try {
            Class<?> prefsStoreClass = findClassSafe(cl, "com.mc.miband1.modelXHelper.a", "uv5", "sv5", "rv5");
            if (prefsStoreClass == null) {
                Class<?> modelXHelperClass = findClassSafe(cl, "com.mc.miband1.model.modelXHelper");
                if (modelXHelperClass != null) {
                    try {
                        Field fA = modelXHelperClass.getDeclaredField("a");
                        prefsStoreClass = fA.getType();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (prefsStoreClass == null) {
                logError("Could not locate modelXHelper.a preference store class", null);
                return;
            }

            // CRITICAL: Force KEY_GNSS_ACTIVE_FLAG (6de38e8f-0c3c-445b-90fb-1a40d4b91572) to FALSE!
            // When this flag is true, Lnl5.a() overwrites our real coordinates & locationKey with the cached ",auto" GNSS key!
            if (hasMethod(prefsStoreClass, "b", String.class)) {
                HookBridge.findAndHookMethod(prefsStoreClass, "b", String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String key = (String) param.args[0];
                        if (KEY_GNSS_ACTIVE_FLAG.equals(key)) {
                            param.setResult(false);
                        }
                    }
                });
            }

            if (hasMethod(prefsStoreClass, "m", String.class, boolean.class)) {
                HookBridge.findAndHookMethod(prefsStoreClass, "m", String.class, boolean.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String key = (String) param.args[0];
                        if (KEY_GNSS_ACTIVE_FLAG.equals(key)) {
                            param.args[1] = false;
                        }
                    }
                });
            }

            // c() reads KEY_GNSS_LOCATION_KEY ("ee7ca29f-0509-4d04-b50d-54c09456e830")
            if (hasMethod(prefsStoreClass, "c")) {
                HookBridge.findAndHookMethod(prefsStoreClass, "c", new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        param.setResult("");
                    }
                });
            }

            // g(String, String) reads string key from modelXHelper.a
            if (hasMethod(prefsStoreClass, "g", String.class, String.class)) {
                HookBridge.findAndHookMethod(prefsStoreClass, "g", String.class, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String key = (String) param.args[0];
                        if (KEY_GNSS_CITY_NAME.equals(key) || KEY_GNSS_CITY_NAME_LEGACY.equals(key)) {
                            String realCity = getRealCityName(cl);
                            if (realCity != null && !realCity.isEmpty()) {
                                param.setResult(realCity);
                            }
                        } else if (KEY_GNSS_LOCATION_KEY.equals(key)) {
                            param.setResult("");
                        }
                    }
                });
            }

            // l(String, String) writes string key to modelXHelper.a
            if (hasMethod(prefsStoreClass, "l", String.class, String.class)) {
                HookBridge.findAndHookMethod(prefsStoreClass, "l", String.class, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String key = (String) param.args[0];
                        if (KEY_GNSS_CITY_NAME.equals(key) || KEY_GNSS_CITY_NAME_LEGACY.equals(key)) {
                            String realCity = getRealCityName(cl);
                            if (realCity != null && !realCity.isEmpty()) {
                                param.args[1] = realCity;
                            }
                        } else if (KEY_GNSS_LOCATION_KEY.equals(key)) {
                            param.args[1] = "";
                        }
                    }
                });
            }

            logInfo("Hooked modelXHelper.a (" + prefsStoreClass.getName() + ") to block stale GNSS cache overwrite");
        } catch (Throwable t) {
            logError("hookModelXHelperCircularLoop failed", t);
        }
    }

    // =========================================================================
    // 3. Hook Place Model Builder (Lll5 in v24.1.1, Lnl5 / Lll5 / Lkl5 in older APKs)
    // =========================================================================
    private void hookKl5PlaceModel(final ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            Class<?> placeClass = findClassSafe(cl, "ll5", "nl5", "kl5");
            if (placeClass == null || userPrefsClass == null) {
                logError("Could not find Lll5/Lnl5 place model class", null);
                return;
            }
            HookBridge.MethodHook placeHook = new HookBridge.MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isWeatherFixEnabled(cl)) return;
                    Object placeObj = param.getResult();
                    if (placeObj == null) return;
                    try {
                        String realCity = getRealCityName(cl);
                        double[] coords = getRealCoordinates(cl, param.args[0]);
                        String activeKey = (sActiveWatchLocationKey != null && !sActiveWatchLocationKey.isEmpty())
                                ? sActiveWatchLocationKey : "notify";

                        // Fields of Lll5 / Lnl5:
                        // a = locationKey, b = longitude, c = latitude, d = affiliation, e = name
                        HookBridge.setObjectField(placeObj, "a", activeKey);
                        String latStr = coords != null ? String.format(Locale.US, "%.6f", coords[0]) : "25.038689";
                        String lonStr = coords != null ? String.format(Locale.US, "%.6f", coords[1]) : "121.566210";
                        if (activeKey.endsWith(",auto")) {
                            String[] parts = activeKey.split(",");
                            if (parts.length >= 2) {
                                latStr = parts[0];
                                lonStr = parts[1];
                            }
                        }
                        HookBridge.setObjectField(placeObj, "c", latStr);
                        HookBridge.setObjectField(placeObj, "b", lonStr);
                        if (realCity != null && !realCity.isEmpty()) {
                            HookBridge.setObjectField(placeObj, "d", realCity);
                            HookBridge.setObjectField(placeObj, "e", realCity);
                        }
                        logInfo("[" + placeClass.getSimpleName() + ".a] Forced place model: key="
                                + HookBridge.getObjectField(placeObj, "a")
                                + ", name=" + HookBridge.getObjectField(placeObj, "e")
                                + ", lat=" + HookBridge.getObjectField(placeObj, "c")
                                + ", lon=" + HookBridge.getObjectField(placeObj, "b"));
                    } catch (Throwable t) {
                        logError("Error in " + placeClass.getSimpleName() + ".a hook", t);
                    }
                }
            };
            if (hasMethod(placeClass, "a", userPrefsClass, String.class, String.class)) {
                HookBridge.findAndHookMethod(placeClass, "a", userPrefsClass, String.class, String.class, placeHook);
                logInfo("Hooked Place Model builder: " + placeClass.getName() + ".a(UserPreferences, String, String)");
            } else if (hasMethod(placeClass, "a", userPrefsClass)) {
                HookBridge.findAndHookMethod(placeClass, "a", userPrefsClass, placeHook);
                logInfo("Hooked Place Model builder: " + placeClass.getName() + ".a(UserPreferences)");
            }
        } catch (Throwable t) {
            logError("hookKl5PlaceModel failed", t);
        }
    }

    // =========================================================================
    // 4. Hook BLE Weather Endpoint 14 (Lkf8 in v24.1.1, Lnf8 in v24.0.3)
    // =========================================================================
    private void hookBleWeatherEndpoint14(final ClassLoader cl) {
        try {
            Class<?> ep14Class = null;
            for (String candName : new String[]{"kf8", "nf8", "ud8", "tc8"}) {
                Class<?> c = findClassSafe(cl, candName);
                if (c != null && hasMethod(c, "w") && hasMethod(c, "v") && hasMethod(c, "j") && hasMethod(c, "l")) {
                    ep14Class = c;
                    break;
                }
            }
            if (ep14Class == null) {
                logError("CRITICAL: Could not find Endpoint 14 Weather Handler class (kf8/nf8)!", null);
                return;
            }
            sEndpoint14Class = ep14Class;

            // Discover companion classes & query method names dynamically by return type:
            // v24.1.1 Lkf8: u()->Llf8 (protocol), x()->Lmf8 (settings), w()->ArrayList (raw 0x05 cities)
            // v24.0.3 Lnf8: t()->Lof8 (protocol), w()->Lpf8 (settings), v()->ArrayList (raw 0x05 cities)
            Method mW = ep14Class.getDeclaredMethod("w");
            if (ArrayList.class.isAssignableFrom(mW.getReturnType()) && hasMethod(ep14Class, "x") && hasMethod(ep14Class, "u")) {
                sQueryProtocolMethodName = "u";
                sQuerySettingsMethodName = "x";
                sQueryCitiesMethodName = "w";
                sProtocolClass = ep14Class.getDeclaredMethod("u").getReturnType();
                sSettingsClass = ep14Class.getDeclaredMethod("x").getReturnType();
            } else {
                sQueryProtocolMethodName = "t";
                sQuerySettingsMethodName = "w";
                sQueryCitiesMethodName = "v";
                sProtocolClass = ep14Class.getDeclaredMethod("t").getReturnType();
                sSettingsClass = mW.getReturnType();
            }

            for (Field f : sSettingsClass.getDeclaredFields()) {
                if (f.getType() != Boolean.class && f.getType() != boolean.class) {
                    sCityClass = f.getType(); // Luo0
                    break;
                }
            }

            // Determine shifted method names between Lnf8 (v24.0.3) and Lkf8 (v24.1.1 / older)
            if (hasMethod(ep14Class, "B", sSettingsClass) && hasMethod(ep14Class, "C", sSettingsClass)) {
                // Lnf8 layout: B(Lpf8)=writeSettings, C(Lpf8)=fallbackWrite, D(Luo0)=verifyDefault, E(Lpf8)=checkGnssUnavail, F(CountDownLatch)=updateLatch, G(byte[])=sendRawBle
                sWriteSettingsMethodName = "B";
                sFallbackWriteMethodName = "C";
                sVerifyDefaultMethodName = "D";
                sCheckGnssUnavailMethodName = "E";
                sLatchUpdateMethodName = "F";
                sSendRawBleMethodName = "G";
            } else {
                // Lkf8 layout: A(Lmf8)=writeSettings, B(Lmf8)=fallbackWrite, C(Luo0)=verifyDefault, D(Lmf8)=checkGnssUnavail, E(CountDownLatch)=updateLatch, F(byte[])=sendRawBle
                sWriteSettingsMethodName = "A";
                sFallbackWriteMethodName = "B";
                sVerifyDefaultMethodName = "C";
                sCheckGnssUnavailMethodName = "D";
                sLatchUpdateMethodName = "E";
                sSendRawBleMethodName = "F";
            }

            logInfo("Resolved Endpoint 14 class=" + ep14Class.getName()
                    + ", settingsClass=" + sSettingsClass.getName()
                    + ", protocolClass=" + sProtocolClass.getName()
                    + ", cityClass=" + (sCityClass != null ? sCityClass.getName() : "null")
                    + ", query(proto=" + sQueryProtocolMethodName + ",settings=" + sQuerySettingsMethodName + ",cities=" + sQueryCitiesMethodName + ")"
                    + ", writeSettings=" + sWriteSettingsMethodName
                    + ", sendRawBle=" + sSendRawBleMethodName);

            // 4.1 Hook m(protocol, settings) -> ALWAYS return null so Notify NEVER enters the broken GNSS rename branch!
            if (hasMethod(ep14Class, "m", sProtocolClass, sSettingsClass)) {
                HookBridge.findAndHookMethod(ep14Class, "m", sProtocolClass, sSettingsClass, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        logInfo("[" + sEndpoint14Class.getSimpleName() + ".m] Blocking GNSS cache branch -> returning null");
                        param.setResult(null);
                    }
                });
            }

            // 4.2 Hook static D(Lmf8) / E(Lpf8) -> ALWAYS return false so Notify never aborts with "previously located GNSS city is temporarily unavailable"
            if (hasMethod(ep14Class, sCheckGnssUnavailMethodName, sSettingsClass)) {
                HookBridge.findAndHookMethod(ep14Class, sCheckGnssUnavailMethodName, sSettingsClass, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        param.setResult(false);
                    }
                });
            }

            // 4.3 Hook static n(Context, UserPreferences) -> ALWAYS return our real city name (truncated to 16 chars if needed)
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            if (userPrefsClass != null && hasMethod(ep14Class, "n", Context.class, userPrefsClass)) {
                HookBridge.findAndHookMethod(ep14Class, "n", Context.class, userPrefsClass, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String realCity = getRealCityName(cl);
                        if (realCity != null && !realCity.isEmpty()) {
                            String truncated = realCity.length() > 16 ? realCity.substring(0, 16) : realCity;
                            param.setResult(truncated);
                            logInfo("[" + sEndpoint14Class.getSimpleName() + ".n] Resolved city name -> " + truncated);
                        }
                    }
                });
            }

            // 4.4 Hook x()/w() (queryWeatherSettings 0x0B) -> LOG the raw watch state and capture active GNSS locationKey (do NOT mutate b/c here!)
            HookBridge.findAndHookMethod(ep14Class, sQuerySettingsMethodName, new HookBridge.MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res != null) {
                        try {
                            Object bObj = HookBridge.getObjectField(res, "b");
                            String bId = getCityId(bObj);
                            if (bId != null && !bId.isEmpty()) {
                                sActiveWatchLocationKey = bId;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    logInfo("[" + sEndpoint14Class.getSimpleName() + "." + sQuerySettingsMethodName + "] Raw 0x0B Settings from Watch: " + formatSettingsObj(res));
                }
            });

            // 4.5 Hook writeSettings (B in Lnf8, A in Lkf8) -> Log exact 0x09 payload and result (NO destructive mutation!)
            HookBridge.findAndHookMethod(ep14Class, sWriteSettingsMethodName, sSettingsClass, new HookBridge.MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    logInfo("[" + sEndpoint14Class.getSimpleName() + "." + sWriteSettingsMethodName + "] 0x09 Write "
                            + formatSettingsObj(param.args[0]) + " -> result=" + param.getResult());
                }
            });

            // 4.6 Hook s(Luo0, boolean) -> Log exact 0x07 add/delete city call and result
            if (sCityClass != null && hasMethod(ep14Class, "s", sCityClass, boolean.class)) {
                HookBridge.findAndHookMethod(ep14Class, "s", sCityClass, boolean.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        logInfo("[" + sEndpoint14Class.getSimpleName() + ".s] 0x07 "
                                + (((Boolean) param.args[1]) ? "ADD" : "DEL") + " city="
                                + formatCityObj(param.args[0]) + " -> result=" + param.getResult());
                    }
                });
            }

            // 4.7 Hook j(), l(), and F(CountDownLatch) / E(CountDownLatch) -> Replace with our deterministic Single-City Sync!
            if (hasMethod(ep14Class, "j")) {
                HookBridge.findAndHookMethod(ep14Class, "j", new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        logInfo("[" + sEndpoint14Class.getSimpleName() + ".j] Intercepted deleteAdditionalCities -> running performSingleCityWeatherSync");
                        boolean ok = performSingleCityWeatherSync(param.thisObject, cl);
                        param.setResult(ok ? 1 : 0);
                    }
                });
            }

            HookBridge.findAndHookMethod(ep14Class, "l", new HookBridge.MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isWeatherFixEnabled(cl)) return;
                    logInfo("[" + sEndpoint14Class.getSimpleName() + ".l] Intercepted city sync -> running performSingleCityWeatherSync");
                    boolean ok = performSingleCityWeatherSync(param.thisObject, cl);
                    param.setResult(ok);
                }
            });

            if (hasMethod(ep14Class, sLatchUpdateMethodName, CountDownLatch.class)) {
                HookBridge.findAndHookMethod(ep14Class, sLatchUpdateMethodName, CountDownLatch.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        CountDownLatch latch = (CountDownLatch) param.args[0];
                        try {
                            HookBridge.setObjectField(param.thisObject, "c", latch);
                        } catch (Throwable ignored) {
                        }
                        logInfo("[" + sEndpoint14Class.getSimpleName() + "." + sLatchUpdateMethodName + "] Intercepted latch city sync -> running performSingleCityWeatherSync");
                        try {
                            boolean ok = performSingleCityWeatherSync(param.thisObject, cl);
                            param.setResult(ok);
                        } finally {
                            if (latch != null) {
                                latch.countDown();
                            }
                        }
                    }
                });
            }

            // 4.8 Hook h() (background thread that sends empty settings after delay) -> block if our sync recently ran
            if (hasMethod(ep14Class, "h")) {
                HookBridge.findAndHookMethod(ep14Class, "h", new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        logInfo("[" + sEndpoint14Class.getSimpleName() + ".h] Blocked clear-settings background thread");
                        param.setResult(null);
                    }
                });
            }

            // 4.9 Hook o(BLEManager, String, HashMap) -> Inject "place" JSON into /weather/v6/ response & fix CBOR /weather/v5/
            Class<?> bleManagerClass = findClassSafe(cl, "com.mc.miband1.bluetooth.BLEManager", "com.mc.miband1.ble.BLEManager");
            if (bleManagerClass != null && hasMethod(ep14Class, "o", bleManagerClass, String.class, HashMap.class)) {
                HookBridge.findAndHookMethod(ep14Class, "o", bleManagerClass, String.class, HashMap.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        try {
                            String path = (String) param.args[1];
                            @SuppressWarnings("unchecked")
                            HashMap<String, String> queryMap = (HashMap<String, String>) param.args[2];
                            if (queryMap != null && queryMap.containsKey("locationKey")) {
                                String reqKey = queryMap.get("locationKey");
                                if (reqKey != null && !reqKey.isEmpty()) {
                                    sActiveWatchLocationKey = reqKey;
                                }
                            } else if (path != null && path.startsWith("/weather/v")) {
                                String[] parts = path.split("/");
                                if (parts.length >= 6 && parts[4] != null && !parts[4].isEmpty()
                                        && parts[5] != null && !parts[5].isEmpty()) {
                                    sActiveWatchLocationKey = parts[4] + "," + parts[5] + ",auto";
                                }
                            }
                            if (path != null && path.startsWith("/weather/v6/") && queryMap != null) {
                                String ds = queryMap.get("datasets");
                                if (ds != null && !ds.contains("place")) {
                                    String expanded = ds + ",place";
                                    queryMap.put("datasets", expanded);
                                    logInfo("[" + sEndpoint14Class.getSimpleName() + ".o] Appended place to v6 datasets: '" + ds + "' -> '" + expanded + "'");
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String path = (String) param.args[1];
                        @SuppressWarnings("unchecked")
                        HashMap<String, String> queryMap = (HashMap<String, String>) param.args[2];
                        Object respWrapper = param.getResult(); // Ls86 / Lq86
                        if (respWrapper == null) return;
                        try {
                            Object bleMgr = param.args[0];
                            Context ctx = bleMgr != null ? (Context) HookBridge.getObjectField(bleMgr, "B") : sAppContext;
                            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
                            Object prefs = (userPrefsClass != null && ctx != null)
                                    ? HookBridge.callStaticMethod(userPrefsClass, "getInstance", ctx)
                                    : null;
                            if (queryMap != null && queryMap.containsKey("locationKey")) {
                                String reqKey = queryMap.get("locationKey");
                                if (reqKey != null && !reqKey.isEmpty()) {
                                    sActiveWatchLocationKey = reqKey;
                                }
                            }
                            Class<?> v6WrapperClass = findClassSafe(cl, "v86", "a96", "y86", "y76");
                            if (v6WrapperClass != null && v6WrapperClass.isInstance(respWrapper)) {
                                Object placeJson = buildPlaceJson(prefs, cl);
                                if (placeJson != null) {
                                    HookBridge.callMethod(respWrapper, "d", "place", placeJson);
                                    logInfo("[" + sEndpoint14Class.getSimpleName() + ".o] Injected place into v6 response (" + path + "): " + placeJson);
                                }
                            }
                        } catch (Throwable t) {
                            logError("Error in Endpoint 14 .o() afterHookedMethod", t);
                        }
                    }
                });
                logInfo("Hooked Endpoint 14 HTTP Weather Builder: " + ep14Class.getName() + ".o(" + bleManagerClass.getName() + ", String, HashMap)");
            } else {
                logError("Could not hook Endpoint 14 .o() - bleManagerClass=" + bleManagerClass, null);
            }

        } catch (Throwable t) {
            logError("hookBleWeatherEndpoint14 failed", t);
        }
    }

    // =========================================================================
    // 5. Core Single-City + Location Overwrite Sequence on Endpoint 14
    // =========================================================================
    private static synchronized boolean performSingleCityWeatherSync(Object ep14Instance, ClassLoader cl) {
        if (ep14Instance == null) return false;
        long now = System.currentTimeMillis();
        if (now - sLastSyncTimestamp < 4000L) {
            logInfo("[SingleCitySync] Skipping duplicate sync within 4s window");
            return true;
        }
        if (isHttpProxyBusyOrRecent(8000L)) {
            logInfo("[SingleCitySync] Skipping Endpoint 14 BLE commands because Watch HTTP Proxy (Lev5) is active or recently transferred");
            return true;
        }
        sIsCustomSyncRunning = true;
        try {
            loadRealLocationFromPrefs(cl);

            // In Lnf8/Lkf8 (extends Lbx), field 'b' is com.mc.miband1.bluetooth.BLEManager, and bleMgr.B is Context
            Object bleMgr = null;
            Context ctx = sAppContext;
            try {
                bleMgr = HookBridge.getObjectField(ep14Instance, "b");
                if (bleMgr != null) {
                    Context c = (Context) HookBridge.getObjectField(bleMgr, "B");
                    if (c != null) ctx = c;
                }
            } catch (Throwable ignored) {
            }

            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            Object prefs = (userPrefsClass != null && ctx != null)
                    ? HookBridge.callStaticMethod(userPrefsClass, "getInstance", ctx)
                    : null;

            String rawCity = getRealCityName(cl);
            if ((rawCity == null || rawCity.isEmpty()) && prefs != null) {
                rawCity = callUserPrefsCity(prefs);
            }
            if ((rawCity == null || rawCity.isEmpty()) && prefs != null) {
                try {
                    Object w7 = callUserPrefsWeatherData(prefs, cl);
                    if (w7 != null) {
                        rawCity = (String) HookBridge.getObjectField(w7, "i");
                    }
                } catch (Throwable ignored) {
                }
            }
            if (rawCity == null || rawCity.trim().isEmpty()) {
                rawCity = "Taipei";
            }
            String cityName = rawCity.trim();
            if (cityName.length() > 16) {
                cityName = cityName.substring(0, 16);
            }
            sRealCityName = cityName;

            logInfo("=== [SingleCitySync] START === targetCity='" + cityName + "', realLat=" + sRealLatitude + ", realLon=" + sRealLongitude);

            // 1. Query Protocol (0x01), Current Settings (0x0B), and Saved Cities (0x05)
            Object proto = HookBridge.callMethod(ep14Instance, sQueryProtocolMethodName);
            Object initSettings = HookBridge.callMethod(ep14Instance, sQuerySettingsMethodName);
            ArrayList<?> initCities = (ArrayList<?>) HookBridge.callMethod(ep14Instance, sQueryCitiesMethodName);

            logInfo("[SingleCitySync] INITIAL 0x01 Protocol: " + formatProtocolObj(proto));
            logInfo("[SingleCitySync] INITIAL 0x0B Settings: " + formatSettingsObj(initSettings));
            logInfo("[SingleCitySync] INITIAL 0x05 Cities (" + (initCities != null ? initCities.size() : 0) + "): " + formatCityList(initCities));

            Object initB = initSettings != null ? HookBridge.getObjectField(initSettings, "b") : null;
            Object initC = initSettings != null ? HookBridge.getObjectField(initSettings, "c") : null;
            String initBId = getCityId(initB);
            String initBName = getCityName(initB);
            String initCId = getCityId(initC);

            // 2. Purge any manual/saved cities in 0x05 ONLY if 0x05 is non-empty (keep BLE traffic minimal!)
            if (initCities != null && !initCities.isEmpty()) {
                for (Object cObj : initCities) {
                    if (cObj != null) {
                        HookBridge.callMethod(ep14Instance, "s", cObj, false);
                    }
                }
            }

            // 2.5 Send VirtualSensorModule (Endpoint 49 / 0x31, Lo2d0 in Zepp.apk) GNSS Location Packet!
            // On Zepp OS 3.5+ watches where supportLocatingViaGNSSCoordinate == true (Balance 2),
            // Zepp App skips 0x09 city sync and instead pushes phone GPS coordinates scaled by 3,000,000
            // to Endpoint 49 (VirtualSensorModule, cmd 0x03). This updates watch slot b (currentLocatedCity)
            // and triggers the watch to fetch /weather/v6/<lang>/<lat>/<lon>?datasets=...,place,...
            double[] coords = getRealCoordinates(cl, prefs);
            int latInt = (int) Math.round(coords[0] * 3000000.0);
            int lonInt = (int) Math.round(coords[1] * 3000000.0);
            String targetAutoId = String.format(Locale.US, "%.6f,%.6f,auto", latInt / 3000000.0, lonInt / 3000000.0);
            boolean needForcePlaceRefresh = (initBName == null || !cityName.equals(initBName)
                    || (initBId != null && !targetAutoId.equals(initBId)));
            sActiveWatchLocationKey = targetAutoId;

            Class<?> fc2Class = findClassSafe(cl, "ac2", "fc2", "cc2");
            if (bleMgr != null && fc2Class != null) {
                try {
                    if (needForcePlaceRefresh) {
                        // Watch's cached GNSS city name (e.g. '興隆里') differs from target cityName ('信義商圈, 西村里').
                        // First send a +0.05 deg (~5.5km) offset coordinate to trip the watch's >1km GNSS distance filter,
                        // then send the exact real coordinate so the watch fetches datasets=place for the exact coordinate!
                        long nowMs = System.currentTimeMillis();
                        java.nio.ByteBuffer farBuf = java.nio.ByteBuffer.allocate(23).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        farBuf.put((byte) 0x03);
                        farBuf.put((byte) 0x01);
                        farBuf.put((byte) 0x01);
                        farBuf.putInt(0);
                        farBuf.putLong(nowMs - 1000L);
                        farBuf.putInt(latInt + 150000);
                        farBuf.putInt(lonInt + 150000);
                        HookBridge.callStaticMethod(fc2Class, "g", bleMgr, 49, null, farBuf.array(), false);
                        Thread.sleep(150L);
                    }

                    java.nio.ByteBuffer vsBuf = java.nio.ByteBuffer.allocate(23).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    vsBuf.put((byte) 0x03); // Ln2d0.b command = 0x03 (push sensor data)
                    vsBuf.put((byte) 0x01); // list count = 1
                    vsBuf.put((byte) 0x01); // Luv30 sensor type = 1 (GNSS location)
                    vsBuf.putInt(0);        // flags = 0 (no altitude)
                    vsBuf.putLong(System.currentTimeMillis()); // timestampMs (8B LE)
                    vsBuf.putInt(latInt);   // latitude * 3,000,000 (4B LE)
                    vsBuf.putInt(lonInt);   // longitude * 3,000,000 (4B LE)
                    byte[] vsPayload = vsBuf.array();

                    Object vsRes = HookBridge.callStaticMethod(fc2Class, "g", bleMgr, 49, null, vsPayload, false);
                    logInfo("[SingleCitySync] Sent Endpoint 49 (VirtualSensor) GNSS location packet (forceRefresh="
                            + needForcePlaceRefresh + ") -> latInt=" + latInt
                            + ", lonInt=" + lonInt + ", targetAutoId='" + targetAutoId + "', res=" + vsRes);

                    Thread.sleep(350L);
                    if (!isHttpProxyBusyOrRecent(2000L)) {
                        Object afterVsSettings = HookBridge.callMethod(ep14Instance, sQuerySettingsMethodName);
                        logInfo("[SingleCitySync] AFTER Endpoint 49 0x0B Settings: " + formatSettingsObj(afterVsSettings));
                        if (afterVsSettings != null) {
                            Object newB = HookBridge.getObjectField(afterVsSettings, "b");
                            String newBId = getCityId(newB);
                            if (newBId != null && !newBId.isEmpty()) {
                                initB = newB;
                                initBId = newBId;
                            }
                        }
                    } else {
                        logInfo("[SingleCitySync] Watch HTTP Proxy immediately became active after Endpoint 49 GNSS packet!");
                    }
                } catch (Throwable t) {
                    logError("[SingleCitySync] Endpoint 49 VirtualSensor GNSS send failed", t);
                }
            }

            String chosenKey;
            if (initB != null && initBId != null && !initBId.isEmpty()) {
                chosenKey = targetAutoId;
                sActiveWatchLocationKey = chosenKey;
                if (!isHttpProxyBusyOrRecent(3000L) && needForcePlaceRefresh) {
                    Object targetGnss = newCityInstance(chosenKey, cityName);
                    boolean r06 = invokeWriteSettings(ep14Instance, newSettingsInstance(null, targetGnss, targetGnss));
                    boolean r04 = invokeWriteSettings(ep14Instance, newSettingsInstance(null, null, targetGnss));
                    logInfo("[SingleCitySync] Updated GNSS/defaultCity (0x09) -> id='" + chosenKey + "', name='" + cityName
                            + "' (09_06=" + r06 + ", 09_04=" + r04 + ")");
                } else {
                    logInfo("[SingleCitySync] Skipping 0x09 writes (proxyBusyOrAlreadySynced, chosenKey='" + chosenKey + "', initBName='" + initBName + "')");
                }
            } else {
                // Slot b is empty -> provision single 'notify' city
                chosenKey = "notify";
                sActiveWatchLocationKey = chosenKey;
                Object targetNotify = newCityInstance("notify", cityName);
                boolean r07 = (Boolean) HookBridge.callMethod(ep14Instance, "s", targetNotify, true);
                boolean r04 = invokeWriteSettings(ep14Instance, newSettingsInstance(null, null, targetNotify));
                logInfo("[SingleCitySync] Provisioned single 'notify' city '" + cityName + "' -> 07_add=" + r07 + ", 09_04=" + r04);
            }

            sActiveWatchLocationKey = chosenKey;
            logInfo("[SingleCitySync] Active Watch LocationKey set to: '" + sActiveWatchLocationKey + "'");

            // 3. Update settings hash & GNSS cache in modelXHelper.a so Notify knows city sync succeeded
            if (prefs != null) {
                try {
                    Class<?> modelXHelperClass = findClassSafe(cl,
                            "com.mc.miband1.modelXHelper.a",
                            "com.mc.miband1.model.modelXHelper");
                    if (modelXHelperClass != null) {
                        if (chosenKey.endsWith(",auto")) {
                            HookBridge.callStaticMethod(modelXHelperClass, "l", "ee7ca29f-0509-4d04-b50d-54c09456e830", chosenKey);
                            HookBridge.callStaticMethod(modelXHelperClass, "l", "55b48dd9-d16f-4b02-b971-cdc155fc6b7e", cityName);
                            HookBridge.callStaticMethod(modelXHelperClass, "m", "6de38e8f-0c3c-445b-90fb-1a40d4b91572", true);
                        }
                        String hash = (String) HookBridge.callStaticMethod(ep14Instance.getClass(), "i", prefs);
                        if (hash != null) {
                            HookBridge.callStaticMethod(modelXHelperClass, "l", "d8fdf7ac-b858-4b6d-b582-878962001bac", hash);
                            HookBridge.callStaticMethod(modelXHelperClass, "l", "849f2cf3-a86e-4399-9516-435a30477179", hash);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 4. Send [0x12, 0x00] on Endpoint 1 to trigger immediate watch HTTP proxy weather fetch (/weather/v6/all)
            if (!isHttpProxyBusyOrRecent(5000L)) {
                try {
                    if (bleMgr != null && fc2Class != null) {
                        Object res = HookBridge.callStaticMethod(fc2Class, "g", bleMgr, 1, null, new byte[]{0x12, 0x00}, false);
                        logInfo("[SingleCitySync] Sent [0x12, 0x00] on Endpoint 1 (bleMgr=" + bleMgr.getClass().getSimpleName() + ") -> " + res);
                    } else {
                        logError("[SingleCitySync] Cannot send [0x12, 0x00]: bleMgr=" + bleMgr + ", fc2Class=" + fc2Class, null);
                    }
                } catch (Throwable t) {
                    logError("Failed sending [0x12, 0x00] trigger on Endpoint 1", t);
                }
            } else {
                logInfo("[SingleCitySync] Skipping [0x12, 0x00] trigger because Watch HTTP Proxy is already active");
            }

            final Object ep14Ref = ep14Instance;
            new Thread(() -> {
                try {
                    Thread.sleep(10000L);
                    waitHttpProxyIdle(cl, 8000L);
                    Object verifySettings = HookBridge.callMethod(ep14Ref, sQuerySettingsMethodName);
                    logInfo("[WeatherGnssSettingsRefresh] Post-HTTP 0x0B Settings on Watch: " + formatSettingsObj(verifySettings));
                } catch (Throwable ignored) {
                }
            }, "Balance2Fix-Verify0x0B").start();

            sLastSyncTimestamp = System.currentTimeMillis();
            logInfo("=== [SingleCitySync] COMPLETE ===");
            return true;
        } catch (Throwable t) {
            logError("performSingleCityWeatherSync failed", t);
            return false;
        } finally {
            sIsCustomSyncRunning = false;
        }
    }

    private static Object newCityInstance(String id, String name) {
        if (sCityClass == null) return null;
        try {
            Constructor<?> c = sCityClass.getDeclaredConstructor(String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(id, name);
        } catch (Throwable t) {
            logError("newCityInstance failed", t);
            return null;
        }
    }

    private static Object newSettingsInstance(Boolean a, Object b, Object c) {
        if (sSettingsClass == null || sCityClass == null) return null;
        try {
            Constructor<?> ctor = sSettingsClass.getDeclaredConstructor(Boolean.class, sCityClass, sCityClass);
            ctor.setAccessible(true);
            return ctor.newInstance(a, b, c);
        } catch (Throwable t) {
            logError("newSettingsInstance failed", t);
            return null;
        }
    }

    private static boolean invokeWriteSettings(Object ep14Instance, Object settingsObj) {
        if (ep14Instance == null || settingsObj == null) return false;
        try {
            Object res = HookBridge.callMethod(ep14Instance, sWriteSettingsMethodName, settingsObj);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            logError("invokeWriteSettings failed", t);
            return false;
        }
    }

    private static String getCityId(Object cityObj) {
        if (cityObj == null) return null;
        try {
            return (String) HookBridge.getObjectField(cityObj, "a");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getCityName(Object cityObj) {
        if (cityObj == null) return null;
        try {
            return (String) HookBridge.getObjectField(cityObj, "b");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatCityObj(Object cityObj) {
        if (cityObj == null) return "null";
        return "{id='" + getCityId(cityObj) + "', name='" + getCityName(cityObj) + "'}";
    }

    private static String formatSettingsObj(Object settingsObj) {
        if (settingsObj == null) return "null";
        try {
            Object a = HookBridge.getObjectField(settingsObj, "a");
            Object b = HookBridge.getObjectField(settingsObj, "b");
            Object c = HookBridge.getObjectField(settingsObj, "c");
            return "{a=" + a + ", b=" + formatCityObj(b) + ", c=" + formatCityObj(c) + "}";
        } catch (Throwable ignored) {
            return String.valueOf(settingsObj);
        }
    }

    private static String formatProtocolObj(Object protoObj) {
        if (protoObj == null) return "null";
        try {
            Object a = HookBridge.getObjectField(protoObj, "a");
            Object b = HookBridge.getObjectField(protoObj, "b");
            return "{version=" + a + ", supported=" + b + "}";
        } catch (Throwable ignored) {
            return String.valueOf(protoObj);
        }
    }

    private static String formatCityList(List<?> list) {
        if (list == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatCityObj(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // =========================================================================
    // 6. Hook Weather v6 JSON Builder (Lq38 in v24.1.1, Lz38 in v24.0.3, Lmh7 in older APK)
    // =========================================================================
    private void hookPlaceJsonBuilder(final ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            Class<?> weatherDataClass = resolveWeatherDataClass(cl);
            Class<?> u51Class = findClassSafe(cl, "v51", "u51");
            Class<?> builderClass = findClassSafe(cl, "q38", "z38", "mh7", "lg7");

            if (builderClass == null || userPrefsClass == null) {
                logError("Could not find Weather v6 JSON Builder class (q38/z38/mh7)", null);
                return;
            }

            // 6.1 Hook place JSON builder: D(UserPreferences, String) in Lq38 (v24.1.1), E(UserPreferences) in Lz38, t(UserPreferences) in Lmh7
            String placeMethodName = null;
            if (hasMethod(builderClass, "D", userPrefsClass, String.class)) {
                placeMethodName = "D";
                HookBridge.findAndHookMethod(builderClass, "D", userPrefsClass, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        Object place = buildPlaceJson(param.args[0], cl);
                        if (place != null) {
                            param.setResult(place);
                            logInfo("[" + builderClass.getSimpleName() + ".D] Built place JSON: " + place);
                        }
                    }
                });
            } else {
                placeMethodName = hasMethod(builderClass, "E", userPrefsClass) ? "E"
                        : (hasMethod(builderClass, "t", userPrefsClass) ? "t" : null);
                if (placeMethodName != null) {
                    final String pm = placeMethodName;
                    HookBridge.findAndHookMethod(builderClass, placeMethodName, userPrefsClass, new HookBridge.MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isWeatherFixEnabled(cl)) return;
                            Object place = buildPlaceJson(param.args[0], cl);
                            if (place != null) {
                                param.setResult(place);
                                logInfo("[" + builderClass.getSimpleName() + "." + pm + "] Built place JSON: " + place);
                            }
                        }
                    });
                }
            }

            // 6.2 Hook hourly forecast JSON builder: x(UserPreferences, Loe8) in Lq38, z(UserPreferences, Lre8) in Lz38, p in Lmh7
            // AND hourlyAirQuality builder: u(Loe8) in Lq38, w(Lre8) in Lz38
            if (weatherDataClass != null) {
                String hourlyMethodName = hasMethod(builderClass, "x", userPrefsClass, weatherDataClass) ? "x"
                        : (hasMethod(builderClass, "z", userPrefsClass, weatherDataClass) ? "z"
                        : (hasMethod(builderClass, "p", userPrefsClass, weatherDataClass) ? "p" : null));
                if (hourlyMethodName != null) {
                    final String hm = hourlyMethodName;
                    HookBridge.findAndHookMethod(builderClass, hourlyMethodName, userPrefsClass, weatherDataClass, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isWeatherFixEnabled(cl)) return;
                            Object weatherData = param.args[1];
                            if (weatherData == null) return;
                            try {
                                ArrayList<?> hourlyList = (ArrayList<?>) HookBridge.getObjectField(weatherData, "A");
                                if ((hourlyList == null || hourlyList.isEmpty()) && sCachedHourlyForecastList != null && !sCachedHourlyForecastList.isEmpty()) {
                                    HookBridge.setObjectField(weatherData, "A", new ArrayList<>(sCachedHourlyForecastList));
                                    logInfo("[" + builderClass.getSimpleName() + "." + hm + "] Restored "
                                            + sCachedHourlyForecastList.size() + " cached hourly forecast entries before building JSON");
                                }
                                sanitizeWeatherDataFeelsLike(weatherData);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }

                String aqMethodName = hasMethod(builderClass, "u", weatherDataClass) ? "u"
                        : (hasMethod(builderClass, "w", weatherDataClass) ? "w" : null);
                if (aqMethodName != null) {
                    HookBridge.findAndHookMethod(builderClass, aqMethodName, weatherDataClass, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isWeatherFixEnabled(cl)) return;
                            Object weatherData = param.args[0];
                            if (weatherData == null) return;
                            try {
                                sanitizeWeatherDataFeelsLike(weatherData);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }

            // 6.3 Hook single-hour fallback builder: h(UserPreferences, Lv51, Lof8, double, long) in Lq38, i in Lz38, f in Lmh7
            Class<?> rf8Class = findClassSafe(cl, "of8", "rf8");
            if (u51Class != null && rf8Class != null) {
                String fallbackHourMethod = hasMethod(builderClass, "h", userPrefsClass, u51Class, rf8Class, double.class, long.class) ? "h"
                        : (hasMethod(builderClass, "i", userPrefsClass, u51Class, rf8Class, double.class, long.class) ? "i"
                        : (hasMethod(builderClass, "f", userPrefsClass, u51Class, rf8Class, double.class, long.class) ? "f" : null));
                if (fallbackHourMethod != null) {
                    HookBridge.findAndHookMethod(builderClass, fallbackHourMethod,
                            userPrefsClass, u51Class, rf8Class, double.class, long.class, new HookBridge.MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (!isWeatherFixEnabled(cl)) return;
                                    fixU51FeelsLike(param.args[1]);
                                }
                            });
                }
            }

            // 6.4 Hook single-hour JSON item builder: w(...) in Lq38, y(...) in Lz38, o(...) in Lmh7
            String singleHourMethod = hasMethod(builderClass, "w", long.class, String.class, double.class, double.class,
                    double.class, int.class, int.class, double.class, Double.class, Double.class, double.class, boolean.class) ? "w"
                    : (hasMethod(builderClass, "y", long.class, String.class, double.class, double.class,
                    double.class, int.class, int.class, double.class, Double.class, Double.class, double.class, boolean.class) ? "y"
                    : (hasMethod(builderClass, "o", long.class, String.class, double.class, double.class,
                    double.class, int.class, int.class, double.class, Double.class, Double.class, double.class, boolean.class) ? "o" : null));
            if (singleHourMethod != null) {
                HookBridge.findAndHookMethod(builderClass, singleHourMethod,
                        long.class, String.class, double.class, double.class, double.class,
                        int.class, int.class, double.class, Double.class, Double.class, double.class, boolean.class,
                        new HookBridge.MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!isWeatherFixEnabled(cl)) return;
                                double humRatio = (Double) param.args[2];
                                double humPct = humRatio <= 1.0 ? humRatio * 100.0 : humRatio;
                                double temp = (Double) param.args[4];
                                double wind = (Double) param.args[7];
                                Double feels = (Double) param.args[8];
                                if (feels == null || (Math.abs(feels) < 0.01 && Math.abs(temp) > 3.0)) {
                                    param.args[8] = Double.valueOf(computeApparentTemperature(
                                            temp,
                                            humPct > 0 ? humPct : 65.0,
                                            wind >= 0 ? wind : 5.0
                                    ));
                                }
                            }
                        });
            }

            logInfo("Hooked Weather v6 JSON Builder: " + builderClass.getName() + " (place=" + placeMethodName + ", singleHour=" + singleHourMethod + ")");
        } catch (Throwable t) {
            logError("hookPlaceJsonBuilder failed", t);
        }
    }

    public static Object buildPlaceJson(Object userPrefs, ClassLoader cl) {
        try {
            loadRealLocationFromPrefs(cl);
            String cityName = getRealCityName(cl);
            if ((cityName == null || cityName.isEmpty()) && userPrefs != null) {
                cityName = callUserPrefsCity(userPrefs);
            }
            if (cityName == null || cityName.trim().isEmpty()) {
                cityName = "Taipei";
            }
            double[] coords = getRealCoordinates(cl, userPrefs);
            double lat = coords != null ? coords[0] : 25.038689;
            double lon = coords != null ? coords[1] : 121.566210;

            String locKey = (sActiveWatchLocationKey != null && !sActiveWatchLocationKey.isEmpty())
                    ? sActiveWatchLocationKey : "notify";

            String latStr = String.format(Locale.US, "%.6f", lat);
            String lonStr = String.format(Locale.US, "%.6f", lon);
            if (locKey.endsWith(",auto")) {
                String[] parts = locKey.split(",");
                if (parts.length >= 2) {
                    latStr = parts[0];
                    lonStr = parts[1];
                }
            }

            Class<?> jsonObjClass = findClassSafe(cl, "com.google.gson.JsonObject");
            if (jsonObjClass == null) return null;
            Object place = jsonObjClass.getDeclaredConstructor().newInstance();
            String addPropName = hasMethod(jsonObjClass, "r", String.class, String.class) ? "r" : "addProperty";
            Method addProp = jsonObjClass.getDeclaredMethod(addPropName, String.class, String.class);
            addProp.setAccessible(true);
            // Exact schema of Lq38.D / Lz38.E: locationKey, longitude, latitude, affiliation, name, countryCode=null (JsonNull.f)
            addProp.invoke(place, "locationKey", locKey);
            addProp.invoke(place, "longitude", lonStr);
            addProp.invoke(place, "latitude", latStr);
            addProp.invoke(place, "affiliation", cityName);
            addProp.invoke(place, "name", cityName);
            addProp.invoke(place, "countryCode", null);
            return place;
        } catch (Throwable t) {
            logError("buildPlaceJson failed", t);
            return null;
        }
    }

    // =========================================================================
    // 7. Hook BLE HTTP Proxy (Lzu5 in v24.1.1, Lev5 in v24.0.3, Lcv5 in older APK)
    // =========================================================================
    private void hookBleHttpProxy(final ClassLoader cl) {
        try {
            Class<?> proxyClass = findClassSafe(cl, "zu5", "ev5", "cv5");
            if (proxyClass == null) {
                logError("Could not find BLE HTTP Proxy class (zu5/ev5/cv5)", null);
                return;
            }
            Class<?> xpClass = findClassSafe(cl, "xp");
            if (xpClass != null && hasMethod(proxyClass, "f", xpClass, int.class, byte[].class)) {
                HookBridge.findAndHookMethod(proxyClass, "f", xpClass, int.class, byte[].class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        byte[] data = (byte[]) param.args[2];
                        if (data != null && data.length > 1 && data[0] == 1) {
                            sLastHttpProxyTimeMs = System.currentTimeMillis();
                            logInfo("[" + proxyClass.getSimpleName() + ".f] Watch HTTP Proxy Request (id=" + data[1] + ", bytes=" + data.length + ")");
                        }
                    }
                });
                logInfo("Hooked BLE HTTP Proxy: " + proxyClass.getName() + ".f(Lxp, int, byte[])");
            } else if (hasMethod(proxyClass, "h", byte.class, String.class, String.class, java.util.LinkedHashMap.class)) {
                HookBridge.findAndHookMethod(proxyClass, "h",
                        byte.class, String.class, String.class, java.util.LinkedHashMap.class,
                        new HookBridge.MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                sLastHttpProxyTimeMs = System.currentTimeMillis();
                                byte reqId = (Byte) param.args[0];
                                String method = (String) param.args[1];
                                String url = (String) param.args[2];
                                logInfo("[" + proxyClass.getSimpleName() + ".h] Watch HTTP Proxy Request (id=" + reqId + "): " + method + " " + url);
                            }
                        });
                logInfo("Hooked BLE HTTP Proxy: " + proxyClass.getName() + ".h()");
            }
            String respSendMethod = hasMethod(proxyClass, "k", int.class, int.class, byte[].class) ? "k"
                    : (hasMethod(proxyClass, "m", int.class, int.class, byte[].class) ? "m" : null);
            if (respSendMethod != null) {
                final String rm = respSendMethod;
                HookBridge.findAndHookMethod(proxyClass, respSendMethod, int.class, int.class, byte[].class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sActiveHttpProxyCount.incrementAndGet();
                        sLastHttpProxyTimeMs = System.currentTimeMillis();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sActiveHttpProxyCount.decrementAndGet();
                        sLastHttpProxyTimeMs = System.currentTimeMillis();
                        byte[] body = (byte[]) param.args[2];
                        logInfo("[" + proxyClass.getSimpleName() + "." + rm + "] Watch HTTP Proxy Response BLE transfer finished (reqId="
                                + param.args[0] + ", status=" + param.args[1] + ", bytes=" + (body != null ? body.length : 0)
                                + ") -> result=" + param.getResult());
                    }
                });
                logInfo("Hooked BLE HTTP Proxy response sender: " + proxyClass.getName() + "." + respSendMethod + "(int, int, byte[])");
            } else {
                Class<?> respClass = findClassSafe(cl, "n86", "s86", "q86");
                if (respClass != null && hasMethod(proxyClass, "m", respClass)) {
                    HookBridge.findAndHookMethod(proxyClass, "m", respClass, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sActiveHttpProxyCount.incrementAndGet();
                            sLastHttpProxyTimeMs = System.currentTimeMillis();
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sActiveHttpProxyCount.decrementAndGet();
                            sLastHttpProxyTimeMs = System.currentTimeMillis();
                            logInfo("[" + proxyClass.getSimpleName() + ".m] Watch HTTP Proxy Response BLE transfer finished -> result=" + param.getResult());
                        }
                    });
                    logInfo("Hooked BLE HTTP Proxy response sender: " + proxyClass.getName() + ".m(" + respClass.getName() + ")");
                }
            }
        } catch (Throwable t) {
            logError("hookBleHttpProxy failed", t);
        }
    }

    private static boolean isHttpProxyBusyOrRecent(long quietWindowMs) {
        if (sActiveHttpProxyCount.get() > 0) return true;
        long elapsed = System.currentTimeMillis() - sLastHttpProxyTimeMs;
        return sLastHttpProxyTimeMs > 0L && elapsed >= 0L && elapsed < quietWindowMs;
    }

    private static void waitHttpProxyIdle(ClassLoader cl, long timeoutMs) {
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (!isHttpProxyBusyOrRecent(3000L)) return;
                Thread.sleep(200L);
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 8. Hook WeatherActivity Manual Update UI Click (WeatherActivity.p0 in v24.1.1, Ldy/Lse8 in older APKs)
    // =========================================================================
    private void hookWeatherActivityUI(final ClassLoader cl) {
        try {
            Class<?> weatherActClass = findClassSafe(cl, "com.mc.miband1.ui.weather.WeatherActivity");
            if (weatherActClass != null && hasMethod(weatherActClass, "p0")) {
                HookBridge.findAndHookMethod(weatherActClass, "p0", new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Context) {
                                Context ctx = (Context) param.thisObject;
                                logInfo("[WeatherActivity.p0] Manual update clicked -> triggering AGPS update & location reload");
                                loadRealLocationFromPrefs(cl);
                                triggerAgpsUpdate(ctx, cl, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                logInfo("Hooked WeatherActivity manual update method: WeatherActivity.p0()");
                return;
            }
            Class<?> clickClass = findClassSafe(cl, "dy", "se8", "cd8");
            if (clickClass != null && hasMethod(clickClass, "onClick", android.view.View.class)) {
                HookBridge.findAndHookMethod(clickClass, "onClick", android.view.View.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object target = HookBridge.getObjectField(param.thisObject, "i");
                            if (target != null && target.getClass().getName().endsWith("WeatherActivity")) {
                                Context ctx = (Context) target;
                                logInfo("[WeatherActivity] Manual update button clicked -> triggering AGPS update & location reload");
                                loadRealLocationFromPrefs(cl);
                                triggerAgpsUpdate(ctx, cl, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                logInfo("Hooked WeatherActivity click handler: " + clickClass.getName());
            }
        } catch (Throwable t) {
            logError("hookWeatherActivityUI failed", t);
        }
    }

    // =========================================================================
    // 9. Hook Internal Dispatcher & Logger (Lv38 in v24.1.1, Le48/Lb48 in older APKs)
    // =========================================================================
    private void hookBroadcastFailureOverride(final ClassLoader cl) {
        try {
            Class<?> dispatcherClass = findClassSafe(cl, "v38", "e48", "b48");
            if (dispatcherClass == null) return;

            // Convert weatherUpdateFailed into weatherUpdated when our custom sync succeeded
            String strBroadcastMethod = hasMethod(dispatcherClass, "h2", Context.class, String.class) ? "h2"
                    : (hasMethod(dispatcherClass, "r2", Context.class, String.class) ? "r2" : null);
            if (strBroadcastMethod != null) {
                final String bm = strBroadcastMethod;
                HookBridge.findAndHookMethod(dispatcherClass, strBroadcastMethod, Context.class, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        String action = (String) param.args[1];
                        if (ACTION_NOTIFY_WEATHER_FAILED_UUID.equals(action)) {
                            logInfo("[" + dispatcherClass.getSimpleName() + "." + bm + "] Intercepted weatherUpdateFailed UUID -> overriding to weatherUpdated UUID");
                            param.args[1] = ACTION_NOTIFY_WEATHER_UPDATED_UUID;
                        } else if ("com.mc.miband.weatherUpdateFailed".equals(action)) {
                            logInfo("[" + dispatcherClass.getSimpleName() + "." + bm + "] Intercepted weatherUpdateFailed -> overriding to weatherUpdated");
                            param.args[1] = "com.mc.miband.weatherUpdated";
                        }
                    }
                });
            }

            if (hasMethod(dispatcherClass, "g2", Context.class, Intent.class)) {
                HookBridge.findAndHookMethod(dispatcherClass, "g2", Context.class, Intent.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isWeatherFixEnabled(cl)) return;
                        Intent intent = (Intent) param.args[1];
                        if (intent == null) return;
                        String action = intent.getAction();
                        if (ACTION_NOTIFY_WEATHER_FAILED_UUID.equals(action)) {
                            logInfo("[" + dispatcherClass.getSimpleName() + ".g2] Intercepted weatherUpdateFailed Intent -> overriding to weatherUpdated UUID");
                            intent.setAction(ACTION_NOTIFY_WEATHER_UPDATED_UUID);
                        }
                    }
                });
            }

            // Forward internal Notify logs for legacy builds (Le48.h)
            if (hasMethod(dispatcherClass, "h", Context.class, String.class, String.class, Exception.class)) {
                HookBridge.findAndHookMethod(dispatcherClass, "h",
                        Context.class, String.class, String.class, Exception.class, new HookBridge.MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String tag = (String) param.args[1];
                                String msg = (String) param.args[2];
                                if ((tag != null && (tag.equals("nf8") || tag.equals("kf8")
                                        || tag.equals("ev5") || tag.equals("cv5") || tag.equals("zu5")
                                        || tag.equals("lf8") || tag.equals("if8")
                                        || tag.equals("ey") || tag.equals("uv") || tag.equals("sv") || tag.equals("l8") || tag.equals("as")))
                                        || (msg != null && (msg.contains("Weather HTTP") || msg.contains("weather HTTP")))) {
                                    logInfo("[NotifyInternal:" + tag + "] " + msg);
                                }
                            }
                        });
            }

            logInfo("Hooked internal broadcast dispatcher & logger: " + dispatcherClass.getName() + " (strBroadcast=" + strBroadcastMethod + ")");
        } catch (Throwable t) {
            logError("hookBroadcastFailureOverride failed", t);
        }
    }

    // =========================================================================
    // 10. Automatic AGPS Sync via Notify's Internal Broadcast Dispatcher (Le48 / Lb48 / Lw38)
    // =========================================================================
    private static void triggerAgpsUpdate(final Context context, final ClassLoader cl, final boolean force) {
        if (context == null || !isAgpsFixEnabled(cl)) return;
        long now = System.currentTimeMillis();
        if (!force && (now - sLastAgpsSyncTimestamp < AGPS_SYNC_INTERVAL_MS)) {
            long minsAgo = (now - sLastAgpsSyncTimestamp) / 60000L;
            logInfo("[AGPS] Skipping automatic AGPS update (last updated " + minsAgo + " mins ago)");
            return;
        }
        sLastAgpsSyncTimestamp = now;

        new Thread(() -> {
            try {
                // Wait 20s so weather HTTP proxy sync AND the 15s WeatherGnssSettingsRefresh readback finish before starting AGPS file transfer
                Thread.sleep(20000L);
                waitHttpProxyIdle(cl, 10000L);
                runOneClickAgpsUpdateInService(context.getApplicationContext(), cl, sAgpsFormatMode);
            } catch (Throwable t) {
                logError("[AGPS] Failed to trigger automatic AGPS update", t);
            }
        }, "Balance2Fix-AgpsTrigger").start();
    }

    // =========================================================================
    // Helper Methods: Real City & Coordinates Resolution
    // =========================================================================
    private static String getRealCityName(ClassLoader cl) {
        loadRealLocationFromPrefs(cl);
        if (sRealCityName != null && !sRealCityName.trim().isEmpty()) {
            return sRealCityName.trim();
        }
        return null;
    }

    private static double[] getRealCoordinates(ClassLoader cl, Object userPrefs) {
        loadRealLocationFromPrefs(cl);
        if (userPrefs != null) {
            try {
                Object w7 = callUserPrefsWeatherData(userPrefs, cl);
                if (w7 != null) {
                    double wLat = HookBridge.getDoubleField(w7, "q");
                    double wLon = HookBridge.getDoubleField(w7, "w");
                    if (Math.abs(wLat) > 0.001 || Math.abs(wLon) > 0.001) {
                        sRealLatitude = wLat;
                        sRealLongitude = wLon;
                        return new double[]{wLat, wLon};
                    }
                }
            } catch (Throwable ignored) {
            }
            double[] latLon = callUserPrefsLatLon(userPrefs);
            if (latLon != null) {
                sRealLatitude = latLon[0];
                sRealLongitude = latLon[1];
                return latLon;
            }
        }
        if (sRealLatitude != null && sRealLongitude != null
                && (Math.abs(sRealLatitude) > 0.001 || Math.abs(sRealLongitude) > 0.001)) {
            return new double[]{sRealLatitude, sRealLongitude};
        }
        return new double[]{25.038689, 121.566210};
    }

    private static void loadRealLocationFromPrefs(ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            if (userPrefsClass == null) return;
            String freshW7City = null;
            if (sAppContext != null) {
                Object prefs = HookBridge.callStaticMethod(userPrefsClass, "getInstance", sAppContext);
                if (prefs != null) {
                    try {
                        Object w7 = callUserPrefsWeatherData(prefs, cl);
                        if (w7 != null) {
                            String wCity = (String) HookBridge.getObjectField(w7, "i");
                            double wLat = HookBridge.getDoubleField(w7, "q");
                            double wLon = HookBridge.getDoubleField(w7, "w");
                            if (wCity != null && !wCity.trim().isEmpty()
                                    && !"Hengke Village".equalsIgnoreCase(wCity.trim())
                                    && !"興隆里".equals(wCity.trim())) {
                                freshW7City = wCity.trim();
                                sRealCityName = freshW7City;
                            }
                            if (Math.abs(wLat) > 0.001 || Math.abs(wLon) > 0.001) {
                                sRealLatitude = wLat;
                                sRealLongitude = wLon;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    double[] latLon = callUserPrefsLatLon(prefs);
                    if (latLon != null) {
                        sRealLatitude = latLon[0];
                        sRealLongitude = latLon[1];
                    }
                }
            }
            SharedPreferences sp = getNotifySharedPrefs(cl);
            if (sp != null) {
                if (freshW7City == null) {
                    String savedCustomCity = sp.getString("balance2_real_city", null);
                    if (savedCustomCity != null && !savedCustomCity.trim().isEmpty()) {
                        sRealCityName = savedCustomCity.trim();
                    } else if (sRealCityName == null || sRealCityName.isEmpty()) {
                        String v7City = sp.getString("weather_city", null);
                        if (v7City != null && !v7City.trim().isEmpty()
                                && !"Hengke Village".equalsIgnoreCase(v7City.trim())
                                && !"興隆里".equals(v7City.trim())) {
                            sRealCityName = v7City.trim();
                        }
                    }
                }
                if (sp.contains("balance2_real_lat") && sRealLatitude == null) {
                    double lat = Double.longBitsToDouble(sp.getLong("balance2_real_lat", 0L));
                    if (Math.abs(lat) > 0.001) sRealLatitude = lat;
                }
                if (sp.contains("balance2_real_lon") && sRealLongitude == null) {
                    double lon = Double.longBitsToDouble(sp.getLong("balance2_real_lon", 0L));
                    if (Math.abs(lon) > 0.001) sRealLongitude = lon;
                }
            }
            if ((sRealCityName == null || sRealCityName.isEmpty()) && sAppContext != null) {
                Object prefs = HookBridge.callStaticMethod(userPrefsClass, "getInstance", sAppContext);
                if (prefs != null) {
                    String a7 = callUserPrefsCity(prefs);
                    if (a7 != null && !a7.trim().isEmpty()
                            && !"Hengke Village".equalsIgnoreCase(a7.trim())
                            && !"興隆里".equals(a7.trim())) {
                        sRealCityName = a7.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveRealLocationToPrefs(Context ctx, ClassLoader cl, String city, Double lat, Double lon) {
        try {
            if (ctx != null && sAppContext == null) {
                sAppContext = ctx.getApplicationContext();
            }
            SharedPreferences sp = getNotifySharedPrefs(cl);
            if (sp != null) {
                SharedPreferences.Editor ed = sp.edit();
                if (city != null && !city.isEmpty()) {
                    ed.putString("balance2_real_city", city);
                    ed.putString("weather_city", city);
                    ed.putString("weather_city_display", city);
                }
                if (lat != null && lon != null) {
                    ed.putLong("balance2_real_lat", Double.doubleToRawLongBits(lat));
                    ed.putLong("balance2_real_lon", Double.doubleToRawLongBits(lon));
                }
                ed.apply();
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 10B. Complete Zepp OS 6 AGPS Pipeline Fix (Luv, Ley, Lku8, Ls6, Lxm6, UpdateFirmwareActivity)
    // =========================================================================
    private void hookAgpsFixPipeline(final ClassLoader cl) {
        try {
            // 1. Hook UpdateFirmwareActivity.p0() -> prevent returning 1 (legacy CEP) on Balance 2 (Zepp OS 6)
            Class<?> updFwClass = findClassSafe(cl, "com.mc.miband1.ui.updateFirmware.UpdateFirmwareActivity");
            if (updFwClass != null && hasMethod(updFwClass, "p0")) {
                HookBridge.findAndHookMethod(updFwClass, "p0", new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        Integer res = (Integer) param.getResult();
                        if (res == null || res == 1) {
                            int fixed = (sAgpsFormatMode == 3 || sAgpsFormatMode == 4) ? sAgpsFormatMode : 2;
                            param.setResult(fixed);
                            logInfo("[UpdateFirmwareActivity.p0] Overrode legacy CEP (1) -> " + fixed + " (Zepp OS 6 EPO/LTO)");
                        }
                    }
                });
            }

            // 2. Hook Lmj4.b (v24.1.1) or Llj4.d (v24.0.3) in UpdateFirmwareActivity so ProgressDialog R updates 0%->100% and dismisses cleanly
            Class<?> fwRecvClass = findClassSafe(cl, "mj4", "lj4");
            String fwRecvMethod = (fwRecvClass != null && hasMethod(fwRecvClass, "b", Context.class, Intent.class)) ? "b"
                    : ((fwRecvClass != null && hasMethod(fwRecvClass, "d", Context.class, Intent.class)) ? "d" : null);
            if (fwRecvClass != null && fwRecvMethod != null) {
                final String targetFieldName = "mj4".equals(fwRecvClass.getSimpleName()) ? "b" : "c";
                HookBridge.findAndHookMethod(fwRecvClass, fwRecvMethod, Context.class, Intent.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        try {
                            Intent intent = (Intent) param.args[1];
                            if (intent == null) return;
                            String action = intent.getAction();
                            Object target = HookBridge.getObjectField(param.thisObject, targetFieldName);
                            if (target == null || !target.getClass().getName().endsWith("UpdateFirmwareActivity")) {
                                return;
                            }
                            android.app.ProgressDialog pd = (android.app.ProgressDialog) HookBridge.getObjectField(target, "R");
                            if (ACTION_NOTIFY_FW_PROGRESS_UUID.equals(action) || "com.mc.miband.firmware.progress".equals(action)) {
                                int pct = intent.hasExtra("progress")
                                        ? intent.getIntExtra("progress", 0)
                                        : intent.getIntExtra("52dba2e7-52ff-4cfa-a5ac-e25c554ba544", 0);
                                if (pd != null && pd.isShowing()) {
                                    pd.setIndeterminate(false);
                                    pd.setMax(100);
                                    pd.setProgress(pct);
                                    pd.setMessage("正在透過藍牙傳送 AGPS 星曆至手錶: " + pct + "%");
                                }
                            } else if (ACTION_NOTIFY_FW_DONE_UUID.equals(action)
                                    || ACTION_NOTIFY_FW_KO_UUID.equals(action)
                                    || "com.mc.miband.firmware.done".equals(action)
                                    || "com.mc.miband.firmwareKO".equals(action)) {
                                if (pd != null && pd.isShowing()) {
                                    pd.dismiss();
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                logInfo("Hooked UpdateFirmwareActivity receiver: " + fwRecvClass.getName() + "." + fwRecvMethod + "(Context, Intent)");
            }

            // 3. Hook Lsv.c(Context) [v24.1.1] or Luv.d(Context) [v24.0.3] -> Prepare valid Zepp OS 6 UIHH (EPO/LTO)
            final Class<?> uvClass = findClassSafe(cl, "sv", "uv");
            String agpsPackMethod = (uvClass != null && hasMethodWithReturn(uvClass, "c", java.io.File.class, Context.class)) ? "c"
                    : ((uvClass != null && hasMethodWithReturn(uvClass, "d", java.io.File.class, Context.class)) ? "d" : null);
            if (uvClass != null && agpsPackMethod != null) {
                final String pm = agpsPackMethod;
                HookBridge.findAndHookMethod(uvClass, agpsPackMethod, Context.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        Context ctx = (Context) param.args[0];
                        if (ctx == null) ctx = sAppContext;
                        try {
                            java.io.File uihFile = prepareZeppOs6AgpsUihFile(ctx, cl);
                            if (uihFile != null && uihFile.exists() && uihFile.length() > 32) {
                                logInfo("[" + uvClass.getSimpleName() + "." + pm + "] Prepared valid Zepp OS 6 UIHH AGPS file: "
                                        + uihFile.getAbsolutePath() + " (" + uihFile.length() + " bytes)");
                                param.setResult(uihFile);
                            } else {
                                logError("[" + uvClass.getSimpleName() + "." + pm + "] prepareZeppOs6AgpsUihFile returned null/empty -> broadcasting firmwareKO to prevent UI hang", null);
                                broadcastFirmwareKo(ctx, cl);
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            logError("[" + uvClass.getSimpleName() + "." + pm + "] Exception in Zepp OS 6 AGPS packer", t);
                            broadcastFirmwareKo(ctx, cl);
                            param.setResult(null);
                        }
                    }
                });
                logInfo("Hooked " + uvClass.getName() + "." + agpsPackMethod + "(Context) for Zepp OS 6 LTO/EPO UIHH packaging");
            }

            // 4. Hook Lsv.h [v24.1.1] or Luv.i [v24.0.3] (BLEManager, File, Ls6) to request HIGH connection priority, log MTU, and track progress
            Class<?> bleMgrClass = findClassSafe(cl, "com.mc.miband1.bluetooth.BLEManager");
            Class<?> s6Class = findClassSafe(cl, "s6");
            String agpsSendMethod = (uvClass != null && bleMgrClass != null && s6Class != null && hasMethod(uvClass, "h", bleMgrClass, java.io.File.class, s6Class)) ? "h"
                    : ((uvClass != null && bleMgrClass != null && s6Class != null && hasMethod(uvClass, "i", bleMgrClass, java.io.File.class, s6Class)) ? "i" : null);
            if (uvClass != null && agpsSendMethod != null) {
                final String sm = agpsSendMethod;
                HookBridge.findAndHookMethod(uvClass, agpsSendMethod, bleMgrClass, java.io.File.class, s6Class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        Object bleMgr = param.args[0];
                        java.io.File f = (java.io.File) param.args[1];
                        if (f != null && f.exists()) {
                            sCurrentAgpsTotalBytes = (int) f.length();
                            sAgpsTransferActive = true;
                            sAgpsTransferStartMs = System.currentTimeMillis();
                            sLoggedAgpsBleParams = false;
                            sLastAgpsInstallStatus = -1;
                            sLastProgressBroadcastMs = 0L;
                            int currentMtu = -1;
                            try {
                                if (bleMgr != null) {
                                    // Bypass 120s S0/V0 cooldown so CONNECTION_PRIORITY_HIGH (1) activates immediately
                                    for (String cdField : new String[]{"F1", "D1"}) {
                                        try {
                                            HookBridge.setLongField(bleMgr, cdField, 0L);
                                            break;
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    if (hasMethod(bleMgr.getClass(), "S0", int.class, boolean.class)) {
                                        HookBridge.callMethod(bleMgr, "S0", 1, false);
                                    } else if (hasMethod(bleMgr.getClass(), "V0", int.class, boolean.class)) {
                                        HookBridge.callMethod(bleMgr, "V0", 1, false);
                                    } else {
                                        android.bluetooth.BluetoothGatt gatt = getBluetoothGattFromBleManager(bleMgr);
                                        if (gatt != null) {
                                            gatt.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                                        }
                                    }
                                    try {
                                        Object lo20 = HookBridge.getObjectField(bleMgr, "b");
                                        if (lo20 != null) {
                                            currentMtu = HookBridge.getIntField(lo20, "w");
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            } catch (Throwable t) {
                                logError("[" + uvClass.getSimpleName() + "." + sm + "] Failed to boost BLE connection priority", t);
                            }
                            logInfo("[" + uvClass.getSimpleName() + "." + sm + "] Starting BLE AGPS transfer (agps://upgrade): size=" + sCurrentAgpsTotalBytes
                                    + " bytes, mtu=" + currentMtu + ", connPriority=HIGH");
                            notifyAgpsProgress(sAppContext, cl, 1, "🛰️ 正在透過藍牙高速傳送 AGPS 星曆至手錶: 1% (0/" + (sCurrentAgpsTotalBytes / 1024) + " KB)", false, true);
                        }
                    }
                });
                logInfo("Hooked " + uvClass.getName() + "." + agpsSendMethod + "(BLEManager, File, Ls6) for fast AGPS transfer");
            }

            // 4b. CRITICAL FIX: Hook BLEManager.x1 [v24.1.1] / C1 [v24.0.3] (BluetoothGattCharacteristic, byte[], int) -> use mode=2 (NO 600ms sleep!),
            // force WRITE_TYPE_NO_RESPONSE when characteristic supports PROPERTY_WRITE_NO_RESPONSE, and protect Lvn7/Lqn7 timeout from 40ms flake.
            String bleWriteMethod = (bleMgrClass != null && hasMethod(bleMgrClass, "x1", android.bluetooth.BluetoothGattCharacteristic.class, byte[].class, int.class)) ? "x1"
                    : ((bleMgrClass != null && hasMethod(bleMgrClass, "C1", android.bluetooth.BluetoothGattCharacteristic.class, byte[].class, int.class)) ? "C1" : null);
            if (bleMgrClass != null && bleWriteMethod != null) {
                final String wm = bleWriteMethod;
                HookBridge.findAndHookMethod(bleMgrClass, bleWriteMethod, android.bluetooth.BluetoothGattCharacteristic.class, byte[].class, int.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        try {
                            android.bluetooth.BluetoothGattCharacteristic ch = (android.bluetooth.BluetoothGattCharacteristic) param.args[0];
                            if (ch != null && ch.getUuid() != null) {
                                String u = ch.getUuid().toString();
                                if (u.startsWith("00000023-") || u.startsWith("00000024-")
                                        || u.startsWith("00000016-") || u.startsWith("00000017-")) {
                                    int origMode = (Integer) param.args[2];
                                    int props = ch.getProperties();
                                    boolean supportsNoResp = (props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                                    if (supportsNoResp && ch.getWriteType() != android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                                        ch.setWriteType(android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                    }
                                    if (origMode == 0 || origMode == 1) {
                                        // Mode 2 bypasses BOTH ReentrantLock and Thread.sleep(600) per packet!
                                        param.args[2] = 2;
                                    }
                                    if (!sLoggedAgpsBleParams) {
                                        sLoggedAgpsBleParams = true;
                                        logInfo("[BLEManager." + wm + "] Fast BLE config uuid=" + u
                                                + ", props=0x" + Integer.toHexString(props)
                                                + ", writeType=" + ch.getWriteType()
                                                + ", origMode=" + origMode + " -> forcedMode=" + param.args[2]);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                logInfo("Hooked BLEManager." + bleWriteMethod + " to use mode=2 (zero 600ms sleep) + WRITE_TYPE_NO_RESPONSE on Zepp OS 6 file transfer characteristics");
            }

            // 4b-2. Hook Lvn7.l(long) [v24.1.1] / Lqn7.l(long) [v24.0.3] so mode=2's 40ms ConditionVariable timeout won't abort under brief BLE jitter
            Class<?> qn7Class = findClassSafe(cl, "vn7", "qn7");
            if (qn7Class != null && hasMethod(qn7Class, "l", long.class)) {
                HookBridge.findAndHookMethod(qn7Class, "l", long.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl) || !sAgpsTransferActive) return;
                        long timeoutMs = (Long) param.args[0];
                        if (timeoutMs > 0L && timeoutMs <= 100L) {
                            param.args[0] = 3000L;
                        }
                    }
                });
                logInfo("Hooked " + qn7Class.getName() + ".l(long) to relax 40ms fast-write timeout to 3000ms safety ceiling");
            }

            // 4c. Hook Lou8.j(), Lou8.c(UUID, byte[]), and Lmu8.run() to prevent Main Thread monitor contention and premature 5s chunk timeout
            Class<?> ou8Class = findClassSafe(cl, "ou8");
            // v24.1.1 Lou8: active transfer = field g (Lp22), last timestamp = field i (long)
            // v24.0.3 Lou8: active transfer = field h (Lu22), last timestamp = field j (long)
            String resolvedTsField = "j";
            String resolvedTransferField = "h";
            if (ou8Class != null) {
                try {
                    Field fI = ou8Class.getDeclaredField("i");
                    if (fI.getType() == long.class) {
                        resolvedTsField = "i";
                        resolvedTransferField = "g";
                    }
                } catch (Throwable ignored) {
                }
            }
            final String ou8TsField = resolvedTsField;
            final String ou8TransferField = resolvedTransferField;

            if (ou8Class != null) {
                if (hasMethod(ou8Class, "j")) {
                    HookBridge.findAndHookMethod(ou8Class, "j", new HookBridge.MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                HookBridge.setLongField(param.thisObject, ou8TsField, System.currentTimeMillis());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                if (hasMethod(ou8Class, "c", java.util.UUID.class, byte[].class)) {
                    HookBridge.findAndHookMethod(ou8Class, "c", java.util.UUID.class, byte[].class, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                byte[] data = (byte[]) param.args[1];
                                if (data != null && data.length > 0 && (data[0] & 0xFF) == 0x13) {
                                    logInfo("[Lou8.c] Watch chunk ACK: status=" + (data.length > 1 ? data[1] : -1)
                                            + ", seq=" + (data.length > 2 ? (data[2] & 0xFF) : -1)
                                            + ", hex=" + bytesToHex(data));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
            Class<?> mu8Class = findClassSafe(cl, "mu8");
            if (mu8Class != null && hasMethod(mu8Class, "run")) {
                HookBridge.findAndHookMethod(mu8Class, "run", new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        try {
                            int f = HookBridge.getIntField(param.thisObject, "f");
                            if (f == 1) {
                                Object ou8 = HookBridge.getObjectField(param.thisObject, "w");
                                if (ou8 != null) {
                                    Object activeTransfer = HookBridge.getObjectField(ou8, ou8TransferField);
                                    long lastJ = HookBridge.getLongField(ou8, ou8TsField);
                                    long elapsed = System.currentTimeMillis() - lastJ;
                                    if (activeTransfer != null && lastJ > 0L && elapsed < 35000L) {
                                        // Transfer is still active; reschedule timeout without acquiring synchronized(ou8) on Main Thread!
                                        Object t22 = HookBridge.getObjectField(ou8, "a");
                                        if (t22 != null) {
                                            android.os.Handler h = (android.os.Handler) HookBridge.getObjectField(t22, "e");
                                            if (h != null) {
                                                h.postDelayed((Runnable) param.thisObject, 5000L);
                                                param.setResult(null);
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }

            // 5. Hook Lku8.q(int) and Lku8.l(boolean) -> Real-time BLE transfer progress + completion/timeout handling
            Class<?> ku8Class = findClassSafe(cl, "ku8");
            if (ku8Class != null) {
                if (hasMethod(ku8Class, "q", int.class)) {
                    HookBridge.findAndHookMethod(ku8Class, "q", int.class, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAgpsFixEnabled(cl)) return;
                            try {
                                int mode = HookBridge.getIntField(param.thisObject, "f");
                                if (mode != 3 && !sAgpsTransferActive) return;
                                int bytesSent = (Integer) param.args[0];
                                int total = sCurrentAgpsTotalBytes > 0 ? sCurrentAgpsTotalBytes : 318066;
                                int pct = Math.max(1, Math.min(98, (int) ((bytesSent * 100L) / total)));
                                logInfo("[Lku8.q] AGPS BLE chunk sent: " + bytesSent + " / " + total + " bytes (" + pct + "%)");
                                long now = System.currentTimeMillis();
                                if (now - sLastProgressBroadcastMs >= 200L || bytesSent >= total) {
                                    sLastProgressBroadcastMs = now;
                                    String msg = "🛰️ 正在透過藍牙傳送 AGPS 星曆至手錶: " + pct + "% ("
                                            + (bytesSent / 1024) + " / " + (total / 1024) + " KB)";
                                    notifyAgpsProgress(sAppContext, cl, pct, msg, false, true);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }

                if (hasMethod(ku8Class, "l", boolean.class)) {
                    HookBridge.findAndHookMethod(ku8Class, "l", boolean.class, new HookBridge.MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAgpsFixEnabled(cl)) return;
                            try {
                                int mode = HookBridge.getIntField(param.thisObject, "f");
                                if (mode != 3) return;
                                boolean success = (Boolean) param.args[0];
                                sAgpsTransferActive = false;
                                Object s6Candidate = null;
                                for (String fn : new String[]{"i", "g", "q", "w"}) {
                                    try {
                                        Object val = HookBridge.getObjectField(param.thisObject, fn);
                                        if (val != null && s6Class != null && s6Class.isInstance(val)) {
                                            s6Candidate = val;
                                            break;
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                final Object s6Obj = s6Candidate;
                                long transferElapsedMs = (sAgpsTransferStartMs > 0L)
                                        ? (System.currentTimeMillis() - sAgpsTransferStartMs)
                                        : -1L;
                                logInfo("[Lku8.l] AGPS BLE transfer finished: success=" + success
                                        + ", elapsedMs=" + transferElapsedMs
                                        + " (s6=" + (s6Obj != null) + ")");

                                if (!success) {
                                    // Prevent sending [0x05] on failure and immediately notify failure so UI doesn't hang!
                                    sLastAgpsInstallStatus = 0xFE;
                                    notifyAgpsProgress(sAppContext, cl, 0, "❌ AGPS 藍牙傳輸失敗，請確認手錶連線穩定後重試", true, false);
                                    broadcastFirmwareKo(sAppContext, cl);
                                    if (s6Obj != null) {
                                        Object bm7 = HookBridge.getObjectField(s6Obj, "d");
                                        if (bm7 != null) {
                                            HookBridge.callMethod(bm7, "e", false);
                                        }
                                    }
                                    param.setResult(null);
                                    return;
                                }

                                // Transfer succeeded! Notify 99% while watch installs uih.bin on Endpoint 0x42 (UART flash can take ~35-50s for EPO)
                                notifyAgpsProgress(sAppContext, cl, 1, "⏳ 藍牙傳輸 100% 完成 (" + String.format(Locale.US, "%.1fs", transferElapsedMs / 1000.0) + ")，手錶正在寫入星曆...", false, true);

                                // Schedule adaptive completion watchdog: wait while cmd=0x06 flash progress keeps arriving (up to 90s max)
                                final long transferFinishMark = System.currentTimeMillis();
                                sLastAgpsFlashProgressMs = transferFinishMark;
                                final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                final Runnable[] watchdogHolder = new Runnable[1];
                                watchdogHolder[0] = () -> {
                                    try {
                                        if (sLastAgpsInstallStatus != -1 || sLastCompletedAgpsTransferMs >= transferFinishMark) {
                                            return;
                                        }
                                        long now = System.currentTimeMillis();
                                        long sinceBle = now - transferFinishMark;
                                        long sinceProgress = now - sLastAgpsFlashProgressMs;
                                        if (sinceProgress < 20000L && sinceBle < 90000L) {
                                            mainHandler.postDelayed(watchdogHolder[0], 10000L);
                                            return;
                                        }
                                        logInfo("[Lku8.l] Watch Endpoint 0x42 did not send 0x07 (sinceBle=" + sinceBle
                                                + "ms, sinceProgress=" + sinceProgress + "ms) -> querying 0x0A and triggering fallback completion");
                                        recordAgpsUpdateSuccess(sAppContext, cl);
                                        if (s6Obj != null) {
                                            Object bm7 = HookBridge.getObjectField(s6Obj, "d");
                                            if (bm7 != null) {
                                                HookBridge.callMethod(bm7, "e", true);
                                            }
                                        }
                                        queryWatchAgpsConfigOnEndpoint0A(cl);
                                    } catch (Throwable t) {
                                        logError("[Lku8.l] Fallback completion error", t);
                                    }
                                };
                                mainHandler.postDelayed(watchdogHolder[0], 20000L);
                            } catch (Throwable t) {
                                logError("[Lku8.l] Hook error", t);
                            }
                        }
                    });
                }
                logInfo("Hooked Lku8.q(int) and Lku8.l(boolean) for AGPS progress & completion");
            }

            // 6. Hook Ls6.f(Lxp, int, byte[]) on Endpoint 0x42 to observe watch AGPS install status
            Class<?> xpClass = findClassSafe(cl, "xp");
            if (s6Class != null && xpClass != null && hasMethod(s6Class, "f", xpClass, int.class, byte[].class)) {
                HookBridge.findAndHookMethod(s6Class, "f", xpClass, int.class, byte[].class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAgpsFixEnabled(cl)) return;
                        try {
                            byte[] data = (byte[]) param.args[2];
                            if (data == null || data.length == 0) return;
                            int cmd = data[0] & 0xFF;
                            logInfo("[Ls6.f] Endpoint 0x42 packet: cmd=0x" + Integer.toHexString(cmd) + ", hex=" + bytesToHex(data));
                            if (cmd == 0x06 && data.length >= 9) {
                                sLastAgpsFlashProgressMs = System.currentTimeMillis();
                                int totalBytes = (data[1] & 0xFF)
                                        | ((data[2] & 0xFF) << 8)
                                        | ((data[3] & 0xFF) << 16)
                                        | ((data[4] & 0xFF) << 24);
                                int installedBytes = (data[5] & 0xFF)
                                        | ((data[6] & 0xFF) << 8)
                                        | ((data[7] & 0xFF) << 16)
                                        | ((data[8] & 0xFF) << 24);
                                int pct = totalBytes > 0 ? Math.max(1, Math.min(99, (int) ((installedBytes * 100L) / totalBytes))) : 99;
                                notifyAgpsProgress(sAppContext, cl, pct, "⏳ 手錶正在驗證並寫入 AGPS 星曆 (" + pct + "%)...", false, true);
                            } else if (cmd == 0x07) {
                                int status = data.length > 1 ? (data[1] & 0xFF) : 1;
                                sLastAgpsInstallStatus = status;
                                logInfo("[Ls6.f] Watch Endpoint 0x42 reported AGPS install finished! status=0x" + Integer.toHexString(status));
                                if (status == 1) {
                                    recordAgpsUpdateSuccess(sAppContext, cl);
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                        queryWatchAgpsConfigOnEndpoint0A(cl);
                                    }, 1000L);
                                } else {
                                    sLastCompletedAgpsTransferMs = System.currentTimeMillis();
                                    notifyAgpsProgress(sAppContext, cl, 0,
                                            "❌ 手錶拒絕 AGPS 檔案 (status=0x" + Integer.toHexString(status) + ")，請確認封裝格式與手錶相容",
                                            true, false);
                                    broadcastFirmwareKo(sAppContext, cl);
                                    logInfo("[Ls6.f] Endpoint 0x42 rejected AGPS (status=0x" + Integer.toHexString(status) + "), querying Endpoint 0x0A capabilities");
                                    queryWatchAgpsConfigOnEndpoint0A(cl);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }

            // 7. Hook Lbn6.f [v24.1.1] / Lxm6.f [v24.0.3] (Lxp, int, byte[]) on Endpoint 0x0a to parse watch AGPS status response
            Class<?> xm6Class = findClassSafe(cl, "bn6", "xm6");
            if (xm6Class != null && xpClass != null && hasMethod(xm6Class, "f", xpClass, int.class, byte[].class)) {
                HookBridge.findAndHookMethod(xm6Class, "f", xpClass, int.class, byte[].class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            byte[] data = (byte[]) param.args[2];
                            if (data != null && data.length >= 4 && data[0] == 0x04 && data[1] == 0x01 && data[2] == 0x00) {
                                logInfo("[" + xm6Class.getSimpleName() + ".f] Endpoint 0x0A AGPS Config Response (group=0x00): " + bytesToHex(data));
                                parseWatchEndpoint0AAgpsTimestamps(data, cl);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                logInfo("Hooked Endpoint 0x0A AGPS config handler: " + xm6Class.getName() + ".f");
            }
        } catch (Throwable t) {
            logError("hookAgpsFixPipeline failed", t);
        }
    }

    private static void parseWatchEndpoint0AAgpsTimestamps(byte[] data, ClassLoader cl) {
        if (data == null || data.length < 9) return;
        try {
            long watchCapMask = 0L;
            long watchUpdMs = 0L;
            long watchExpMs = 0L;
            for (int i = 3; i + 5 < data.length; i++) {
                int tag = data[i] & 0xFF;
                int sub = data[i + 1] & 0xFF;
                if (tag == 0x08 && sub == 0x50 && i + 5 < data.length) {
                    watchCapMask = ((long) (data[i + 2] & 0xFF))
                            | (((long) (data[i + 3] & 0xFF)) << 8)
                            | (((long) (data[i + 4] & 0xFF)) << 16)
                            | (((long) (data[i + 5] & 0xFF)) << 24);
                } else if ((tag == 0x09 || tag == 0x0A) && sub == 0x40 && i + 9 < data.length) {
                    long val = 0L;
                    for (int b = 0; b < 8; b++) {
                        val |= ((long) (data[i + 2 + b] & 0xFF)) << (b * 8);
                    }
                    if (val > 1700000000000L && val < 2500000000000L) {
                        if (tag == 0x09) watchUpdMs = val;
                        if (tag == 0x0A) watchExpMs = val;
                    }
                }
            }
            if (watchCapMask != 0L) {
                sWatchAgpsCapMask = watchCapMask;
                boolean lto = (watchCapMask & (1L << 9)) != 0L;
                boolean epo = (watchCapMask & (0x7L << 10)) != 0L;
                boolean elpo = (watchCapMask & (0xFL << 16)) != 0L;
                logInfo("[AGPS-0x0A] Watch AGPS capability bitmask=0x" + Long.toHexString(watchCapMask)
                        + " (LTO_0x8B=" + lto + ", EPO_0x8C_8E=" + epo + ", ELPO_0x95_98=" + elpo + ")");
                if (!lto && epo && (sAgpsFormatMode == 3 || sAgpsFormatMode == 4)) {
                    sAgpsFormatMode = 2;
                    saveModuleConfigToNotifyPrefs(cl);
                    logInfo("[AGPS-0x0A] Auto-switched AGPS mode -> 2 (EPO), because watch does not support LTO (0x8B)");
                }
            }
            if (watchUpdMs > 0L || watchExpMs > 0L) {
                logInfo("[AGPS-0x0A] Watch hardware reported AGPS timestamps: updateMs=" + watchUpdMs
                        + " (" + formatTimestampDisplay(watchUpdMs) + "), expireMs=" + watchExpMs
                        + " (" + formatTimestampDisplay(watchExpMs) + ")");
                boolean changed = false;
                if (watchUpdMs > 0L && (sAgpsLastUpdateMs <= 0L || watchUpdMs >= sAgpsLastUpdateMs
                        || (System.currentTimeMillis() - sLastCompletedAgpsTransferMs > 180_000L))) {
                    sAgpsLastUpdateMs = watchUpdMs;
                    changed = true;
                }
                if (watchExpMs > 0L && (sAgpsExpireMs <= 0L || watchUpdMs >= sAgpsLastUpdateMs
                        || (System.currentTimeMillis() - sLastCompletedAgpsTransferMs > 180_000L))) {
                    sAgpsExpireMs = watchExpMs;
                    changed = true;
                }
                if (changed) {
                    saveModuleConfigToNotifyPrefs(cl);
                    notifyAgpsProgress(sAppContext, cl, -1, null, false, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static void broadcastFirmwareKo(Context ctx, ClassLoader cl) {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return;
        try {
            Class<?> v38Class = findClassSafe(cl, "v38");
            if (v38Class != null && hasMethod(v38Class, "h2", Context.class, String.class)) {
                HookBridge.callStaticMethod(v38Class, "h2", ctx.getApplicationContext(), ACTION_NOTIFY_FW_KO_UUID);
                return;
            }
            Class<?> w38Class = findClassSafe(cl, "w38", "e48", "b48");
            if (w38Class != null && hasMethod(w38Class, "s2", Context.class, String.class)) {
                HookBridge.callStaticMethod(w38Class, "s2", ctx.getApplicationContext(), "com.mc.miband.firmwareKO");
            } else {
                Intent ko = new Intent(ACTION_NOTIFY_FW_KO_UUID);
                ko.setPackage(TARGET_PACKAGE);
                ctx.sendBroadcast(ko);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void notifyAgpsProgress(Context ctx, ClassLoader cl, int pct, String msg, boolean done, boolean success) {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return;
        try {
            // 1. Broadcast Notify's native progress intent so UpdateFirmwareActivity updates
            if (!done && pct >= 0) {
                Class<?> v38Class = findClassSafe(cl, "v38");
                if (v38Class != null && hasMethod(v38Class, "k0", String.class)
                        && hasMethod(v38Class, "g2", Context.class, Intent.class)) {
                    Intent progIntent = (Intent) HookBridge.callStaticMethod(v38Class, "k0", ACTION_NOTIFY_FW_PROGRESS_UUID);
                    progIntent.putExtra("progress", pct);
                    HookBridge.callStaticMethod(v38Class, "g2", ctx.getApplicationContext(), progIntent);
                } else {
                    Class<?> w38Class = findClassSafe(cl, "w38", "e48", "b48");
                    if (w38Class != null && hasMethod(w38Class, "r0", String.class)
                            && hasMethod(w38Class, "r2", Context.class, Intent.class)) {
                        Intent progIntent = (Intent) HookBridge.callStaticMethod(w38Class, "r0", "com.mc.miband.firmware.progress");
                        progIntent.putExtra("52dba2e7-52ff-4cfa-a5ac-e25c554ba544", pct);
                        HookBridge.callStaticMethod(w38Class, "r2", ctx.getApplicationContext(), progIntent);
                    }
                }
            }
            // 2. Broadcast internal progress to Notify UI process (BandSettingsActivity)
            Intent internal = new Intent(ACTION_AGPS_INTERNAL_PROGRESS);
            internal.setPackage(TARGET_PACKAGE);
            internal.putExtra("pct", pct);
            internal.putExtra("msg", msg);
            internal.putExtra("done", done);
            internal.putExtra("success", success);
            internal.putExtra(PREF_AGPS_LAST_UPDATE_MS, sAgpsLastUpdateMs);
            internal.putExtra(PREF_AGPS_EXPIRE_MS, sAgpsExpireMs);
            ctx.sendBroadcast(internal);

            // 3. Broadcast to MainActivity (com.amazfit.balance2fix)
            broadcastStatusToModuleApp(ctx, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void recordAgpsUpdateSuccess(Context ctx, ClassLoader cl) {
        if (ctx == null) ctx = sAppContext;
        long now = System.currentTimeMillis();
        sLastCompletedAgpsTransferMs = now;
        sAgpsLastUpdateMs = now;
        long validDays = (sAgpsFormatMode == 4) ? 2L : 7L;
        sAgpsExpireMs = now + validDays * 24L * 3600L * 1000L;

        saveModuleConfigToNotifyPrefs(cl);

        // Also update Notify's own Lyx7/Lux7 AGPS timestamp key ("22d907dc-6606-4aac-ba8d-d6219402818e")
        if (ctx != null && cl != null) {
            try {
                Class<?> ux7Class = findClassSafe(cl, "yx7", "ux7");
                if (ux7Class != null && hasMethod(ux7Class, "j", Context.class, String.class, long.class)) {
                    HookBridge.callStaticMethod(ux7Class, "j", ctx.getApplicationContext(), NOTIFY_AGPS_TIME_KEY, now);
                }
            } catch (Throwable ignored) {
            }
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        String doneMsg = "✅ AGPS 星曆更新成功！(" + sdf.format(new java.util.Date(now))
                + "，有效期至 " + sdf.format(new java.util.Date(sAgpsExpireMs)) + ")";
        logInfo("[AGPS] " + doneMsg);
        notifyAgpsProgress(ctx, cl, 100, doneMsg, true, true);
    }

    private static Object getBleManagerInstance(ClassLoader cl) {
        try {
            Class<?> bleMgrClass = findClassSafe(cl, "com.mc.miband1.bluetooth.BLEManager");
            if (bleMgrClass == null) return null;
            for (String candidate : new String[]{"W1", "U1", "S1", "T1", "R1"}) {
                try {
                    Field f = bleMgrClass.getDeclaredField(candidate);
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == bleMgrClass) {
                        f.setAccessible(true);
                        Object inst = f.get(null);
                        if (inst != null) return inst;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (Field f : bleMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == bleMgrClass) {
                    f.setAccessible(true);
                    Object inst = f.get(null);
                    if (inst != null) return inst;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static android.bluetooth.BluetoothGatt getBluetoothGattFromBleManager(Object bleMgr) {
        if (bleMgr == null) return null;
        try {
            Class<?> c = bleMgr.getClass();
            for (String candidate : new String[]{"S1", "Q1", "O1", "P1", "R1"}) {
                try {
                    Field f = c.getDeclaredField(candidate);
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && f.getType() == android.bluetooth.BluetoothGatt.class) {
                        f.setAccessible(true);
                        Object g = f.get(bleMgr);
                        if (g instanceof android.bluetooth.BluetoothGatt) return (android.bluetooth.BluetoothGatt) g;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType() == android.bluetooth.BluetoothGatt.class) {
                    f.setAccessible(true);
                    Object g = f.get(bleMgr);
                    if (g instanceof android.bluetooth.BluetoothGatt) return (android.bluetooth.BluetoothGatt) g;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void queryWatchAgpsConfigOnEndpoint0A(ClassLoader cl) {
        try {
            Class<?> bleMgrClass = findClassSafe(cl, "com.mc.miband1.bluetooth.BLEManager");
            Class<?> xm6Class = findClassSafe(cl, "bn6", "xm6");
            Class<?> nv0Class = findClassSafe(cl, "mv0", "nv0");
            if (bleMgrClass != null && xm6Class != null && nv0Class != null) {
                Object bleMgr = getBleManagerInstance(cl);
                Object nv0Agps = HookBridge.getStaticObjectField(nv0Class, "q");
                if (bleMgr != null && nv0Agps != null && hasMethod(xm6Class, "k", bleMgrClass, nv0Class)) {
                    HookBridge.callStaticMethod(xm6Class, "k", bleMgr, nv0Agps);
                    logInfo("[AGPS] Queried watch Endpoint 0x0A for AGPS config group (" + xm6Class.getSimpleName() + ".k / " + nv0Class.getSimpleName() + ".q)");
                }
            }
        } catch (Throwable t) {
            logError("[AGPS] queryWatchAgpsConfigOnEndpoint0A failed", t);
        }
    }

    private static boolean uihhMatchesDesiredMode(byte[] uihBytes, int desiredMode) {
        if (uihBytes == null || uihBytes.length < 42) return false;
        if (uihBytes[0] != 0x55 || uihBytes[1] != 0x49 || uihBytes[2] != 0x48 || uihBytes[3] != 0x48) {
            return false;
        }
        boolean hasLto = false;
        boolean hasEpo = false;
        int pos = 32;
        while (pos + 10 <= uihBytes.length) {
            if (uihBytes[pos] != 0x01) break;
            int tag = uihBytes[pos + 1] & 0xFF;
            int itemLen = (uihBytes[pos + 2] & 0xFF)
                    | ((uihBytes[pos + 3] & 0xFF) << 8)
                    | ((uihBytes[pos + 4] & 0xFF) << 16)
                    | ((uihBytes[pos + 5] & 0xFF) << 24);
            if (itemLen <= 0 || pos + 10 + itemLen > uihBytes.length) break;
            if (tag == 0x8B) hasLto = true;
            if (tag == 0x8C || tag == 0x8D || tag == 0x8E) hasEpo = true;
            pos += 10 + itemLen;
        }
        if (desiredMode == 2) {
            return hasEpo && !hasLto;
        }
        return hasLto;
    }

    /**
     * Reads cacheDir/agps.bin (or downloads fresh EPO/LTO zip if missing/stale/wrong format),
     * extracts EPO_*.DAT or lto*.brm, wraps in UIHH container (cacheDir/uih.bin), and returns it.
     */
    private static java.io.File prepareZeppOs6AgpsUihFile(Context ctx, ClassLoader cl) throws Exception {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return null;

        if (sWatchAgpsCapMask != 0L
                && ((sWatchAgpsCapMask & (1L << 9)) == 0L)
                && ((sWatchAgpsCapMask & (0x7L << 10)) != 0L)
                && (sAgpsFormatMode == 3 || sAgpsFormatMode == 4)) {
            sAgpsFormatMode = 2;
            saveModuleConfigToNotifyPrefs(cl);
            logInfo("[AGPS] Enforced EPO mode (2) per watch capability bitmask 0x" + Long.toHexString(sWatchAgpsCapMask));
        }

        java.io.File agpsBin = new java.io.File(ctx.getCacheDir(), "agps.bin");
        java.io.File uihBin = new java.io.File(ctx.getCacheDir(), "uih.bin");
        long maxCacheAgeMs = 6L * 3600L * 1000L;

        if (agpsBin.exists() && agpsBin.length() > 32
                && (System.currentTimeMillis() - agpsBin.lastModified() < maxCacheAgeMs)) {
            byte[] rawBytes = readAllBytes(agpsBin);
            if (rawBytes.length > 32) {
                // Case 1: Already a UIHH container ("UIHH" = 0x55, 0x49, 0x48, 0x48)
                if (rawBytes[0] == 0x55 && rawBytes[1] == 0x49 && rawBytes[2] == 0x48 && rawBytes[3] == 0x48) {
                    if (uihhMatchesDesiredMode(rawBytes, sAgpsFormatMode)) {
                        logInfo("[AGPS] Reusing valid UIHH cache (" + rawBytes.length + " bytes, mode=" + sAgpsFormatMode + ")");
                        writeAllBytes(uihBin, rawBytes);
                        return uihBin;
                    } else {
                        logInfo("[AGPS] Cached agps.bin UIHH does NOT match desired mode=" + sAgpsFormatMode + " (stale 0x8B LTO cache?) -> re-downloading fresh EPO/LTO");
                    }
                } else if (rawBytes[0] == 0x50 && rawBytes[1] == 0x4B && rawBytes[2] == 0x03 && rawBytes[3] == 0x04) {
                    // Case 2: ZIP archive ("PK\x03\x04")
                    byte[] packed = packAgpsZipBytesToUihh(cl, rawBytes, sAgpsFormatMode);
                    if (packed != null && packed.length > 32) {
                        writeAllBytes(uihBin, packed);
                        return uihBin;
                    }
                    logInfo("[AGPS] agps.bin ZIP did not contain matching EPO/LTO entries -> downloading fresh package");
                } else if (sAgpsFormatMode != 2) {
                    // Case 3: Raw .brm file (only valid for LTO modes, NOT EPO mode)
                    logInfo("[AGPS] agps.bin is raw BRM binary (" + rawBytes.length + " bytes) -> wrapping in UIHH (0x8B)");
                    java.util.LinkedHashMap<Byte, byte[]> map = new java.util.LinkedHashMap<>();
                    map.put((byte) 0x8B, rawBytes);
                    byte[] packed = buildUihhContainerBytes(cl, map);
                    if (packed != null && packed.length > 32) {
                        writeAllBytes(uihBin, packed);
                        return uihBin;
                    }
                }
            }
        }

        // Case 4: agps.bin missing, stale, or wrong format -> automatically download fresh EPO/LTO package from unlock.freemyband.com
        logInfo("[AGPS] Downloading fresh Zepp OS 6 AGPS package (mode=" + sAgpsFormatMode + ")...");
        return downloadAndPackAgpsUihFile(ctx, cl, sAgpsFormatMode);
    }

    private static java.io.File downloadAndPackAgpsUihFile(Context ctx, ClassLoader cl, int mode) throws Exception {
        int webVersion = (mode == 2) ? 2 : 3;
        String pageUrl = "https://unlock.freemyband.com/agps.html?v=" + webVersion;
        String html = new String(httpGetBytes(pageUrl, "https://unlock.freemyband.com/"), StandardCharsets.UTF_8);

        String zipRelPath = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=[\"']([^\"']+\\.zip)[\"']").matcher(html);
        if (m.find()) {
            zipRelPath = m.group(1);
        }
        if (zipRelPath == null || zipRelPath.isEmpty()) {
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yy-MM-dd", Locale.US);
            String datePart = df.format(new java.util.Date());
            zipRelPath = (webVersion == 2)
                    ? ("/huamifw/epo_" + datePart + ".zip")
                    : ("/huamifw/lto_" + datePart + ".zip");
        }
        String fullZipUrl = zipRelPath.startsWith("http")
                ? zipRelPath
                : ("https://unlock.freemyband.com" + (zipRelPath.startsWith("/") ? "" : "/") + zipRelPath);

        logInfo("[AGPS] Downloading AGPS ZIP from: " + fullZipUrl);
        byte[] zipBytes = httpGetBytes(fullZipUrl, pageUrl);
        logInfo("[AGPS] Downloaded AGPS ZIP: " + zipBytes.length + " bytes");

        byte[] uihBytes = packAgpsZipBytesToUihh(cl, zipBytes, mode);
        if (uihBytes == null || uihBytes.length <= 32) {
            throw new java.io.IOException("Failed to pack UIHH container from downloaded ZIP (" + fullZipUrl + ")");
        }

        java.io.File agpsBin = new java.io.File(ctx.getCacheDir(), "agps.bin");
        java.io.File uihBin = new java.io.File(ctx.getCacheDir(), "uih.bin");

        writeAllBytes(agpsBin, uihBytes);
        writeAllBytes(uihBin, uihBytes);
        logInfo("[AGPS] Wrote UIHH container (" + uihBytes.length + " bytes, mode=" + mode + ") to " + uihBin.getAbsolutePath());
        return uihBin;
    }

    private static byte[] packAgpsZipBytesToUihh(ClassLoader cl, byte[] zipBytes, int mode) throws Exception {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry ze;
            byte[] buf = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                String name = new java.io.File(ze.getName()).getName();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                entries.put(name, baos.toByteArray());
                zis.closeEntry();
            }
        }
        logInfo("[AGPS] Extracted ZIP entries: " + entries.keySet());

        java.util.LinkedHashMap<Byte, byte[]> epoItems = new java.util.LinkedHashMap<>();
        if (entries.containsKey("EPO_GR_3.DAT")) epoItems.put((byte) 0x8C, entries.get("EPO_GR_3.DAT"));
        if (entries.containsKey("EPO_GAL_7.DAT")) epoItems.put((byte) 0x8D, entries.get("EPO_GAL_7.DAT"));
        if (entries.containsKey("EPO_BDS_3.DAT")) epoItems.put((byte) 0x8E, entries.get("EPO_BDS_3.DAT"));

        if (mode == 2 && !epoItems.isEmpty()) {
            logInfo("[AGPS] Packing EPO DAT files (" + epoItems.size() + " files) with tags 0x8C/0x8D/0x8E into UIHH");
            return buildUihhContainerBytes(cl, epoItems);
        }

        // Check for LTO (.brm) files
        byte[] brmBytes = null;
        String chosenBrm = null;
        if (mode == 4 && entries.containsKey("lto2dv5.brm")) {
            brmBytes = entries.get("lto2dv5.brm");
            chosenBrm = "lto2dv5.brm";
        } else if (entries.containsKey("lto7dv5.brm")) {
            brmBytes = entries.get("lto7dv5.brm");
            chosenBrm = "lto7dv5.brm";
        } else if (entries.containsKey("lto2dv5.brm")) {
            brmBytes = entries.get("lto2dv5.brm");
            chosenBrm = "lto2dv5.brm";
        } else {
            for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (e.getKey().toLowerCase(Locale.US).endsWith(".brm")) {
                    brmBytes = e.getValue();
                    chosenBrm = e.getKey();
                    break;
                }
            }
        }

        if (mode != 2 && brmBytes != null && brmBytes.length > 0) {
            logInfo("[AGPS] Packing LTO BRM '" + chosenBrm + "' (" + brmBytes.length + " bytes) with tag 0x8B into UIHH");
            java.util.LinkedHashMap<Byte, byte[]> items = new java.util.LinkedHashMap<>();
            items.put((byte) 0x8B, brmBytes);
            return buildUihhContainerBytes(cl, items);
        }

        if (!epoItems.isEmpty()) {
            logInfo("[AGPS] Packing EPO DAT files (" + epoItems.size() + " files) with tags 0x8C/0x8D/0x8E into UIHH");
            return buildUihhContainerBytes(cl, epoItems);
        }

        return null;
    }

    /**
     * Builds the exact Huami/Zepp UIHH container (Lsx7) using Notify's Lqn0.c([B)I CRC32.
     */
    private static byte[] buildUihhContainerBytes(ClassLoader cl, java.util.Map<Byte, byte[]> items) throws Exception {
        Class<?> qn0Class = findClassSafe(cl, "qn0");
        java.io.ByteArrayOutputStream bodyStream = new java.io.ByteArrayOutputStream();
        for (java.util.Map.Entry<Byte, byte[]> entry : items.entrySet()) {
            byte tag = entry.getKey();
            byte[] payload = entry.getValue();
            int crc = (qn0Class != null)
                    ? (Integer) HookBridge.callStaticMethod(qn0Class, "c", (Object) payload)
                    : 0;
            java.nio.ByteBuffer itemHeader = java.nio.ByteBuffer.allocate(10).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            itemHeader.put((byte) 0x01);
            itemHeader.put(tag);
            itemHeader.putInt(payload.length);
            itemHeader.putInt(crc);
            bodyStream.write(itemHeader.array());
            bodyStream.write(payload);
        }
        byte[] bodyBytes = bodyStream.toByteArray();
        int bodyCrc = (qn0Class != null)
                ? (Integer) HookBridge.callStaticMethod(qn0Class, "c", (Object) bodyBytes)
                : 0;

        java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{0x55, 0x49, 0x48, 0x48}); // "UIHH"
        header.put(new byte[]{0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01});
        header.putInt(bodyCrc);
        header.put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        header.putInt(bodyBytes.length);
        header.put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        byte[] out = new byte[32 + bodyBytes.length];
        System.arraycopy(header.array(), 0, out, 0, 32);
        System.arraycopy(bodyBytes, 0, out, 32, bodyBytes.length);
        return out;
    }

    private static byte[] httpGetBytes(String urlStr, String referer) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new java.io.IOException("HTTP " + code + " for " + urlStr);
        }
        try (java.io.InputStream is = conn.getInputStream();
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAllBytes(java.io.File file) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static void writeAllBytes(java.io.File file, byte[] data) throws java.io.IOException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false)) {
            fos.write(data);
            fos.flush();
        }
    }

    private static void runOneClickAgpsUpdateInService(Context ctx, ClassLoader cl, int mode) {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return;
        try {
            String modeLabel = (mode == 2) ? "Zepp OS 6 EPO (0x8C/8D/8E)"
                    : (mode == 4 ? "2天快速 LTO (lto2dv5.brm)" : "7天完整 LTO (lto7dv5.brm)");
            notifyAgpsProgress(ctx, cl, 5, "🌐 正在下載最新 " + modeLabel + " 星曆檔案...", false, true);

            java.io.File uihFile = downloadAndPackAgpsUihFile(ctx, cl, mode);
            int kb = (int) (uihFile.length() / 1024L);
            notifyAgpsProgress(ctx, cl, 12, "📦 星曆封裝完成 (" + kb + " KB)，正在啟動手錶藍牙傳輸...", false, true);

            Class<?> genericFpClass = findClassSafe(cl, "com.mc.miband1.GenericFileProvider");
            android.net.Uri fileUri = (genericFpClass != null && hasMethod(genericFpClass, "c", Context.class, java.io.File.class))
                    ? (android.net.Uri) HookBridge.callStaticMethod(genericFpClass, "c", ctx.getApplicationContext(), uihFile)
                    : android.net.Uri.fromFile(uihFile);

            Class<?> v38Class = findClassSafe(cl, "v38");
            if (v38Class != null && hasMethod(v38Class, "k0", String.class)
                    && hasMethod(v38Class, "g2", Context.class, Intent.class)) {
                Intent agpsIntent = (Intent) HookBridge.callStaticMethod(v38Class, "k0", ACTION_NOTIFY_DO_AGPS_UUID);
                agpsIntent.putExtra("firmwareFile", fileUri);
                agpsIntent.putExtra("4436627f-408a-43f1-a12b-983c5b501c01", (mode == 2) ? 2 : 3);
                HookBridge.callStaticMethod(v38Class, "g2", ctx.getApplicationContext(), agpsIntent);
                logInfo("[OneClickAGPS] Dispatched AGPS update UUID via v38.g2 (uri=" + fileUri + ")");
            } else {
                Class<?> w38Class = findClassSafe(cl, "w38", "e48", "b48");
                if (w38Class != null && hasMethod(w38Class, "r0", String.class)
                        && hasMethod(w38Class, "r2", Context.class, Intent.class)) {
                    Intent agpsIntent = (Intent) HookBridge.callStaticMethod(w38Class, "r0", "com.mc.miband.doAgpsUpdate");
                    agpsIntent.putExtra("firmwareFile", fileUri);
                    agpsIntent.putExtra("4436627f-408a-43f1-a12b-983c5b501c01", (mode == 2) ? 2 : 3);
                    HookBridge.callStaticMethod(w38Class, "r2", ctx.getApplicationContext(), agpsIntent);
                    logInfo("[OneClickAGPS] Dispatched com.mc.miband.doAgpsUpdate via " + w38Class.getName() + ".r2 (uri=" + fileUri + ")");
                } else {
                    Intent fallback = new Intent(ACTION_NOTIFY_DO_AGPS_UUID);
                    fallback.setPackage(TARGET_PACKAGE);
                    fallback.putExtra("firmwareFile", fileUri);
                    fallback.putExtra("4436627f-408a-43f1-a12b-983c5b501c01", (mode == 2) ? 2 : 3);
                    ctx.sendBroadcast(fallback);
                    logInfo("[OneClickAGPS] Dispatched AGPS update UUID via standard broadcast");
                }
            }
        } catch (Throwable t) {
            logError("[OneClickAGPS] Failed to download/start AGPS update", t);
            notifyAgpsProgress(ctx, cl, 0, "❌ 下載或封裝 AGPS 失敗: " + t.getMessage(), true, false);
        }
    }

    // =========================================================================
    // 11. Xiaomi HyperOS Bluetooth Screen Unlock & AGPS Menu in BandSettingsActivity
    // =========================================================================
    private static final String UUID_SERVICE_FE95 = "0000fe95-0000-1000-8000-00805f9b34fb";
    private static final String UUID_SERVICE_FEE0 = "0000fee0-0000-1000-8000-00805f9b34fb";
    private static final String UUID_SERVICE_FEE1 = "0000fee1-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CHAR_FEDD_WRITE = "0000fedd-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CHAR_FEDE_READ = "0000fede-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CHAR_FF0F_PAIR = "0000ff0f-0000-1000-8000-00805f9b34fb";
    private static final String PREF_UNLOCK_RSSI_TYPE = "balance2_unlock_rssi_type"; // 1=Close(-55), 2=Medium(-70), 3=Far(-85)
    private static final String PREF_GATT_SUMMARY = "balance2_gatt_summary";
    private static final String PREF_GATT_HAS_FEE1 = "balance2_gatt_has_fee1";
    private static final String PREF_GATT_HAS_FEDD = "balance2_gatt_has_fedd";
    private static final String PREF_GATT_HAS_FEDE = "balance2_gatt_has_fede";
    private static final String PREF_GATT_HAS_FE95 = "balance2_gatt_has_fe95";
    private static final String PREF_GATT_HAS_FF0F = "balance2_gatt_has_ff0f";

    private void hookBandSettingsUnlockUI(final ClassLoader cl) {
        try {
            final Class<?> bandSettingsClass = findClassSafe(cl, "com.mc.miband1.ui.settings.BandSettingsActivity");
            if (bandSettingsClass == null) {
                logError("Could not find BandSettingsActivity", null);
                return;
            }

            HookBridge.findAndHookMethod(bandSettingsClass, "onCreate", android.os.Bundle.class, new HookBridge.MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final android.app.Activity activity = (android.app.Activity) param.thisObject;
                        sCurrentBandSettingsActivity = new java.lang.ref.WeakReference<>(activity);
                        loadModuleConfigFromPrefs(activity, cl);
                        injectAgpsSettingRow(activity, cl);
                        injectUnlockSettingRow(activity, cl);
                        // Request live GATT & MiBle dump from :NotifyService in background
                        Intent dumpIntent = new Intent(ACTION_DUMP_GATT);
                        dumpIntent.setPackage(TARGET_PACKAGE);
                        activity.sendBroadcast(dumpIntent);
                    } catch (Throwable t) {
                        logError("BandSettingsActivity.onCreate UI injection failed", t);
                    }
                }
            });

            if (hasMethod(bandSettingsClass, "onResume")) {
                HookBridge.findAndHookMethod(bandSettingsClass, "onResume", new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            final android.app.Activity activity = (android.app.Activity) param.thisObject;
                            sCurrentBandSettingsActivity = new java.lang.ref.WeakReference<>(activity);
                            loadModuleConfigFromPrefs(activity, cl);
                            updateAgpsRowSubtitle(activity, cl);
                            updateUnlockRowSubtitle(activity, cl);
                            // If already bound to HyperOS unlock and BT unlock switch is enabled, auto-sync RSSI threshold with IBluetoothMiBle
                            if (isBtUnlockEnabled(cl)) {
                                String mac = getWatchBleMac(activity, cl);
                                if (isWatchBoundToHyperOsUnlock(activity, mac)) {
                                    int rssi = getConfiguredUnlockRssi(activity, cl);
                                    connectAndControlMiBleService(activity.getApplicationContext(), mac, rssi, false, null);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } else {
                HookBridge.findAndHookMethod(android.app.Activity.class, "onResume", new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null && param.thisObject.getClass() == bandSettingsClass) {
                            try {
                                final android.app.Activity activity = (android.app.Activity) param.thisObject;
                                sCurrentBandSettingsActivity = new java.lang.ref.WeakReference<>(activity);
                                loadModuleConfigFromPrefs(activity, cl);
                                updateAgpsRowSubtitle(activity, cl);
                                updateUnlockRowSubtitle(activity, cl);
                                if (isBtUnlockEnabled(cl)) {
                                    String mac = getWatchBleMac(activity, cl);
                                    if (isWatchBoundToHyperOsUnlock(activity, mac)) {
                                        int rssi = getConfiguredUnlockRssi(activity, cl);
                                        connectAndControlMiBleService(activity.getApplicationContext(), mac, rssi, false, null);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            }

            logInfo("Hooked BandSettingsActivity for AGPS Update & Xiaomi HyperOS Bluetooth Unlock menus");
        } catch (Throwable t) {
            logError("hookBandSettingsUnlockUI failed", t);
        }
    }

    private static void refreshBandSettingsRowsOnUiThread(final ClassLoader cl) {
        try {
            java.lang.ref.WeakReference<android.app.Activity> ref = sCurrentBandSettingsActivity;
            final android.app.Activity activity = (ref != null) ? ref.get() : null;
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                try {
                    injectAgpsSettingRow(activity, cl);
                    injectUnlockSettingRow(activity, cl);
                    updateAgpsRowSubtitle(activity, cl);
                    updateUnlockRowSubtitle(activity, cl);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void updateCustomAgpsProgressDialogOnUiThread(
            final ClassLoader cl, final int pct, final String msg, final boolean done, final boolean success) {
        try {
            java.lang.ref.WeakReference<android.app.Activity> ref = sCurrentBandSettingsActivity;
            final android.app.Activity activity = (ref != null) ? ref.get() : null;
            if (activity == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                try {
                    android.app.ProgressDialog pd = sCustomAgpsProgressDialog;
                    if (pd != null && pd.isShowing()) {
                        if (msg != null && !msg.isEmpty()) {
                            pd.setMessage(msg + (pct >= 0 ? " (" + pct + "%)" : ""));
                        }
                        if (done) {
                            try {
                                pd.dismiss();
                            } catch (Throwable ignored) {
                            }
                            sCustomAgpsProgressDialog = null;
                            android.widget.Toast.makeText(activity,
                                    msg != null ? msg : (success ? "✅ AGPS 更新完成" : "❌ AGPS 更新失敗"),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                    updateAgpsRowSubtitle(activity, cl);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String formatTimestampDisplay(long tsMs) {
        if (tsMs <= 0L) return "尚未記錄";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new java.util.Date(tsMs));
    }

    private static String formatExpireDisplay(long expMs) {
        if (expMs <= 0L) return "未知 (更新後自動計算)";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        long diffMs = expMs - System.currentTimeMillis();
        if (diffMs > 0L) {
            long days = diffMs / (24L * 3600L * 1000L);
            long hours = (diffMs % (24L * 3600L * 1000L)) / (3600L * 1000L);
            return sdf.format(new java.util.Date(expMs)) + " (剩餘 " + days + " 天 " + hours + " 小時)";
        } else {
            long overHours = (-diffMs) / (3600L * 1000L);
            return "⚠️ 已過期 (" + sdf.format(new java.util.Date(expMs)) + "，已過期 " + overHours + " 小時)";
        }
    }

    private static android.view.View findViewByResNameOrFallback(android.app.Activity activity, String resName, int... fallbackIds) {
        if (activity == null) return null;
        try {
            int id = activity.getResources().getIdentifier(resName, "id", activity.getPackageName());
            if (id != 0) {
                android.view.View v = activity.findViewById(id);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {
        }
        for (int fid : fallbackIds) {
            android.view.View v = activity.findViewById(fid);
            if (v != null) return v;
        }
        return null;
    }

    private static void injectAgpsSettingRow(final android.app.Activity activity, final ClassLoader cl) {
        android.view.View weatherRow = findViewByResNameOrFallback(activity, "relativeWeather", 0x7f0a0da0, 0x7f0a0d94);
        android.view.ViewGroup parentGroup = null;
        int insertIndex = 0;

        if (weatherRow != null && weatherRow.getParent() instanceof android.view.ViewGroup) {
            parentGroup = (android.view.ViewGroup) weatherRow.getParent();
            insertIndex = parentGroup.indexOfChild(weatherRow);
            if (insertIndex < 0) insertIndex = 0;
        } else {
            android.view.View content = activity.findViewById(android.R.id.content);
            parentGroup = findFirstScrollableLinearLayout(content);
        }

        if (parentGroup == null) {
            return;
        }

        if (parentGroup.findViewWithTag("balance2_agps_update_row") != null) {
            updateAgpsRowSubtitle(activity, cl);
            return;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density + 0.5f);
        int padV = (int) (14 * density + 0.5f);

        int titleColor = 0xFFFFFFFF;
        int subColor = 0xFFB0BEC5;
        android.view.View existingSub = findViewByResNameOrFallback(activity, "textViewWeatherHint", 0x7f0a1535, 0x7f0a1514);
        if (existingSub instanceof android.widget.TextView) {
            subColor = ((android.widget.TextView) existingSub).getCurrentTextColor();
        }
        if (weatherRow instanceof android.view.ViewGroup) {
            android.widget.TextView firstTv = findFirstTextView((android.view.ViewGroup) weatherRow, existingSub);
            if (firstTv != null) {
                titleColor = firstTv.getCurrentTextColor();
            }
        }

        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setTag("balance2_agps_update_row");
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);

        android.util.TypedValue outValue = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            row.setBackgroundResource(outValue.resourceId);
        }

        android.widget.TextView titleTv = new android.widget.TextView(activity);
        titleTv.setText("更新 AGPS 衛星定位星曆 (Zepp OS 6)");
        titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        titleTv.setTextColor(titleColor);
        titleTv.setTypeface(titleTv.getTypeface(), android.graphics.Typeface.BOLD);

        android.widget.TextView subTv = new android.widget.TextView(activity);
        subTv.setTag("balance2_agps_update_sub");
        subTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        subTv.setTextColor(subColor);
        subTv.setPadding(0, (int) (4 * density + 0.5f), 0, 0);

        row.addView(titleTv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(subTv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> showAgpsUpdateDialog(activity, cl));

        android.view.View divider = new android.view.View(activity);
        divider.setTag("balance2_agps_update_divider");
        divider.setBackgroundColor(0x22888888);
        android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) (1 * density)));

        parentGroup.addView(row, insertIndex, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        parentGroup.addView(divider, insertIndex + 1, divLp);

        updateAgpsRowSubtitle(activity, cl);
        logInfo("Injected '更新 AGPS 衛星定位星曆 (Zepp OS 6)' row into BandSettingsActivity at index " + insertIndex);
    }

    private static void updateAgpsRowSubtitle(final android.app.Activity activity, final ClassLoader cl) {
        try {
            android.view.View root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            android.view.View rowView = root.findViewWithTag("balance2_agps_update_row");
            android.view.View divView = root.findViewWithTag("balance2_agps_update_divider");
            boolean enabled = isAgpsFixEnabled(cl);
            if (rowView != null) {
                rowView.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            if (divView != null) {
                divView.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            if (!enabled) return;

            android.view.View subView = root.findViewWithTag("balance2_agps_update_sub");
            if (!(subView instanceof android.widget.TextView)) return;
            android.widget.TextView subTv = (android.widget.TextView) subView;

            loadModuleConfigFromPrefs(activity, cl);

            String modeStr = (sAgpsFormatMode == 2) ? "EPO (0x8C/0x8D/0x8E - Balance 2 支援)"
                    : (sAgpsFormatMode == 4 ? "2 天快速 LTO (0x8B)" : "7 天完整 LTO (0x8B)");

            StringBuilder sb = new StringBuilder();
            sb.append("上次成功更新：").append(formatTimestampDisplay(sAgpsLastUpdateMs)).append("\n");
            sb.append("AGPS 過期時間：").append(formatExpireDisplay(sAgpsExpireMs)).append("\n");
            sb.append("封裝格式：").append(modeStr).append(" | 點擊立即更新或切換設定");

            subTv.setText(sb.toString());
        } catch (Throwable t) {
            logError("updateAgpsRowSubtitle failed", t);
        }
    }

    private static void startOneClickAgpsWithProgressDialog(
            final android.app.Activity activity, final ClassLoader cl, final int mode) {
        sAgpsFormatMode = mode;
        saveModuleConfigToNotifyPrefs(cl);
        updateAgpsRowSubtitle(activity, cl);

        try {
            if (sCustomAgpsProgressDialog != null && sCustomAgpsProgressDialog.isShowing()) {
                sCustomAgpsProgressDialog.dismiss();
            }
            android.app.ProgressDialog pd = new android.app.ProgressDialog(activity);
            pd.setTitle("正在更新 Balance 2 AGPS 星曆");
            pd.setMessage("正在下載並打包 Zepp OS 6 UIHH 星曆檔案...");
            pd.setIndeterminate(false);
            pd.setCancelable(true);
            pd.show();
            sCustomAgpsProgressDialog = pd;
        } catch (Throwable ignored) {
        }

        Intent intent = new Intent(MainActivity.ACTION_ONE_CLICK_AGPS);
        intent.setPackage(TARGET_PACKAGE);
        intent.putExtra(MainActivity.KEY_AGPS_FORMAT, mode);
        activity.sendBroadcast(intent);
    }

    private static void showAgpsUpdateDialog(final android.app.Activity activity, final ClassLoader cl) {
        loadModuleConfigFromPrefs(activity, cl);

        String modeLabel = (sAgpsFormatMode == 2) ? "EPO 封裝 (EPO_GR_3/GAL_7/BDS_3.DAT, ~171 KB)"
                : (sAgpsFormatMode == 4 ? "2 天快速 LTO (lto2dv5.brm, 0x8B)" : "7 天完整 LTO (lto7dv5.brm, 0x8B)");

        final String[] items = new String[]{
                "1. 🚀 立即一鍵更新 AGPS (EPO 星曆 - Balance 2 相容推薦)",
                "2. ⚡ 嘗試一鍵更新 LTO 7 天格式 (lto7dv5.brm)",
                "3. 📦 使用原版選單頁面更新 (開啟 Notify 韌體/AGPS 更新頁)",
                "4. 🎛️ 開啟 Balance 2 模組設定中心 (管理 天氣/AGPS/藍牙解鎖 三大開關)"
        };

        String title = "更新 AGPS 衛星定位星曆\n"
                + "上次更新：" + formatTimestampDisplay(sAgpsLastUpdateMs) + "\n"
                + "過期時間：" + formatExpireDisplay(sAgpsExpireMs);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
        builder.setTitle("更新 AGPS 星曆 (Zepp OS 6)");
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                startOneClickAgpsWithProgressDialog(activity, cl, 2);
            } else if (which == 1) {
                startOneClickAgpsWithProgressDialog(activity, cl, 3);
            } else if (which == 2) {
                try {
                    Class<?> updateFwClass = findClassSafe(cl, "com.mc.miband1.ui.updateFirmware.UpdateFirmwareActivity");
                    if (updateFwClass != null) {
                        Intent fwIntent = new Intent(activity, updateFwClass);
                        activity.startActivity(fwIntent);
                    }
                } catch (Throwable t) {
                    android.widget.Toast.makeText(activity, "無法開啟更新頁: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            } else if (which == 3) {
                try {
                    Intent modIntent = new Intent();
                    modIntent.setClassName("com.amazfit.balance2fix", "com.amazfit.balance2fix.MainActivity");
                    modIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(modIntent);
                } catch (Throwable t) {
                    android.widget.Toast.makeText(activity, "無法開啟模組設定頁: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNeutralButton("查看詳細狀態", (d, w) -> {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Balance 2 (Zepp OS 6) AGPS 狀態與原理")
                    .setMessage("【目前 AGPS 記錄】\n"
                            + "• 上次成功更新：" + formatTimestampDisplay(sAgpsLastUpdateMs) + "\n"
                            + "• AGPS 過期時間：" + formatExpireDisplay(sAgpsExpireMs) + "\n"
                            + "• 預設封裝格式：" + modeLabel + "\n\n"
                            + "【修正內容說明】\n"
                            + "1. 繞過原版 BLEManager.C1 的每包 600ms 強制延遲 (改用 mode=2 + WRITE_TYPE_NO_RESPONSE + HIGH 連線優先級)，將數分鐘縮短至數秒內。\n"
                            + "2. 依據 Balance 2 Endpoint 0x0A 硬體支援位元遮罩 (0x000F1C00: 支援 EPO 0x8C/8D/8E 與 ELPO，不支援 LTO 0x8B)，打包 EPO_GR_3/GAL_7/BDS_3 為 UIHH 容器並由 Notify 傳輸。")
                    .setPositiveButton("立即更新 (EPO)", (d2, w2) -> startOneClickAgpsWithProgressDialog(activity, cl, 2))
                    .setNegativeButton("返回", null)
                    .show();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private static void injectUnlockSettingRow(final android.app.Activity activity, final ClassLoader cl) {
        // id/relativeWeather is the top Weather setting row in BandSettingsActivity (0x7f0a0da0 in v24.1.1, 0x7f0a0d94 in v24.0.3)
        android.view.View weatherRow = findViewByResNameOrFallback(activity, "relativeWeather", 0x7f0a0da0, 0x7f0a0d94);
        android.view.ViewGroup parentGroup = null;
        int insertIndex = 0;

        if (weatherRow != null && weatherRow.getParent() instanceof android.view.ViewGroup) {
            parentGroup = (android.view.ViewGroup) weatherRow.getParent();
            insertIndex = parentGroup.indexOfChild(weatherRow);
            if (insertIndex < 0) insertIndex = 0;
        } else {
            android.view.View content = activity.findViewById(android.R.id.content);
            parentGroup = findFirstScrollableLinearLayout(content);
        }

        if (parentGroup == null) {
            logError("Could not find parent LinearLayout in BandSettingsActivity", null);
            return;
        }

        // Avoid duplicate injection
        if (parentGroup.findViewWithTag("balance2_hyperos_unlock_row") != null) {
            updateUnlockRowSubtitle(activity, cl);
            return;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density + 0.5f);
        int padV = (int) (14 * density + 0.5f);

        // Extract theme text colors from existing row if possible
        int titleColor = 0xFFFFFFFF;
        int subColor = 0xFFB0BEC5;
        android.view.View existingSub = findViewByResNameOrFallback(activity, "textViewWeatherHint", 0x7f0a1535, 0x7f0a1514);
        if (existingSub instanceof android.widget.TextView) {
            subColor = ((android.widget.TextView) existingSub).getCurrentTextColor();
        }
        if (weatherRow instanceof android.view.ViewGroup) {
            android.widget.TextView firstTv = findFirstTextView((android.view.ViewGroup) weatherRow, existingSub);
            if (firstTv != null) {
                titleColor = firstTv.getCurrentTextColor();
            }
        }

        android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
        row.setTag("balance2_hyperos_unlock_row");
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);

        // Apply ripple / selectable item background
        android.util.TypedValue outValue = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            row.setBackgroundResource(outValue.resourceId);
        }

        android.widget.TextView titleTv = new android.widget.TextView(activity);
        titleTv.setText("小米 HyperOS 藍牙螢幕解鎖 (Balance 2)");
        titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        titleTv.setTextColor(titleColor);
        titleTv.setTypeface(titleTv.getTypeface(), android.graphics.Typeface.BOLD);

        android.widget.TextView subTv = new android.widget.TextView(activity);
        subTv.setTag("balance2_hyperos_unlock_sub");
        subTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        subTv.setTextColor(subColor);
        subTv.setPadding(0, (int) (4 * density + 0.5f), 0, 0);

        row.addView(titleTv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(subTv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> showBluetoothUnlockDialog(activity, cl));

        // Divider line matching Notify's "line" tag
        android.view.View divider = new android.view.View(activity);
        divider.setTag("balance2_hyperos_unlock_divider");
        divider.setBackgroundColor(0x22888888);
        android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) (1 * density)));

        parentGroup.addView(row, insertIndex, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        parentGroup.addView(divider, insertIndex + 1, divLp);

        updateUnlockRowSubtitle(activity, cl);
        logInfo("Injected '小米 HyperOS 藍牙螢幕解鎖 (Balance 2)' row into BandSettingsActivity at index " + insertIndex);
    }

    private static void updateUnlockRowSubtitle(final android.app.Activity activity, final ClassLoader cl) {
        try {
            android.view.View root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            android.view.View rowView = root.findViewWithTag("balance2_hyperos_unlock_row");
            android.view.View divView = root.findViewWithTag("balance2_hyperos_unlock_divider");
            boolean enabled = isBtUnlockEnabled(cl);
            if (rowView != null) {
                rowView.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            if (divView != null) {
                divView.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            if (!enabled) return;

            android.view.View subView = root.findViewWithTag("balance2_hyperos_unlock_sub");
            if (!(subView instanceof android.widget.TextView)) return;
            android.widget.TextView subTv = (android.widget.TextView) subView;

            String watchMac = getWatchBleMac(activity, cl);
            boolean keyguardSecure = isKeyguardSecure(activity);
            int sysStatus = getSystemUnlockStatus(activity);
            String sysMac = getSystemUnlockAddress(activity);
            boolean isBoundThisWatch = (sysStatus == 1 && watchMac != null && !watchMac.isEmpty() && watchMac.equalsIgnoreCase(sysMac));

            int rssiType = getConfiguredUnlockRssiType(activity, cl);
            String distStr = (rssiType == 1) ? "近 (-55dBm)" : (rssiType == 3 ? "遠 (-85dBm)" : "適中 (-70dBm)");

            SharedPreferences sp = getNotifySharedPrefs(cl);
            String gattFlag = "尚未掃描";
            if (sp != null && (sp.contains(PREF_GATT_HAS_FEE1) || sp.contains(PREF_GATT_HAS_FE95))) {
                boolean hasFee1 = sp.getBoolean(PREF_GATT_HAS_FEE1, true);
                boolean hasFedd = sp.getBoolean(PREF_GATT_HAS_FEDD, true);
                boolean hasFede = sp.getBoolean(PREF_GATT_HAS_FEDE, true);
                gattFlag = (hasFee1 && hasFedd && hasFede) ? "✅ FEE1(FEDD/FEDE) 支援" : "FEE1=" + hasFee1;
            }

            StringBuilder sb = new StringBuilder();
            if (!keyguardSecure) {
                sb.append("狀態：手機尚未設定螢幕鎖定密碼\n");
            } else if (isBoundThisWatch) {
                sb.append("狀態：✅ 已啟用並綁定 Balance 2 (").append(watchMac).append(")\n");
            } else if (sysStatus == 1 && sysMac != null && !sysMac.isEmpty()) {
                sb.append("狀態：⚠️ 系統目前已綁定其他裝置 (").append(sysMac).append(")\n");
            } else {
                sb.append("狀態：未啟用 (點擊以綁定 Balance 2 解鎖手機)\n");
            }
            sb.append("手錶 BLE MAC：").append(watchMac != null ? watchMac : "未知")
              .append(" | 距離：").append(distStr)
              .append(" | GATT：").append(gattFlag);

            subTv.setText(sb.toString());
        } catch (Throwable t) {
            logError("updateUnlockRowSubtitle failed", t);
        }
    }

    private static void showBluetoothUnlockDialog(final android.app.Activity activity, final ClassLoader cl) {
        final String watchMac = getWatchBleMac(activity, cl);
        final String watchName = getWatchName(activity, cl);
        final int sysStatus = getSystemUnlockStatus(activity);
        final String sysMac = getSystemUnlockAddress(activity);
        final String sysName = getSystemUnlockName(activity);
        final int rssiType = getConfiguredUnlockRssiType(activity, cl);
        final String distLabel = (rssiType == 1) ? "近 (-55 dBm)" : (rssiType == 3 ? "遠 (-85 dBm)" : "適中 (-70 dBm)");

        SharedPreferences sp = getNotifySharedPrefs(cl);
        boolean hasFee1 = sp == null || sp.getBoolean(PREF_GATT_HAS_FEE1, true);
        boolean hasFedd = sp == null || sp.getBoolean(PREF_GATT_HAS_FEDD, true);
        boolean hasFede = sp == null || sp.getBoolean(PREF_GATT_HAS_FEDE, true);

        String header = "手錶名稱：" + (watchName != null ? watchName : "Amazfit Balance 2")
                + "\n手錶 BLE MAC：" + (watchMac != null ? watchMac : "未知")
                + "\n系統解鎖狀態：" + (sysStatus == 1 ? "✅ 已開啟 (status=1)" : "未開啟 (status=" + sysStatus + ")")
                + "\n系統綁定 MAC：" + (sysMac != null && !sysMac.isEmpty() ? sysMac : "無")
                + (sysName != null && !sysName.isEmpty() ? " (" + sysName + ")" : "")
                + "\n目前解鎖距離：" + distLabel
                + "\n韌體解鎖 GATT：FEE1=" + (hasFee1 ? "✅有" : "❌無")
                + ", FEDD(寫)=" + (hasFedd ? "✅有" : "❌無")
                + ", FEDE(讀)=" + (hasFede ? "✅有" : "❌無");

        final String[] items = new String[]{
                "1. 🚀 綁定 Balance 2 為小米藍牙解鎖裝置 (帶入 BLE MAC: " + (watchMac != null ? watchMac : "") + ")",
                "2. ⚙️ 開啟小米系統「藍牙設備解鎖」管理頁面 (檢視/切換開關)",
                "3. 📏 設定解鎖距離門檻 (目前：" + distLabel + ")",
                "4. 🔍 檢視 Balance 2 實機 BLE GATT 服務表與解鎖特徵報告",
                "5. 🔒 開啟小米系統密碼與安全性設定 (備用入口)"
        };

        android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density + 0.5f);

        android.widget.TextView infoTv = new android.widget.TextView(activity);
        infoTv.setText(header);
        infoTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        infoTv.setPadding(pad, (int) (12 * density), pad, (int) (8 * density));
        container.addView(infoTv);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
        builder.setTitle("小米 HyperOS 藍牙螢幕解鎖 (Balance 2)");
        builder.setCustomTitle(null);
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                // 1. Launch MiuiSecurityBluetoothSettings with BLE MAC in extra.NAME (opens MiuiSecurityBluetoothMatchDeviceFragment directly)
                Intent pairIntent = new Intent(ACTION_PAIR_UNLOCK_CHAR);
                pairIntent.setPackage(TARGET_PACKAGE);
                activity.sendBroadcast(pairIntent);
                launchMiuiSecurityBluetoothSettings(activity, watchMac);
            } else if (which == 1) {
                // 2. Open MiuiSecurityBluetoothSettingsFragment management screen
                try {
                    Intent mgmtIntent = new Intent("android.intent.action.MAIN");
                    mgmtIntent.setClassName("com.android.settings", "com.android.settings.MiuiSecurityBluetoothSettings");
                    mgmtIntent.putExtra(":android:show_fragment", "com.android.settings.MiuiSecurityBluetoothSettingsFragment");
                    activity.startActivity(mgmtIntent);
                } catch (Throwable t) {
                    android.widget.Toast.makeText(activity, "無法開啟藍牙解鎖管理頁: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            } else if (which == 2) {
                // 3. Distance threshold selector
                showDistanceSelectionDialog(activity, cl, watchMac);
            } else if (which == 3) {
                // 4. Full GATT & MiBle diagnostic report
                showGattDiagnosticDialog(activity, cl, watchMac);
            } else if (which == 4) {
                // 5. Fallback Security Settings
                try {
                    Intent secIntent = new Intent("android.settings.SECURITY_SETTINGS");
                    activity.startActivity(secIntent);
                } catch (Throwable t) {
                    android.widget.Toast.makeText(activity, "無法開啟安全性設定: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNeutralButton("狀態說明", (d, w) -> {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Balance 2 藍牙解鎖技術說明")
                    .setMessage(header + "\n\n【逆向分析結論】\n"
                            + "1. 經逆向小米 HyperOS 的 MiuiBluetooth.apk (LD0/A UnlockProperty) 與實機掃描 Balance 2 的 BLE GATT 表，證實 Balance 2 韌體完整保留了小米/華米藍牙解鎖專屬的 0xFEE1 服務與 0xFEDD (寫入挑戰金鑰) / 0xFEDE (讀取加密回應) 特徵值！\n"
                            + "2. Zepp App 僅因在產品設定檔中隱藏了 Balance 2 的入口，且小米設定若不帶入 'android.bluetooth.device.extra.NAME' 無法直接掃描已連線的 BLE 手錶。\n"
                            + "3. 點擊選項 1 即會按照 Zepp 原生 ScreenUnlockActivity (Lxtt.h) 的方式將手錶 BLE MAC (" + watchMac + ") 傳入 MiuiSecurityBluetoothMatchDeviceFragment 進行金鑰配對綁定。")
                    .setPositiveButton("返回選單", (d2, w2) -> showBluetoothUnlockDialog(activity, cl))
                    .show();
        });
        builder.setNegativeButton("關閉", null);
        builder.show();
    }

    private static void showDistanceSelectionDialog(final android.app.Activity activity, final ClassLoader cl, final String watchMac) {
        final String[] distOptions = new String[]{
                "近 (Close: -55 dBm)",
                "適中 (Medium: -70 dBm) [預設]",
                "遠 (Farther: -85 dBm)"
        };
        int currentType = getConfiguredUnlockRssiType(activity, cl);
        int checkedItem = Math.max(0, Math.min(2, currentType - 1));

        new android.app.AlertDialog.Builder(activity)
                .setTitle("設定藍牙解鎖距離門檻")
                .setSingleChoiceItems(distOptions, checkedItem, (dialog, which) -> {
                    int newType = which + 1;
                    SharedPreferences sp = getNotifySharedPrefs(cl);
                    if (sp != null) {
                        sp.edit().putInt(PREF_UNLOCK_RSSI_TYPE, newType).apply();
                    }
                    int rssi = (newType == 1) ? -55 : (newType == 3 ? -85 : -70);
                    dialog.dismiss();
                    updateUnlockRowSubtitle(activity, cl);
                    if (watchMac != null && !watchMac.isEmpty()) {
                        connectAndControlMiBleService(activity.getApplicationContext(), watchMac, rssi, false, msg -> {
                            activity.runOnUiThread(() -> {
                                android.widget.Toast.makeText(activity,
                                        "已設定解鎖距離門檻為 " + rssi + " dBm\n" + msg,
                                        android.widget.Toast.LENGTH_LONG).show();
                            });
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showGattDiagnosticDialog(final android.app.Activity activity, final ClassLoader cl, final String watchMac) {
        // Trigger fresh GATT dump in :NotifyService
        Intent dumpIntent = new Intent(ACTION_DUMP_GATT);
        dumpIntent.setPackage(TARGET_PACKAGE);
        activity.sendBroadcast(dumpIntent);

        android.widget.Toast.makeText(activity, "正在讀取手錶 BLE GATT 服務與小米 MiBle 狀態...", android.widget.Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                Thread.sleep(900L);
            } catch (Throwable ignored) {
            }
            int rssi = getConfiguredUnlockRssi(activity, cl);
            connectAndControlMiBleService(activity.getApplicationContext(), watchMac, rssi, false, miBleReport -> {
                activity.runOnUiThread(() -> {
                    updateUnlockRowSubtitle(activity, cl);
                    SharedPreferences sp = getNotifySharedPrefs(cl);
                    String gattReport = sp != null ? sp.getString(PREF_GATT_SUMMARY, "尚未取得 GATT 服務表 (請確認手錶目前已連線至 Notify)") : "無法讀取 SharedPreferences";
                    String fullText = "=== 小米 HyperOS MiBle 服務診斷 ===\n"
                            + miBleReport
                            + "\n\n=== Amazfit Balance 2 實機 GATT 服務表 ===\n"
                            + gattReport;
                    logInfo("[DiagnosticReport]\n" + fullText);

                    android.widget.ScrollView sv = new android.widget.ScrollView(activity);
                    android.widget.TextView tv = new android.widget.TextView(activity);
                    float density = activity.getResources().getDisplayMetrics().density;
                    int pad = (int) (16 * density + 0.5f);
                    tv.setPadding(pad, pad, pad, pad);
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                    tv.setTextIsSelectable(true);
                    tv.setText(fullText);
                    sv.addView(tv);

                    new android.app.AlertDialog.Builder(activity)
                            .setTitle("Balance 2 GATT 與藍牙解鎖診斷")
                            .setView(sv)
                            .setPositiveButton("確定", null)
                            .setNeutralButton("複製報告", (d, w) -> {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Balance2GattReport", fullText));
                                    android.widget.Toast.makeText(activity, "診斷報告已複製到剪貼簿", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            })
                            .show();
                });
            });
        }, "Balance2Fix-DiagDialog").start();
    }

    /**
     * Exact implementation of Zepp's Lxtt.h(FragmentActivity, int, String) from com.xiaomi.hm.health.ui.smartplay.ScreenUnlockActivity:
     * Passes the watch BLE MAC address in "android.bluetooth.device.extra.NAME" to com.android.settings.MiuiSecurityBluetoothSettings.
     */
    private static void launchMiuiSecurityBluetoothSettings(final android.app.Activity activity, final String watchBleMac) {
        try {
            boolean keyguardSecure = isKeyguardSecure(activity);
            Intent intent = new Intent();
            if (keyguardSecure && watchBleMac != null && !watchBleMac.isEmpty()) {
                intent.putExtra("android.bluetooth.device.extra.NAME", watchBleMac);
            }
            intent.setComponent(new android.content.ComponentName(
                    "com.android.settings",
                    "com.android.settings.MiuiSecurityBluetoothSettings"));
            logInfo("[UnlockUI] Launching MiuiSecurityBluetoothSettings with extra.NAME=" + watchBleMac + " (keyguardSecure=" + keyguardSecure + ")");
            activity.startActivityForResult(intent, 0x100);
        } catch (Throwable t) {
            logError("[UnlockUI] Failed to launch MiuiSecurityBluetoothSettings", t);
            android.widget.Toast.makeText(activity, "無法啟動 MiuiSecurityBluetoothSettings: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Executed in :NotifyService to inspect live BluetoothGatt services exposed by Amazfit Balance 2
     */
    private static void dumpWatchGattAndMiBleStatus(final Context ctx, final ClassLoader cl, final boolean verboseLog) {
        try {
            queryWatchAgpsConfigOnEndpoint0A(cl);
            Object bleMgr = getBleManagerInstance(cl);
            if (bleMgr == null) {
                logInfo("[GATT] BLEManager instance is currently null (watch not connected in this process)");
                return;
            }

            android.bluetooth.BluetoothGatt gatt = getBluetoothGattFromBleManager(bleMgr);
            android.bluetooth.BluetoothDevice dev = null;
            if (gatt != null) {
                dev = gatt.getDevice();
            }
            if (dev == null) {
                try {
                    dev = (android.bluetooth.BluetoothDevice) HookBridge.getObjectField(bleMgr, "C");
                } catch (Throwable ignored) {
                }
            }

            String devMac = dev != null ? dev.getAddress() : getWatchBleMac(ctx, cl);
            String devName = dev != null ? dev.getName() : getWatchName(ctx, cl);

            if (gatt == null) {
                logInfo("[GATT] BluetoothGatt is null for device " + devMac);
                return;
            }

            List<android.bluetooth.BluetoothGattService> services = gatt.getServices();
            StringBuilder sb = new StringBuilder();
            sb.append("Device: ").append(devName).append(" (").append(devMac).append(")\n");
            sb.append("Total GATT Services: ").append(services != null ? services.size() : 0).append("\n");

            boolean hasFee1 = false;
            boolean hasFedd = false;
            boolean hasFede = false;
            boolean hasFe95 = false;
            boolean hasFf0f = false;

            if (services != null) {
                for (android.bluetooth.BluetoothGattService svc : services) {
                    if (svc == null || svc.getUuid() == null) continue;
                    String sUuid = svc.getUuid().toString().toLowerCase(Locale.US);
                    String sTag = "";
                    if (UUID_SERVICE_FEE1.equals(sUuid) || sUuid.startsWith("0000fee1")) {
                        hasFee1 = true;
                        sTag = " [★ Xiaomi/Huami UnlockProperty Auth Service FEE1]";
                    } else if (UUID_SERVICE_FE95.equals(sUuid)) {
                        hasFe95 = true;
                        sTag = " [Xiaomi MiService FE95]";
                    } else if (UUID_SERVICE_FEE0.equals(sUuid)) {
                        sTag = " [★ Huami/Amazfit Main Service FEE0]";
                    } else if (sUuid.startsWith("00001802")) {
                        sTag = " [Immediate Alert 1802]";
                    } else if (sUuid.startsWith("00001812")) {
                        sTag = " [HID Service 1812]";
                    }
                    sb.append("\n[Service] ").append(sUuid).append(sTag).append("\n");

                    List<android.bluetooth.BluetoothGattCharacteristic> chars = svc.getCharacteristics();
                    if (chars != null) {
                        for (android.bluetooth.BluetoothGattCharacteristic ch : chars) {
                            if (ch == null || ch.getUuid() == null) continue;
                            String cUuid = ch.getUuid().toString().toLowerCase(Locale.US);
                            int props = ch.getProperties();
                            String cTag = "";
                            if (UUID_CHAR_FEDD_WRITE.equals(cUuid)) {
                                hasFedd = true;
                                cTag = " [★ UnlockProperty Key/Challenge Write FEDD]";
                            } else if (UUID_CHAR_FEDE_READ.equals(cUuid)) {
                                hasFede = true;
                                cTag = " [★ UnlockProperty Encrypted Response Read FEDE]";
                            } else if (UUID_CHAR_FF0F_PAIR.equals(cUuid)) {
                                hasFf0f = true;
                                cTag = " [mCharPair FF0F]";
                            }
                            sb.append("  └─ ").append(cUuid)
                              .append(" (props=0x").append(Integer.toHexString(props))
                              .append(":").append(formatGattProps(props)).append(")")
                              .append(cTag).append("\n");
                        }
                    }
                }
            }

            sb.append("\n結論：小米藍牙解鎖服務 FEE1=").append(hasFee1 ? "✅ 存在" : "❌ 不存在")
              .append(", 金鑰寫入特徵 FEDD=").append(hasFedd ? "✅ 存在" : "❌ 不存在")
              .append(", 加密回應讀取特徵 FEDE=").append(hasFede ? "✅ 存在" : "❌ 不存在");

            String summary = sb.toString();
            if (verboseLog) {
                logInfo("[GATT-DUMP]\n" + summary);
            } else {
                logInfo("[GATT-SUMMARY] Watch=" + devMac + ", services=" + (services != null ? services.size() : 0)
                        + ", hasFEE1=" + hasFee1 + ", hasFEDD=" + hasFedd + ", hasFEDE=" + hasFede);
            }

            SharedPreferences sp = getNotifySharedPrefs(cl);
            if (sp != null) {
                sp.edit()
                        .putString(PREF_GATT_SUMMARY, summary)
                        .putBoolean(PREF_GATT_HAS_FEE1, hasFee1)
                        .putBoolean(PREF_GATT_HAS_FEDD, hasFedd)
                        .putBoolean(PREF_GATT_HAS_FEDE, hasFede)
                        .putBoolean(PREF_GATT_HAS_FE95, hasFe95)
                        .putBoolean(PREF_GATT_HAS_FF0F, hasFf0f)
                        .apply();
            }
        } catch (Throwable t) {
            logError("[GATT] dumpWatchGattAndMiBleStatus failed", t);
        }
    }

    /**
     * Writes 0x02 to 0000ff0f-0000-1000-8000-00805f9b34fb (Lbpt.R2 / Lcmk.c1 in Zepp.apk) if present on watch
     */
    private static void triggerWatchPairCharacteristic(final Context ctx, final ClassLoader cl) {
        try {
            Object bleMgr = getBleManagerInstance(cl);
            if (bleMgr == null) return;
            android.bluetooth.BluetoothGatt gatt = getBluetoothGattFromBleManager(bleMgr);
            if (gatt == null) return;

            android.bluetooth.BluetoothGattService fee0 = gatt.getService(java.util.UUID.fromString(UUID_SERVICE_FEE0));
            if (fee0 != null) {
                android.bluetooth.BluetoothGattCharacteristic ff0f = fee0.getCharacteristic(java.util.UUID.fromString(UUID_CHAR_FF0F_PAIR));
                if (ff0f != null) {
                    ff0f.setValue(new byte[]{0x02});
                    boolean ok = gatt.writeCharacteristic(ff0f);
                    logInfo("[UnlockPair] Wrote 0x02 to FF0F (mCharPair) -> result=" + ok);
                } else {
                    logInfo("[UnlockPair] FF0F characteristic not present in FEE0 service");
                }
            }
        } catch (Throwable t) {
            logError("[UnlockPair] triggerWatchPairCharacteristic failed", t);
        }
    }

    private interface MiBleResultCallback {
        void onResult(String message);
    }

    /**
     * Connects directly to Xiaomi HyperOS's BluetoothMiBleService (miui.bluetooth.ble.IBluetoothMiBle)
     * using raw Binder transactions matching Zepp's IBluetoothMiBle$Stub$Proxy:
     *  - Code 1: registerClient(IBinder token, String mac, ParcelUuid clientId, IBinder callback)
     *  - Code 2: unregisterClient(IBinder token, String mac, ParcelUuid clientId)
     *  - Code 3: isConnected(String mac) -> boolean
     *  - Code 4: connect(String mac, ParcelUuid clientId)
     *  - Code 7: getRssi(String mac, ParcelUuid clientId) -> int
     *  - Code 8: supportProperty(String mac, int property) -> boolean (1 = PROPERTY_UNLOCK)
     *  - Code 14 (0x0e): setRssiThreshold(String mac, ParcelUuid clientId, int rssi) -> boolean
     *  - Code 15 (0x0f): authenticate(String mac, ParcelUuid clientId) -> boolean
     *  - Code 18 (0x12): getServiceVersion() -> int
     */
    private static void connectAndControlMiBleService(
            final Context context,
            final String watchMac,
            final int rssiThreshold,
            final boolean runAuthenticate,
            final MiBleResultCallback callback) {
        if (context == null || watchMac == null || watchMac.isEmpty()) {
            if (callback != null) callback.onResult("Context 或 Watch MAC 為空");
            return;
        }
        try {
            final android.os.ParcelUuid clientId = new android.os.ParcelUuid(java.util.UUID.randomUUID());
            final android.os.IBinder token = new android.os.Binder();

            // Minimal IBluetoothMiBleCallback Stub (Interface: "miui.bluetooth.ble.IBluetoothMiBleCallback")
            final android.os.Binder miBleCallbackBinder = new android.os.Binder() {
                @Override
                protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
                    if (code == 1) { // onConnectionState(ParcelUuid clientId, int state)
                        data.enforceInterface("miui.bluetooth.ble.IBluetoothMiBleCallback");
                        if (data.readInt() != 0) {
                            android.os.ParcelUuid.CREATOR.createFromParcel(data);
                        }
                        int state = data.readInt();
                        logInfo("[MiBleService] onConnectionState callback: state=" + state);
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };

            android.content.ServiceConnection conn = new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, final android.os.IBinder service) {
                    final android.content.ServiceConnection selfConn = this;
                    new Thread(() -> {
                        StringBuilder report = new StringBuilder();
                        try {
                            int version = transactGetServiceVersion(service);
                            report.append("MiBleService Version: ").append(version).append("\n");

                            transactRegisterClient(service, token, watchMac, clientId, miBleCallbackBinder);
                            transactConnect(service, watchMac, clientId);
                            Thread.sleep(600L);

                            boolean connected = transactIsConnected(service, watchMac);
                            boolean supportUnlock = transactSupportProperty(service, watchMac, 1); // 1 = PROPERTY_UNLOCK
                            int currentRssi = transactGetRssi(service, watchMac, clientId);
                            boolean rssiSetOk = transactSetRssiThreshold(service, watchMac, clientId, rssiThreshold);

                            report.append("Watch MAC: ").append(watchMac).append("\n")
                                  .append("isConnected(mac): ").append(connected).append("\n")
                                  .append("supportProperty(PROPERTY_UNLOCK=1): ").append(supportUnlock).append("\n")
                                  .append("Current RSSI: ").append(currentRssi).append(" dBm\n")
                                  .append("setRssiThreshold(").append(rssiThreshold).append(" dBm): ").append(rssiSetOk);

                            if (runAuthenticate) {
                                boolean authOk = transactAuthenticate(service, watchMac, clientId);
                                report.append("\nauthenticate(unlock): ").append(authOk);
                            }

                            logInfo("[MiBleService] " + report.toString().replace('\n', ' '));
                        } catch (Throwable t) {
                            logError("[MiBleService] Binder transaction failed", t);
                            report.append("\n錯誤: ").append(t.getMessage());
                        } finally {
                            try {
                                transactUnregisterClient(service, token, watchMac, clientId);
                            } catch (Throwable ignored) {
                            }
                            try {
                                context.unbindService(selfConn);
                            } catch (Throwable ignored) {
                            }
                            if (callback != null) {
                                callback.onResult(report.toString());
                            }
                        }
                    }, "Balance2Fix-MiBleWorker").start();
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                }
            };

            Intent intent = new Intent("miui.bluetooth.mible.Service");
            intent.setClassName("com.xiaomi.bluetooth", "com.android.bluetooth.ble.BluetoothMiBleService");
            intent.setPackage("com.xiaomi.bluetooth");
            boolean bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Intent fallback = new Intent("miui.bluetooth.mible.Service");
                fallback.setClassName("com.android.bluetooth", "com.android.bluetooth.ble.BluetoothMiBleService");
                fallback.setPackage("com.android.bluetooth");
                bound = context.bindService(fallback, conn, Context.BIND_AUTO_CREATE);
            }
            if (!bound && callback != null) {
                callback.onResult("無法綁定小米系統 BluetoothMiBleService (com.xiaomi.bluetooth / com.android.bluetooth)");
            }
        } catch (Throwable t) {
            logError("[MiBleService] connectAndControlMiBleService failed", t);
            if (callback != null) callback.onResult("例外錯誤: " + t.getMessage());
        }
    }

    private static final String MIBLE_DESCRIPTOR = "miui.bluetooth.ble.IBluetoothMiBle";

    private static int transactGetServiceVersion(android.os.IBinder remote) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            remote.transact(18, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactRegisterClient(android.os.IBinder remote, android.os.IBinder token, String mac, android.os.ParcelUuid clientId, android.os.IBinder cb) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeStrongBinder(token);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            data.writeStrongBinder(cb);
            remote.transact(1, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactUnregisterClient(android.os.IBinder remote, android.os.IBinder token, String mac, android.os.ParcelUuid clientId) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeStrongBinder(token);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            remote.transact(2, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactIsConnected(android.os.IBinder remote, String mac) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            remote.transact(3, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactConnect(android.os.IBinder remote, String mac, android.os.ParcelUuid clientId) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            remote.transact(4, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static int transactGetRssi(android.os.IBinder remote, String mac, android.os.ParcelUuid clientId) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            remote.transact(7, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactSupportProperty(android.os.IBinder remote, String mac, int prop) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            data.writeInt(prop);
            remote.transact(8, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactSetRssiThreshold(android.os.IBinder remote, String mac, android.os.ParcelUuid clientId, int rssi) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            data.writeInt(rssi);
            remote.transact(14, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactAuthenticate(android.os.IBinder remote, String mac, android.os.ParcelUuid clientId) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(MIBLE_DESCRIPTOR);
            data.writeString(mac);
            data.writeInt(1);
            clientId.writeToParcel(data, 0);
            remote.transact(15, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String getWatchBleMac(Context ctx, ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            if (userPrefsClass != null && ctx != null) {
                Object prefs = HookBridge.callStaticMethod(userPrefsClass, "getInstance", ctx.getApplicationContext());
                if (prefs != null) {
                    String mac = (String) HookBridge.getObjectField(prefs, "miBandMAC");
                    if (mac != null && !mac.trim().isEmpty()) {
                        return mac.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getWatchName(Context ctx, ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            if (userPrefsClass != null && ctx != null) {
                Object prefs = HookBridge.callStaticMethod(userPrefsClass, "getInstance", ctx.getApplicationContext());
                if (prefs != null) {
                    String name = (String) HookBridge.getObjectField(prefs, "miBandName");
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Amazfit Balance 2";
    }

    private static SharedPreferences getNotifySharedPrefs(ClassLoader cl) {
        try {
            Class<?> userPrefsClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
            if (userPrefsClass != null && hasMethodWithReturn(userPrefsClass, "N6", SharedPreferences.class)) {
                Object res = HookBridge.callStaticMethod(userPrefsClass, "N6");
                if (res instanceof SharedPreferences) {
                    return (SharedPreferences) res;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = sAppContext;
            if (ctx == null && sCurrentBandSettingsActivity != null) {
                ctx = sCurrentBandSettingsActivity.get();
            }
            if (ctx != null) {
                return ctx.getApplicationContext().getSharedPreferences("preferences", Context.MODE_PRIVATE);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int getConfiguredUnlockRssiType(Context ctx, ClassLoader cl) {
        SharedPreferences sp = getNotifySharedPrefs(cl);
        if (sp != null) {
            return sp.getInt(PREF_UNLOCK_RSSI_TYPE, 2); // Default 2 = Medium (-70 dBm)
        }
        return 2;
    }

    private static int getConfiguredUnlockRssi(Context ctx, ClassLoader cl) {
        int type = getConfiguredUnlockRssiType(ctx, cl);
        if (type == 1) return -55;
        if (type == 3) return -85;
        return -70;
    }

    private static boolean isKeyguardSecure(Context ctx) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardSecure();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int getSystemUnlockStatus(Context ctx) {
        try {
            return android.provider.Settings.Secure.getInt(ctx.getContentResolver(), "bluetooth_unlock_status", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String getSystemUnlockAddress(Context ctx) {
        try {
            return android.provider.Settings.Secure.getString(ctx.getContentResolver(), "bluetooth_address_to_unlock");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getSystemUnlockName(Context ctx) {
        try {
            return android.provider.Settings.Secure.getString(ctx.getContentResolver(), "bluetooth_name_to_unlock");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isWatchBoundToHyperOsUnlock(Context ctx, String watchMac) {
        if (watchMac == null || watchMac.isEmpty()) return false;
        int status = getSystemUnlockStatus(ctx);
        String sysMac = getSystemUnlockAddress(ctx);
        return status == 1 && watchMac.equalsIgnoreCase(sysMac);
    }

    private static String formatGattProps(int props) {
        List<String> list = new ArrayList<>();
        if ((props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ) != 0) list.add("READ");
        if ((props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list.add("WRITE");
        if ((props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list.add("WRITE_NO_RSP");
        if ((props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list.add("NOTIFY");
        if ((props & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list.add("INDICATE");
        return String.join("|", list);
    }

    private static android.widget.LinearLayout findFirstScrollableLinearLayout(android.view.View root) {
        if (root == null) return null;
        if (root instanceof android.widget.ScrollView && ((android.widget.ScrollView) root).getChildCount() > 0) {
            android.view.View child = ((android.widget.ScrollView) root).getChildAt(0);
            if (child instanceof android.widget.LinearLayout) {
                return (android.widget.LinearLayout) child;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.LinearLayout found = findFirstScrollableLinearLayout(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static android.widget.TextView findFirstTextView(android.view.ViewGroup vg, android.view.View exclude) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            android.view.View c = vg.getChildAt(i);
            if (c == exclude) continue;
            if (c instanceof android.widget.TextView) return (android.widget.TextView) c;
            if (c instanceof android.view.ViewGroup) {
                android.widget.TextView tv = findFirstTextView((android.view.ViewGroup) c, exclude);
                if (tv != null) return tv;
            }
        }
        return null;
    }

    // =========================================================================
    // 10. VoIP Call (LINE / WhatsApp / Telegram) Watch Answering & HFP SCO Audio Routing
    // =========================================================================
    private static volatile boolean sVoipMonitorRegistered = false;
    private static volatile long sLastVoipRingingStartMs = 0L;
    private static volatile long sLastVoipAnsweredMs = 0L;
    private static volatile long sLastHfpScoActiveMs = 0L;
    private static volatile long sLastWatchHfpActiveMs = 0L;
    private static volatile boolean sVoipCallAnswered = false;
    private static volatile boolean sAnsweredFromWatch = false;
    private static volatile boolean sOtherBtHeadsetClaimedAudio = false;
    private static volatile boolean sVoipVirtualScoStarted = false;
    private static volatile boolean sForceScoApplied = false;
    private static volatile String sCurrentVoipPackage = null;
    private static volatile String sCurrentVoipCaller = null;
    private static volatile android.app.PendingIntent sCachedVoipAnswerIntent = null;
    private static volatile android.app.PendingIntent sCachedVoipDeclineIntent = null;
    private static volatile android.bluetooth.BluetoothHeadset sCachedBluetoothHeadset = null;
    private static volatile android.os.Handler sVoipHandler = null;

    private static synchronized android.os.Handler getVoipHandler() {
        if (sVoipHandler == null) {
            android.os.HandlerThread ht = new android.os.HandlerThread("Balance2Fix-VoipAudio");
            ht.start();
            sVoipHandler = new android.os.Handler(ht.getLooper());
        }
        return sVoipHandler;
    }

    private static boolean isWithinVoipWindow() {
        long now = System.currentTimeMillis();
        return (now - sLastVoipRingingStartMs) < 90_000L || (sVoipCallAnswered && (now - sLastVoipAnsweredMs) < 3600_000L);
    }

    private static boolean isPhoneInVoipCommunication(Context ctx) {
        if (ctx == null) ctx = sAppContext;
        if (ctx == null) return false;
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int mode = am.getMode();
                return mode == android.media.AudioManager.MODE_IN_COMMUNICATION;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static volatile String sNs50InstanceField = "w0";
    private static volatile String sNs50DeclineField = "T";
    private static volatile String sNs50AnswerField = "U";

    private static void resolveNotificationService50Fields(Class<?> ns50Class) {
        if (ns50Class == null) return;
        try {
            for (String cand : new String[]{"w0", "z0"}) {
                try {
                    Field f = ns50Class.getDeclaredField(cand);
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == ns50Class) {
                        sNs50InstanceField = cand;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            // v24.1.1: T=Decline, U=Answer; v24.0.3: W=Decline, X=Answer
            try {
                Field fT = ns50Class.getDeclaredField("T");
                Field fU = ns50Class.getDeclaredField("U");
                if (fT.getType() == android.app.PendingIntent.class && fU.getType() == android.app.PendingIntent.class) {
                    sNs50DeclineField = "T";
                    sNs50AnswerField = "U";
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Field fW = ns50Class.getDeclaredField("W");
                Field fX = ns50Class.getDeclaredField("X");
                if (fW.getType() == android.app.PendingIntent.class && fX.getType() == android.app.PendingIntent.class) {
                    sNs50DeclineField = "W";
                    sNs50AnswerField = "X";
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookVoipCallPipeline(final ClassLoader cl) {
        try {
            Class<?> ns50Class = findClassSafe(cl, "com.mc.miband1.NotificationService50");
            resolveNotificationService50Fields(ns50Class);
            Class<?> yf0Class = findClassSafe(cl, "zf0", "yf0");
            Class<?> ba8Class = findClassSafe(cl, "ba8");
            boolean isV2411Voip = (yf0Class != null && "zf0".equals(yf0Class.getSimpleName()))
                    || (ba8Class != null && ns50Class != null && hasMethod(ba8Class, "f", ns50Class, android.service.notification.StatusBarNotification.class));

            // 10.1 Hook com.mc.miband1.model.d.s6() [v24.1.1] or t6() [v24.0.3] (isVoipCall)
            // Prevent Le64.run() from entering Ltb.o() ("deviceSpeaker notification mode incoming call")
            // which spams 20 generic app notifications + cancel packets over 44s, and allow Lqy.run()
            // to properly stop the ZeppOS Endpoint 0x1E call alert when the VoIP call ends/answers.
            Class<?> callModelClass = findClassSafe(cl, "com.mc.miband1.model.d");
            String voipGetterMethod = isV2411Voip ? "s6" : "t6";
            if (callModelClass != null && hasMethodWithReturn(callModelClass, voipGetterMethod, boolean.class)) {
                final String vm = voipGetterMethod;
                HookBridge.findAndHookMethod(callModelClass, voipGetterMethod, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        Boolean orig = (Boolean) param.getResult();
                        if (orig != null && orig) {
                            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                            for (StackTraceElement el : stack) {
                                String cn = el.getClassName();
                                if ("e64".equals(cn) || "qy".equals(cn)) {
                                    logInfo("[VoipFix] Suppressing d." + vm + "() (voipCall=true -> false) in " + cn
                                            + " to use clean ZeppOS Endpoint 0x1E native call alert without 20x notification spam");
                                    param.setResult(false);
                                    return;
                                }
                            }
                        }
                    }
                });
                logInfo("[VoipFix] Hooked com.mc.miband1.model.d." + voipGetterMethod + "()");
            }

            // 10.2 Hook Lzf0.t / Lyf0.t (Context, String, String) - VoIP incoming call trigger in Notify
            if (yf0Class != null && hasMethod(yf0Class, "t", Context.class, String.class, String.class)) {
                HookBridge.findAndHookMethod(yf0Class, "t", Context.class, String.class, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        Context ctx = (Context) param.args[0];
                        String caller = (String) param.args[1];
                        String pkg = (String) param.args[2];
                        sLastVoipRingingStartMs = System.currentTimeMillis();
                        sVoipCallAnswered = false;
                        sAnsweredFromWatch = false;
                        sOtherBtHeadsetClaimedAudio = false;
                        sLastWatchHfpActiveMs = 0L;
                        sLastHfpScoActiveMs = 0L;
                        sCurrentVoipCaller = caller;
                        sCurrentVoipPackage = pkg;
                        if (ctx != null) {
                            registerVoipAudioAndHfpMonitorIfNeeded(ctx.getApplicationContext(), cl);
                            ensureBluetoothHeadsetProxy(ctx.getApplicationContext());
                        }
                        logInfo("[VoipFix] Incoming VoIP call detected via " + yf0Class.getSimpleName() + ".t: pkg=" + pkg + ", caller=" + caller
                                + ", otherBtHeadsetConnected=" + hasOtherConnectedBluetoothHeadset(ctx, cl));
                    }
                });
            }

            // 10.3 Hook Lba8.f [v24.1.1] / Lba8.g [v24.0.3] (NotificationService50, StatusBarNotification)
            // Fix Notify's bug where LINE's RemoteViews (fl_btn_left / fl_btn_right) is skipped whenever
            // android.title is non-empty, and accurately extract Answer (U/X) and Decline (T/W) PendingIntents.
            String ba8ParseMethod = (ba8Class != null && ns50Class != null && hasMethod(ba8Class, "f", ns50Class, android.service.notification.StatusBarNotification.class)) ? "f"
                    : ((ba8Class != null && ns50Class != null && hasMethod(ba8Class, "g", ns50Class, android.service.notification.StatusBarNotification.class)) ? "g" : null);
            if (ba8Class != null && ns50Class != null && ba8ParseMethod != null) {
                HookBridge.findAndHookMethod(ba8Class, ba8ParseMethod, ns50Class, android.service.notification.StatusBarNotification.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        Object ns50 = param.args[0];
                        android.service.notification.StatusBarNotification sbn =
                                (android.service.notification.StatusBarNotification) param.args[1];
                        if (ns50 == null || sbn == null) return;
                        String pkg = sbn.getPackageName();
                        if (pkg == null) return;

                        Boolean handled = (Boolean) param.getResult();
                        boolean isKnownVoipPkg = "jp.naver.line.android".equals(pkg)
                                || "com.whatsapp".equals(pkg)
                                || "com.whatsapp.w4b".equals(pkg)
                                || "org.telegram.messenger".equals(pkg)
                                || "com.facebook.orca".equals(pkg)
                                || "com.viber.voip".equals(pkg)
                                || "com.tencent.mm".equals(pkg);

                        if ((handled != null && handled) || (isKnownVoipPkg && isWithinVoipWindow())) {
                            extractAndFixVoipPendingIntents(ns50, sbn, cl);
                        }
                    }
                });
                logInfo("[VoipFix] Hooked Lba8." + ba8ParseMethod + "(NotificationService50, StatusBarNotification)");
            }

            // 10.4 Hook Lba8.b(NotificationService50, String) - VoIP call notification ended/removed
            if (ba8Class != null && ns50Class != null && hasMethod(ba8Class, "b", ns50Class, String.class)) {
                HookBridge.findAndHookMethod(ba8Class, "b", ns50Class, String.class, new HookBridge.MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        String pkg = (String) param.args[1];
                        Context ctx = (param.args[0] instanceof Context)
                                ? ((Context) param.args[0]).getApplicationContext() : sAppContext;
                        // When LINE transitions from incoming ringing notification to active in-call,
                        // Lba8.b may be called for the ringing notification while AudioManager enters MODE_IN_COMMUNICATION!
                        getVoipHandler().postDelayed(() -> {
                            if (isPhoneInVoipCommunication(ctx)) {
                                sVoipCallAnswered = true;
                                if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = System.currentTimeMillis();
                                if (shouldRouteVoipToWatch(ctx, cl)) {
                                    logInfo("[VoipFix] Lba8.b called for " + pkg + " while in MODE_IN_COMMUNICATION (answered from watch) -> Routing audio to watch...");
                                    routeVoipAudioToWatch(ctx, cl, "Lba8.b transition to MODE_IN_COMMUNICATION (watch answered)");
                                } else {
                                    logInfo("[VoipFix] Lba8.b called for " + pkg + " while in MODE_IN_COMMUNICATION, but call was answered on PHONE / BT Earbuds -> keeping phone/earbuds audio priority!");
                                }
                            } else if (!isPhoneInVoipCommunication(ctx) && sVoipVirtualScoStarted) {
                                logInfo("[VoipFix] VoIP call ended (Lba8.b & not in communication) -> releasing watch SCO audio");
                                stopVoipWatchAudioRouting(ctx, "Lba8.b call ended");
                            }
                        }, 300L);
                    }
                });
            }

            // 10.5 Hook Lq25.f [v24.1.1] / Lpa5.f [v24.0.3] (Lxp, int, byte[]) - ZeppOS Endpoint 0x1E incoming BLE commands from watch
            // NOTE: Endpoint 0x1E only carries 05 01 (Reject/Dismiss), 05 02 (Silence), 05 03 (SMS).
            // Do NOT mark sAnsweredFromWatch here; Watch Answer is strictly detected via HFP ATA
            // (ACTIVE_DEVICE_CHANGED / AUDIO_STATE_CHANGED pointing to watch, or Lzf0/Lyf0.a/b).
            Class<?> pa5Class = findClassSafe(cl, "q25", "pa5");
            Class<?> xpClass = findClassSafe(cl, "xp");
            if (pa5Class != null && xpClass != null && hasMethod(pa5Class, "f", xpClass, int.class, byte[].class)) {
                HookBridge.findAndHookMethod(pa5Class, "f", xpClass, int.class, byte[].class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        byte[] data = (byte[]) param.args[2];
                        if (data != null && data.length >= 2 && data[0] == 0x05) {
                            logInfo("[VoipFix] Watch Endpoint 0x1E event received (" + pa5Class.getSimpleName() + ".f): 05 "
                                    + String.format("%02X", data[1])
                                    + " (withinVoip=" + isWithinVoipWindow()
                                    + ", answered=" + sVoipCallAnswered
                                    + ", answeredFromWatch=" + sAnsweredFromWatch
                                    + ", inComm=" + isPhoneInVoipCommunication(sAppContext) + ")");
                        }
                    }
                });
                logInfo("[VoipFix] Hooked Endpoint 0x1E handler: " + pa5Class.getName() + ".f");
            }

            // 10.6 Hook Lyf0.w(Context, boolean) - Reject/Hangup Call
            // Prevents Case 1 (Immediate Call Drop) when the watch answers via HFP ATA and simultaneously
            // dismisses the BLE call alert (sending 05 01 on Endpoint 0x1E -> Loq.run -> Lyf0.w).
            if (yf0Class != null && hasMethod(yf0Class, "w", Context.class, boolean.class)) {
                HookBridge.findAndHookMethod(yf0Class, "w", Context.class, boolean.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        final Context ctx = (Context) param.args[0];
                        boolean isVoipNow = false;
                        try {
                            Boolean nRes = (Boolean) HookBridge.callMethod(param.thisObject, "n");
                            isVoipNow = (nRes != null && nRes) || isWithinVoipWindow();
                        } catch (Throwable ignored) {
                            isVoipNow = isWithinVoipWindow();
                        }
                        if (!isVoipNow) return;

                        long now = System.currentTimeMillis();
                        boolean inComm = isPhoneInVoipCommunication(ctx);
                        boolean watchIsActiveHfp = isWatchCurrentActiveHfpDevice(ctx, cl);
                        boolean recentWatchSco = (now - sLastHfpScoActiveMs) < 4500L;
                        boolean recentWatchHfpActive = (now - sLastWatchHfpActiveMs) < 4500L;

                        if (watchIsActiveHfp || recentWatchSco || recentWatchHfpActive) {
                            sAnsweredFromWatch = true;
                            sOtherBtHeadsetClaimedAudio = false;
                            if (sLastWatchHfpActiveMs == 0L) sLastWatchHfpActiveMs = now;
                        }

                        if (sVoipCallAnswered || inComm || sAnsweredFromWatch) {
                            logInfo("[VoipFix] BLOCKED false Lyf0.w (Reject Call) during active/answered VoIP call! (answered="
                                    + sVoipCallAnswered + ", inComm=" + inComm + ", answeredFromWatch=" + sAnsweredFromWatch
                                    + ", watchActiveHfp=" + watchIsActiveHfp + ")");
                            sVoipCallAnswered = true;
                            if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = now;
                            param.setResult(null);
                            if (shouldRouteVoipToWatch(ctx, cl)) {
                                routeVoipAudioToWatch(ctx, cl, "Lyf0.w blocked on Watch VoIP answer");
                            }
                            return;
                        }

                        // Delay VoIP reject by 650ms to distinguish between:
                        // (A) User tapped Green Answer on watch -> HFP ATA sets activeDevice=watch & answers call within ~500ms, while BLE 05 01 dismisses overlay
                        // (B) User answered on phone screen -> MODE_IN_COMMUNICATION starts within ~500ms (do NOT hang up and do NOT route to watch)
                        // (C) User genuinely tapped Red Hangup on watch -> call is still ringing after 650ms with no watch HFP active / no MODE_IN_COMMUNICATION
                        final android.app.PendingIntent declinePi = getOrExtractVoipIntent(cl, false);
                        logInfo("[VoipFix] Intercepted Lyf0.w during VoIP ringing; deferring 650ms to distinguish Watch Answer vs Genuine Hangup...");
                        param.setResult(null);

                        getVoipHandler().postDelayed(() -> {
                            try {
                                long checkNow = System.currentTimeMillis();
                                boolean watchActiveNow = isWatchCurrentActiveHfpDevice(ctx, cl)
                                        || (checkNow - sLastHfpScoActiveMs) < 4500L
                                        || (checkNow - sLastWatchHfpActiveMs) < 4500L;
                                if (watchActiveNow) {
                                    sAnsweredFromWatch = true;
                                    sOtherBtHeadsetClaimedAudio = false;
                                }
                                boolean answeredNow = sVoipCallAnswered
                                        || isPhoneInVoipCommunication(ctx)
                                        || sAnsweredFromWatch;
                                if (answeredNow) {
                                    logInfo("[VoipFix] 650ms check: VoIP call WAS ANSWERED! Aborting reject. (answeredFromWatch="
                                            + sAnsweredFromWatch + ", watchActiveNow=" + watchActiveNow + ")");
                                    sVoipCallAnswered = true;
                                    if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = checkNow;
                                    if (shouldRouteVoipToWatch(ctx, cl)) {
                                        routeVoipAudioToWatch(ctx, cl, "650ms post-Lyf0.w watch answer confirmed");
                                    }
                                } else {
                                    logInfo("[VoipFix] 650ms check: VoIP call still ringing (genuine Hangup on watch). Sending Decline PendingIntent: " + declinePi);
                                    if (declinePi != null) {
                                        declinePi.send();
                                    }
                                    clearNotifyVoipIntents(cl);
                                }
                            } catch (Throwable t) {
                                logError("[VoipFix] Deferred VoIP reject check failed", t);
                            }
                        }, 650L);
                    }
                });
            }

            // 10.7 Hook Lyf0.a(Context, boolean) & Lyf0.b(Context) - Answer Call
            // Ensure clean single-shot PendingIntent answer without double KEYCODE_HEADSETHOOK or forced Speakerphone,
            // followed immediately by Watch HFP SCO audio routing!
            if (yf0Class != null && hasMethod(yf0Class, "a", Context.class, boolean.class)) {
                HookBridge.findAndHookMethod(yf0Class, "a", Context.class, boolean.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        final Context ctx = (Context) param.args[0];
                        boolean isVoipNow = false;
                        try {
                            Boolean nRes = (Boolean) HookBridge.callMethod(param.thisObject, "n");
                            isVoipNow = (nRes != null && nRes) || isWithinVoipWindow();
                        } catch (Throwable ignored) {
                            isVoipNow = isWithinVoipWindow();
                        }
                        if (!isVoipNow) return;

                        sAnsweredFromWatch = true;
                        sLastWatchHfpActiveMs = System.currentTimeMillis();
                        param.setResult(null); // Prevent fallback to Lyf0.b() (which sends 3x KEYCODE_HEADSETHOOK + speakerphone)
                        answerVoipCallAndRouteToWatch(ctx, cl, "Lyf0.a (Watch BLE Answer)");
                    }
                });
            }

            if (yf0Class != null && hasMethod(yf0Class, "b", Context.class)) {
                HookBridge.findAndHookMethod(yf0Class, "b", Context.class, new HookBridge.MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isVoipCallFixEnabled(cl)) return;
                        final Context ctx = (Context) param.args[0];
                        if (isWithinVoipWindow()) {
                            logInfo("[VoipFix] Intercepted Lyf0.b during VoIP call -> preventing 3x KEYCODE_HEADSETHOOK & forced speakerphone");
                            sAnsweredFromWatch = true;
                            sLastWatchHfpActiveMs = System.currentTimeMillis();
                            param.setResult(null);
                            answerVoipCallAndRouteToWatch(ctx, cl, "Lyf0.b (Watch BLE Answer)");
                        }
                    }
                });
            }

            logInfo("hookVoipCallPipeline installed successfully");
        } catch (Throwable t) {
            logError("hookVoipCallPipeline failed", t);
        }
    }

    private static void extractAndFixVoipPendingIntents(Object ns50, android.service.notification.StatusBarNotification sbn, ClassLoader cl) {
        try {
            android.app.Notification n = sbn.getNotification();
            if (n == null) return;
            String pkg = sbn.getPackageName();
            Context ctx = (ns50 instanceof Context) ? ((Context) ns50).getApplicationContext() : sAppContext;

            android.app.PendingIntent existingW = (android.app.PendingIntent) HookBridge.getObjectField(ns50, sNs50DeclineField);
            android.app.PendingIntent existingX = (android.app.PendingIntent) HookBridge.getObjectField(ns50, sNs50AnswerField);

            android.app.PendingIntent foundDecline = null;
            android.app.PendingIntent foundAnswer = null;

            // 1. Check Android 12+ CallStyle extras first
            if (n.extras != null) {
                Object ansObj = n.extras.getParcelable("android.callAnswerIntent");
                if (ansObj instanceof android.app.PendingIntent) {
                    foundAnswer = (android.app.PendingIntent) ansObj;
                }
                Object decObj = n.extras.getParcelable("android.callDeclineIntent");
                if (decObj instanceof android.app.PendingIntent) {
                    foundDecline = (android.app.PendingIntent) decObj;
                } else {
                    Object hangObj = n.extras.getParcelable("android.callHangUpIntent");
                    if (hangObj instanceof android.app.PendingIntent) {
                        foundDecline = (android.app.PendingIntent) hangObj;
                    }
                }
            }

            // 2. Check RemoteViews (fl_btn_left / fl_btn_right) for LINE, which Notify's Lba8.g skipped when android.title was non-empty!
            if ((foundAnswer == null || foundDecline == null) && ctx != null && "jp.naver.line.android".equals(pkg)) {
                try {
                    android.content.res.Resources res = ctx.getPackageManager().getResourcesForApplication(pkg);
                    int idLeft = res.getIdentifier("fl_btn_left", "id", pkg);
                    int idRight = res.getIdentifier("fl_btn_right", "id", pkg);
                    android.widget.RemoteViews rv = n.headsUpContentView != null ? n.headsUpContentView
                            : (n.bigContentView != null ? n.bigContentView : n.contentView);
                    if (rv != null && (idLeft != 0 || idRight != 0)) {
                        Field mActionsField = android.widget.RemoteViews.class.getDeclaredField("mActions");
                        mActionsField.setAccessible(true);
                        ArrayList<?> actionsList = (ArrayList<?>) mActionsField.get(rv);
                        if (actionsList != null) {
                            for (Object act : actionsList) {
                                if (act == null) continue;
                                String actClass = act.getClass().getName();
                                if (!actClass.contains("SetOnClickPendingIntent") && !actClass.contains("SetOnClickResponse")) {
                                    continue;
                                }
                                Field viewIdField = findFieldRecursive(act.getClass(), "viewId");
                                if (viewIdField == null) continue;
                                viewIdField.setAccessible(true);
                                int viewId = (Integer) viewIdField.get(act);
                                android.app.PendingIntent pi = extractPendingIntentFromRemoteAction(act);
                                if (pi != null) {
                                    if (viewId == idLeft && foundDecline == null) {
                                        foundDecline = pi;
                                    } else if (viewId == idRight && foundAnswer == null) {
                                        foundAnswer = pi;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    logError("[VoipFix] RemoteViews fl_btn extraction error", t);
                }
            }

            // 3. Check Notification.actions by title keywords
            if (n.actions != null && n.actions.length > 0) {
                for (android.app.Notification.Action action : n.actions) {
                    if (action == null || action.actionIntent == null) continue;
                    String title = action.title != null ? action.title.toString().toLowerCase(Locale.ROOT) : "";
                    if (title.contains("接聽") || title.contains("通話") || title.contains("answer")
                            || title.contains("accept") || title.contains("応答") || title.contains("수락")) {
                        if (foundAnswer == null) foundAnswer = action.actionIntent;
                    } else if (title.contains("掛斷") || title.contains("拒接") || title.contains("拒絕")
                            || title.contains("結束") || title.contains("decline") || title.contains("reject")
                            || title.contains("hang") || title.contains("拒否") || title.contains("切断") || title.contains("거절")) {
                        if (foundDecline == null) foundDecline = action.actionIntent;
                    }
                }
                // Fallback if 2 actions without matched text
                if (foundDecline == null && foundAnswer == null && n.actions.length == 2) {
                    foundDecline = n.actions[0].actionIntent;
                    foundAnswer = n.actions[1].actionIntent;
                }
            }

            if (foundDecline != null) {
                HookBridge.setObjectField(ns50, sNs50DeclineField, foundDecline);
                sCachedVoipDeclineIntent = foundDecline;
            } else if (existingW != null) {
                sCachedVoipDeclineIntent = existingW;
            }

            if (foundAnswer != null) {
                HookBridge.setObjectField(ns50, sNs50AnswerField, foundAnswer);
                sCachedVoipAnswerIntent = foundAnswer;
            } else if (existingX != null) {
                sCachedVoipAnswerIntent = existingX;
            }

            logInfo("[VoipFix] Extracted VoIP PendingIntents for " + pkg
                    + " -> Answer(" + sNs50AnswerField + ")=" + sCachedVoipAnswerIntent
                    + ", Decline(" + sNs50DeclineField + ")=" + sCachedVoipDeclineIntent
                    + " (actionsCount=" + (n.actions != null ? n.actions.length : 0) + ")");

            // Check if this notification is actually an active In-Call notification (already answered)
            if (isPhoneInVoipCommunication(ctx)) {
                sVoipCallAnswered = true;
                if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = System.currentTimeMillis();
                if (shouldRouteVoipToWatch(ctx, cl)) {
                    routeVoipAudioToWatch(ctx, cl, "VoIP notification updated while in MODE_IN_COMMUNICATION (watch answered)");
                } else {
                    logInfo("[VoipFix] VoIP notification updated in MODE_IN_COMMUNICATION, but answered on PHONE/Earbuds -> preserving phone/earbuds audio!");
                }
            }
        } catch (Throwable t) {
            logError("[VoipFix] extractAndFixVoipPendingIntents failed", t);
        }
    }

    private static Field findFieldRecursive(Class<?> clazz, String fieldName) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            try {
                return curr.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    private static android.app.PendingIntent extractPendingIntentFromRemoteAction(Object act) {
        try {
            Field piField = findFieldRecursive(act.getClass(), "pendingIntent");
            if (piField != null) {
                piField.setAccessible(true);
                Object val = piField.get(act);
                if (val instanceof android.app.PendingIntent) {
                    return (android.app.PendingIntent) val;
                }
            }
            Field respField = findFieldRecursive(act.getClass(), "mResponse");
            if (respField != null) {
                respField.setAccessible(true);
                Object resp = respField.get(act);
                if (resp != null) {
                    Field respPiField = findFieldRecursive(resp.getClass(), "mPendingIntent");
                    if (respPiField != null) {
                        respPiField.setAccessible(true);
                        Object val = respPiField.get(resp);
                        if (val instanceof android.app.PendingIntent) {
                            return (android.app.PendingIntent) val;
                        }
                    }
                }
            }
            if (act instanceof android.os.Parcelable) {
                android.os.Parcel p = android.os.Parcel.obtain();
                try {
                    ((android.os.Parcelable) act).writeToParcel(p, 0);
                    p.setDataPosition(0);
                    p.readInt(); // viewId
                    return android.app.PendingIntent.readPendingIntentOrNullFromParcel(p);
                } finally {
                    p.recycle();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static android.app.PendingIntent getOrExtractVoipIntent(ClassLoader cl, boolean isAnswer) {
        try {
            Class<?> ns50Class = findClassSafe(cl, "com.mc.miband1.NotificationService50");
            if (ns50Class != null) {
                resolveNotificationService50Fields(ns50Class);
                Object inst = HookBridge.getStaticObjectField(ns50Class, sNs50InstanceField);
                if (inst != null) {
                    android.app.PendingIntent pi = (android.app.PendingIntent) HookBridge.getObjectField(
                            inst, isAnswer ? sNs50AnswerField : sNs50DeclineField);
                    if (pi != null) return pi;
                }
            }
        } catch (Throwable ignored) {
        }
        return isAnswer ? sCachedVoipAnswerIntent : sCachedVoipDeclineIntent;
    }

    private static void clearNotifyVoipIntents(ClassLoader cl) {
        try {
            Class<?> ns50Class = findClassSafe(cl, "com.mc.miband1.NotificationService50");
            if (ns50Class != null) {
                resolveNotificationService50Fields(ns50Class);
                Object inst = HookBridge.getStaticObjectField(ns50Class, sNs50InstanceField);
                if (inst != null) {
                    HookBridge.setObjectField(inst, sNs50DeclineField, null);
                    HookBridge.setObjectField(inst, sNs50AnswerField, null);
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedVoipAnswerIntent = null;
        sCachedVoipDeclineIntent = null;
    }

    private static void answerVoipCallAndRouteToWatch(final Context ctx, final ClassLoader cl, String triggerSource) {
        try {
            sAnsweredFromWatch = true;
            sLastWatchHfpActiveMs = System.currentTimeMillis();
            boolean alreadyInComm = isPhoneInVoipCommunication(ctx);
            if (!sVoipCallAnswered && !alreadyInComm) {
                android.app.PendingIntent answerPi = getOrExtractVoipIntent(cl, true);
                if (answerPi != null) {
                    logInfo("[VoipFix] Answering VoIP call from WATCH via PendingIntent (" + triggerSource + "): " + answerPi);
                    answerPi.send();
                    sVoipCallAnswered = true;
                    sLastVoipAnsweredMs = System.currentTimeMillis();
                } else {
                    logInfo("[VoipFix] No Answer PendingIntent found in " + triggerSource + "; relying on HFP ATA / Telecom");
                }
            } else {
                logInfo("[VoipFix] VoIP call already answered (inComm=" + alreadyInComm + ") in " + triggerSource);
                sVoipCallAnswered = true;
                if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = System.currentTimeMillis();
            }
            routeVoipAudioToWatch(ctx, cl, triggerSource);
        } catch (Throwable t) {
            logError("[VoipFix] answerVoipCallAndRouteToWatch failed", t);
        }
    }

    private static boolean isWatchBluetoothDevice(Context ctx, ClassLoader cl, android.bluetooth.BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            String addr = dev.getAddress();
            String watchMac = getConfiguredWatchMac(ctx, cl);
            if (watchMac != null && !watchMac.isEmpty() && watchMac.equalsIgnoreCase(addr)) {
                return true;
            }
            String name = dev.getName();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("balance") || lower.contains("amazfit") || lower.contains("zepp")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isWatchCurrentActiveHfpDevice(Context ctx, ClassLoader cl) {
        try {
            if (sCachedBluetoothHeadset != null) {
                Method getActive = android.bluetooth.BluetoothHeadset.class.getMethod("getActiveDevice");
                Object activeObj = getActive.invoke(sCachedBluetoothHeadset);
                if (activeObj instanceof android.bluetooth.BluetoothDevice) {
                    return isWatchBluetoothDevice(ctx, cl, (android.bluetooth.BluetoothDevice) activeObj);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void setHeadsetForceScoAudio(boolean force) {
        try {
            if (sCachedBluetoothHeadset != null) {
                Method setForce = android.bluetooth.BluetoothHeadset.class.getMethod("setForceScoAudio", boolean.class);
                setForce.invoke(sCachedBluetoothHeadset, force);
                sForceScoApplied = force;
                logInfo("[VoipFix] BluetoothHeadset.setForceScoAudio(" + force + ") invoked successfully");
            }
        } catch (Throwable t) {
            logInfo("[VoipFix] BluetoothHeadset.setForceScoAudio(" + force + ") unavailable: " + t.getMessage());
        }
    }

    private static String getConfiguredWatchMac(Context ctx, ClassLoader cl) {
        String mac = getWatchBleMac(ctx, cl);
        if (mac != null && !mac.isEmpty()) return mac;
        try {
            if (cl != null && ctx != null) {
                Class<?> upClass = findClassSafe(cl, "com.mc.miband1.model.UserPreferences");
                if (upClass != null) {
                    Object up = HookBridge.callStaticMethod(upClass, "getInstance", ctx);
                    if (up != null && hasMethodWithReturn(upClass, "z2", String.class)) {
                        String z2Mac = (String) HookBridge.callMethod(up, "z2");
                        if (z2Mac != null && !z2Mac.isEmpty()) return z2Mac;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasOtherConnectedBluetoothHeadset(Context ctx, ClassLoader cl) {
        try {
            if (sCachedBluetoothHeadset != null) {
                List<android.bluetooth.BluetoothDevice> connected = sCachedBluetoothHeadset.getConnectedDevices();
                if (connected != null) {
                    for (android.bluetooth.BluetoothDevice d : connected) {
                        if (d != null && !isWatchBluetoothDevice(ctx, cl, d)) {
                            return true;
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    List<android.media.AudioDeviceInfo> commDevs = am.getAvailableCommunicationDevices();
                    if (commDevs != null) {
                        for (android.media.AudioDeviceInfo info : commDevs) {
                            int type = info.getType();
                            if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                                    || type == android.media.AudioDeviceInfo.TYPE_HEARING_AID) {
                                CharSequence prod = info.getProductName();
                                String pName = prod != null ? prod.toString().toLowerCase(Locale.ROOT) : "";
                                if (!pName.contains("balance") && !pName.contains("amazfit") && !pName.contains("zepp")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Strict decision check: ONLY route VoIP audio to the watch if the call was explicitly
     * answered from the watch (HFP ATA set watch active device, or watch opened HFP SCO, or BLE Answer),
     * AND another Bluetooth headset/earbuds has not claimed the audio!
     */
    private static boolean shouldRouteVoipToWatch(Context ctx, ClassLoader cl) {
        if (sOtherBtHeadsetClaimedAudio) {
            return false;
        }
        if (sAnsweredFromWatch) {
            return true;
        }
        long now = System.currentTimeMillis();
        if ((now - sLastWatchHfpActiveMs) < 4500L || (now - sLastHfpScoActiveMs) < 4500L
                || isWatchCurrentActiveHfpDevice(ctx, cl)) {
            sAnsweredFromWatch = true;
            return true;
        }
        return false;
    }

    private static synchronized void registerVoipAudioAndHfpMonitorIfNeeded(final Context ctx, final ClassLoader cl) {
        if (sVoipMonitorRegistered || ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String procName = android.app.Application.getProcessName();
                if (procName != null && !procName.endsWith(":NotifyService")) {
                    return;
                }
            }
            ensureBluetoothHeadsetProxy(ctx);

            // 1. Register Bluetooth HFP Audio, Active Device & Vendor Event Receiver
            BroadcastReceiver hfpReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !isVoipCallFixEnabled(cl)) return;
                    String action = intent.getAction();
                    android.bluetooth.BluetoothDevice dev = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    boolean isWatchDev = isWatchBluetoothDevice(ctx, cl, dev);
                    long now = System.currentTimeMillis();

                    if (android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED.equals(action)) {
                        int state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1);
                        int prevState = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                        logInfo("[VoipFix] HFP ACTION_AUDIO_STATE_CHANGED: " + prevState + " -> " + state
                                + " (device=" + (dev != null ? dev.getAddress() + "/" + dev.getName() : "null")
                                + ", isWatch=" + isWatchDev
                                + ", withinVoip=" + isWithinVoipWindow() + ")");

                        if (state == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTING
                                || state == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                            if (isWatchDev) {
                                sLastHfpScoActiveMs = now;
                                sLastWatchHfpActiveMs = now;
                                if (isWithinVoipWindow()) {
                                    sAnsweredFromWatch = true;
                                    sOtherBtHeadsetClaimedAudio = false;
                                    getVoipHandler().postDelayed(() -> {
                                        if (isWithinVoipWindow() && !sVoipCallAnswered && !isPhoneInVoipCommunication(ctx)) {
                                            logInfo("[VoipFix] Watch opened HFP SCO while VoIP still ringing -> firing Answer PendingIntent!");
                                            answerVoipCallAndRouteToWatch(ctx, cl, "WATCH_HFP_SCO_CONNECTING");
                                        } else if (isWithinVoipWindow() && shouldRouteVoipToWatch(ctx, cl)) {
                                            sVoipCallAnswered = true;
                                            if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = System.currentTimeMillis();
                                            routeVoipAudioToWatch(ctx, cl, "WATCH_HFP_SCO_CONNECTED");
                                        }
                                    }, 250L);
                                }
                            } else if (dev != null && isWithinVoipWindow()) {
                                // Another Bluetooth headset/earbuds actively opened SCO audio!
                                // Only yield if watch didn't just initiate ATA within the last 2.5s
                                if (!sAnsweredFromWatch || (now - sLastWatchHfpActiveMs) > 2500L) {
                                    logInfo("[VoipFix] Non-watch Bluetooth headset (" + dev.getName() + ") connected SCO audio -> giving priority to Bluetooth headset!");
                                    sOtherBtHeadsetClaimedAudio = true;
                                    sAnsweredFromWatch = false;
                                }
                            }
                        }
                    } else if ("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED".equals(action)) {
                        logInfo("[VoipFix] HFP ACTION_ACTIVE_DEVICE_CHANGED -> device="
                                + (dev != null ? dev.getAddress() + "/" + dev.getName() : "null")
                                + ", isWatch=" + isWatchDev + ", withinVoip=" + isWithinVoipWindow()
                                + ", answeredFromWatch=" + sAnsweredFromWatch);
                        if (isWithinVoipWindow()) {
                            if (isWatchDev) {
                                // Watch became active HFP device during VoIP ringing -> Watch sent HFP ATA (Green Answer)!
                                sLastWatchHfpActiveMs = now;
                                sAnsweredFromWatch = true;
                                sOtherBtHeadsetClaimedAudio = false;
                                setHeadsetForceScoAudio(true);
                                // Wait briefly for BluetoothInCallService.answerCall() to put LINE into MODE_IN_COMMUNICATION,
                                // then open/keep SCO on watch (and defend against Xiaomi's 600ms isWearableWatchDeviceSetActive timer!)
                                routeVoipAudioToWatch(ctx, cl, "WATCH_HFP_ACTIVE_DEVICE_CHANGED(ATA)");
                            } else if (sAnsweredFromWatch && (now - sLastWatchHfpActiveMs) < 5000L) {
                                // Xiaomi HyperOS HeadsetStateMachine 600ms timer (isWearableWatchDeviceSetActive) kicked watch off
                                // mActiveDevice before LINE finished transitioning to ACTIVE! Defend Watch Answer route!
                                logInfo("[VoipFix] Detected HyperOS kicking watch off mActiveDevice -> "
                                        + (dev != null ? dev.getName() : "null")
                                        + " right after Watch Answer! Re-asserting watch audio route...");
                                routeVoipAudioToWatch(ctx, cl, "Defend against HyperOS 600ms watch deactivation");
                            } else if (dev != null) {
                                // User answered on earbuds or switched active HFP device to earbuds
                                sOtherBtHeadsetClaimedAudio = true;
                                sAnsweredFromWatch = false;
                            }
                        }
                    } else if (android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(action)) {
                        if (isWatchDev && isWithinVoipWindow()) {
                            logInfo("[VoipFix] Watch HFP VENDOR_SPECIFIC_HEADSET_EVENT received during VoIP window");
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            filter.addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(android.bluetooth.BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
            filter.addAction("android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED");
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(hfpReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(hfpReceiver, filter);
            }

            // 2. Register AudioManager OnModeChangedListener (Android 12+ / API 31+)
            if (Build.VERSION.SDK_INT >= 31) {
                android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.addOnModeChangedListener(ctx.getMainExecutor(), new android.media.AudioManager.OnModeChangedListener() {
                        @Override
                        public void onModeChanged(int mode) {
                            if (!isVoipCallFixEnabled(cl)) return;
                            logInfo("[VoipFix] AudioManager mode changed -> " + mode
                                    + " (withinVoip=" + isWithinVoipWindow()
                                    + ", answeredFromWatch=" + sAnsweredFromWatch
                                    + ", watchActiveHfp=" + isWatchCurrentActiveHfpDevice(ctx, cl)
                                    + ", otherBtHeadset=" + hasOtherConnectedBluetoothHeadset(ctx, cl) + ")");
                            if (mode == android.media.AudioManager.MODE_IN_COMMUNICATION && isWithinVoipWindow()) {
                                sVoipCallAnswered = true;
                                if (sLastVoipAnsweredMs == 0L) sLastVoipAnsweredMs = System.currentTimeMillis();
                                // Wait 150ms in case HFP ACTIVE_DEVICE_CHANGED arrives right alongside MODE_IN_COMMUNICATION
                                getVoipHandler().postDelayed(() -> {
                                    if (shouldRouteVoipToWatch(ctx, cl)) {
                                        logInfo("[VoipFix] MODE_IN_COMMUNICATION confirmed answered from WATCH -> routing audio to watch!");
                                        routeVoipAudioToWatch(ctx, cl, "OnModeChangedListener(MODE_IN_COMMUNICATION from Watch)");
                                    } else {
                                        logInfo("[VoipFix] MODE_IN_COMMUNICATION answered from PHONE or BT Earbuds (otherBtConnected="
                                                + hasOtherConnectedBluetoothHeadset(ctx, cl) + ") -> NOT routing to watch (preserving earbuds/phone priority)!");
                                    }
                                }, 150L);
                            } else if (mode == android.media.AudioManager.MODE_NORMAL && (sVoipCallAnswered || sAnsweredFromWatch || sForceScoApplied)) {
                                getVoipHandler().postDelayed(() -> {
                                    if (!isPhoneInVoipCommunication(ctx)) {
                                        stopVoipWatchAudioRouting(ctx, "OnModeChangedListener(MODE_NORMAL)");
                                    }
                                }, 1200L);
                            }
                        }
                    });
                }
            }

            sVoipMonitorRegistered = true;
            logInfo("[VoipFix] Registered HFP & AudioManager mode monitors in :NotifyService");
        } catch (Throwable t) {
            logError("[VoipFix] registerVoipAudioAndHfpMonitorIfNeeded failed", t);
        }
    }

    private static void ensureBluetoothHeadsetProxy(Context ctx) {
        if (sCachedBluetoothHeadset != null || ctx == null) return;
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                adapter.getProfileProxy(ctx.getApplicationContext(), new android.bluetooth.BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                        if (profile == android.bluetooth.BluetoothProfile.HEADSET && proxy instanceof android.bluetooth.BluetoothHeadset) {
                            sCachedBluetoothHeadset = (android.bluetooth.BluetoothHeadset) proxy;
                            logInfo("[VoipFix] BluetoothHeadset profile proxy connected");
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == android.bluetooth.BluetoothProfile.HEADSET) {
                            sCachedBluetoothHeadset = null;
                        }
                    }
                }, android.bluetooth.BluetoothProfile.HEADSET);
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.bluetooth.BluetoothDevice findWatchHeadsetDevice(Context ctx, ClassLoader cl) {
        try {
            String watchMac = getConfiguredWatchMac(ctx, cl);
            if (sCachedBluetoothHeadset != null) {
                List<android.bluetooth.BluetoothDevice> connected = sCachedBluetoothHeadset.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    for (android.bluetooth.BluetoothDevice d : connected) {
                        if (isWatchBluetoothDevice(ctx, cl, d)) {
                            return d;
                        }
                    }
                    // Do NOT blindly return connected.get(0) if another Bluetooth headset/earbuds is connected!
                    if (watchMac == null) {
                        return null;
                    }
                }
            }
            if (watchMac != null && android.bluetooth.BluetoothAdapter.checkBluetoothAddress(watchMac)) {
                return android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(watchMac);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void routeVoipAudioToWatch(final Context ctx, final ClassLoader cl, final String reason) {
        if (ctx == null) return;
        if (!shouldRouteVoipToWatch(ctx, cl)) {
            logInfo("[VoipFix] Skipping routeVoipAudioToWatch (" + reason + ") because call was NOT answered from watch (or other BT headset has priority)");
            return;
        }
        ensureBluetoothHeadsetProxy(ctx);
        // Defend across HyperOS 600ms watch deactivation timer & LINE's initial audio setup
        int[] delaysMs = new int[]{0, 350, 850, 1600, 2800};
        for (final int delay : delaysMs) {
            getVoipHandler().postDelayed(() -> {
                try {
                    if (!shouldRouteVoipToWatch(ctx, cl)) return;
                    boolean inComm = isPhoneInVoipCommunication(ctx);
                    if (delay > 1200 && !inComm) return;

                    android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;

                    // 1. Ensure speakerphone is OFF so it doesn't override Bluetooth SCO
                    if (am.isSpeakerphoneOn()) {
                        am.setSpeakerphoneOn(false);
                    }

                    // 2. Enable forceScoAudio in HeadsetService (ONLY requires BLUETOOTH_CONNECT, verified in Bluetooth.apk!)
                    // so HeadsetService.isScoAcceptable() always returns 0 (ACCEPTABLE) during VoIP transition.
                    if (!sForceScoApplied) {
                        setHeadsetForceScoAudio(true);
                    }

                    // 3. Android 12+ (API 31+) setCommunicationDevice specifically to the Watch Bluetooth SCO device!
                    // AudioService (system_server) handles setActiveDevice(watch) + startScoUsingVirtualVoiceCall() with privileged perms.
                    boolean routedSpecificWatchSco = false;
                    if (Build.VERSION.SDK_INT >= 31) {
                        String watchMac = getConfiguredWatchMac(ctx, cl);
                        android.media.AudioDeviceInfo currentComm = am.getCommunicationDevice();
                        boolean alreadyOnWatchSco = false;
                        if (currentComm != null && currentComm.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            CharSequence curProd = currentComm.getProductName();
                            String curName = curProd != null ? curProd.toString().toLowerCase(Locale.ROOT) : "";
                            String curAddr = "";
                            try {
                                curAddr = currentComm.getAddress() != null ? currentComm.getAddress() : "";
                            } catch (Throwable ignored) {
                            }
                            if (curName.contains("balance") || curName.contains("amazfit") || curName.contains("zepp")
                                    || (watchMac != null && watchMac.equalsIgnoreCase(curAddr))
                                    || !hasOtherConnectedBluetoothHeadset(ctx, cl)) {
                                alreadyOnWatchSco = true;
                                routedSpecificWatchSco = true;
                            }
                        }

                        if (!alreadyOnWatchSco) {
                            List<android.media.AudioDeviceInfo> commDevs = am.getAvailableCommunicationDevices();
                            android.media.AudioDeviceInfo targetSco = null;
                            StringBuilder devSummary = (delay == 0 || delay == 350) ? new StringBuilder() : null;
                            if (commDevs != null) {
                                for (android.media.AudioDeviceInfo info : commDevs) {
                                    int type = info.getType();
                                    CharSequence prod = info.getProductName();
                                    String pNameRaw = prod != null ? prod.toString() : "";
                                    String addr = "";
                                    try {
                                        addr = info.getAddress() != null ? info.getAddress() : "";
                                    } catch (Throwable ignored) {
                                    }
                                    if (devSummary != null) {
                                        devSummary.append("[").append(type).append(":").append(pNameRaw)
                                                .append("/").append(addr).append("] ");
                                    }
                                    if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                            || type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) {
                                        String pName = pNameRaw.toLowerCase(Locale.ROOT);
                                        boolean isWatchInfo = pName.contains("balance") || pName.contains("amazfit") || pName.contains("zepp")
                                                || (watchMac != null && watchMac.equalsIgnoreCase(addr));
                                        if (isWatchInfo) {
                                            targetSco = info;
                                            break;
                                        } else if (targetSco == null && !hasOtherConnectedBluetoothHeadset(ctx, cl)) {
                                            targetSco = info;
                                        }
                                    }
                                }
                            }
                            if (devSummary != null) {
                                logInfo("[VoipFix] AvailableCommDevices [+" + delay + "ms, inComm=" + inComm
                                        + ", curComm=" + (currentComm != null ? currentComm.getType() + ":" + currentComm.getProductName() : "null")
                                        + "]: " + devSummary);
                            }
                            if (targetSco != null) {
                                boolean setOk = am.setCommunicationDevice(targetSco);
                                routedSpecificWatchSco = setOk;
                                if (setOk) {
                                    sVoipVirtualScoStarted = true;
                                }
                                logInfo("[VoipFix] AudioManager.setCommunicationDevice(" + targetSco.getProductName()
                                        + ", type=" + targetSco.getType() + ") -> " + setOk
                                        + " [+" + delay + "ms, " + reason + "]");
                            }
                        }
                    }

                    // 4. Legacy startBluetoothSco fallback ONLY when watch is active HFP device or no other BT headset is connected
                    // (avoid accidentally opening SCO on earbuds if earbuds are connected and watch wasn't in availableCommDevices)
                    boolean safeForGenericSco = !hasOtherConnectedBluetoothHeadset(ctx, cl)
                            || isWatchCurrentActiveHfpDevice(ctx, cl);
                    if (!routedSpecificWatchSco && safeForGenericSco && !am.isBluetoothScoOn()) {
                        am.startBluetoothSco();
                        am.setBluetoothScoOn(true);
                        sVoipVirtualScoStarted = true;
                        logInfo("[VoipFix] Fallback am.startBluetoothSco() triggered [+" + delay + "ms, " + reason + "]");
                    }
                } catch (Throwable t) {
                    logError("[VoipFix] routeVoipAudioToWatch step (+" + delay + "ms) failed", t);
                }
            }, delay);
        }
    }

    private static void stopVoipWatchAudioRouting(Context ctx, String reason) {
        if (ctx == null) ctx = sAppContext;
        try {
            boolean wasRoutedToWatch = sAnsweredFromWatch || sVoipVirtualScoStarted || sForceScoApplied;
            sVoipCallAnswered = false;
            sAnsweredFromWatch = false;
            sOtherBtHeadsetClaimedAudio = false;
            sLastWatchHfpActiveMs = 0L;
            if (sForceScoApplied) {
                setHeadsetForceScoAudio(false);
            }
            if (!wasRoutedToWatch) {
                return;
            }
            logInfo("[VoipFix] Stopping VoIP watch audio routing (" + reason + ")");
            sVoipVirtualScoStarted = false;
            if (ctx != null) {
                android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    try {
                        am.stopBluetoothSco();
                        am.setBluetoothScoOn(false);
                    } catch (Throwable ignored) {
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        try {
                            am.clearCommunicationDevice();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logError("[VoipFix] stopVoipWatchAudioRouting failed", t);
        }
    }
}
