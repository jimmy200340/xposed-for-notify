package com.amazfit.balance2fix;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves missing watch mini-program names, descriptions, and thumbnails in Notify's
 * {@code ZeppOsAppsStoreActivity} ("裝置 > 應用程式").
 *
 * 3-Layer Resolution:
 *   1. Local .zpk metadata & Zepp TGA-to-PNG icon cache (auto-seeded from bundled HA_Lights_Balance2.zpk
 *      and auto-recorded whenever DfuV5 installs any .zpk).
 *   2. User custom AppID=Name overrides configured in MainActivity.
 *   3. Official Zepp App Store cloud batch API (https://api-watch.zepp.com/market/devices/{src}/apps)
 *      when user provides a Zepp apptoken (including 1-click Root extraction in MainActivity).
 *
 * NOTE: Zero references to de.robv.android.xposed.* or io.github.libxposed.*.
 */
public final class ZeppAppMetadataManager {

    private static final String TAG = "Balance2WeatherFix";
    private static final String META_DIR_NAME = "balance2_app_meta";
    private static final String CATALOG_FILE_NAME = "catalog.json";
    private static final String MODULE_PACKAGE = "com.amazfit.balance2fix";

    // Balance 2 (9568512 / 9568513) + Balance 1st gen (8454401) deviceSource fallbacks
    private static final int[] ZEPP_DEVICE_SOURCES = new int[]{9568512, 9568513, 8454401};
    private static final String[] ZEPP_MARKET_HOSTS = new String[]{
            "https://api-watch.zepp.com",
            "https://api-watch-us2.zepp.com",
            "https://api-watch-cn.zepp.com"
    };

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Map<Integer, AppMeta> sCache = new ConcurrentHashMap<>();
    private static volatile boolean sCatalogLoaded = false;
    private static volatile boolean sBundledSeeded = false;
    private static volatile String sZeppAppToken = "";
    private static volatile String sCustomNamesRaw = "";
    private static final Map<Integer, String> sCustomNames = new ConcurrentHashMap<>();
    private static final Set<String> sInFlightQueries = Collections.synchronizedSet(new HashSet<>());

    public static final class AppMeta {
        public final int appId;
        public final String name;
        public final String description;
        public final String iconUrl;
        public final String source;

        public AppMeta(int appId, String name, String description, String iconUrl, String source) {
            this.appId = appId;
            this.name = name != null ? name : "";
            this.description = description != null ? description : "";
            this.iconUrl = iconUrl != null ? iconUrl : "";
            this.source = source != null ? source : "";
        }
    }

    private ZeppAppMetadataManager() {
    }

    public static void updateConfig(String zeppAppToken, String customNamesRaw) {
        sZeppAppToken = zeppAppToken != null ? zeppAppToken.trim() : "";
        sCustomNamesRaw = customNamesRaw != null ? customNamesRaw : "";
        parseCustomNames(sCustomNamesRaw);
    }

    public static String getZeppAppToken() {
        return sZeppAppToken;
    }

    public static String getCustomNamesRaw() {
        return sCustomNamesRaw;
    }

    private static void parseCustomNames(String raw) {
        sCustomNames.clear();
        if (raw == null || raw.trim().isEmpty()) return;
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) eq = trimmed.indexOf(':');
            if (eq <= 0) continue;
            String idPart = trimmed.substring(0, eq).trim();
            String namePart = trimmed.substring(eq + 1).trim();
            if (namePart.isEmpty()) continue;
            try {
                int id = Integer.parseInt(idPart);
                if (id > 0) {
                    sCustomNames.put(id, namePart);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Installs hooks on Notify's {@code ZeppOsAppsStoreActivity} to enrich missing names & thumbnails.
     */
    public static void hookZeppOsAppsStoreActivity(final ClassLoader cl) {
        try {
            Class<?> storeCls = HookBridge.findClassIfExists(
                    "com.mc.miband1.ui.updateFirmware.zepposapp.ZeppOsAppsStoreActivity", cl);
            if (storeCls == null) {
                HookBridge.logInfo(TAG, "[AppMeta] ZeppOsAppsStoreActivity not found; skipping hook");
                return;
            }
            HookBridge.findAndHookMethod(storeCls, "l0", new HookBridge.MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    final Activity act = (Activity) param.thisObject;
                    try {
                        enrichAppsStoreActivity(act);
                    } catch (Throwable t) {
                        HookBridge.logError(TAG, "[AppMeta] enrichAppsStoreActivity failed", t);
                    }
                }
            });
            HookBridge.logInfo(TAG, "[AppMeta] Hooked ZeppOsAppsStoreActivity.l0() successfully");
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] hookZeppOsAppsStoreActivity failed", t);
        }
    }

    /**
     * Called from DfuV5 installer whenever a device.zip is installed, to persist its name/desc/icon.
     */
    public static void extractAndCacheFromDeviceZip(Context ctx, byte[] deviceZipBytes) {
        if (ctx == null || deviceZipBytes == null || deviceZipBytes.length < 32) return;
        try {
            Context appCtx = ctx.getApplicationContext();
            ensureCatalogLoaded(appCtx);

            String appJsonText = null;
            Map<String, byte[]> iconCandidates = new HashMap<>();

            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(deviceZipBytes))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    String name = ze.getName();
                    String low = name.toLowerCase(Locale.ROOT);
                    if ("app.json".equals(low)) {
                        appJsonText = new String(readAllBytes(zis, 256 * 1024), StandardCharsets.UTF_8);
                    } else if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".webp") || low.endsWith(".tga")) {
                        if (low.contains("icon") || low.startsWith("assets/")) {
                            byte[] img = readAllBytes(zis, 1024 * 1024);
                            if (img != null && img.length > 16) {
                                iconCandidates.put(name, img);
                            }
                        }
                    }
                }
            }

            if (appJsonText == null || appJsonText.isEmpty()) return;
            JSONObject root = new JSONObject(appJsonText);
            JSONObject app = root.optJSONObject("app");
            if (app == null) return;

            int appId = app.optInt("appId", 0);
            if (appId <= 0) return;

            String appName = app.optString("appName", "").trim();
            // Prefer zh-TW / current locale i18n name if present
            JSONObject i18n = root.optJSONObject("i18n");
            if (i18n != null) {
                String langTag = Locale.getDefault().toLanguageTag();
                JSONObject locObj = i18n.optJSONObject(langTag);
                if (locObj == null && langTag.startsWith("zh")) {
                    locObj = i18n.optJSONObject("zh-TW");
                    if (locObj == null) locObj = i18n.optJSONObject("zh-CN");
                }
                if (locObj != null) {
                    String locName = locObj.optString("appName", "").trim();
                    if (!locName.isEmpty()) appName = locName;
                }
            }
            if (appName.isEmpty()) appName = "App #" + appId;

            String desc = app.optString("description", "").trim();
            String iconSpec = app.optString("icon", "icon.png").trim();
            byte[] rawIcon = findMatchingIconBytes(iconCandidates, iconSpec);

            String iconUri = "";
            if (rawIcon != null) {
                File pngFile = decodeZeppIconToPngFile(appCtx, appId, rawIcon);
                if (pngFile != null && pngFile.exists() && pngFile.length() > 0) {
                    iconUri = "file://" + pngFile.getAbsolutePath();
                }
            }

            AppMeta meta = new AppMeta(appId, appName, desc, iconUri, "zpk");
            sCache.put(appId, meta);
            saveCatalog(appCtx);
            HookBridge.logInfo(TAG, "[AppMeta] Cached .zpk metadata: appId=" + appId
                    + ", name='" + appName + "', icon='" + iconUri + "'");
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] extractAndCacheFromDeviceZip failed", t);
        }
    }

    private static byte[] findMatchingIconBytes(Map<String, byte[]> candidates, String iconSpec) {
        if (candidates.isEmpty()) return null;
        if (iconSpec != null && !iconSpec.isEmpty()) {
            String normalized = iconSpec.startsWith("/") ? iconSpec.substring(1) : iconSpec;
            if (candidates.containsKey(normalized)) return candidates.get(normalized);
            if (candidates.containsKey("assets/" + normalized)) return candidates.get("assets/" + normalized);
            for (Map.Entry<String, byte[]> e : candidates.entrySet()) {
                if (e.getKey().endsWith("/" + normalized) || e.getKey().equalsIgnoreCase(normalized)) {
                    return e.getValue();
                }
            }
        }
        for (Map.Entry<String, byte[]> e : candidates.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains("icon")) {
                return e.getValue();
            }
        }
        return null;
    }

    private static void seedBundledHaZpkIfNeeded(Context ctx) {
        if (sBundledSeeded || ctx == null) return;
        sBundledSeeded = true;
        try {
            AppMeta existing = sCache.get(1066510);
            if (existing != null && !existing.name.isEmpty() && !existing.iconUrl.isEmpty()) {
                if (existing.iconUrl.startsWith("file://")) {
                    File f = new File(existing.iconUrl.substring(7));
                    if (f.exists() && f.length() > 0) return;
                }
            }
            Context modCtx = ctx.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            try (InputStream is = modCtx.getAssets().open("HA_Lights_Balance2.zpk")) {
                byte[] zpkBytes = readAllBytes(is, 4 * 1024 * 1024);
                byte[] devZip = extractDeviceZipFromZpk(zpkBytes);
                if (devZip != null) {
                    extractAndCacheFromDeviceZip(ctx, devZip);
                }
            }
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] seedBundledHaZpkIfNeeded failed", t);
        }
    }

    private static byte[] extractDeviceZipFromZpk(byte[] zpkBytes) {
        if (zpkBytes == null || zpkBytes.length < 32) return null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zpkBytes))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if ("device.zip".equals(ze.getName())) {
                    return readAllBytes(zis, 4 * 1024 * 1024);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void enrichAppsStoreActivity(final Activity act) {
        final Context appCtx = act.getApplicationContext();
        ensureCatalogLoaded(appCtx);
        seedBundledHaZpkIfNeeded(appCtx);

        List<?> displayList = getFieldAsList(act, "x");
        Object adapter = getFieldObject(act, "w");
        List<?> adapterList = adapter != null ? getFieldAsList(adapter, "f") : null;

        List<Object> allItems = new ArrayList<>();
        if (displayList != null) allItems.addAll(displayList);
        if (adapterList != null && adapterList != displayList) {
            for (Object o : adapterList) {
                if (!allItems.contains(o)) allItems.add(o);
            }
        }
        if (allItems.isEmpty()) return;

        boolean modifiedAny = false;
        final List<Integer> missingIds = new ArrayList<>();
        Set<Integer> seenMissing = new HashSet<>();

        for (Object item : allItems) {
            if (item == null) continue;
            String idStr = getStringField(item, "a");
            int appId = parseAppId(idStr);
            if (appId <= 0) continue;

            String curName = getStringField(item, "c");
            String curDesc = getStringField(item, "d");
            String curIcon = getStringField(item, "e");

            boolean needsName = (curName == null || curName.trim().isEmpty() || curName.startsWith("App #"));
            boolean needsIcon = (curIcon == null || curIcon.trim().isEmpty());

            // 1. Apply cached metadata (.zpk or previous Zepp Store lookup)
            AppMeta cached = sCache.get(appId);
            if (cached != null) {
                if (needsName && !cached.name.isEmpty()) {
                    setStringField(item, "c", cached.name);
                    curName = cached.name;
                    needsName = false;
                    modifiedAny = true;
                }
                if ((curDesc == null || curDesc.trim().isEmpty()) && !cached.description.isEmpty()) {
                    setStringField(item, "d", cached.description);
                    modifiedAny = true;
                }
                if (needsIcon && !cached.iconUrl.isEmpty()) {
                    if (!cached.iconUrl.startsWith("file://") || new File(cached.iconUrl.substring(7)).exists()) {
                        setStringField(item, "e", cached.iconUrl);
                        curIcon = cached.iconUrl;
                        needsIcon = false;
                        modifiedAny = true;
                    }
                }
            }

            // 2. Apply user custom name override (highest priority for display name)
            String customName = sCustomNames.get(appId);
            if (customName != null && !customName.isEmpty() && !customName.equals(curName)) {
                setStringField(item, "c", customName);
                needsName = false;
                modifiedAny = true;
            }

            // 3. Queue for Zepp Cloud Store lookup if still missing name or icon
            if ((needsName || needsIcon) && seenMissing.add(appId)) {
                missingIds.add(appId);
            }
        }

        if (modifiedAny && adapter != null) {
            notifyAdapterSafe(adapter);
        }

        if (!missingIds.isEmpty()) {
            String token = sZeppAppToken;
            if (token != null && !token.isEmpty()) {
                fetchFromZeppStoreAsync(act, appCtx, adapter, missingIds, token);
            } else {
                HookBridge.logInfo(TAG, "[AppMeta] " + missingIds.size()
                        + " apps still missing metadata (" + missingIds + "); Zepp apptoken not configured");
            }
        }
    }

    private static void fetchFromZeppStoreAsync(
            final Activity act,
            final Context appCtx,
            final Object adapter,
            final List<Integer> missingIds,
            final String token) {
        final String key = missingIds.toString();
        if (!sInFlightQueries.add(key)) return;

        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<Integer, AppMeta> resolved = queryZeppStoreBatch(missingIds, token);
                    if (resolved.isEmpty()) return;

                    for (Map.Entry<Integer, AppMeta> entry : resolved.entrySet()) {
                        AppMeta prev = sCache.get(entry.getKey());
                        AppMeta next = entry.getValue();
                        // Keep local .zpk icon if Zepp didn't return one
                        if (prev != null && next.iconUrl.isEmpty() && !prev.iconUrl.isEmpty()) {
                            next = new AppMeta(next.appId, next.name, next.description, prev.iconUrl, next.source);
                        }
                        sCache.put(entry.getKey(), next);
                    }
                    saveCatalog(appCtx);

                    if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    applyCacheToActivityItems(act, adapter);
                                } catch (Throwable t) {
                                    HookBridge.logError(TAG, "[AppMeta] UI refresh failed", t);
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    HookBridge.logError(TAG, "[AppMeta] fetchFromZeppStoreAsync failed", t);
                } finally {
                    sInFlightQueries.remove(key);
                }
            }
        });
    }

    private static void applyCacheToActivityItems(Activity act, Object adapter) {
        List<?> displayList = getFieldAsList(act, "x");
        List<?> adapterList = adapter != null ? getFieldAsList(adapter, "f") : null;
        List<Object> allItems = new ArrayList<>();
        if (displayList != null) allItems.addAll(displayList);
        if (adapterList != null && adapterList != displayList) {
            for (Object o : adapterList) {
                if (!allItems.contains(o)) allItems.add(o);
            }
        }
        boolean changed = false;
        for (Object item : allItems) {
            int appId = parseAppId(getStringField(item, "a"));
            if (appId <= 0) continue;
            AppMeta meta = sCache.get(appId);
            String customName = sCustomNames.get(appId);
            if (meta != null) {
                String curName = getStringField(item, "c");
                if (customName != null && !customName.isEmpty()) {
                    if (!customName.equals(curName)) {
                        setStringField(item, "c", customName);
                        changed = true;
                    }
                } else if (!meta.name.isEmpty() && (curName == null || curName.isEmpty() || curName.startsWith("App #"))) {
                    setStringField(item, "c", meta.name);
                    changed = true;
                }
                String curDesc = getStringField(item, "d");
                if (!meta.description.isEmpty() && (curDesc == null || curDesc.isEmpty())) {
                    setStringField(item, "d", meta.description);
                    changed = true;
                }
                String curIcon = getStringField(item, "e");
                if (!meta.iconUrl.isEmpty() && (curIcon == null || curIcon.isEmpty())) {
                    setStringField(item, "e", meta.iconUrl);
                    changed = true;
                }
            }
        }
        if (changed && adapter != null) {
            notifyAdapterSafe(adapter);
        }
    }

    /**
     * Queries official Zepp Market API for a batch of appIds. Also callable from MainActivity for testing.
     */
    public static Map<Integer, AppMeta> queryZeppStoreBatch(List<Integer> appIds, String token) {
        Map<Integer, AppMeta> result = new LinkedHashMap<>();
        if (appIds == null || appIds.isEmpty() || token == null || token.trim().isEmpty()) {
            return result;
        }
        Set<Integer> remaining = new HashSet<>(appIds);
        String lang = Locale.getDefault().toLanguageTag();
        if (lang == null || lang.isEmpty()) lang = "zh-TW";

        for (String host : ZEPP_MARKET_HOSTS) {
            boolean hostAuthFailed = false;
            for (int devSource : ZEPP_DEVICE_SOURCES) {
                if (remaining.isEmpty()) break;
                StringBuilder csv = new StringBuilder();
                for (Integer id : remaining) {
                    if (csv.length() > 0) csv.append(',');
                    csv.append(id);
                }
                String urlStr = host + "/market/devices/" + devSource
                        + "/apps?customizable=disable&app_ids=" + csv
                        + "&page=1&per_page=" + Math.max(50, remaining.size());
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("apptoken", token.trim());
                    conn.setRequestProperty("appname", "com.huami.midong");
                    conn.setRequestProperty("appplatform", "android_phone");
                    conn.setRequestProperty("callid", String.valueOf(System.currentTimeMillis()));
                    conn.setRequestProperty("v", "1.0");
                    conn.setRequestProperty("Accept-Language", lang);
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    if (code == 401 || code == 403) {
                        HookBridge.logInfo(TAG, "[AppMeta] Zepp API " + host + " returned HTTP " + code);
                        hostAuthFailed = true;
                        break;
                    }
                    if (code < 200 || code >= 300) {
                        continue;
                    }
                    String body = readStreamUtf8(conn.getInputStream());
                    int foundHere = parseZeppMarketJson(body, result, remaining);
                    HookBridge.logInfo(TAG, "[AppMeta] Zepp API " + host + " dev=" + devSource
                            + " resolved " + foundHere + " apps, remaining=" + remaining.size());
                } catch (Throwable t) {
                    HookBridge.logError(TAG, "[AppMeta] Zepp API request failed: " + urlStr, t);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (remaining.isEmpty() || (!hostAuthFailed && !result.isEmpty())) {
                break;
            }
        }
        return result;
    }

    private static int parseZeppMarketJson(String body, Map<Integer, AppMeta> out, Set<Integer> remaining) {
        if (body == null || body.trim().isEmpty()) return 0;
        int count = 0;
        try {
            JSONArray arr = null;
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                arr = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                arr = root.optJSONArray("data");
                if (arr == null) {
                    JSONObject dataObj = root.optJSONObject("data");
                    if (dataObj != null) {
                        arr = dataObj.optJSONArray("data");
                        if (arr == null) arr = dataObj.optJSONArray("list");
                        if (arr == null) arr = dataObj.optJSONArray("apps");
                    }
                }
                if (arr == null) arr = root.optJSONArray("apps");
                if (arr == null) arr = root.optJSONArray("items");
            }
            if (arr == null) return 0;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                int id = obj.optInt("id", 0);
                if (id <= 0) id = obj.optInt("app_id", 0);
                if (id <= 0) id = obj.optInt("appId", 0);
                if (id <= 0) continue;

                String name = obj.optString("name", "").trim();
                if (name.isEmpty()) name = obj.optString("title", "").trim();
                String desc = obj.optString("briefDescription", "").trim();
                if (desc.isEmpty()) desc = obj.optString("brief_description", "").trim();
                if (desc.isEmpty()) desc = obj.optString("description", "").trim();
                if (desc.length() > 140) desc = desc.substring(0, 140) + "…";

                String icon = obj.optString("image", "").trim();
                if (icon.isEmpty()) icon = obj.optString("icon", "").trim();
                if (icon.isEmpty()) icon = obj.optString("iconUrl", "").trim();
                if (icon.isEmpty()) icon = obj.optString("icon_url", "").trim();

                if (!name.isEmpty() || !icon.isEmpty()) {
                    out.put(id, new AppMeta(id, name, desc, icon, "zepp_api"));
                    remaining.remove(id);
                    count++;
                }
            }
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] parseZeppMarketJson failed", t);
        }
        return count;
    }

    // =========================================================================
    // Zepp TGA / Standard Image -> Real PNG Decoder
    // =========================================================================

    public static File decodeZeppIconToPngFile(Context ctx, int appId, byte[] raw) {
        if (ctx == null || raw == null || raw.length < 18) return null;
        Bitmap bmp = null;
        try {
            // 1. Try standard Android image formats (PNG/JPEG/WebP)
            bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            // 2. Fallback: Zepp OS custom TGA ("SOMH" header, color-mapped type 1 or truecolor type 2)
            if (bmp == null) {
                bmp = decodeZeppTgaBitmap(raw);
            }
            if (bmp == null) return null;

            File dir = new File(ctx.getFilesDir(), META_DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, appId + ".png");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos);
                fos.flush();
            }
            return outFile;
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] decodeZeppIconToPngFile failed for appId=" + appId, t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static Bitmap decodeZeppTgaBitmap(byte[] b) {
        if (b == null || b.length < 18) return null;
        int idLen = b[0] & 0xFF;
        int colorMapType = b[1] & 0xFF;
        int imageType = b[2] & 0xFF;
        int cmOrigin = u16le(b, 3);
        int cmLen = u16le(b, 5);
        int cmBpp = b[7] & 0xFF;
        int strideW = u16le(b, 12);
        int height = u16le(b, 14);
        int bpp = b[16] & 0xFF;
        int desc = b[17] & 0xFF;

        if (strideW <= 0 || height <= 0 || strideW > 2048 || height > 2048) return null;
        boolean topDown = (desc & 0x20) != 0;

        // Zepp OS "SOMH" ID field encodes the actual visible width (row stride is 16-byte aligned)
        int realW = strideW;
        if (idLen >= 8 && b.length >= 18 + idLen
                && b[18] == 'S' && b[19] == 'O' && b[20] == 'M' && b[21] == 'H') {
            int somhW = u16le(b, 22);
            if (somhW > 0 && somhW <= strideW) {
                realW = somhW;
            }
        }

        int pos = 18 + idLen;
        int[] palette = null;
        if (colorMapType == 1 && cmLen > 0) {
            int cmBytesPerEntry = (cmBpp + 7) / 8;
            if (pos + cmLen * cmBytesPerEntry > b.length) return null;
            palette = new int[cmOrigin + cmLen];
            for (int i = 0; i < cmLen; i++) {
                int o = pos + i * cmBytesPerEntry;
                int blue = b[o] & 0xFF;
                int green = (cmBytesPerEntry >= 2) ? (b[o + 1] & 0xFF) : blue;
                int red = (cmBytesPerEntry >= 3) ? (b[o + 2] & 0xFF) : blue;
                int alpha = (cmBytesPerEntry >= 4) ? (b[o + 3] & 0xFF) : 0xFF;
                palette[cmOrigin + i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            pos += cmLen * cmBytesPerEntry;
        }

        int[] pixels = new int[realW * height];
        if (imageType == 1 && palette != null && bpp == 8) {
            if (pos + strideW * height > b.length) return null;
            for (int y = 0; y < height; y++) {
                int dstY = topDown ? y : (height - 1 - y);
                int rowOff = pos + y * strideW;
                int dstRow = dstY * realW;
                for (int x = 0; x < realW; x++) {
                    int idx = b[rowOff + x] & 0xFF;
                    pixels[dstRow + x] = (idx < palette.length) ? palette[idx] : 0;
                }
            }
            return Bitmap.createBitmap(pixels, realW, height, Bitmap.Config.ARGB_8888);
        }

        if (imageType == 2 && (bpp == 32 || bpp == 24 || bpp == 16)) {
            int bytesPerPx = bpp / 8;
            if (pos + strideW * height * bytesPerPx > b.length) return null;
            for (int y = 0; y < height; y++) {
                int dstY = topDown ? y : (height - 1 - y);
                int rowOff = pos + y * strideW * bytesPerPx;
                int dstRow = dstY * realW;
                for (int x = 0; x < realW; x++) {
                    int o = rowOff + x * bytesPerPx;
                    int argb;
                    if (bpp == 32) {
                        argb = ((b[o + 3] & 0xFF) << 24)
                                | ((b[o + 2] & 0xFF) << 16)
                                | ((b[o + 1] & 0xFF) << 8)
                                | (b[o] & 0xFF);
                    } else if (bpp == 24) {
                        argb = 0xFF000000
                                | ((b[o + 2] & 0xFF) << 16)
                                | ((b[o + 1] & 0xFF) << 8)
                                | (b[o] & 0xFF);
                    } else {
                        int v = u16le(b, o);
                        int r = ((v >> 11) & 0x1F) * 255 / 31;
                        int g = ((v >> 5) & 0x3F) * 255 / 63;
                        int bl = (v & 0x1F) * 255 / 31;
                        argb = 0xFF000000 | (r << 16) | (g << 8) | bl;
                    }
                    pixels[dstRow + x] = argb;
                }
            }
            return Bitmap.createBitmap(pixels, realW, height, Bitmap.Config.ARGB_8888);
        }
        return null;
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    // =========================================================================
    // Persistent Disk Catalog (Notify app private filesDir/balance2_app_meta)
    // =========================================================================

    private static synchronized void ensureCatalogLoaded(Context ctx) {
        if (sCatalogLoaded || ctx == null) return;
        sCatalogLoaded = true;
        try {
            File f = new File(new File(ctx.getFilesDir(), META_DIR_NAME), CATALOG_FILE_NAME);
            if (!f.exists() || f.length() == 0) return;
            String text;
            try (FileInputStream fis = new FileInputStream(f)) {
                text = readStreamUtf8(fis);
            }
            JSONObject root = new JSONObject(text);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                int id = parseAppId(k);
                if (id <= 0) continue;
                JSONObject o = root.optJSONObject(k);
                if (o == null) continue;
                sCache.put(id, new AppMeta(
                        id,
                        o.optString("name", ""),
                        o.optString("desc", ""),
                        o.optString("icon", ""),
                        o.optString("source", "")));
            }
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] ensureCatalogLoaded failed", t);
        }
    }

    private static synchronized void saveCatalog(Context ctx) {
        if (ctx == null) return;
        try {
            File dir = new File(ctx.getFilesDir(), META_DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            for (Map.Entry<Integer, AppMeta> e : sCache.entrySet()) {
                AppMeta m = e.getValue();
                JSONObject o = new JSONObject();
                o.put("name", m.name);
                o.put("desc", m.description);
                o.put("icon", m.iconUrl);
                o.put("source", m.source);
                root.put(String.valueOf(e.getKey()), o);
            }
            File f = new File(dir, CATALOG_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] saveCatalog failed", t);
        }
    }

    // =========================================================================
    // Reflection & IO Utilities
    // =========================================================================

    private static int parseAppId(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try {
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            try {
                return Integer.parseInt(t, 16);
            } catch (Throwable ignored2) {
                return 0;
            }
        }
    }

    private static Object getFieldObject(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<?> getFieldAsList(Object obj, String fieldName) {
        Object val = getFieldObject(obj, fieldName);
        return (val instanceof List<?>) ? (List<?>) val : null;
    }

    private static String getStringField(Object obj, String fieldName) {
        Object val = getFieldObject(obj, fieldName);
        return (val instanceof String) ? (String) val : null;
    }

    private static void setStringField(Object obj, String fieldName, String value) {
        if (obj == null) return;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static void notifyAdapterSafe(Object adapter) {
        if (adapter == null) return;
        try {
            Method m = findMethodHierarchy(adapter.getClass(), "notifyDataSetChanged");
            if (m != null) {
                m.setAccessible(true);
                m.invoke(adapter);
            } else {
                // Obfuscated RecyclerView.Adapter fallback: look for notifyDataSetChanged via no-arg void method on superclass
                Class<?> sc = adapter.getClass().getSuperclass();
                if (sc != null) {
                    for (Field f : sc.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object obs = f.get(adapter);
                        if (obs != null && obs.getClass().getName().contains("Observable")) {
                            for (Method om : obs.getClass().getDeclaredMethods()) {
                                if (om.getParameterTypes().length == 0 && om.getReturnType() == void.class) {
                                    om.setAccessible(true);
                                    om.invoke(obs);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            HookBridge.logError(TAG, "[AppMeta] notifyAdapterSafe failed", t);
        }
    }

    private static Method findMethodHierarchy(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream is, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Entry exceeds max size " + maxBytes);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readStreamUtf8(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
