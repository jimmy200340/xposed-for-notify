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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String PREFS_NAME = "balance2_module_prefs";
    public static final String KEY_WEATHER_FIX = "enable_weather_fix";
    public static final String KEY_AGPS_FIX = "enable_agps_fix";
    public static final String KEY_BT_UNLOCK = "enable_bt_unlock";
    public static final String KEY_VOIP_CALL_FIX = "enable_voip_call_fix";
    public static final String KEY_AGPS_FORMAT = "agps_format_mode"; // 2 = EPO (0x8C/8D/8E Balance 2), 3 = LTO (0x8B)
    public static final String KEY_AGPS_MIGRATED_EPO_V1 = "agps_migrated_epo_v1";
    public static final String KEY_AGPS_LAST_UPDATE = "agps_last_update_ms";
    public static final String KEY_AGPS_EXPIRE = "agps_expire_ms";

    public static final String ACTION_UPDATE_CONFIG = "com.amazfit.balance2fix.ACTION_UPDATE_CONFIG";
    public static final String ACTION_REQUEST_STATUS = "com.amazfit.balance2fix.ACTION_REQUEST_STATUS";
    public static final String ACTION_STATUS_REPORT = "com.amazfit.balance2fix.ACTION_STATUS_REPORT";
    public static final String ACTION_ONE_CLICK_AGPS = "com.amazfit.balance2fix.ACTION_ONE_CLICK_AGPS";

    private Switch swWeather;
    private Switch swAgps;
    private Switch swBtUnlock;
    private Switch swVoipCall;
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
                    String verLabel = (notifyVer != null && !notifyVer.isEmpty()) ? ("Notify v" + notifyVer) : "Notify";
                    String apiSuffix = (xposedApi != null && !xposedApi.isEmpty()) ? (" · " + xposedApi) : "";
                    tvHookStatus.setText("● LSPosed 模組已生效 (" + verLabel + apiSuffix + " 已連線)");
                    tvHookStatus.setTextColor(Color.parseColor("#66BB6A"));
                }
                if (progressMsg != null && tvAgpsProgress != null) {
                    tvAgpsProgress.setVisibility(View.VISIBLE);
                    tvAgpsProgress.setText(progressMsg);
                }
                refreshAgpsTimestampsUI();
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
        IntentFilter filter = new IntentFilter(ACTION_STATUS_REPORT);
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
        suppressSwitchCallback = false;
        refreshAgpsTimestampsUI();
    }

    private void saveAndSyncConfig() {
        if (suppressSwitchCallback) return;
        SharedPreferences prefs = getSafePrefs();
        prefs.edit()
                .putBoolean(KEY_WEATHER_FIX, swWeather.isChecked())
                .putBoolean(KEY_AGPS_FIX, swAgps.isChecked())
                .putBoolean(KEY_BT_UNLOCK, swBtUnlock.isChecked())
                .putBoolean(KEY_VOIP_CALL_FIX, swVoipCall != null ? swVoipCall.isChecked() : true)
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
        intent.putExtra(KEY_AGPS_FORMAT, prefs.getInt(KEY_AGPS_FORMAT, 2));
        sendBroadcast(intent);
        if (showToast) {
            Toast.makeText(this, "已即時同步開關設定至 Notify for Amazfit", Toast.LENGTH_SHORT).show();
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
        setContentView(scroll);
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
