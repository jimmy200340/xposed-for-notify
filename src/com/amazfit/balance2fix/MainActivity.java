package com.amazfit.balance2fix;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String PREFS_NAME = "balance2_module_prefs";
    public static final String KEY_WEATHER_FIX = "enable_weather_fix";
    public static final String KEY_AGPS_FIX = "enable_agps_fix";
    public static final String KEY_BT_UNLOCK = "enable_bt_unlock";
    public static final String KEY_VOIP_CALL_FIX = "enable_voip_call_fix";
    public static final String KEY_HA_BRIDGE_FIX = "enable_ha_bridge";
    public static final String KEY_HA_URL = "ha_url";
    public static final String KEY_HA_TOKEN = "ha_token";
    public static final String KEY_HA_FILTER = "ha_light_filter";
    public static final String KEY_AGPS_FORMAT = "agps_format_mode"; // 2 = EPO (0x8C/8D/8E Balance 2), 3 = LTO (0x8B)
    public static final String KEY_AGPS_MIGRATED_EPO_V1 = "agps_migrated_epo_v1";
    public static final String KEY_AGPS_LAST_UPDATE = "agps_last_update_ms";
    public static final String KEY_AGPS_EXPIRE = "agps_expire_ms";

    public static final String ACTION_UPDATE_CONFIG = "com.amazfit.balance2fix.ACTION_UPDATE_CONFIG";
    public static final String ACTION_REQUEST_STATUS = "com.amazfit.balance2fix.ACTION_REQUEST_STATUS";
    public static final String ACTION_STATUS_REPORT = "com.amazfit.balance2fix.ACTION_STATUS_REPORT";
    public static final String ACTION_ONE_CLICK_AGPS = "com.amazfit.balance2fix.ACTION_ONE_CLICK_AGPS";
    public static final String ACTION_INSTALL_ZPK = "com.amazfit.balance2fix.ACTION_INSTALL_ZPK";
    public static final String ACTION_ZPK_INSTALL_PROGRESS = "com.amazfit.balance2fix.ACTION_ZPK_INSTALL_PROGRESS";
    private static final int REQ_PICK_ZPK = 4091;

    private Switch swWeather;
    private Switch swAgps;
    private Switch swBtUnlock;
    private Switch swVoipCall;
    private Switch swHaBridge;
    private EditText etHaUrl;
    private EditText etHaToken;
    private EditText etHaFilter;
    private TextView tvHaTestResult;
    private TextView tvZpkProgress;
    private TextView tvHookStatus;
    private TextView tvAgpsUpdate;
    private TextView tvAgpsExpire;
    private TextView tvAgpsProgress;
    private boolean suppressSwitchCallback = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_STATUS_REPORT.equals(action)) {
                long lastUpdate = intent.getLongExtra(KEY_AGPS_LAST_UPDATE, 0L);
                long expire = intent.getLongExtra(KEY_AGPS_EXPIRE, 0L);
                String progressMsg = intent.getStringExtra("agps_progress_msg");
                boolean hookActive = intent.getBooleanExtra("hook_active", true);

                SharedPreferences prefs = getSafePrefs();
                SharedPreferences.Editor ed = prefs.edit();
                if (lastUpdate > 0L) ed.putLong(KEY_AGPS_LAST_UPDATE, lastUpdate);
                if (expire > 0L) ed.putLong(KEY_AGPS_EXPIRE, expire);
                ed.apply();
                makePrefsWorldReadable();

                if (hookActive && tvHookStatus != null) {
                    String notifyVer = intent.getStringExtra("notify_version");
                    String xposedApi = intent.getStringExtra("xposed_api");
                    String resolverStatus = intent.getStringExtra("resolver_status");
                    String verLabel = (notifyVer != null && !notifyVer.isEmpty()) ? ("Notify v" + notifyVer) : "Notify";
                    String apiSuffix = (xposedApi != null && !xposedApi.isEmpty()) ? (" · " + xposedApi) : "";
                    String resolverLine = (resolverStatus != null && !resolverStatus.isEmpty())
                            ? ("\n● Dynamic Auto-Resolver: " + resolverStatus)
                            : "";
                    tvHookStatus.setText("● LSPosed 模組已生效 (" + verLabel + apiSuffix + " 已連線)" + resolverLine);
                    tvHookStatus.setTextColor(Color.parseColor("#66BB6A"));
                }
                if (progressMsg != null && tvAgpsProgress != null) {
                    tvAgpsProgress.setVisibility(View.VISIBLE);
                    tvAgpsProgress.setText(progressMsg);
                }
                refreshAgpsTimestampsUI();
            } else if (ACTION_ZPK_INSTALL_PROGRESS.equals(action)) {
                String zpkMsg = intent.getStringExtra("msg");
                boolean done = intent.getBooleanExtra("done", false);
                boolean success = intent.getBooleanExtra("success", true);
                if (zpkMsg != null && tvZpkProgress != null) {
                    tvZpkProgress.setVisibility(View.VISIBLE);
                    tvZpkProgress.setText(zpkMsg);
                    if (done) {
                        tvZpkProgress.setTextColor(Color.parseColor(success ? "#81C784" : "#FF8A80"));
                    } else {
                        tvZpkProgress.setTextColor(Color.parseColor("#4FC3F7"));
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        loadPrefsIntoUI();
        broadcastConfigToNotify(false);
        requestStatusFromNotify();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STATUS_REPORT);
        filter.addAction(ACTION_ZPK_INSTALL_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        refreshAgpsTimestampsUI();
        requestStatusFromNotify();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences getSafePrefs() {
        try {
            return getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (Throwable t) {
            return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private void makePrefsWorldReadable() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);
            File prefsDir = new File(dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            File prefsFile = new File(prefsDir, PREFS_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {}
    }

    private void loadPrefsIntoUI() {
        SharedPreferences prefs = getSafePrefs();
        if (!prefs.getBoolean(KEY_AGPS_MIGRATED_EPO_V1, false)) {
            int curFmt = prefs.getInt(KEY_AGPS_FORMAT, 2);
            if (curFmt == 3 || curFmt == 4) curFmt = 2;
            prefs.edit()
                    .putInt(KEY_AGPS_FORMAT, curFmt)
                    .putBoolean(KEY_AGPS_MIGRATED_EPO_V1, true)
                    .apply();
            makePrefsWorldReadable();
        }
        suppressSwitchCallback = true;
        swWeather.setChecked(prefs.getBoolean(KEY_WEATHER_FIX, true));
        swAgps.setChecked(prefs.getBoolean(KEY_AGPS_FIX, true));
        swBtUnlock.setChecked(prefs.getBoolean(KEY_BT_UNLOCK, true));
        if (swVoipCall != null) {
            swVoipCall.setChecked(prefs.getBoolean(KEY_VOIP_CALL_FIX, true));
        }
        if (swHaBridge != null) {
            swHaBridge.setChecked(prefs.getBoolean(KEY_HA_BRIDGE_FIX, true));
        }
        if (etHaUrl != null) {
            etHaUrl.setText(prefs.getString(KEY_HA_URL, ""));
        }
        if (etHaToken != null) {
            etHaToken.setText(prefs.getString(KEY_HA_TOKEN, ""));
        }
        if (etHaFilter != null) {
            etHaFilter.setText(prefs.getString(KEY_HA_FILTER, ""));
        }
        suppressSwitchCallback = false;
        refreshAgpsTimestampsUI();
    }

    private static String normalizeHaUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private void saveAndSyncConfig() {
        if (suppressSwitchCallback) return;
        SharedPreferences prefs = getSafePrefs();
        String haUrl = etHaUrl != null ? normalizeHaUrl(etHaUrl.getText().toString()) : prefs.getString(KEY_HA_URL, "");
        String haToken = etHaToken != null ? etHaToken.getText().toString().trim() : prefs.getString(KEY_HA_TOKEN, "");
        String haFilter = etHaFilter != null ? etHaFilter.getText().toString().trim() : prefs.getString(KEY_HA_FILTER, "");
        prefs.edit()
                .putBoolean(KEY_WEATHER_FIX, swWeather.isChecked())
                .putBoolean(KEY_AGPS_FIX, swAgps.isChecked())
                .putBoolean(KEY_BT_UNLOCK, swBtUnlock.isChecked())
                .putBoolean(KEY_VOIP_CALL_FIX, swVoipCall != null ? swVoipCall.isChecked() : true)
                .putBoolean(KEY_HA_BRIDGE_FIX, swHaBridge != null ? swHaBridge.isChecked() : true)
                .putString(KEY_HA_URL, haUrl)
                .putString(KEY_HA_TOKEN, haToken)
                .putString(KEY_HA_FILTER, haFilter)
                .apply();
        makePrefsWorldReadable();
        broadcastConfigToNotify(true);
    }

    private void broadcastConfigToNotify(boolean showToast) {
        SharedPreferences prefs = getSafePrefs();
        Intent intent = new Intent(ACTION_UPDATE_CONFIG);
        intent.setPackage("com.mc.amazfit1");
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra(KEY_WEATHER_FIX, prefs.getBoolean(KEY_WEATHER_FIX, true));
        intent.putExtra(KEY_AGPS_FIX, prefs.getBoolean(KEY_AGPS_FIX, true));
        intent.putExtra(KEY_BT_UNLOCK, prefs.getBoolean(KEY_BT_UNLOCK, true));
        intent.putExtra(KEY_VOIP_CALL_FIX, prefs.getBoolean(KEY_VOIP_CALL_FIX, true));
        intent.putExtra(KEY_HA_BRIDGE_FIX, prefs.getBoolean(KEY_HA_BRIDGE_FIX, true));
        intent.putExtra(KEY_HA_URL, prefs.getString(KEY_HA_URL, ""));
        intent.putExtra(KEY_HA_TOKEN, prefs.getString(KEY_HA_TOKEN, ""));
        intent.putExtra(KEY_HA_FILTER, prefs.getString(KEY_HA_FILTER, ""));
        intent.putExtra(KEY_AGPS_FORMAT, prefs.getInt(KEY_AGPS_FORMAT, 2));
        sendBroadcast(intent);
        if (showToast) {
            Toast.makeText(this, "已即時同步設定至 Notify for Amazfit", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestStatusFromNotify() {
        Intent intent = new Intent(ACTION_REQUEST_STATUS);
        intent.setPackage("com.mc.amazfit1");
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(intent);
    }

    private void refreshAgpsTimestampsUI() {
        if (tvAgpsUpdate == null || tvAgpsExpire == null) return;
        SharedPreferences prefs = getSafePrefs();
        long lastUpdate = prefs.getLong(KEY_AGPS_LAST_UPDATE, 0L);
        long expire = prefs.getLong(KEY_AGPS_EXPIRE, 0L);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        if (lastUpdate > 0L) {
            tvAgpsUpdate.setText("上次成功更新：" + sdf.format(new Date(lastUpdate)));
        } else {
            tvAgpsUpdate.setText("上次成功更新：尚無紀錄 (可於下方或手環設定更新)");
        }

        if (expire > 0L) {
            long now = System.currentTimeMillis();
            boolean valid = expire > now;
            long diffHours = Math.abs(expire - now) / (3600L * 1000L);
            long days = diffHours / 24L;
            long hours = diffHours % 24L;
            String stateStr = valid
                    ? ("有效，剩餘 " + days + " 天 " + hours + " 小時")
                    : ("已過期 " + days + " 天");
            tvAgpsExpire.setText("AGPS 過期時間：" + sdf.format(new Date(expire)) + " (" + stateStr + ")");
            tvAgpsExpire.setTextColor(valid ? Color.parseColor("#81C784") : Color.parseColor("#FF8A80"));
        } else {
            tvAgpsExpire.setText("AGPS 過期時間：尚未同步");
            tvAgpsExpire.setTextColor(Color.parseColor("#B0BEC5"));
        }
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1419"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(24), pad, dp(28));
        scroll.addView(root);

        // Header Card
        LinearLayout headerCard = createCard("#1A2332", "#2E4057");
        TextView title = new TextView(this);
        title.setText("Notify Balance 2 Fix");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerCard.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Amazfit Balance 2 (Zepp OS 6) 專用 LSPosed 模組控制台");
        subtitle.setTextColor(Color.parseColor("#90CAF9"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        headerCard.addView(subtitle);

        tvHookStatus = new TextView(this);
        tvHookStatus.setText("● 等待 Notify for Amazfit 回應狀態...");
        tvHookStatus.setTextColor(Color.parseColor("#FFD54F"));
        tvHookStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerCard.addView(tvHookStatus);
        root.addView(headerCard);

        addSpacer(root, 14);

        // Section Title: Hook Switches
        TextView secSwitches = new TextView(this);
        secSwitches.setText("模組 Hook 功能開關 (即時生效)");
        secSwitches.setTextColor(Color.parseColor("#80CBC4"));
        secSwitches.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        secSwitches.setTypeface(Typeface.DEFAULT_BOLD);
        secSwitches.setPadding(dp(4), 0, 0, dp(8));
        root.addView(secSwitches);

        // Switch 1: Weather
        swWeather = new Switch(this);
        root.addView(createSwitchCard(
                "🌤️ 天氣同步修正 (Weather Hook)",
                "修復 Zepp OS 6 天氣封包結構與真實地點名稱提取 (支援 v24.1.1 原生與加強修正)",
                swWeather
        ));
        addSpacer(root, 10);

        // Switch 2: AGPS
        swAgps = new Switch(this);
        root.addView(createSwitchCard(
                "🛰️ AGPS 星曆更新修正與選單 (GPS Hook)",
                "修復 Zepp OS 6 AGPS 傳輸延遲 (移除 600ms/包 限速並啟用 EPO 封裝)，並於「裝置 > 手環設定 > 設定」新增 AGPS 更新選單",
                swAgps
        ));
        addSpacer(root, 10);

        // Switch 3: Bluetooth Unlock
        swBtUnlock = new Switch(this);
        root.addView(createSwitchCard(
                "🔓 小米 HyperOS 藍牙螢幕解鎖 (BT Unlock Hook)",
                "提供手機端雙模 GATT Server 模擬，並於「裝置 > 手環設定 > 設定」新增小米藍牙解鎖綁定選單",
                swBtUnlock
        ));
        addSpacer(root, 10);

        // Switch 4: VoIP Call Watch Answering & Audio Routing
        swVoipCall = new Switch(this);
        root.addView(createSwitchCard(
                "📞 VoIP 網路電話手錶通話修正 (VoIP Call Hook)",
                "修復 LINE / WhatsApp 等網路電話於手錶接聽時斷線或無聲問題，自動建立手錶藍牙 HFP SCO 雙向語音通話",
                swVoipCall
        ));
        addSpacer(root, 10);

        // Switch 5: Home Assistant BLE Bridge (Endpoint 0xA0)
        swHaBridge = new Switch(this);
        root.addView(createSwitchCard(
                "💡 Home Assistant 電燈藍牙橋接 (Endpoint 0xA0)",
                "免安裝官方 Zepp App！讓手錶上的「HA 電燈」小程式透過 Notify 藍牙通道即時控制家中 Home Assistant 電燈",
                swHaBridge
        ));

        CompoundButton.OnCheckedChangeListener listener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                saveAndSyncConfig();
            }
        };
        swWeather.setOnCheckedChangeListener(listener);
        swAgps.setOnCheckedChangeListener(listener);
        swBtUnlock.setOnCheckedChangeListener(listener);
        swVoipCall.setOnCheckedChangeListener(listener);
        swHaBridge.setOnCheckedChangeListener(listener);

        addSpacer(root, 18);

        // Section Title: AGPS Status & Actions
        TextView secAgps = new TextView(this);
        secAgps.setText("AGPS 衛星定位星曆狀態與捷徑");
        secAgps.setTextColor(Color.parseColor("#80CBC4"));
        secAgps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        secAgps.setTypeface(Typeface.DEFAULT_BOLD);
        secAgps.setPadding(dp(4), 0, 0, dp(8));
        root.addView(secAgps);

        LinearLayout agpsCard = createCard("#18202C", "#2B3A4E");

        tvAgpsUpdate = new TextView(this);
        tvAgpsUpdate.setText("上次成功更新：讀取中...");
        tvAgpsUpdate.setTextColor(Color.WHITE);
        tvAgpsUpdate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        agpsCard.addView(tvAgpsUpdate);

        tvAgpsExpire = new TextView(this);
        tvAgpsExpire.setText("AGPS 過期時間：讀取中...");
        tvAgpsExpire.setTextColor(Color.parseColor("#B0BEC5"));
        tvAgpsExpire.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvAgpsExpire.setPadding(0, dp(6), 0, dp(10));
        agpsCard.addView(tvAgpsExpire);

        tvAgpsProgress = new TextView(this);
        tvAgpsProgress.setTextColor(Color.parseColor("#4FC3F7"));
        tvAgpsProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvAgpsProgress.setPadding(0, 0, 0, dp(10));
        tvAgpsProgress.setVisibility(View.GONE);
        agpsCard.addView(tvAgpsProgress);

        Button btnOneClickAgps = createStyledButton("🚀 立即一鍵下載並更新 AGPS (Zepp OS 6 EPO)", "#1E88E5");
        btnOneClickAgps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!swAgps.isChecked()) {
                    Toast.makeText(MainActivity.this, "請先開啟「AGPS 星曆更新修正與選單」開關", Toast.LENGTH_SHORT).show();
                    return;
                }
                tvAgpsProgress.setVisibility(View.VISIBLE);
                tvAgpsProgress.setText("⏳ 正在通知 Notify 背景下載最新 Zepp OS 6 EPO 星曆並高速傳送至手錶...");
                Intent intent = new Intent(ACTION_ONE_CLICK_AGPS);
                intent.setPackage("com.mc.amazfit1");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.putExtra(KEY_AGPS_FORMAT, 2);
                sendBroadcast(intent);
            }
        });
        agpsCard.addView(btnOneClickAgps);

        addSpacer(agpsCard, 10);

        Button btnOpenSettings = createStyledButton("⚙️ 開啟 Notify「裝置 > 手環設定 > 設定」", "#37474F");
        btnOpenSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    broadcastConfigToNotify(false);
                    Intent openBs = new Intent("com.amazfit.balance2fix.OPEN_BAND_SETTINGS");
                    openBs.setPackage("com.mc.amazfit1");
                    openBs.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    sendBroadcast(openBs);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "無法開啟 BandSettingsActivity: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        agpsCard.addView(btnOpenSettings);

        root.addView(agpsCard);

        addSpacer(root, 18);

        // Section Title: Home Assistant Settings
        TextView secHa = new TextView(this);
        secHa.setText("Home Assistant 區域網路電燈設定 (手錶小程式專用)");
        secHa.setTextColor(Color.parseColor("#80CBC4"));
        secHa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        secHa.setTypeface(Typeface.DEFAULT_BOLD);
        secHa.setPadding(dp(4), 0, 0, dp(8));
        root.addView(secHa);

        LinearLayout haCard = createCard("#18202C", "#2B3A4E");

        TextView lblHaUrl = new TextView(this);
        lblHaUrl.setText("Home Assistant 網址 (例：http://192.168.1.100:8123)");
        lblHaUrl.setTextColor(Color.parseColor("#CFD8DC"));
        lblHaUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        haCard.addView(lblHaUrl);

        etHaUrl = createStyledInput("http://192.168.1.100:8123", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        haCard.addView(etHaUrl);
        addSpacer(haCard, 10);

        TextView lblHaToken = new TextView(this);
        lblHaToken.setText("長期存取權杖 (Long-Lived Access Token)");
        lblHaToken.setTextColor(Color.parseColor("#CFD8DC"));
        lblHaToken.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        haCard.addView(lblHaToken);

        etHaToken = createStyledInput("貼上 HA 個人檔案 > 安全性建立的 Token", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        haCard.addView(etHaToken);
        addSpacer(haCard, 10);

        TextView lblHaFilter = new TextView(this);
        lblHaFilter.setText("電燈篩選關鍵字 (選填，逗號分隔，空白代表顯示全部 light.*)");
        lblHaFilter.setTextColor(Color.parseColor("#CFD8DC"));
        lblHaFilter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        haCard.addView(lblHaFilter);

        etHaFilter = createStyledInput("例：客廳, 臥室, light.desk (留空顯示全部)", InputType.TYPE_CLASS_TEXT);
        haCard.addView(etHaFilter);
        addSpacer(haCard, 12);

        tvHaTestResult = new TextView(this);
        tvHaTestResult.setTextColor(Color.parseColor("#B0BEC5"));
        tvHaTestResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvHaTestResult.setPadding(0, 0, 0, dp(10));
        tvHaTestResult.setVisibility(View.GONE);
        haCard.addView(tvHaTestResult);

        Button btnSaveHa = createStyledButton("💾 儲存並即時同步 HA 設定至 Notify", "#2E7D32");
        btnSaveHa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String normalized = normalizeHaUrl(etHaUrl.getText().toString());
                etHaUrl.setText(normalized);
                saveAndSyncConfig();
                if (tvHaTestResult != null) {
                    tvHaTestResult.setVisibility(View.VISIBLE);
                    tvHaTestResult.setText("✅ 已儲存並同步至 Notify！可在手錶開啟「HA 電燈」測試。");
                    tvHaTestResult.setTextColor(Color.parseColor("#81C784"));
                }
            }
        });
        haCard.addView(btnSaveHa);

        addSpacer(haCard, 10);

        Button btnTestHa = createStyledButton("🔍 測試 HA 連線並預覽電燈清單", "#0277BD");
        btnTestHa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testHomeAssistantConnection();
            }
        });
        haCard.addView(btnTestHa);

        root.addView(haCard);

        addSpacer(root, 18);

        // Section Title: Official Zepp DfuV5 Safe Watch App (.zpk) Installer
        TextView secZpk = new TextView(this);
        secZpk.setText("手錶小程式安裝器 (官方 Zepp DfuV5 安全協定)");
        secZpk.setTextColor(Color.parseColor("#80CBC4"));
        secZpk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        secZpk.setTypeface(Typeface.DEFAULT_BOLD);
        secZpk.setPadding(dp(4), 0, 0, dp(8));
        root.addView(secZpk);

        LinearLayout zpkCard = createCard("#18202C", "#2B3A4E");

        TextView tvZpkInfo = new TextView(this);
        tvZpkInfo.setText("採用官方 Zepp App 同款 DfuV5 協定 (0xD0~0xD6 + FIRMWARE_LITE_APP 0xA0)，僅寫入手錶小程式沙盒分區，傳輸前強制驗證 app.json / app.bin 與 CRC32 並封鎖任何系統韌體，絕不會導致變磚。");
        tvZpkInfo.setTextColor(Color.parseColor("#B0BEC5"));
        tvZpkInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvZpkInfo.setPadding(0, 0, 0, dp(12));
        zpkCard.addView(tvZpkInfo);

        tvZpkProgress = new TextView(this);
        tvZpkProgress.setTextColor(Color.parseColor("#4FC3F7"));
        tvZpkProgress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvZpkProgress.setPadding(0, 0, 0, dp(10));
        tvZpkProgress.setVisibility(View.GONE);
        zpkCard.addView(tvZpkProgress);

        Button btnInstallBundledHa = createStyledButton("📲 一鍵安裝內建「HA 電燈」手錶小程式 (Balance 2)", "#00897B");
        btnInstallBundledHa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installBundledHaLightsZpk();
            }
        });
        zpkCard.addView(btnInstallBundledHa);

        addSpacer(zpkCard, 10);

        Button btnPickCustomZpk = createStyledButton("📂 選擇其他 .zpk 手錶小程式安裝 (安全驗證)", "#455A64");
        btnPickCustomZpk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    startActivityForResult(pick, REQ_PICK_ZPK);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "無法開啟檔案選擇器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        zpkCard.addView(btnPickCustomZpk);

        root.addView(zpkCard);
        setContentView(scroll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZPK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            installZpkFromUri(uri);
        }
    }

    private void installBundledHaLightsZpk() {
        setZpkStatus("⏳ 正在讀取內建 HA_Lights_Balance2.zpk 並執行安全檢查...", "#4FC3F7");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream is = getAssets().open("HA_Lights_Balance2.zpk")) {
                    byte[] rawBytes = readAllBytes(is, 4 * 1024 * 1024);
                    validateAndDispatchZpk(rawBytes, "HA 電燈 (內建)");
                } catch (final Throwable t) {
                    setZpkStatusOnUi("❌ 讀取內建 .zpk 失敗：" + t.getMessage(), "#FF8A80");
                }
            }
        }, "ZPK-Bundled").start();
    }

    private void installZpkFromUri(final android.net.Uri uri) {
        setZpkStatus("⏳ 正在讀取選擇的檔案並執行官方小程式格式驗證...", "#4FC3F7");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) {
                        setZpkStatusOnUi("❌ 無法開啟所選檔案串流", "#FF8A80");
                        return;
                    }
                    byte[] rawBytes = readAllBytes(is, 4 * 1024 * 1024);
                    validateAndDispatchZpk(rawBytes, "自訂 .zpk");
                } catch (final Throwable t) {
                    setZpkStatusOnUi("❌ 讀取檔案失敗：" + t.getMessage(), "#FF8A80");
                }
            }
        }, "ZPK-Custom").start();
    }

    private void validateAndDispatchZpk(byte[] rawBytes, String sourceLabel) throws Exception {
        if (rawBytes == null || rawBytes.length < 64
                || rawBytes[0] != 0x50 || rawBytes[1] != 0x4B || rawBytes[2] != 0x03 || rawBytes[3] != 0x04) {
            setZpkStatusOnUi("❌ 檔案無效：不是標準 ZIP / .zpk 壓縮格式", "#FF8A80");
            return;
        }

        // 1. Extract device.zip if this is an outer .zpk (or accept direct device.zip containing app.json)
        byte[] deviceZipBytes = null;
        boolean directHasAppJson = false;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(rawBytes))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if ("device.zip".equals(name)) {
                    deviceZipBytes = readAllBytes(zis, 4 * 1024 * 1024);
                    break;
                } else if ("app.json".equals(name)) {
                    directHasAppJson = true;
                }
            }
        }
        if (deviceZipBytes == null) {
            if (directHasAppJson) {
                deviceZipBytes = rawBytes;
            } else {
                setZpkStatusOnUi("❌ 格式檢查未通過：找不到 device.zip 或 app.json（若是 .zab 請先改選 .zpk）", "#FF8A80");
                return;
            }
        }

        // 2. Strict Anti-Brick Safety Check on device.zip BEFORE sending to Notify
        boolean hasAppJson = false;
        boolean hasAppBin = false;
        String appJsonText = null;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(deviceZipBytes))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                String low = name.toLowerCase(Locale.ROOT);
                if (low.startsWith("meta/") || low.endsWith(".fw") || low.contains("firmware.bin") || low.contains("bootloader")) {
                    setZpkStatusOnUi("🛡️ 安全封鎖：偵測到系統韌體檔案 (" + name + ")，已拒絕傳輸以保護手錶！", "#FF8A80");
                    return;
                }
                if ("app.json".equals(name)) {
                    hasAppJson = true;
                    appJsonText = new String(readAllBytes(zis, 128 * 1024), StandardCharsets.UTF_8);
                } else if ("app.bin".equals(name)) {
                    hasAppBin = true;
                }
            }
        }

        if (!hasAppJson || !hasAppBin || appJsonText == null) {
            setZpkStatusOnUi("❌ 格式檢查未通過：device.zip 缺少 app.json 或 app.bin", "#FF8A80");
            return;
        }

        JSONObject rootJson = new JSONObject(appJsonText);
        JSONObject appNode = rootJson.optJSONObject("app");
        String appType = appNode != null ? appNode.optString("appType", "") : "";
        int appId = appNode != null ? appNode.optInt("appId", 0) : 0;
        String appName = appNode != null ? appNode.optString("appName", sourceLabel) : sourceLabel;
        if (!"app".equalsIgnoreCase(appType) || appId <= 0) {
            setZpkStatusOnUi("❌ 安全檢查未通過：appType=" + appType + ", appId=" + appId + "（僅允許標準小程式 app）", "#FF8A80");
            return;
        }
        if (deviceZipBytes.length > 450 * 1024) {
            setZpkStatusOnUi("❌ device.zip 過大 (" + (deviceZipBytes.length / 1024) + " KB，目前上限 450 KB)", "#FF8A80");
            return;
        }

        final String label = appName + " (AppID " + appId + ", " + (deviceZipBytes.length / 1024) + " KB)";
        setZpkStatusOnUi("✅ 安全驗證通過：" + label + "\n⏳ 正在交由 Notify 透過官方 Zepp DfuV5 (0xA0) 安裝至手錶...", "#4FC3F7");

        Intent installIntent = new Intent(ACTION_INSTALL_ZPK);
        installIntent.setPackage("com.mc.amazfit1");
        installIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        installIntent.putExtra("zpk_device_zip_bytes", deviceZipBytes);
        installIntent.putExtra("zpk_app_label", label);
        sendBroadcast(installIntent);
    }

    private static byte[] readAllBytes(InputStream is, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("檔案超過安全大小限制");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private void setZpkStatus(String msg, String colorHex) {
        if (tvZpkProgress != null) {
            tvZpkProgress.setVisibility(View.VISIBLE);
            tvZpkProgress.setText(msg);
            tvZpkProgress.setTextColor(Color.parseColor(colorHex));
        }
    }

    private void setZpkStatusOnUi(final String msg, final String colorHex) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setZpkStatus(msg, colorHex);
            }
        });
    }

    private EditText createStyledInput(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#607D8B"));
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#0F1722"));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), Color.parseColor("#37474F"));
        et.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        et.setLayoutParams(lp);
        return et;
    }

    private void testHomeAssistantConnection() {
        final String baseUrl = normalizeHaUrl(etHaUrl != null ? etHaUrl.getText().toString() : "");
        final String token = etHaToken != null ? etHaToken.getText().toString().trim() : "";
        final String filterRaw = etHaFilter != null ? etHaFilter.getText().toString().trim() : "";

        if (baseUrl.isEmpty() || token.isEmpty()) {
            tvHaTestResult.setVisibility(View.VISIBLE);
            tvHaTestResult.setText("❌ 請先填寫 Home Assistant 網址與長期存取權杖 (Token)");
            tvHaTestResult.setTextColor(Color.parseColor("#FF8A80"));
            return;
        }

        if (etHaUrl != null) etHaUrl.setText(baseUrl);
        saveAndSyncConfig();

        tvHaTestResult.setVisibility(View.VISIBLE);
        tvHaTestResult.setText("⏳ 正在透過手機 Wi-Fi 連線至 " + baseUrl + "/api/states ...");
        tvHaTestResult.setTextColor(Color.parseColor("#4FC3F7"));

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(baseUrl + "/api/states");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(7000);
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    if (is != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line);
                            }
                        }
                    }

                    if (code != 200) {
                        final String err = "❌ HA 回應 HTTP " + code + (code == 401 ? " (Token 無效或過期)" : "");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tvHaTestResult.setText(err);
                                tvHaTestResult.setTextColor(Color.parseColor("#FF8A80"));
                            }
                        });
                        return;
                    }

                    JSONArray arr = new JSONArray(sb.toString());
                    String[] filters = filterRaw.isEmpty() ? new String[0] : filterRaw.split(",");
                    StringBuilder preview = new StringBuilder();
                    int matched = 0;
                    int onCount = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject st = arr.optJSONObject(i);
                        if (st == null) continue;
                        String eid = st.optString("entity_id", "");
                        if (!eid.startsWith("light.")) continue;
                        String state = st.optString("state", "");
                        if ("unavailable".equalsIgnoreCase(state)) continue;
                        JSONObject attrs = st.optJSONObject("attributes");
                        String fname = (attrs != null) ? attrs.optString("friendly_name", eid) : eid;

                        if (filters.length > 0) {
                            boolean hit = false;
                            String eidLow = eid.toLowerCase(Locale.ROOT);
                            String fnLow = fname.toLowerCase(Locale.ROOT);
                            for (String f : filters) {
                                String kw = f.trim().toLowerCase(Locale.ROOT);
                                if (!kw.isEmpty() && (eidLow.contains(kw) || fnLow.contains(kw))) {
                                    hit = true;
                                    break;
                                }
                            }
                            if (!hit) continue;
                        }

                        boolean isOn = "on".equalsIgnoreCase(state);
                        if (isOn) onCount++;
                        matched++;
                        if (matched <= 12) {
                            preview.append("\n  • ").append(fname).append(isOn ? " [開啟]" : " [關閉]");
                        }
                    }

                    final String msg = "✅ 連線成功！共找到 " + matched + " 盞電燈 (" + onCount + " 盞開啟)"
                            + (matched > 12 ? "，前 12 盞預覽：" : "：") + preview.toString();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvHaTestResult.setText(msg);
                            tvHaTestResult.setTextColor(Color.parseColor("#81C784"));
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvHaTestResult.setText("❌ 連線失敗：" + t.getClass().getSimpleName() + ": " + t.getMessage());
                            tvHaTestResult.setTextColor(Color.parseColor("#FF8A80"));
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "HA-ConnTest").start();
    }

    private LinearLayout createCard(String bgHex, String strokeHex) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(14));
        gd.setStroke(dp(1), Color.parseColor(strokeHex));
        card.setBackground(gd);
        return card;
    }

    private LinearLayout createSwitchCard(String titleText, String descText, final Switch sw) {
        LinearLayout card = createCard("#18202C", "#263238");
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(titleText);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(tvTitle, lpTitle);

        topRow.addView(sw);
        card.addView(topRow);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(descText);
        tvDesc.setTextColor(Color.parseColor("#90A4AE"));
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvDesc.setPadding(0, dp(6), 0, 0);
        card.addView(tvDesc);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
            }
        });
        return card;
    }

    private Button createStyledButton(String text, String colorHex) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(colorHex));
        gd.setCornerRadius(dp(10));
        btn.setBackground(gd);
        btn.setPadding(dp(12), dp(10), dp(12), dp(10));
        return btn;
    }

    private void addSpacer(LinearLayout parent, int dpHeight) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dpHeight)));
    }

    private int dp(float val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }
}
