package com.amazfit.balance2fix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Version-independent on-device DEX bytecode auto-discovery engine for Notify for Amazfit
 * ({@code com.mc.amazfit1}), modeled after {@code MapsAutoResolver}.
 *
 * Locates all obfuscated hook targets (Weather, AGPS, BT Unlock, VoIP, Home Assistant Endpoint 0xA0)
 * directly from Notify's {@code base.apk} DEX bytecode using invariant protocol/log/UUID anchors
 * and bytecode call-graph expansion.
 *
 * Pure Java (zero Android/Xposed/native dependencies) — works identically on ART (libxposed API 102
 * and legacy Xposed API 82) and desktop JVM. Results are cached per APK fingerprint
 * (length + lastModified + pathHash) so subsequent launches take ~0ms.
 */
public final class NotifyAutoResolver {

    private static final String CACHE_SCHEMA_VERSION = "1.3";

    private static final String DESC_BLE_MANAGER = "Lcom/mc/miband1/bluetooth/BLEManager;";
    private static final String DESC_GATT_CALLBACK = "Landroid/bluetooth/BluetoothGattCallback;";
    private static final String DESC_USER_PREFS = "Lcom/mc/miband1/model/UserPreferences;";
    private static final String DESC_CONTEXT = "Landroid/content/Context;";
    private static final String DESC_INTENT = "Landroid/content/Intent;";
    private static final String DESC_STRING = "Ljava/lang/String;";
    private static final String DESC_FILE = "Ljava/io/File;";
    private static final String DESC_HASHMAP = "Ljava/util/HashMap;";
    private static final String DESC_UUID = "Ljava/util/UUID;";
    private static final String DESC_JSON_OBJ = "Lcom/google/gson/JsonObject;";
    private static final String DESC_JSON_ELEM = "Lcom/google/gson/JsonElement;";

    // Fixed Dalvik instruction lengths (in 16-bit code units) for opcodes 0x00..0xFF
    private static final int[] INSN_LEN = new int[256];

    static {
        for (int i = 0; i < 256; i++) INSN_LEN[i] = 1;

        INSN_LEN[0x02] = 2; INSN_LEN[0x03] = 3;
        INSN_LEN[0x05] = 2; INSN_LEN[0x06] = 3;
        INSN_LEN[0x08] = 2; INSN_LEN[0x09] = 3;

        INSN_LEN[0x13] = 2; INSN_LEN[0x14] = 3; INSN_LEN[0x15] = 2;
        INSN_LEN[0x16] = 2; INSN_LEN[0x17] = 3; INSN_LEN[0x18] = 5; INSN_LEN[0x19] = 2;
        INSN_LEN[0x1a] = 2; INSN_LEN[0x1b] = 3; INSN_LEN[0x1c] = 2;
        INSN_LEN[0x1f] = 2; INSN_LEN[0x20] = 2;
        INSN_LEN[0x22] = 2; INSN_LEN[0x23] = 2;
        INSN_LEN[0x24] = 3; INSN_LEN[0x25] = 3; INSN_LEN[0x26] = 3;
        INSN_LEN[0x29] = 2; INSN_LEN[0x2a] = 3;
        INSN_LEN[0x2b] = 3; INSN_LEN[0x2c] = 3;

        for (int op = 0x2d; op <= 0x3d; op++) INSN_LEN[op] = 2;
        for (int op = 0x44; op <= 0x6d; op++) INSN_LEN[op] = 2;
        for (int op = 0x6e; op <= 0x72; op++) INSN_LEN[op] = 3;
        for (int op = 0x74; op <= 0x78; op++) INSN_LEN[op] = 3;
        for (int op = 0x90; op <= 0xaf; op++) INSN_LEN[op] = 2;
        for (int op = 0xd0; op <= 0xe2; op++) INSN_LEN[op] = 2;

        INSN_LEN[0xfa] = 4; INSN_LEN[0xfb] = 4;
        INSN_LEN[0xfc] = 3; INSN_LEN[0xfd] = 3;
        INSN_LEN[0xfe] = 2; INSN_LEN[0xff] = 2;
    }

    public static final class ResolvedTargets {
        public String apkFingerprint;
        public long resolveTimeMs;
        public boolean fromCache;

        // --- Core BLE & Broadcast ---
        public String baseEndpointClass;          // e.g. bx
        public String bleDevicePacketClass;       // e.g. xp
        public String bleRawSenderClass;          // e.g. ac2 / gc2 / fc2
        public String bleRawSenderMethod;         // e.g. g (BLEManager, int, cb, byte[], boolean)
        public String bleRawSender3ArgMethod;     // e.g. l (BLEManager, int, byte[])
        public String endpointA0Class;            // e.g. g33 / x23
        public String endpointA0ReceiveMethod;    // e.g. f (xp, int, byte[])
        public String localBroadcastClass;        // e.g. v38 / w38 / s28 / e48
        public String localBroadcastMethod;       // e.g. h2 / s2 / f2 (Context, String)V
        public String localBroadcastIntentMethod; // e.g. k0 / r0 / j0 (String)Intent
        public String localBroadcastSendIntentMethod; // e.g. g2 / r2 / e2 (Context, Intent)V
        public String agpsTimeHelperClass;        // e.g. yx7 / ux7

        // --- Weather Sync Pipeline ---
        public String endpoint14Class;            // e.g. kf8 / nf8 / jf8
        public String endpoint14HttpMethod;       // e.g. o (BLEManager, String, HashMap)
        public String weatherDataClass;           // e.g. oe8 / re8 / ne8
        public String weatherSyncClass;           // e.g. if8 / lf8 / hf8
        public String weatherNeedSyncMethod;      // e.g. j / i (UserPreferences)Z
        public String weatherTriggerMethod;       // e.g. l / k (Context, boolean)
        public String placeModelClass;            // e.g. ll5 / nl5 / fl5
        public String dailyWeatherU51Class;       // e.g. v51 / u51
        public String placeJsonBuilderClass;      // e.g. q38 / z38 / wf
        public String placeJsonMethod;            // e.g. D / E / u (UserPreferences[, String])
        public String weatherHelperRf8Class;      // e.g. of8 / rf8 / nf8
        public String fallbackHourMethod;         // e.g. h / i / g (UserPreferences, u51, rf8, D, J)
        public String httpRespClass;              // e.g. n86 / s86 / q86
        public String v6WrapperClass;             // e.g. v86 / a96 / r86
        public String bleHttpProxyClass;          // e.g. zu5 / ev5 / wu5

        // --- AGPS Pipeline ---
        public String agpsPackerClass;            // e.g. sv / uv
        public String agpsPackMethod;             // e.g. d (Context)File
        public String agpsSendMethod;             // e.g. h / i (BLEManager, File, s6)
        public String agpsEndpointS6Class;        // e.g. s6
        public String agpsCrcClass;               // e.g. qn0
        public String agpsCrcMethod;              // e.g. c ([B)I
        public String agpsWaitQn7Class;           // e.g. vn7 / qn7
        public String agpsTransferOu8Class;       // e.g. ou8
        public String agpsTimeoutMu8Class;        // e.g. mu8
        public String agpsProgressKu8Class;       // e.g. ku8 / ci
        public String agpsProgressDoneMethod;     // e.g. l / o (Z)V
        public String agpsCapXm6Class;            // e.g. bn6 / xm6
        public String agpsConfigGroupEnumClass;   // e.g. mv0 / nv0
        public String agpsConfigGroupField;       // e.g. q (AGPS enum constant field)
        public String fwRecvClass;                // e.g. mj4 / lj4

        // --- VoIP Call Pipeline ---
        public String voipNotifyHelperClass;      // e.g. zf0 / yf0
        public String voipBa8Class;               // e.g. ba8
        public String voipWatchAnswerClass;       // e.g. q25 / pa5

        // --- GATT Callback Subclasses (for Official Zepp DfuV5 Installer) ---
        public final List<String> gattCallbackClasses = new ArrayList<>();

        public int countResolvedClasses() {
            int n = 0;
            if (notEmpty(baseEndpointClass)) n++;
            if (notEmpty(bleDevicePacketClass)) n++;
            if (notEmpty(bleRawSenderClass)) n++;
            if (notEmpty(endpointA0Class)) n++;
            if (notEmpty(localBroadcastClass)) n++;
            if (notEmpty(agpsTimeHelperClass)) n++;
            if (notEmpty(endpoint14Class)) n++;
            if (notEmpty(weatherDataClass)) n++;
            if (notEmpty(weatherSyncClass)) n++;
            if (notEmpty(placeModelClass)) n++;
            if (notEmpty(dailyWeatherU51Class)) n++;
            if (notEmpty(placeJsonBuilderClass)) n++;
            if (notEmpty(weatherHelperRf8Class)) n++;
            if (notEmpty(httpRespClass)) n++;
            if (notEmpty(v6WrapperClass)) n++;
            if (notEmpty(bleHttpProxyClass)) n++;
            if (notEmpty(agpsPackerClass)) n++;
            if (notEmpty(agpsEndpointS6Class)) n++;
            if (notEmpty(agpsCrcClass)) n++;
            if (notEmpty(agpsWaitQn7Class)) n++;
            if (notEmpty(agpsTransferOu8Class)) n++;
            if (notEmpty(agpsTimeoutMu8Class)) n++;
            if (notEmpty(agpsProgressKu8Class)) n++;
            if (notEmpty(agpsCapXm6Class)) n++;
            if (notEmpty(agpsConfigGroupEnumClass)) n++;
            if (notEmpty(fwRecvClass)) n++;
            if (notEmpty(voipNotifyHelperClass)) n++;
            if (notEmpty(voipBa8Class)) n++;
            if (notEmpty(voipWatchAnswerClass)) n++;
            return n;
        }

        public static final int TOTAL_CLASS_SLOTS = 29;

        public boolean isCoreComplete() {
            return notEmpty(endpoint14Class)
                    && notEmpty(bleRawSenderClass)
                    && notEmpty(endpointA0Class)
                    && notEmpty(agpsPackerClass)
                    && notEmpty(agpsEndpointS6Class)
                    && notEmpty(voipWatchAnswerClass)
                    && notEmpty(voipNotifyHelperClass);
        }

        public String toShortStatusString() {
            return countResolvedClasses() + "/" + TOTAL_CLASS_SLOTS
                    + (fromCache ? " (快取)" : (" (掃描 " + resolveTimeMs + "ms)"));
        }

        public String toSummaryString() {
            return "resolved=" + countResolvedClasses() + "/" + TOTAL_CLASS_SLOTS
                    + " [baseEp=" + baseEndpointClass
                    + ", xp=" + bleDevicePacketClass
                    + ", rawTx=" + bleRawSenderClass + "." + bleRawSenderMethod
                    + ", epA0=" + endpointA0Class + "." + endpointA0ReceiveMethod
                    + ", bcast=" + localBroadcastClass + "(" + localBroadcastIntentMethod + "/" + localBroadcastSendIntentMethod + "/" + localBroadcastMethod + ")"
                    + ", agpsTime=" + agpsTimeHelperClass
                    + " | ep14=" + endpoint14Class + "." + endpoint14HttpMethod
                    + ", wData=" + weatherDataClass
                    + ", wSync=" + weatherSyncClass + "(" + weatherNeedSyncMethod + "/" + weatherTriggerMethod + ")"
                    + ", place=" + placeModelClass
                    + ", daily=" + dailyWeatherU51Class
                    + ", jsonBld=" + placeJsonBuilderClass + "(" + placeJsonMethod + "/" + fallbackHourMethod + ")"
                    + ", rf8=" + weatherHelperRf8Class
                    + ", resp=" + httpRespClass
                    + ", v6Wrap=" + v6WrapperClass
                    + ", proxy=" + bleHttpProxyClass
                    + " | agpsPack=" + agpsPackerClass + "(" + agpsPackMethod + "/" + agpsSendMethod + ")"
                    + ", s6=" + agpsEndpointS6Class
                    + ", crc=" + agpsCrcClass + "." + agpsCrcMethod
                    + ", qn7=" + agpsWaitQn7Class
                    + ", ou8=" + agpsTransferOu8Class
                    + ", mu8=" + agpsTimeoutMu8Class
                    + ", ku8=" + agpsProgressKu8Class + "." + agpsProgressDoneMethod
                    + ", xm6=" + agpsCapXm6Class
                    + ", nv0=" + agpsConfigGroupEnumClass + "." + agpsConfigGroupField
                    + ", fwRecv=" + fwRecvClass
                    + " | voipHelper=" + voipNotifyHelperClass
                    + ", ba8=" + voipBa8Class
                    + ", pa5=" + voipWatchAnswerClass + "]";
        }

        public Properties toProperties() {
            Properties p = new Properties();
            p.setProperty("schema", CACHE_SCHEMA_VERSION);
            put(p, "apkFingerprint", apkFingerprint);
            put(p, "baseEndpointClass", baseEndpointClass);
            put(p, "bleDevicePacketClass", bleDevicePacketClass);
            put(p, "bleRawSenderClass", bleRawSenderClass);
            put(p, "bleRawSenderMethod", bleRawSenderMethod);
            put(p, "bleRawSender3ArgMethod", bleRawSender3ArgMethod);
            put(p, "endpointA0Class", endpointA0Class);
            put(p, "endpointA0ReceiveMethod", endpointA0ReceiveMethod);
            put(p, "localBroadcastClass", localBroadcastClass);
            put(p, "localBroadcastMethod", localBroadcastMethod);
            put(p, "localBroadcastIntentMethod", localBroadcastIntentMethod);
            put(p, "localBroadcastSendIntentMethod", localBroadcastSendIntentMethod);
            put(p, "agpsTimeHelperClass", agpsTimeHelperClass);
            put(p, "endpoint14Class", endpoint14Class);
            put(p, "endpoint14HttpMethod", endpoint14HttpMethod);
            put(p, "weatherDataClass", weatherDataClass);
            put(p, "weatherSyncClass", weatherSyncClass);
            put(p, "weatherNeedSyncMethod", weatherNeedSyncMethod);
            put(p, "weatherTriggerMethod", weatherTriggerMethod);
            put(p, "placeModelClass", placeModelClass);
            put(p, "dailyWeatherU51Class", dailyWeatherU51Class);
            put(p, "placeJsonBuilderClass", placeJsonBuilderClass);
            put(p, "placeJsonMethod", placeJsonMethod);
            put(p, "weatherHelperRf8Class", weatherHelperRf8Class);
            put(p, "fallbackHourMethod", fallbackHourMethod);
            put(p, "httpRespClass", httpRespClass);
            put(p, "v6WrapperClass", v6WrapperClass);
            put(p, "bleHttpProxyClass", bleHttpProxyClass);
            put(p, "agpsPackerClass", agpsPackerClass);
            put(p, "agpsPackMethod", agpsPackMethod);
            put(p, "agpsSendMethod", agpsSendMethod);
            put(p, "agpsEndpointS6Class", agpsEndpointS6Class);
            put(p, "agpsCrcClass", agpsCrcClass);
            put(p, "agpsCrcMethod", agpsCrcMethod);
            put(p, "agpsWaitQn7Class", agpsWaitQn7Class);
            put(p, "agpsTransferOu8Class", agpsTransferOu8Class);
            put(p, "agpsTimeoutMu8Class", agpsTimeoutMu8Class);
            put(p, "agpsProgressKu8Class", agpsProgressKu8Class);
            put(p, "agpsProgressDoneMethod", agpsProgressDoneMethod);
            put(p, "agpsCapXm6Class", agpsCapXm6Class);
            put(p, "agpsConfigGroupEnumClass", agpsConfigGroupEnumClass);
            put(p, "agpsConfigGroupField", agpsConfigGroupField);
            put(p, "fwRecvClass", fwRecvClass);
            put(p, "voipNotifyHelperClass", voipNotifyHelperClass);
            put(p, "voipBa8Class", voipBa8Class);
            put(p, "voipWatchAnswerClass", voipWatchAnswerClass);
            StringBuilder gcb = new StringBuilder();
            for (int i = 0; i < gattCallbackClasses.size(); i++) {
                if (i > 0) gcb.append(",");
                gcb.append(gattCallbackClasses.get(i));
            }
            p.setProperty("gattCallbackClasses", gcb.toString());
            return p;
        }

        public static ResolvedTargets fromProperties(Properties p, String expectedFingerprint) {
            if (p == null) return null;
            if (!CACHE_SCHEMA_VERSION.equals(p.getProperty("schema"))) return null;
            String fp = p.getProperty("apkFingerprint");
            if (expectedFingerprint == null || !expectedFingerprint.equals(fp)) return null;

            ResolvedTargets r = new ResolvedTargets();
            r.apkFingerprint = fp;
            r.fromCache = true;
            r.baseEndpointClass = p.getProperty("baseEndpointClass");
            r.bleDevicePacketClass = p.getProperty("bleDevicePacketClass");
            r.bleRawSenderClass = p.getProperty("bleRawSenderClass");
            r.bleRawSenderMethod = p.getProperty("bleRawSenderMethod");
            r.bleRawSender3ArgMethod = p.getProperty("bleRawSender3ArgMethod");
            r.endpointA0Class = p.getProperty("endpointA0Class");
            r.endpointA0ReceiveMethod = p.getProperty("endpointA0ReceiveMethod");
            r.localBroadcastClass = p.getProperty("localBroadcastClass");
            r.localBroadcastMethod = p.getProperty("localBroadcastMethod");
            r.localBroadcastIntentMethod = p.getProperty("localBroadcastIntentMethod");
            r.localBroadcastSendIntentMethod = p.getProperty("localBroadcastSendIntentMethod");
            r.agpsTimeHelperClass = p.getProperty("agpsTimeHelperClass");
            r.endpoint14Class = p.getProperty("endpoint14Class");
            r.endpoint14HttpMethod = p.getProperty("endpoint14HttpMethod");
            r.weatherDataClass = p.getProperty("weatherDataClass");
            r.weatherSyncClass = p.getProperty("weatherSyncClass");
            r.weatherNeedSyncMethod = p.getProperty("weatherNeedSyncMethod");
            r.weatherTriggerMethod = p.getProperty("weatherTriggerMethod");
            r.placeModelClass = p.getProperty("placeModelClass");
            r.dailyWeatherU51Class = p.getProperty("dailyWeatherU51Class");
            r.placeJsonBuilderClass = p.getProperty("placeJsonBuilderClass");
            r.placeJsonMethod = p.getProperty("placeJsonMethod");
            r.weatherHelperRf8Class = p.getProperty("weatherHelperRf8Class");
            r.fallbackHourMethod = p.getProperty("fallbackHourMethod");
            r.httpRespClass = p.getProperty("httpRespClass");
            r.v6WrapperClass = p.getProperty("v6WrapperClass");
            r.bleHttpProxyClass = p.getProperty("bleHttpProxyClass");
            r.agpsPackerClass = p.getProperty("agpsPackerClass");
            r.agpsPackMethod = p.getProperty("agpsPackMethod");
            r.agpsSendMethod = p.getProperty("agpsSendMethod");
            r.agpsEndpointS6Class = p.getProperty("agpsEndpointS6Class");
            r.agpsCrcClass = p.getProperty("agpsCrcClass");
            r.agpsCrcMethod = p.getProperty("agpsCrcMethod");
            r.agpsWaitQn7Class = p.getProperty("agpsWaitQn7Class");
            r.agpsTransferOu8Class = p.getProperty("agpsTransferOu8Class");
            r.agpsTimeoutMu8Class = p.getProperty("agpsTimeoutMu8Class");
            r.agpsProgressKu8Class = p.getProperty("agpsProgressKu8Class");
            r.agpsProgressDoneMethod = p.getProperty("agpsProgressDoneMethod");
            r.agpsCapXm6Class = p.getProperty("agpsCapXm6Class");
            r.agpsConfigGroupEnumClass = p.getProperty("agpsConfigGroupEnumClass");
            r.agpsConfigGroupField = p.getProperty("agpsConfigGroupField");
            r.fwRecvClass = p.getProperty("fwRecvClass");
            r.voipNotifyHelperClass = p.getProperty("voipNotifyHelperClass");
            r.voipBa8Class = p.getProperty("voipBa8Class");
            r.voipWatchAnswerClass = p.getProperty("voipWatchAnswerClass");
            String gcb = p.getProperty("gattCallbackClasses", "");
            if (!gcb.isEmpty()) {
                for (String s : gcb.split(",")) {
                    if (!s.trim().isEmpty()) r.gattCallbackClasses.add(s.trim());
                }
            }
            return r.isCoreComplete() ? r : null;
        }

        private static void put(Properties p, String k, String v) {
            if (v != null && !v.isEmpty()) p.setProperty(k, v);
        }

        private static boolean notEmpty(String s) {
            return s != null && !s.isEmpty();
        }
    }

    /**
     * Resolves all hook targets from disk cache if APK fingerprint matches, otherwise scans
     * Notify's base.apk DEX bytecode and writes cache file atomically.
     */
    public static ResolvedTargets resolveOrLoadCache(String apkPath, File cacheFile) throws IOException {
        if (apkPath == null || apkPath.isEmpty()) {
            throw new IOException("Empty apkPath");
        }
        File apkFile = new File(apkPath);
        if (!apkFile.isFile() || !apkFile.canRead()) {
            throw new IOException("Cannot read APK: " + apkPath);
        }
        String fingerprint = apkFile.length() + "_" + apkFile.lastModified() + "_" + apkFile.getAbsolutePath().hashCode();

        // 1. Fast disk cache (0ms path)
        if (cacheFile != null && cacheFile.isFile() && cacheFile.canRead()) {
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                Properties p = new Properties();
                p.load(fis);
                ResolvedTargets cached = ResolvedTargets.fromProperties(p, fingerprint);
                if (cached != null) {
                    cached.resolveTimeMs = 0L;
                    return cached;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Cold DEX bytecode scan
        long t0 = System.currentTimeMillis();
        ResolvedTargets resolved = scanApk(apkFile, fingerprint);
        resolved.resolveTimeMs = System.currentTimeMillis() - t0;
        resolved.fromCache = false;

        // 3. Persist cache atomically
        if (cacheFile != null && resolved.isCoreComplete()) {
            try {
                File parent = cacheFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                File tmp = new File(cacheFile.getAbsolutePath() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    resolved.toProperties().store(fos, "NotifyBalance2Fix Dynamic Auto-Resolver Cache");
                }
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
                tmp.renameTo(cacheFile);
            } catch (Throwable ignored) {
            }
        }
        return resolved;
    }

    /**
     * Direct multi-pass DEX scan (no cache). Suitable for both cold runtime scan and offline PC verification.
     */
    public static ResolvedTargets scanApk(File apkFile, String fingerprint) throws IOException {
        ResolvedTargets out = new ResolvedTargets();
        out.apkFingerprint = fingerprint;

        List<DexReader> dexList = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            List<String> dexNames = new ArrayList<>();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!e.isDirectory() && name.startsWith("classes") && name.endsWith(".dex") && !name.contains("/")) {
                    dexNames.add(name);
                }
            }
            for (String entryName : dexNames) {
                dexList.add(new DexReader(readZipEntry(zip, entryName)));
            }
        }

        // =========================================================================
        // PASS 1: String-Anchor Root Discovery
        // =========================================================================
        MethodRef ep14Anchor = null;
        MethodRef agpsPackAnchor = null;
        MethodRef epA0Anchor = null;
        MethodRef configGroupClinit = null;
        Set<String> agpsPackCrcCandidates = new LinkedHashSet<>();

        for (DexReader dex : dexList) {
            if (out.endpoint14Class == null) {
                ep14Anchor = findFirstMethodByStrings(dex, new String[]{
                        "d8fdf7ac-b858-4b6d-b582-878962001bac",
                        "GNSS weather cache updated: previousLocationKey="
                }, null);
                if (ep14Anchor != null) {
                    out.endpoint14Class = descriptorToJavaName(ep14Anchor.classDesc);
                    String superDesc = dex.getSuperClassDesc(ep14Anchor.classTypeIdx);
                    if (isObfuscatedAppClass(superDesc)) {
                        out.baseEndpointClass = descriptorToJavaName(superDesc);
                    }
                }
            }

            if (out.agpsPackerClass == null) {
                agpsPackAnchor = findFirstMethodByStrings(dex, new String[]{
                        "EPO_GR_3.DAT",
                        "The AGPS archive uses an unrecognized file format"
                }, null);
                if (agpsPackAnchor != null) {
                    out.agpsPackerClass = descriptorToJavaName(agpsPackAnchor.classDesc);
                    out.agpsPackMethod = agpsPackAnchor.methodName;
                    collectCrcCandidatesFromAgpsPacker(dex, agpsPackAnchor.codeOff, agpsPackCrcCandidates);
                }
            }

            if (out.endpointA0Class == null) {
                epA0Anchor = findFirstMethodByStrings(dex, new String[]{
                        "manageInitApp - appId = ",
                        "manageInitApp - appId =",
                        "manageInitApp = App not supported ",
                        "manageAppData = ",
                        "KOMOOT_GET",
                        "GMAP_GET",
                        "GCLOCK_SNOOZE",
                        "BUTTON_LIST"
                }, null);
                if (epA0Anchor != null) {
                    out.endpointA0Class = descriptorToJavaName(epA0Anchor.classDesc);
                    if (out.baseEndpointClass == null) {
                        String superDesc = dex.getSuperClassDesc(epA0Anchor.classTypeIdx);
                        if (isObfuscatedAppClass(superDesc)) {
                            out.baseEndpointClass = descriptorToJavaName(superDesc);
                        }
                    }
                }
            }

            if (out.bleRawSenderClass == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "ERROR: need encrypt but auth key is missing ["
                }, null);
                if (m != null) {
                    out.bleRawSenderClass = descriptorToJavaName(m.classDesc);
                    resolveRawSenderMethods(dex, m.classTypeIdx, out);
                }
            }

            if (out.voipWatchAnswerClass == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "ZeppOS NOTIFICATION_DISMISS_MUTE_CALL received",
                        "ZeppOS NOTIFICATION_DISMISS_REJECT_CALL received"
                }, null);
                if (m != null) {
                    out.voipWatchAnswerClass = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.voipNotifyHelperClass == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "rejectCall - reject mechanisms failed; falling back to muteCall",
                        "muteCall requested - incomingCallNow=",
                        "Unknown number - call ignored",
                        "com.mc.miband.stopIncomingCall",
                        "silenceRinger",
                        "getITelephony",
                        "android.permission.CALL_PRIVILEGED",
                        "5533acf8-a035-45c2-bd70-ed7546552523"
                }, null);
                if (m != null) {
                    out.voipNotifyHelperClass = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.voipBa8Class == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "Teams VoIP notification event=",
                        "3cx_missed_calls",
                        "incoming-call-channel"
                }, null);
                if (m != null) {
                    out.voipBa8Class = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.fwRecvClass == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "\nSuggestion: Try to agps v2",
                        "\nSuggestion: Try to agps v3"
                }, null);
                if (m != null) {
                    out.fwRecvClass = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.agpsTimeHelperClass == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "cHJlZmVyZW5jZXNVSQ=="
                }, null);
                if (m != null) {
                    out.agpsTimeHelperClass = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.dailyWeatherU51Class == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "com.mc.miband1.modelVirtual.weather.Daily"
                }, null);
                if (m != null) {
                    out.dailyWeatherU51Class = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.agpsWaitQn7Class == null) {
                MethodRef m = findFirstMethodByStrings(dex, new String[]{
                        "Cannot execute synchronous operation from the UI thread."
                }, null);
                if (m != null) {
                    out.agpsWaitQn7Class = descriptorToJavaName(m.classDesc);
                }
            }

            if (out.agpsConfigGroupEnumClass == null) {
                configGroupClinit = findFirstMethodByStrings(dex, new String[]{
                        "SOUND_AND_VIBRATION"
                }, "<clinit>");
                if (configGroupClinit != null) {
                    out.agpsConfigGroupEnumClass = descriptorToJavaName(configGroupClinit.classDesc);
                    out.agpsConfigGroupField = findEnumFieldForStringInClinit(dex, configGroupClinit.codeOff, configGroupClinit.classDesc, "AGPS");
                }
            }
        }

        // =========================================================================
        // PASS 2: Call-Graph & Type-Signature Expansion
        // =========================================================================
        String ep14Desc = javaNameToDescriptor(out.endpoint14Class);
        String agpsPackerDesc = javaNameToDescriptor(out.agpsPackerClass);
        String baseEpDesc = javaNameToDescriptor(out.baseEndpointClass);
        String epA0Desc = javaNameToDescriptor(out.endpointA0Class);
        String configGroupDesc = javaNameToDescriptor(out.agpsConfigGroupEnumClass);
        Set<String> ku8InitCandidates = new LinkedHashSet<>();

        for (DexReader dex : dexList) {
            // 2A. Expand Endpoint 14 HTTP Weather Builder -> httpResp, v6Wrapper, weatherSync, placeJsonBuilder, placeModel
            if (ep14Desc != null && (out.endpoint14HttpMethod == null || out.placeJsonBuilderClass == null)) {
                int ep14TypeIdx = dex.findTypeIndex(ep14Desc);
                if (ep14TypeIdx >= 0) {
                    expandEndpoint14(dex, ep14TypeIdx, out);
                }
            }

            // 2B. Expand AGPS Packer -> agpsSendMethod, agpsEndpointS6Class, & ku8 candidates
            if (agpsPackerDesc != null && out.agpsEndpointS6Class == null) {
                int packerTypeIdx = dex.findTypeIndex(agpsPackerDesc);
                if (packerTypeIdx >= 0) {
                    expandAgpsPacker(dex, packerTypeIdx, out, ku8InitCandidates);
                }
            }

            // 2C-0. Structural fallback for Endpoint 0xA0: baseEndpointClass.e()Z has `instance-of v0, v0, <EndpointA0Class>` (opcode 0x20)
            if (epA0Desc == null && baseEpDesc != null) {
                int baseIdx = dex.findTypeIndex(baseEpDesc);
                if (baseIdx >= 0) {
                    String foundA0Desc = resolveEndpointA0FromBaseEndpoint(dex, baseIdx);
                    if (foundA0Desc != null) {
                        out.endpointA0Class = descriptorToJavaName(foundA0Desc);
                        epA0Desc = foundA0Desc;
                    }
                }
            }

            // 2C. Expand Endpoint 0xA0 -> bleDevicePacketClass (xp), endpointA0ReceiveMethod, & bleRawSender fallback
            if (epA0Desc != null && (out.bleDevicePacketClass == null || out.endpointA0ReceiveMethod == null || out.bleRawSenderClass == null)) {
                int a0TypeIdx = dex.findTypeIndex(epA0Desc);
                if (a0TypeIdx >= 0) {
                    expandEndpointA0(dex, a0TypeIdx, out);
                }
            }

            // 2D. Validate CRC32 helper class (qn0: has both ([B)I and ([B)J)
            if (out.agpsCrcClass == null && !agpsPackCrcCandidates.isEmpty()) {
                for (String cand : agpsPackCrcCandidates) {
                    int sep = cand.indexOf('#');
                    String cDesc = cand.substring(0, sep);
                    String mName = cand.substring(sep + 1);
                    int cIdx = dex.findTypeIndex(cDesc);
                    if (cIdx < 0) continue;
                    List<MethodRef> ms = dex.getDefinedMethodsOfClass(cIdx);
                    if (ms.isEmpty() || ms.size() > 10) continue;
                    boolean hasLongCrc = false;
                    for (MethodRef mr : ms) {
                        if ("([B)J".equals(dex.getProtoKey(dex.getMethodProtoIdx(mr.methodIdx)))) {
                            hasLongCrc = true;
                            break;
                        }
                    }
                    if (hasLongCrc) {
                        out.agpsCrcClass = descriptorToJavaName(cDesc);
                        out.agpsCrcMethod = mName;
                        break;
                    }
                }
            }

            // 2E. Expand agpsCapXm6Class (subclass of baseEndpointClass with method taking (BLEManager, configGroupEnum))
            if (out.agpsCapXm6Class == null && baseEpDesc != null && configGroupDesc != null) {
                int baseIdx = dex.findTypeIndex(baseEpDesc);
                int cfgIdx = dex.findTypeIndex(configGroupDesc);
                if (baseIdx >= 0 && cfgIdx >= 0) {
                    String foundXm6 = findSubclassWithMethodParams(dex, baseIdx, DESC_BLE_MANAGER, configGroupDesc);
                    if (foundXm6 != null) {
                        out.agpsCapXm6Class = descriptorToJavaName(foundXm6);
                    }
                }
            }

            // 2F. Expand voipWatchAnswerClass fallback (subclass of baseEndpointClass referencing "notificationModuleVersion")
            if (out.voipWatchAnswerClass == null && baseEpDesc != null) {
                int sIdx = dex.findStringIndex("notificationModuleVersion");
                int baseIdx = dex.findTypeIndex(baseEpDesc);
                if (sIdx >= 0 && baseIdx >= 0) {
                    MethodRef m = dex.findMethodReferencingStringInSubclass(sIdx, baseIdx);
                    if (m != null) {
                        out.voipWatchAnswerClass = descriptorToJavaName(m.classDesc);
                    }
                }
            }

            // 2G. Expand BLE HTTP Proxy (subclass of baseEndpointClass referencing Zepp OS HTTP proxy URI or legacy action)
            if (baseEpDesc != null && out.bleHttpProxyClass == null) {
                int baseTypeIdx = dex.findTypeIndex(baseEpDesc);
                if (baseTypeIdx >= 0) {
                    for (String pStr : new String[]{
                            "httpproxy://download?sessionid=",
                            "com.huami.midong/settings",
                            "com.mc.miband.weatherProxyStatusUpdate",
                            "weatherProxyStatusUpdate"
                    }) {
                        int proxyStrIdx = dex.findStringIndex(pStr);
                        if (proxyStrIdx >= 0) {
                            MethodRef m = dex.findMethodReferencingStringInSubclass(proxyStrIdx, baseTypeIdx);
                            if (m != null) {
                                out.bleHttpProxyClass = descriptorToJavaName(m.classDesc);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PASS 3: Secondary Graph Expansion (ku8, localBroadcast, ou8/mu8, rawSender methods,
        //         weatherSync trigger, placeJsonBuilder methods, rf8, weatherData)
        // =========================================================================
        String wSyncDesc = javaNameToDescriptor(out.weatherSyncClass);
        String jsonBldDesc = javaNameToDescriptor(out.placeJsonBuilderClass);
        String s6Desc = javaNameToDescriptor(out.agpsEndpointS6Class);
        String crcDesc = javaNameToDescriptor(out.agpsCrcClass);
        String rawSenderDesc = javaNameToDescriptor(out.bleRawSenderClass);

        // First resolve ku8 from ku8InitCandidates (classes instantiated inside agpsPacker.<sendMethod> having q(I)V & l(Z)V or referencing AGPS broadcast keys)
        if (out.agpsProgressKu8Class == null && !ku8InitCandidates.isEmpty()) {
            for (DexReader dex : dexList) {
                int[] fwStrIdxs = new int[]{
                        dex.findStringIndex("4436627f-408a-43f1-a12b-983c5b501c01"),
                        dex.findStringIndex("2c1cdc19-8e2f-46c2-9d53-05832e1c0240"),
                        dex.findStringIndex("com.mc.miband.firmwareKO"),
                        dex.findStringIndex("d288b5ef-4b71-4432-9c49-ec641bf0c788"),
                        dex.findStringIndex("com.mc.miband.firmware.done")
                };
                for (String candDesc : ku8InitCandidates) {
                    int tIdx = dex.findTypeIndex(candDesc);
                    if (tIdx < 0) continue;
                    List<MethodRef> ms = dex.getDefinedMethodsOfClass(tIdx);
                    boolean hasQInt = false, hasLBool = false, hasBoolWithFwStr = false;
                    String boolMethodName = null;
                    for (MethodRef mr : ms) {
                        String pk = dex.getProtoKey(dex.getMethodProtoIdx(mr.methodIdx));
                        if ("q".equals(mr.methodName) && "(I)V".equals(pk)) hasQInt = true;
                        if ("(Z)V".equals(pk)) {
                            if ("l".equals(mr.methodName)) hasLBool = true;
                            if (mr.codeOff > 0) {
                                for (int sIdx : fwStrIdxs) {
                                    if (sIdx >= 0 && dex.bytecodeReferencesString(mr.codeOff, sIdx)) {
                                        hasBoolWithFwStr = true;
                                        boolMethodName = mr.methodName;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if ((hasQInt && hasLBool) || hasBoolWithFwStr) {
                        out.agpsProgressKu8Class = descriptorToJavaName(candDesc);
                        if (boolMethodName != null) {
                            out.agpsProgressDoneMethod = boolMethodName;
                        } else if (hasLBool) {
                            out.agpsProgressDoneMethod = "l";
                        }
                        break;
                    }
                }
                if (out.agpsProgressKu8Class != null) break;
            }
        }
        String ku8Desc = javaNameToDescriptor(out.agpsProgressKu8Class);

        for (DexReader dex : dexList) {
            if (rawSenderDesc != null && (out.bleRawSenderMethod == null || out.bleRawSender3ArgMethod == null)) {
                int tIdx = dex.findTypeIndex(rawSenderDesc);
                if (tIdx >= 0) {
                    resolveRawSenderMethods(dex, tIdx, out);
                }
            }

            if (ku8Desc != null && out.localBroadcastClass == null) {
                int tIdx = dex.findTypeIndex(ku8Desc);
                if (tIdx >= 0) {
                    expandLocalBroadcastFromKu8(dex, tIdx, out);
                }
            }

            if (out.agpsTransferOu8Class == null && crcDesc != null && out.agpsCrcMethod != null) {
                int crcMethodIdx = dex.findMethodIndex(crcDesc, out.agpsCrcMethod, "([B)I");
                if (crcMethodIdx >= 0) {
                    expandAgpsTransferOu8(dex, crcMethodIdx, out);
                }
            }

            if (wSyncDesc != null && out.weatherTriggerMethod == null) {
                int tIdx = dex.findTypeIndex(wSyncDesc);
                if (tIdx >= 0) {
                    expandWeatherSyncMethods(dex, tIdx, out);
                }
            }

            if (jsonBldDesc != null && (out.weatherHelperRf8Class == null || out.weatherDataClass == null || out.placeJsonMethod == null)) {
                int tIdx = dex.findTypeIndex(jsonBldDesc);
                if (tIdx >= 0) {
                    expandPlaceJsonBuilder(dex, tIdx, out);
                }
            }

            if (s6Desc != null && out.bleDevicePacketClass == null) {
                int tIdx = dex.findTypeIndex(s6Desc);
                if (tIdx >= 0) {
                    expandEndpointReceiveSignature(dex, tIdx, out, false);
                }
            }

            if (out.weatherDataClass == null) {
                int upIdx = dex.findTypeIndex(DESC_USER_PREFS);
                if (upIdx >= 0) {
                    expandWeatherDataFromUserPrefs(dex, upIdx, out);
                }
            }

            // Collect direct subclasses of android.bluetooth.BluetoothGattCallback (e.g. La40, Lvt$a)
            int gcbIdx = dex.findTypeIndex(DESC_GATT_CALLBACK);
            if (gcbIdx >= 0) {
                for (int c = 0; c < dex.classDefsSize; c++) {
                    int base = dex.classDefsOff + c * 32;
                    if (dex.readInt(base + 8) == gcbIdx) {
                        String clsDesc = dex.getTypeDesc(dex.readInt(base));
                        String javaName = descriptorToJavaName(clsDesc);
                        if (javaName != null && !javaName.isEmpty() && !out.gattCallbackClasses.contains(javaName)) {
                            out.gattCallbackClasses.add(javaName);
                        }
                    }
                }
            }
        }

        // Second pass for 2nd-level BluetoothGattCallback subclasses (e.g. Lfs extends La40 extends BluetoothGattCallback!)
        if (!out.gattCallbackClasses.isEmpty()) {
            List<String> firstLevel = new ArrayList<>(out.gattCallbackClasses);
            for (DexReader dex : dexList) {
                for (String baseName : firstLevel) {
                    int superIdx = dex.findTypeIndex(javaNameToDescriptor(baseName));
                    if (superIdx < 0) continue;
                    for (int c = 0; c < dex.classDefsSize; c++) {
                        int base = dex.classDefsOff + c * 32;
                        if (dex.readInt(base + 8) == superIdx) {
                            String clsDesc = dex.getTypeDesc(dex.readInt(base));
                            String javaName = descriptorToJavaName(clsDesc);
                            if (javaName != null && !javaName.isEmpty() && !out.gattCallbackClasses.contains(javaName)) {
                                out.gattCallbackClasses.add(javaName);
                            }
                        }
                    }
                }
            }
        }

        return out;
    }

    private static MethodRef findFirstMethodByStrings(DexReader dex, String[] candidates, String requiredMethodName) {
        for (String s : candidates) {
            int sIdx = dex.findStringIndex(s);
            if (sIdx < 0) continue;
            MethodRef m = dex.findMethodReferencingString(sIdx, requiredMethodName);
            if (m != null) return m;
        }
        return null;
    }

    private static void collectCrcCandidatesFromAgpsPacker(DexReader dex, int codeOff, Set<String> outCandidates) {
        if (codeOff <= 0) return;
        int insnsSize = dex.readInt(codeOff + 12);
        int insnsStart = codeOff + 16;
        int pc = 0;
        while (pc < insnsSize) {
            int unit0 = dex.readUShort(insnsStart + (pc << 1));
            int op = unit0 & 0xFF;
            int len = getInstructionLength(dex, insnsStart, pc, unit0);
            if (len <= 0) break;
            if (op == 0x71 || op == 0x77) { // invoke-static
                int mIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                if ("([B)I".equals(dex.getProtoKey(dex.getMethodProtoIdx(mIdx)))) {
                    String cls = dex.getMethodClassDesc(mIdx);
                    if (isObfuscatedAppClass(cls)) {
                        outCandidates.add(cls + "#" + dex.getMethodName(mIdx));
                    }
                }
            }
            pc += len;
        }
    }

    private static void resolveRawSenderMethods(DexReader dex, int classTypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(classTypeIdx);
        for (MethodRef m : methods) {
            if ((m.accessFlags & 0x0008) == 0) continue; // static only
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 5
                    && DESC_BLE_MANAGER.equals(pts.get(0))
                    && "I".equals(pts.get(1))
                    && ("[B".equals(pts.get(2)) || pts.get(2).startsWith("L"))
                    && "[B".equals(pts.get(3))
                    && "Z".equals(pts.get(4))) {
                if (out.bleRawSenderMethod == null || "g".equals(m.methodName)) {
                    out.bleRawSenderMethod = m.methodName;
                }
            } else if (pts.size() == 3
                    && DESC_BLE_MANAGER.equals(pts.get(0))
                    && "I".equals(pts.get(1))
                    && "[B".equals(pts.get(2))
                    && "V".equals(dex.getMethodReturnDesc(m.methodIdx))) {
                if (out.bleRawSender3ArgMethod == null || "l".equals(m.methodName)) {
                    out.bleRawSender3ArgMethod = m.methodName;
                }
            }
        }
    }

    private static String findSubclassWithMethodParams(DexReader dex, int superTypeIdx, String param0, String param1) {
        for (int c = 0; c < dex.classDefsSize; c++) {
            int base = dex.classDefsOff + c * 32;
            if (dex.readInt(base + 8) != superTypeIdx) continue;
            int classDataOff = dex.readInt(base + 24);
            if (classDataOff == 0) continue;
            int clsTypeIdx = dex.readInt(base);
            String clsDesc = dex.getTypeDesc(clsTypeIdx);
            List<MethodRef> methods = dex.getDefinedMethodsOfClassData(clsTypeIdx, clsDesc, classDataOff);
            for (MethodRef m : methods) {
                List<String> pts = dex.getMethodParamDescs(m.methodIdx);
                if (pts.size() == 2 && param0.equals(pts.get(0)) && param1.equals(pts.get(1))) {
                    return clsDesc;
                }
            }
        }
        return null;
    }

    private static String findEnumFieldForStringInClinit(DexReader dex, int codeOff, String enumClassDesc, String targetEnumName) {
        if (codeOff <= 0) return null;
        int targetStrIdx = dex.findStringIndex(targetEnumName);
        if (targetStrIdx < 0) return null;

        int insnsSize = dex.readInt(codeOff + 12);
        int insnsStart = codeOff + 16;
        int pc = 0;
        int seenTargetPc = -1;
        while (pc < insnsSize) {
            int unit0 = dex.readUShort(insnsStart + (pc << 1));
            int op = unit0 & 0xFF;
            int len = getInstructionLength(dex, insnsStart, pc, unit0);
            if (len <= 0) break;

            if (op == 0x1a) { // const-string
                int sIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                if (sIdx == targetStrIdx) {
                    seenTargetPc = pc;
                }
            } else if (op == 0x1b) { // const-string/jumbo
                int lo = dex.readUShort(insnsStart + ((pc + 1) << 1));
                int hi = dex.readUShort(insnsStart + ((pc + 2) << 1));
                if (((hi << 16) | lo) == targetStrIdx) {
                    seenTargetPc = pc;
                }
            } else if (op == 0x69 && seenTargetPc >= 0 && (pc - seenTargetPc) <= 24) { // sput-object
                int fIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                if (enumClassDesc.equals(dex.getFieldClassDesc(fIdx))
                        && enumClassDesc.equals(dex.getFieldTypeDesc(fIdx))) {
                    return dex.getFieldName(fIdx);
                }
            }
            pc += len;
        }
        return null;
    }

    private static void expandEndpoint14(DexReader dex, int ep14TypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(ep14TypeIdx);
        MethodRef httpMethod = null;
        for (MethodRef m : methods) {
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 3
                    && DESC_BLE_MANAGER.equals(pts.get(0))
                    && DESC_STRING.equals(pts.get(1))
                    && DESC_HASHMAP.equals(pts.get(2))) {
                httpMethod = m;
                out.endpoint14HttpMethod = m.methodName;
                String retDesc = dex.getMethodReturnDesc(m.methodIdx);
                if (isObfuscatedAppClass(retDesc)) {
                    out.httpRespClass = descriptorToJavaName(retDesc);
                }
                break;
            }
        }
        if (httpMethod == null || httpMethod.codeOff <= 0) return;

        int insnsSize = dex.readInt(httpMethod.codeOff + 12);
        int insnsStart = httpMethod.codeOff + 16;
        int pc = 0;

        Set<String> v6Candidates = new LinkedHashSet<>();

        while (pc < insnsSize) {
            int unit0 = dex.readUShort(insnsStart + (pc << 1));
            int op = unit0 & 0xFF;
            int len = getInstructionLength(dex, insnsStart, pc, unit0);
            if (len <= 0) break;

            if (op == 0x6e || op == 0x74 || op == 0x70 || op == 0x76 || op == 0x71 || op == 0x77) {
                int mIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                String cls = dex.getMethodClassDesc(mIdx);
                if (!isObfuscatedAppClass(cls)) {
                    pc += len;
                    continue;
                }
                String mName = dex.getMethodName(mIdx);
                List<String> pts = dex.getMethodParamDescs(mIdx);
                String ret = dex.getMethodReturnDesc(mIdx);

                // v6WrapperClass: has method (String, JsonElement) or (String, JsonObject) called inside ep14.o
                if ((op == 0x6e || op == 0x74)
                        && pts.size() == 2
                        && DESC_STRING.equals(pts.get(0))
                        && (DESC_JSON_ELEM.equals(pts.get(1)) || DESC_JSON_OBJ.equals(pts.get(1)))) {
                    v6Candidates.add(cls);
                }

                // weatherSyncClass: static or virtual method (UserPreferences)Z called inside ep14.o
                if ((op == 0x6e || op == 0x74 || op == 0x71 || op == 0x77)
                        && out.weatherSyncClass == null
                        && pts.size() == 1
                        && DESC_USER_PREFS.equals(pts.get(0))
                        && "Z".equals(ret)) {
                    out.weatherSyncClass = descriptorToJavaName(cls);
                    out.weatherNeedSyncMethod = mName;
                }

                // placeJsonBuilderClass: static method (UserPreferences[, String]) -> JsonObject
                if ((op == 0x71 || op == 0x77)
                        && DESC_JSON_OBJ.equals(ret)
                        && !pts.isEmpty()
                        && DESC_USER_PREFS.equals(pts.get(0))) {
                    if (out.placeJsonBuilderClass == null) {
                        out.placeJsonBuilderClass = descriptorToJavaName(cls);
                    }
                    if (out.placeJsonMethod == null
                            && (pts.size() == 1 || (pts.size() == 2 && DESC_STRING.equals(pts.get(1))))) {
                        out.placeJsonMethod = mName;
                    }
                }

                // placeModelClass: prefer static method (UserPreferences, String, String) -> <self>
                if ((op == 0x71 || op == 0x77)
                        && cls.equals(ret)
                        && pts.size() == 3
                        && DESC_USER_PREFS.equals(pts.get(0))
                        && DESC_STRING.equals(pts.get(1))
                        && DESC_STRING.equals(pts.get(2))) {
                    out.placeModelClass = descriptorToJavaName(cls);
                } else if ((op == 0x71 || op == 0x77)
                        && out.placeModelClass == null
                        && cls.equals(ret)
                        && pts.size() == 1
                        && DESC_USER_PREFS.equals(pts.get(0))) {
                    out.placeModelClass = descriptorToJavaName(cls);
                }
            }
            pc += len;
        }

        if (out.v6WrapperClass == null && !v6Candidates.isEmpty()) {
            out.v6WrapperClass = descriptorToJavaName(v6Candidates.iterator().next());
        }
    }

    private static void expandWeatherSyncMethods(DexReader dex, int wSyncTypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(wSyncTypeIdx);
        for (MethodRef m : methods) {
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 2 && DESC_CONTEXT.equals(pts.get(0)) && "Z".equals(pts.get(1))) {
                out.weatherTriggerMethod = m.methodName;
                return;
            }
        }
    }

    private static void expandPlaceJsonBuilder(DexReader dex, int bldTypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(bldTypeIdx);
        for (MethodRef m : methods) {
            if ((m.accessFlags & 0x0008) == 0) continue; // static only
            String ret = dex.getMethodReturnDesc(m.methodIdx);
            if (!DESC_JSON_OBJ.equals(ret)) continue;
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);

            // (UserPreferences, String) -> JsonObject or (UserPreferences) -> JsonObject
            if (out.placeJsonMethod == null
                    && ((pts.size() == 2 && DESC_USER_PREFS.equals(pts.get(0)) && DESC_STRING.equals(pts.get(1)))
                    || (pts.size() == 1 && DESC_USER_PREFS.equals(pts.get(0))))) {
                out.placeJsonMethod = m.methodName;
            }

            // (UserPreferences, dailyU51Class, rf8Class, D, J) -> JsonObject
            if (pts.size() == 5
                    && DESC_USER_PREFS.equals(pts.get(0))
                    && isObfuscatedAppClass(pts.get(1))
                    && isObfuscatedAppClass(pts.get(2))
                    && "D".equals(pts.get(3))
                    && "J".equals(pts.get(4))) {
                out.dailyWeatherU51Class = descriptorToJavaName(pts.get(1));
                out.weatherHelperRf8Class = descriptorToJavaName(pts.get(2));
                out.fallbackHourMethod = m.methodName;
            }

            // (UserPreferences, weatherDataClass) -> JsonObject
            if (out.weatherDataClass == null
                    && pts.size() == 2
                    && DESC_USER_PREFS.equals(pts.get(0))
                    && isObfuscatedAppClass(pts.get(1))) {
                out.weatherDataClass = descriptorToJavaName(pts.get(1));
            }
        }
    }

    private static void expandWeatherDataFromUserPrefs(DexReader dex, int upTypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(upTypeIdx);
        for (MethodRef m : methods) {
            if ("D7".equals(m.methodName) || "w7".equals(m.methodName)) {
                if (dex.getMethodParamCount(m.methodIdx) == 0) {
                    String ret = dex.getMethodReturnDesc(m.methodIdx);
                    if (isObfuscatedAppClass(ret)) {
                        out.weatherDataClass = descriptorToJavaName(ret);
                        return;
                    }
                }
            }
        }
    }

    private static void expandAgpsPacker(DexReader dex, int packerTypeIdx, ResolvedTargets out, Set<String> ku8InitCandidates) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(packerTypeIdx);
        for (MethodRef m : methods) {
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 3
                    && DESC_BLE_MANAGER.equals(pts.get(0))
                    && DESC_FILE.equals(pts.get(1))
                    && isObfuscatedAppClass(pts.get(2))) {
                out.agpsSendMethod = m.methodName;
                out.agpsEndpointS6Class = descriptorToJavaName(pts.get(2));
                // Collect all <init> classes instantiated in agpsSendMethod (includes ku8!)
                if (m.codeOff > 0) {
                    int insnsSize = dex.readInt(m.codeOff + 12);
                    int insnsStart = m.codeOff + 16;
                    int pc = 0;
                    while (pc < insnsSize) {
                        int unit0 = dex.readUShort(insnsStart + (pc << 1));
                        int op = unit0 & 0xFF;
                        int len = getInstructionLength(dex, insnsStart, pc, unit0);
                        if (len <= 0) break;
                        if (op == 0x70 || op == 0x76) {
                            int calleeIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                            if ("<init>".equals(dex.getMethodName(calleeIdx))) {
                                String targetCls = dex.getMethodClassDesc(calleeIdx);
                                if (isObfuscatedAppClass(targetCls)) {
                                    ku8InitCandidates.add(targetCls);
                                }
                            }
                        }
                        pc += len;
                    }
                }
                return;
            }
        }
    }

    private static void expandEndpointA0(DexReader dex, int a0TypeIdx, ResolvedTargets out) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(a0TypeIdx);
        MethodRef rxMethod = null;
        for (MethodRef m : methods) {
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 3
                    && isObfuscatedAppClass(pts.get(0))
                    && "I".equals(pts.get(1))
                    && "[B".equals(pts.get(2))) {
                if (out.bleDevicePacketClass == null) {
                    out.bleDevicePacketClass = descriptorToJavaName(pts.get(0));
                }
                if (out.endpointA0ReceiveMethod == null || "f".equals(m.methodName)) {
                    out.endpointA0ReceiveMethod = m.methodName;
                    rxMethod = m;
                }
                if ("f".equals(m.methodName)) break;
            }
        }
        // Inside epA0 receive method, find invoke-static (BLEManager, int, [B)V -> bleRawSenderClass
        if (rxMethod != null && rxMethod.codeOff > 0 && out.bleRawSenderClass == null) {
            int insnsSize = dex.readInt(rxMethod.codeOff + 12);
            int insnsStart = rxMethod.codeOff + 16;
            int pc = 0;
            while (pc < insnsSize) {
                int unit0 = dex.readUShort(insnsStart + (pc << 1));
                int op = unit0 & 0xFF;
                int len = getInstructionLength(dex, insnsStart, pc, unit0);
                if (len <= 0) break;
                if (op == 0x71 || op == 0x77) {
                    int calleeIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                    List<String> pts = dex.getMethodParamDescs(calleeIdx);
                    if (pts.size() == 3
                            && DESC_BLE_MANAGER.equals(pts.get(0))
                            && "I".equals(pts.get(1))
                            && "[B".equals(pts.get(2))) {
                        String cls = dex.getMethodClassDesc(calleeIdx);
                        if (isObfuscatedAppClass(cls)) {
                            out.bleRawSenderClass = descriptorToJavaName(cls);
                            out.bleRawSender3ArgMethod = dex.getMethodName(calleeIdx);
                            return;
                        }
                    }
                }
                pc += len;
            }
        }
    }

    private static void expandEndpointReceiveSignature(DexReader dex, int epTypeIdx, ResolvedTargets out, boolean setEpA0Method) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(epTypeIdx);
        for (MethodRef m : methods) {
            List<String> pts = dex.getMethodParamDescs(m.methodIdx);
            if (pts.size() == 3
                    && isObfuscatedAppClass(pts.get(0))
                    && "I".equals(pts.get(1))
                    && "[B".equals(pts.get(2))) {
                if (out.bleDevicePacketClass == null) {
                    out.bleDevicePacketClass = descriptorToJavaName(pts.get(0));
                }
                if (setEpA0Method && (out.endpointA0ReceiveMethod == null || "f".equals(m.methodName))) {
                    out.endpointA0ReceiveMethod = m.methodName;
                }
                if ("f".equals(m.methodName)) return;
            }
        }
    }

    private static String resolveEndpointA0FromBaseEndpoint(DexReader dex, int baseTypeIdx) {
        List<MethodRef> methods = dex.getDefinedMethodsOfClass(baseTypeIdx);
        for (MethodRef m : methods) {
            if (m.codeOff <= 0) continue;
            if (!"()Z".equals(dex.getProtoKey(dex.getMethodProtoIdx(m.methodIdx)))) continue;
            int insnsSize = dex.readInt(m.codeOff + 12);
            int insnsStart = m.codeOff + 16;
            if (insnsSize > 8) continue;
            int pc = 0;
            while (pc < insnsSize) {
                int unit0 = dex.readUShort(insnsStart + (pc << 1));
                int op = unit0 & 0xFF;
                int len = getInstructionLength(dex, insnsStart, pc, unit0);
                if (len <= 0) break;
                if (op == 0x20) { // instance-of vA, vB, type@CCCC
                    int tIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                    String tDesc = dex.getTypeDesc(tIdx);
                    if (isObfuscatedAppClass(tDesc)) {
                        return tDesc;
                    }
                }
                pc += len;
            }
        }
        return null;
    }

    private static void expandLocalBroadcastFromKu8(DexReader dex, int ku8TypeIdx, ResolvedTargets out) {
        int[] koStrIdxs = new int[]{
                dex.findStringIndex("4436627f-408a-43f1-a12b-983c5b501c01"),
                dex.findStringIndex("2c1cdc19-8e2f-46c2-9d53-05832e1c0240"),
                dex.findStringIndex("com.mc.miband.firmwareKO"),
                dex.findStringIndex("d288b5ef-4b71-4432-9c49-ec641bf0c788"),
                dex.findStringIndex("com.mc.miband.firmware.done")
        };

        List<MethodRef> methods = dex.getDefinedMethodsOfClass(ku8TypeIdx);
        for (MethodRef m : methods) {
            if (m.codeOff <= 0) continue;
            boolean referencesFwAction = false;
            for (int sIdx : koStrIdxs) {
                if (sIdx >= 0 && dex.bytecodeReferencesString(m.codeOff, sIdx)) {
                    referencesFwAction = true;
                    break;
                }
            }
            if (!referencesFwAction) continue;
            if (out.agpsProgressDoneMethod == null && "(Z)V".equals(dex.getProtoKey(dex.getMethodProtoIdx(m.methodIdx)))) {
                out.agpsProgressDoneMethod = m.methodName;
            }

            int insnsSize = dex.readInt(m.codeOff + 12);
            int insnsStart = m.codeOff + 16;
            int pc = 0;
            while (pc < insnsSize) {
                int unit0 = dex.readUShort(insnsStart + (pc << 1));
                int op = unit0 & 0xFF;
                int len = getInstructionLength(dex, insnsStart, pc, unit0);
                if (len <= 0) break;
                if (op == 0x71 || op == 0x77) { // invoke-static
                    int calleeIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                    String cls = dex.getMethodClassDesc(calleeIdx);
                    if (isObfuscatedAppClass(cls)) {
                        List<String> pts = dex.getMethodParamDescs(calleeIdx);
                        String ret = dex.getMethodReturnDesc(calleeIdx);
                        if (pts.size() == 2 && DESC_CONTEXT.equals(pts.get(0)) && DESC_STRING.equals(pts.get(1))
                                && "V".equals(ret)) {
                            out.localBroadcastClass = descriptorToJavaName(cls);
                            out.localBroadcastMethod = dex.getMethodName(calleeIdx);
                        } else if (pts.size() == 1 && DESC_STRING.equals(pts.get(0)) && DESC_INTENT.equals(ret)) {
                            if (out.localBroadcastClass == null) {
                                out.localBroadcastClass = descriptorToJavaName(cls);
                            }
                            out.localBroadcastIntentMethod = dex.getMethodName(calleeIdx);
                        } else if (pts.size() == 2 && DESC_CONTEXT.equals(pts.get(0)) && DESC_INTENT.equals(pts.get(1))
                                && "V".equals(ret)) {
                            if (out.localBroadcastClass == null) {
                                out.localBroadcastClass = descriptorToJavaName(cls);
                            }
                            out.localBroadcastSendIntentMethod = dex.getMethodName(calleeIdx);
                        }
                    }
                }
                pc += len;
            }
            if (out.localBroadcastClass != null && out.localBroadcastIntentMethod != null
                    && out.localBroadcastSendIntentMethod != null && out.localBroadcastMethod != null) {
                return;
            }
        }
    }

    private static void expandAgpsTransferOu8(DexReader dex, int crcMethodIdx, ResolvedTargets out) {
        int cProtoIdx = dex.findProtoIndex("V", new String[]{DESC_UUID, "[B"});
        int jProtoIdx = dex.findProtoIndex("V", new String[0]);
        if (cProtoIdx < 0 || jProtoIdx < 0) return;

        for (int c = 0; c < dex.classDefsSize; c++) {
            int base = dex.classDefsOff + c * 32;
            int clsTypeIdx = dex.readInt(base);
            String clsDesc = dex.getTypeDesc(clsTypeIdx);
            if (!isObfuscatedAppClass(clsDesc)) continue;

            int classDataOff = dex.readInt(base + 24);
            if (classDataOff == 0) continue;

            List<MethodRef> methods = dex.getDefinedMethodsOfClassData(clsTypeIdx, clsDesc, classDataOff);
            boolean hasUuidBytesMethod = false;
            boolean callsCrc = false;
            List<MethodRef> noArgVoidMethods = new ArrayList<>();

            for (MethodRef m : methods) {
                int pIdx = dex.getMethodProtoIdx(m.methodIdx);
                if (pIdx == cProtoIdx && "c".equals(m.methodName)) {
                    hasUuidBytesMethod = true;
                }
                if (pIdx == jProtoIdx && !"<init>".equals(m.methodName) && !"<clinit>".equals(m.methodName)) {
                    noArgVoidMethods.add(m);
                }
                if (!callsCrc && m.codeOff > 0 && dex.bytecodeInvokesMethod(m.codeOff, crcMethodIdx)) {
                    callsCrc = true;
                }
            }

            if (hasUuidBytesMethod && callsCrc) {
                String foundMu8 = null;
                for (MethodRef jMethod : noArgVoidMethods) {
                    if (jMethod.codeOff <= 0) continue;
                    int insnsSize = dex.readInt(jMethod.codeOff + 12);
                    int insnsStart = jMethod.codeOff + 16;
                    int pc = 0;
                    while (pc < insnsSize) {
                        int unit0 = dex.readUShort(insnsStart + (pc << 1));
                        int op = unit0 & 0xFF;
                        int len = getInstructionLength(dex, insnsStart, pc, unit0);
                        if (len <= 0) break;
                        if (op == 0x70 || op == 0x76) { // invoke-direct (<init>)
                            int mIdx = dex.readUShort(insnsStart + ((pc + 1) << 1));
                            if ("<init>".equals(dex.getMethodName(mIdx))) {
                                String targetCls = dex.getMethodClassDesc(mIdx);
                                List<String> pts = dex.getMethodParamDescs(mIdx);
                                if (isObfuscatedAppClass(targetCls) && !targetCls.equals(clsDesc)
                                        && !pts.isEmpty() && clsDesc.equals(pts.get(0))) {
                                    foundMu8 = descriptorToJavaName(targetCls);
                                    break;
                                }
                            }
                        }
                        pc += len;
                    }
                    if (foundMu8 != null) break;
                }
                if (foundMu8 != null) {
                    out.agpsTransferOu8Class = descriptorToJavaName(clsDesc);
                    out.agpsTimeoutMu8Class = foundMu8;
                    return;
                }
            }
        }
    }

    private static boolean isObfuscatedAppClass(String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L') return false;
        if (desc.startsWith("Ljava/") || desc.startsWith("Ljavax/")
                || desc.startsWith("Landroid/") || desc.startsWith("Landroidx/")
                || desc.startsWith("Lkotlin/") || desc.startsWith("Lcom/google/")) {
            return false;
        }
        return true;
    }

    private static int getInstructionLength(DexReader dex, int insnsStart, int pc, int unit0) {
        int op = unit0 & 0xFF;
        if (op == 0x00 && unit0 != 0x0000) {
            if (unit0 == 0x0100) { // packed-switch-payload
                int size = dex.readUShort(insnsStart + ((pc + 1) << 1));
                return (size * 2) + 4;
            } else if (unit0 == 0x0200) { // sparse-switch-payload
                int size = dex.readUShort(insnsStart + ((pc + 1) << 1));
                return (size * 4) + 2;
            } else if (unit0 == 0x0300) { // fill-array-data-payload
                int elemWidth = dex.readUShort(insnsStart + ((pc + 1) << 1));
                int lo = dex.readUShort(insnsStart + ((pc + 2) << 1));
                int hi = dex.readUShort(insnsStart + ((pc + 3) << 1));
                int count = (hi << 16) | lo;
                long byteLen = (long) elemWidth * (long) count;
                return (int) (4 + ((byteLen + 1) / 2));
            }
        }
        return INSN_LEN[op];
    }

    private static byte[] readZipEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing zip entry: " + name);
        int size = (int) Math.max(0, entry.getSize());
        try (InputStream is = zip.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(size > 0 ? size : 65536);
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static String descriptorToJavaName(String desc) {
        if (desc == null) return null;
        if (desc.length() >= 2 && desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    private static String javaNameToDescriptor(String name) {
        if (name == null || name.isEmpty()) return null;
        return "L" + name.replace('.', '/') + ";";
    }

    private static final class MethodRef {
        final int classTypeIdx;
        final String classDesc;
        final int methodIdx;
        final String methodName;
        final int accessFlags;
        final int codeOff;

        MethodRef(int classTypeIdx, String classDesc, int methodIdx, String methodName, int accessFlags, int codeOff) {
            this.classTypeIdx = classTypeIdx;
            this.classDesc = classDesc;
            this.methodIdx = methodIdx;
            this.methodName = methodName;
            this.accessFlags = accessFlags;
            this.codeOff = codeOff;
        }
    }

    /**
     * Ultra-lightweight read-only DEX binary table reader with O(log N) binary search
     * over sorted string_ids, type_ids, field_ids, and method_ids.
     */
    private static final class DexReader {
        final byte[] data;
        final int stringIdsSize, stringIdsOff;
        final int typeIdsSize, typeIdsOff;
        final int protoIdsSize, protoIdsOff;
        final int fieldIdsSize, fieldIdsOff;
        final int methodIdsSize, methodIdsOff;
        final int classDefsSize, classDefsOff;

        private final Map<Integer, String> stringCache = new HashMap<>();

        DexReader(byte[] data) {
            this.data = data;
            this.stringIdsSize = readInt(56);
            this.stringIdsOff = readInt(60);
            this.typeIdsSize = readInt(64);
            this.typeIdsOff = readInt(68);
            this.protoIdsSize = readInt(72);
            this.protoIdsOff = readInt(76);
            this.fieldIdsSize = readInt(80);
            this.fieldIdsOff = readInt(84);
            this.methodIdsSize = readInt(88);
            this.methodIdsOff = readInt(92);
            this.classDefsSize = readInt(96);
            this.classDefsOff = readInt(100);
        }

        int readInt(int off) {
            return (data[off] & 0xFF)
                    | ((data[off + 1] & 0xFF) << 8)
                    | ((data[off + 2] & 0xFF) << 16)
                    | ((data[off + 3] & 0xFF) << 24);
        }

        int readUShort(int off) {
            return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
        }

        int[] readUleb128(int off) {
            int result = 0;
            int shift = 0;
            int pos = off;
            while (true) {
                int b = data[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return new int[]{result, pos};
        }

        String getString(int idx) {
            String cached = stringCache.get(idx);
            if (cached != null) return cached;
            int dataOff = readInt(stringIdsOff + (idx << 2));
            int pos = dataOff;
            while ((data[pos++] & 0x80) != 0) {
                // skip ULEB128 utf16_size
            }
            int start = pos;
            while (data[pos] != 0) {
                pos++;
            }
            String s = new String(data, start, pos - start, StandardCharsets.UTF_8);
            if (stringCache.size() < 4096) {
                stringCache.put(idx, s);
            }
            return s;
        }

        int findStringIndex(String target) {
            int lo = 0, hi = stringIdsSize - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                String s = getString(mid);
                int cmp = s.compareTo(target);
                if (cmp == 0) return mid;
                if (cmp < 0) lo = mid + 1;
                else hi = mid - 1;
            }
            return -1;
        }

        String getTypeDesc(int typeIdx) {
            int descStrIdx = readInt(typeIdsOff + (typeIdx << 2));
            return getString(descStrIdx);
        }

        int findTypeIndex(String desc) {
            int strIdx = findStringIndex(desc);
            if (strIdx < 0) return -1;
            int lo = 0, hi = typeIdsSize - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int midStr = readInt(typeIdsOff + (mid << 2));
                if (midStr == strIdx) return mid;
                if (midStr < strIdx) lo = mid + 1;
                else hi = mid - 1;
            }
            return -1;
        }

        String getSuperClassDesc(int classTypeIdx) {
            for (int c = 0; c < classDefsSize; c++) {
                int base = classDefsOff + c * 32;
                if (readInt(base) == classTypeIdx) {
                    int superIdx = readInt(base + 8);
                    return (superIdx >= 0 && superIdx < typeIdsSize) ? getTypeDesc(superIdx) : null;
                }
            }
            return null;
        }

        String getFieldClassDesc(int fieldIdx) {
            return getTypeDesc(readUShort(fieldIdsOff + (fieldIdx << 3)));
        }

        String getFieldTypeDesc(int fieldIdx) {
            return getTypeDesc(readUShort(fieldIdsOff + (fieldIdx << 3) + 2));
        }

        String getFieldName(int fieldIdx) {
            return getString(readInt(fieldIdsOff + (fieldIdx << 3) + 4));
        }

        int getProtoParamCount(int protoIdx) {
            int paramsOff = readInt(protoIdsOff + protoIdx * 12 + 8);
            if (paramsOff == 0) return 0;
            return readInt(paramsOff);
        }

        String getProtoReturnDesc(int protoIdx) {
            int retTypeIdx = readInt(protoIdsOff + protoIdx * 12 + 4);
            return getTypeDesc(retTypeIdx);
        }

        List<String> getProtoParamDescs(int protoIdx) {
            int paramsOff = readInt(protoIdsOff + protoIdx * 12 + 8);
            List<String> res = new ArrayList<>();
            if (paramsOff == 0) return res;
            int count = readInt(paramsOff);
            for (int i = 0; i < count; i++) {
                int tIdx = readUShort(paramsOff + 4 + (i << 1));
                res.add(getTypeDesc(tIdx));
            }
            return res;
        }

        String getProtoKey(int protoIdx) {
            StringBuilder sb = new StringBuilder("(");
            int paramsOff = readInt(protoIdsOff + protoIdx * 12 + 8);
            if (paramsOff != 0) {
                int count = readInt(paramsOff);
                for (int i = 0; i < count; i++) {
                    sb.append(getTypeDesc(readUShort(paramsOff + 4 + (i << 1))));
                }
            }
            sb.append(")").append(getProtoReturnDesc(protoIdx));
            return sb.toString();
        }

        int findProtoIndex(String returnDesc, String[] paramDescs) {
            int retTypeIdx = findTypeIndex(returnDesc);
            if (retTypeIdx < 0) return -1;
            int[] paramTypeIdxs = new int[paramDescs.length];
            for (int i = 0; i < paramDescs.length; i++) {
                paramTypeIdxs[i] = findTypeIndex(paramDescs[i]);
                if (paramTypeIdxs[i] < 0) return -1;
            }
            for (int p = 0; p < protoIdsSize; p++) {
                int base = protoIdsOff + p * 12;
                if (readInt(base + 4) != retTypeIdx) continue;
                int paramsOff = readInt(base + 8);
                if (paramTypeIdxs.length == 0) {
                    if (paramsOff == 0) return p;
                } else {
                    if (paramsOff == 0) continue;
                    int count = readInt(paramsOff);
                    if (count != paramTypeIdxs.length) continue;
                    boolean match = true;
                    for (int i = 0; i < count; i++) {
                        if (readUShort(paramsOff + 4 + (i << 1)) != paramTypeIdxs[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) return p;
                }
            }
            return -1;
        }

        int getMethodClassIdx(int methodIdx) {
            return readUShort(methodIdsOff + (methodIdx << 3));
        }

        int getMethodProtoIdx(int methodIdx) {
            return readUShort(methodIdsOff + (methodIdx << 3) + 2);
        }

        int getMethodNameIdx(int methodIdx) {
            return readInt(methodIdsOff + (methodIdx << 3) + 4);
        }

        String getMethodClassDesc(int methodIdx) {
            return getTypeDesc(getMethodClassIdx(methodIdx));
        }

        String getMethodName(int methodIdx) {
            return getString(getMethodNameIdx(methodIdx));
        }

        String getMethodReturnDesc(int methodIdx) {
            return getProtoReturnDesc(getMethodProtoIdx(methodIdx));
        }

        int getMethodParamCount(int methodIdx) {
            return getProtoParamCount(getMethodProtoIdx(methodIdx));
        }

        List<String> getMethodParamDescs(int methodIdx) {
            return getProtoParamDescs(getMethodProtoIdx(methodIdx));
        }

        int findMethodIndex(String classDesc, String methodName, String protoKey) {
            int clsIdx = findTypeIndex(classDesc);
            if (clsIdx < 0) return -1;
            int nameIdx = findStringIndex(methodName);
            if (nameIdx < 0) return -1;

            int lo = 0, hi = methodIdsSize - 1;
            int hit = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int mCls = getMethodClassIdx(mid);
                if (mCls != clsIdx) {
                    if (mCls < clsIdx) lo = mid + 1;
                    else hi = mid - 1;
                    continue;
                }
                int mName = getMethodNameIdx(mid);
                if (mName != nameIdx) {
                    if (mName < nameIdx) lo = mid + 1;
                    else hi = mid - 1;
                    continue;
                }
                hit = mid;
                break;
            }
            if (hit < 0) return -1;

            int i = hit;
            while (i >= 0 && getMethodClassIdx(i) == clsIdx && getMethodNameIdx(i) == nameIdx) {
                if (protoKey.equals(getProtoKey(getMethodProtoIdx(i)))) return i;
                i--;
            }
            i = hit + 1;
            while (i < methodIdsSize && getMethodClassIdx(i) == clsIdx && getMethodNameIdx(i) == nameIdx) {
                if (protoKey.equals(getProtoKey(getMethodProtoIdx(i)))) return i;
                i++;
            }
            return -1;
        }

        MethodRef findMethodReferencingString(int targetStrIdx, String requiredMethodName) {
            int reqNameIdx = (requiredMethodName != null) ? findStringIndex(requiredMethodName) : -1;
            if (requiredMethodName != null && reqNameIdx < 0) return null;

            for (int c = 0; c < classDefsSize; c++) {
                int base = classDefsOff + c * 32;
                int classDataOff = readInt(base + 24);
                if (classDataOff == 0) continue;

                int clsTypeIdx = readInt(base);
                int[] r = readUleb128(classDataOff);
                int staticFields = r[0]; int pos = r[1];
                r = readUleb128(pos); int instanceFields = r[0]; pos = r[1];
                r = readUleb128(pos); int directMethods = r[0]; pos = r[1];
                r = readUleb128(pos); int virtualMethods = r[0]; pos = r[1];

                pos = skipFields(pos, staticFields + instanceFields);

                MethodRef found = scanMethodsForString(clsTypeIdx, pos, directMethods, targetStrIdx, reqNameIdx);
                if (found != null) return found;
                pos = skipMethods(pos, directMethods);

                found = scanMethodsForString(clsTypeIdx, pos, virtualMethods, targetStrIdx, reqNameIdx);
                if (found != null) return found;
            }
            return null;
        }

        MethodRef findMethodReferencingStringInSubclass(int targetStrIdx, int requiredSuperTypeIdx) {
            for (int c = 0; c < classDefsSize; c++) {
                int base = classDefsOff + c * 32;
                if (readInt(base + 8) != requiredSuperTypeIdx) continue;
                int classDataOff = readInt(base + 24);
                if (classDataOff == 0) continue;

                int clsTypeIdx = readInt(base);
                int[] r = readUleb128(classDataOff);
                int staticFields = r[0]; int pos = r[1];
                r = readUleb128(pos); int instanceFields = r[0]; pos = r[1];
                r = readUleb128(pos); int directMethods = r[0]; pos = r[1];
                r = readUleb128(pos); int virtualMethods = r[0]; pos = r[1];

                pos = skipFields(pos, staticFields + instanceFields);

                MethodRef found = scanMethodsForString(clsTypeIdx, pos, directMethods, targetStrIdx, -1);
                if (found != null) return found;
                pos = skipMethods(pos, directMethods);

                found = scanMethodsForString(clsTypeIdx, pos, virtualMethods, targetStrIdx, -1);
                if (found != null) return found;
            }
            return null;
        }

        private MethodRef scanMethodsForString(int clsTypeIdx, int pos, int count, int targetStrIdx, int reqNameIdx) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                int[] r = readUleb128(pos); methodIdx += r[0]; pos = r[1];
                r = readUleb128(pos); int accessFlags = r[0]; pos = r[1];
                r = readUleb128(pos); int codeOff = r[0]; pos = r[1];
                if (codeOff == 0) continue;
                if (reqNameIdx >= 0 && getMethodNameIdx(methodIdx) != reqNameIdx) continue;
                if (bytecodeReferencesString(codeOff, targetStrIdx)) {
                    return new MethodRef(clsTypeIdx, getTypeDesc(clsTypeIdx), methodIdx, getMethodName(methodIdx), accessFlags, codeOff);
                }
            }
            return null;
        }

        List<MethodRef> getDefinedMethodsOfClass(int targetClassTypeIdx) {
            for (int c = 0; c < classDefsSize; c++) {
                int base = classDefsOff + c * 32;
                if (readInt(base) != targetClassTypeIdx) continue;
                int classDataOff = readInt(base + 24);
                if (classDataOff == 0) return new ArrayList<>();
                return getDefinedMethodsOfClassData(targetClassTypeIdx, getTypeDesc(targetClassTypeIdx), classDataOff);
            }
            return new ArrayList<>();
        }

        List<MethodRef> getDefinedMethodsOfClassData(int clsTypeIdx, String clsDesc, int classDataOff) {
            List<MethodRef> out = new ArrayList<>();
            int[] r = readUleb128(classDataOff);
            int staticFields = r[0]; int pos = r[1];
            r = readUleb128(pos); int instanceFields = r[0]; pos = r[1];
            r = readUleb128(pos); int directMethods = r[0]; pos = r[1];
            r = readUleb128(pos); int virtualMethods = r[0]; pos = r[1];

            pos = skipFields(pos, staticFields + instanceFields);
            pos = collectMethods(clsTypeIdx, clsDesc, pos, directMethods, out);
            collectMethods(clsTypeIdx, clsDesc, pos, virtualMethods, out);
            return out;
        }

        private int collectMethods(int clsTypeIdx, String clsDesc, int pos, int count, List<MethodRef> out) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                int[] r = readUleb128(pos); methodIdx += r[0]; pos = r[1];
                r = readUleb128(pos); int accessFlags = r[0]; pos = r[1];
                r = readUleb128(pos); int codeOff = r[0]; pos = r[1];
                out.add(new MethodRef(clsTypeIdx, clsDesc, methodIdx, getMethodName(methodIdx), accessFlags, codeOff));
            }
            return pos;
        }

        boolean bytecodeReferencesString(int codeOff, int targetStrIdx) {
            int insnsSize = readInt(codeOff + 12);
            int insnsStart = codeOff + 16;
            int pc = 0;
            while (pc < insnsSize) {
                int unit0 = readUShort(insnsStart + (pc << 1));
                int op = unit0 & 0xFF;
                int len = getInstructionLength(this, insnsStart, pc, unit0);
                if (len <= 0) break;
                if (op == 0x1a) {
                    int sIdx = readUShort(insnsStart + ((pc + 1) << 1));
                    if (sIdx == targetStrIdx) return true;
                } else if (op == 0x1b) {
                    int lo = readUShort(insnsStart + ((pc + 1) << 1));
                    int hi = readUShort(insnsStart + ((pc + 2) << 1));
                    if (((hi << 16) | lo) == targetStrIdx) return true;
                }
                pc += len;
            }
            return false;
        }

        boolean bytecodeInvokesMethod(int codeOff, int targetMethodIdx) {
            int insnsSize = readInt(codeOff + 12);
            int insnsStart = codeOff + 16;
            int pc = 0;
            while (pc < insnsSize) {
                int unit0 = readUShort(insnsStart + (pc << 1));
                int op = unit0 & 0xFF;
                int len = getInstructionLength(this, insnsStart, pc, unit0);
                if (len <= 0) break;
                if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                    int mIdx = readUShort(insnsStart + ((pc + 1) << 1));
                    if (mIdx == targetMethodIdx) return true;
                }
                pc += len;
            }
            return false;
        }

        private int skipFields(int pos, int count) {
            for (int i = 0; i < count; i++) {
                pos = readUleb128(pos)[1];
                pos = readUleb128(pos)[1];
            }
            return pos;
        }

        private int skipMethods(int pos, int count) {
            for (int i = 0; i < count; i++) {
                pos = readUleb128(pos)[1];
                pos = readUleb128(pos)[1];
                pos = readUleb128(pos)[1];
            }
            return pos;
        }
    }

    /**
     * Offline PC verification entry point: pass one or more Notify APK paths to verify
     * 100% auto-resolution across versions.
     */
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            File f = new File(path);
            long t0 = System.currentTimeMillis();
            ResolvedTargets r = scanApk(f, f.getName());
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("=== " + f.getName() + " (" + elapsed + " ms, coreComplete=" + r.isCoreComplete() + ") ===");
            System.out.println(r.toSummaryString());
        }
    }
}
