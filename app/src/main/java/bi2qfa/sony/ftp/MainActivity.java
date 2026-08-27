package bi2qfa.sony.ftp;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.io.IOException;

import bi2qfa.sony.ftp.radio.RadioWrapper;
import bi2qfa.sony.ftp.radio.RadioWrapperFactory;

/**
 * 相机 WiFi 热点 + 匿名只读 FTP（v1.2）。
 *
 * 启动/退出流程照抄官方 Smart Remote Embedded v4.31 的
 * SRCtrlRootState —— 标志位状态机 + WiFi 关净后固定 5 秒沉降 +
 * 组就绪以连接层广播为准；兼容性结构与官方一致：
 * SDK≥16 走 WifiP2pExtManager，旧设备走 DirectManager（见 radio 包）。
 *
 * 退出纪律：停 FTP 并强拆会话（释放 SD 句柄）→ 同步关 Direct → 关 WiFi →
 * 确认关闭 → 恢复自动关机 → 才通知相机结束应用；ExitCompleted 广播兜底同一套，
 * 清理完成后才允许进程死亡。
 *
 * 直接拨关机拨杆：官方 BaseApp 把整套关停挂在 onPause() 里先做完再放行框架，
 * 照抄同款时序 —— onPause 里同步完成毫秒级关键清理后，系统才会继续断电
 * （这就是智能遥控“等程序关闭完成再关机”的实现方式）。
 */
public class MainActivity extends Activity {

    public static final String MY_IP_ADDRESS = "192.168.122.1";
    // 固定 2121：内核拒绝普通应用绑定低端口（CAP_NET_BIND_SERVICE），21 永远起不来
    public static final int FTP_PORT = 2121;

    private static final long DELAY_WIFI_ENABLE_MS   = 5000;  // 官方同款：WiFi 关净后再等 5 秒才重开
    private static final long DELAY_FATAL_CHECK_MS   = 5000;
    private static final long EXIT_CONFIRM_CAP_MS    = 10000;

    // ===== 界面相位（仅用于渲染）=====
    private static final int PH_STARTING = 0;
    private static final int PH_RUNNING  = 1;
    private static final int PH_FAILED   = 2;
    private static final int PH_CLOSING  = 3;
    private static final int PH_CLOSED   = 4;

    private TextView statusView;
    private ImageView qrView;
    private TextView qrCaption;
    private Handler handler;

    private WifiManager wifiManager;
    private RadioWrapper radio;
    private FtpServer ftpServer;
    private int ftpBoundPort = FTP_PORT;

    // ===== 官方标志位 =====
    private boolean isInitialDisabling;
    private boolean isDisableActionFiltered;
    private boolean isEnableActionFiltered;
    private boolean isGroupCreateActionFiltered;
    private boolean isDisablingForFinish;
    private boolean isRetrying;
    /** Direct 使能命令的独立重试标志（与官方 isRetrying 分离，避免语义混淆） */
    private boolean isDirectEnableRetrying;
    /** 自动整链恢复次数（每次用户手动触发时清零） */
    private int watchdogRecoveries = 0;

    private int curWifiMgrState = WifiManager.WIFI_STATE_ENABLED;
    private int curWifiDirectMgrState = RadioWrapper.DIRECT_STATE_UNKNOWN;

    private String ssid = null;
    private String password = null;
    private String errorMsg = null;
    private int phase = PH_STARTING;

    private boolean confirmingExit = false;
    private boolean shuttingDown = false;
    private long phaseEnterTime = 0;

    private BroadcastReceiver receiver;
    private IntentFilter iFilter;

    private final Runnable delayedWifiEnabler = new Runnable() {
        @Override public void run() {
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Throwable t) {}
        }
    };

    private final Runnable delayedFatalCheck = new Runnable() {
        @Override public void run() {
            if (isRetrying) {
            }
            if (curWifiMgrState == WifiManager.WIFI_STATE_DISABLED
                    || curWifiDirectMgrState == RadioWrapper.DIRECT_STATE_DISABLED) {
                String d = "";
                try { d = radio.getLastError(); } catch (Throwable t) {}
                goFatal("无线模块未能启动" + (d != null && d.length() > 0 ? "\n" + d : ""));
            }
        }
    };

    /** Direct 使能应答超时后的二次尝试；再败才报致命（官方无限等 + 我们的兜底） */
    private final Runnable delayedDirectEnableRetry = new Runnable() {
        @Override public void run() {
            if (shuttingDown || phase != PH_STARTING || curWifiDirectMgrState == RadioWrapper.DIRECT_STATE_ENABLED) return;
            boolean acked = false;
            try { acked = radio.setDirectEnabled(true); } catch (Throwable t) {}
            if (!acked) {
                goFatal("无法启用Direct模式");
            }
        }
    };

    /** 整条启动链的看门狗：卡死超过时限先自动重跑一轮，仍不行才报致命 */
    private final Runnable stalledStartupCheck = new Runnable() {
        @Override public void run() {
            if (shuttingDown || phase != PH_STARTING) return;
            if (watchdogRecoveries < 1) {
                watchdogRecoveries++;
                initAndStartWifiCycle(false);
            } else {
                goFatal("启动超时");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = (TextView) findViewById(R.id.statusView);
        qrView = (ImageView) findViewById(R.id.qrView);
        qrCaption = (TextView) findViewById(R.id.qrCaption);
        handler = new Handler();

        Context ctx = getApplicationContext();
        try {
            wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            wifiManager = null;
        }
        radio = RadioWrapperFactory.getInstance(ctx);

        iFilter = new IntentFilter();
        iFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        iFilter.addAction(RadioWrapper.ACTION_DIRECT_STATE_CHANGED);
        iFilter.addAction(RadioWrapper.ACTION_GROUP_CREATE_SUCCESS);
        iFilter.addAction(RadioWrapper.ACTION_GROUP_CREATE_FAILURE);
        iFilter.addAction(RadioWrapper.ACTION_STA_CONNECTED);
        iFilter.addAction(RadioWrapper.ACTION_STA_DISCONNECTED);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try { handleEvent(intent); } catch (Throwable t) {}
            }
        };

        notifyAppInfo();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyAppInfo();   // 官方每次 onResume 重发（SRCtrl 同款）
        if (isAirplaneModeOn()) {
            daFinishOnly();
            return;
        }
        watchdogRecoveries = 0;
        initAndStartWifiCycle(true);
    }

    /** 官方 onResume 的无线初始化段：首次、系统回到前台与 [确定键] 重试共用 */
    private void initAndStartWifiCycle(boolean manual) {
        if (shuttingDown) return;
        if (manual) watchdogRecoveries = 0;
        initFlags();
        phase = PH_STARTING;
        phaseEnterTime = now();
        errorMsg = null;
        ssid = null;
        password = null;
        try { registerReceiver(receiver, iFilter); } catch (Throwable t) {}
        if (wifiManager != null && wifiManager.getWifiState() != WifiManager.WIFI_STATE_DISABLED) {
            isDisableActionFiltered = false;
            isInitialDisabling = true;
            try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}
        } else {
            isEnableActionFiltered = false;
            try { wifiManager.setWifiEnabled(true); } catch (Throwable t) {}
        }
        // 整链看门狗：正常路径最迟应在组创建成功时进入 PH_RUNNING 而解除
        handler.removeCallbacks(stalledStartupCheck);
        handler.postDelayed(stalledStartupCheck, 30000);
        updateStatus();
    }

    private void initFlags() {
        isInitialDisabling = false;
        isDisableActionFiltered = true;
        isEnableActionFiltered = true;
        isGroupCreateActionFiltered = true;
        isDisablingForFinish = false;
        isRetrying = false;
        isDirectEnableRetrying = false;
    }

    private boolean isAirplaneModeOn() {
        try {
            return Settings.System.getInt(getContentResolver(), "airplane_mode_on", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onPause() {
        // 官方 BaseApp.onPause 同款：先把收尾做完、最后才 super.onPause()。
        // 相机上“离开前台”只有两种来路——界面里发起的退出（shuttingDown 已置位，
        // 不进这里），或直接拨了关机拨杆：系统即将断电，必须抢在被杀前
        // 同步完成关键清理，断电才会等我们收完这一手。
        if (!shuttingDown) {
            runForcedExitCritical();
        }
        super.onPause();
    }

    /**
     * 直接关机的竞速收尾（照官方把清理挂在 onPause 里同步做的机制）。
     * 毫秒级的关键步骤全部同步执行完才放行：停 FTP 释放 SD 句柄 → 恢复 APO
     * → 立刻下发两条无线关闭命令 → 告知相机应用已就绪退出；
     * 无线沉降确认是慢环节，丢给后台线程尽力补，进程若先被杀则自然终止。
     */
    private void runForcedExitCritical() {
        shuttingDown = true;
        isDisablingForFinish = true;
        confirmingExit = false;
        phase = PH_CLOSING;
        phaseEnterTime = now();
        handler.removeCallbacks(delayedWifiEnabler);
        handler.removeCallbacks(delayedFatalCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        handler.removeCallbacks(stalledStartupCheck);
        try { unregisterReceiver(receiver); } catch (Throwable t) {}

        stopFtpServer();
        restoreAutoPowerOff();
        try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}
        try { radio.issueDirectOff(); } catch (Throwable t) {}
        daFinishOnly();

        new Thread(new Runnable() {
            @Override public void run() {
                long deadline = SystemClock.elapsedRealtime() + EXIT_CONFIRM_CAP_MS;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (radiosConfirmedDown()) break;
                    // 可能还没沉下去（命令与驱动切换有时差）：补发后再等
                    try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}
                    try { radio.issueDirectOff(); } catch (Throwable t) {}
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                }
                restoreAutoPowerOff();
            }
        }, "FtpShutdown").start();
    }

    // 官方 AppInfo 键表常量（BaseApp 原文，SRCtrl.onResume 同款组合）
    private static final String[] PULLING_BACK_KEYS_FOR_PLAYBACK = {
            "KEY_S2", "KEY_S1_1", "KEY_S1_2", "KEY_MOVREC", "KEY_MODE_DIAL", "KEY_USB_CONNECT" };
    private static final String[] RESUME_KEYS_FOR_SHOOTING = {
            "KEY_POWER_SLIDE_PON", "KEY_RELEASE_APO", "KEY_PLAY_APO", "KEY_MEDIA_INOUT_APO",
            "KEY_LENS_APO", "KEY_ACCESSORY_APO", "KEY_DEDICATED_APO", "KEY_POWER_APO", "KEY_PLAY_PON" };

    /**
     * 向 DACM 服务上报应用信息。官方每次 onResume 都发，且带 REC/STILL 分类
     * 与两组按键表；只报名字的应用退出后落点是应用菜单——要回到取景界面，
     * 这条广播的参数必须与官方逐项一致。
     */
    private void notifyAppInfo() {
        try {
            Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            intent.putExtra("package_name", getComponentName().getPackageName());
            intent.putExtra("class_name", getComponentName().getClassName());
            intent.putExtra("large_category", "CATEGORY_REC");
            intent.putExtra("small_category", "STILL");
            intent.putExtra("pullingback_key", PULLING_BACK_KEYS_FOR_PLAYBACK);
            intent.putExtra("resume_key", RESUME_KEYS_FOR_SHOOTING);
            sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    // ===== 事件处理（官方 handleEvent 移植）=====

    private void handleEvent(Intent intent) {
        String action = intent.getAction();
        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
            handleWifiStateChanged(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN));
        } else if (RadioWrapper.ACTION_DIRECT_STATE_CHANGED.equals(action)) {
            int prev = intent.getIntExtra(RadioWrapper.EXTRA_PREV_STATE, RadioWrapper.DIRECT_STATE_UNKNOWN);
            int cur = intent.getIntExtra(RadioWrapper.EXTRA_STATE, RadioWrapper.DIRECT_STATE_UNKNOWN);
            handleDirectStateChanged(prev, cur);
        } else if (RadioWrapper.ACTION_GROUP_CREATE_SUCCESS.equals(action)) {
            handleGroupCreateSuccess((RadioWrapper.GroupConfig)
                    intent.getParcelableExtra(RadioWrapper.EXTRA_CONFIG));
        } else if (RadioWrapper.ACTION_STA_DISCONNECTED.equals(action)) {
            onClientDisconnected();
        } else if (RadioWrapper.ACTION_GROUP_CREATE_FAILURE.equals(action)) {
            handleGroupCreateFailure(intent.getIntExtra(RadioWrapper.EXTRA_STATE, -1));
        } else if (RadioWrapper.ACTION_STA_CONNECTED.equals(action)) {
        }
    }

    private void handleWifiStateChanged(int state) {
        curWifiMgrState = state;
        switch (state) {
            case WifiManager.WIFI_STATE_DISABLED:
                if (isDisableActionFiltered) {
                } else if (isInitialDisabling) {
                    isInitialDisabling = false;
                    isEnableActionFiltered = false;
                    handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                } else if (isDisablingForFinish) {
                } else if (isRetrying) {
                    delayFatalCheck();
                    } else {
                        isRetrying = true;
                        handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                    }
                    return;
            case WifiManager.WIFI_STATE_ENABLING:
                isDisableActionFiltered = false;
                return;
            case WifiManager.WIFI_STATE_ENABLED:
                if (isEnableActionFiltered) {
                    return;
                }
                isRetrying = false;
                issueDirectEnable();
                return;
            case WifiManager.WIFI_STATE_UNKNOWN:
                if (isDisableActionFiltered) {
                    return;
                }
                if (isRetrying) {
                    delayFatalCheck();
                    return;
                }
                isRetrying = true;
                handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                return;
            default:
                return;
        }
    }

    private void handleDirectStateChanged(int previousState, int currentState) {
        curWifiDirectMgrState = currentState;
        switch (currentState) {
            case RadioWrapper.DIRECT_STATE_DISABLED:
                if (previousState == RadioWrapper.DIRECT_STATE_ENABLED) return;
                if (isDisableActionFiltered) {
                } else if (isDisablingForFinish) {
                } else if (isInitialDisabling) {
                    // 初次复位阶段的 Direct 关闭属正常
                } else {
                    if (isRetrying) {
                    } else {
                        delayFatalCheck();
                    }
                }
                return;
            case RadioWrapper.DIRECT_STATE_ENABLED:
                isRetrying = false;
                isDirectEnableRetrying = false;   // 应答已到，撤销重试计划
                handler.removeCallbacks(delayedDirectEnableRetry);
                if (isEnableActionFiltered) {
                    return;
                }
                startGroupOwner();
                return;
            case RadioWrapper.DIRECT_STATE_UNKNOWN:
                if (isDisableActionFiltered) {
                    return;
                }
                delayFatalCheck();
                return;
            default:
                return;
        }
    }

    private void handleGroupCreateSuccess(RadioWrapper.GroupConfig cfg) {
        isRetrying = false;
        if (isGroupCreateActionFiltered) {
            return;
        }
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        if (cfg != null) {
            ssid = cfg.ssid;
            password = cfg.preSharedKey;
        }
        startFtpServer();
        if (ftpServer != null) {
            disableAutoPowerOff();
        } else {
            goFatal("FTP 启动失败");
            return;
        }
        phase = PH_RUNNING;
        phaseEnterTime = now();
        updateStatus();
    }

    private void handleGroupCreateFailure(int err) {
        if (isGroupCreateActionFiltered) {
        } else if (isRetrying) {
            goFatal("热点创建失败(" + err + ")");
        } else {
            isRetrying = true;
            startGroupOwner();
        }
    }

    private void onClientDisconnected() {
        // 官方此处会重启 Web 服务；FTP 会话由 Session 线程自行随断连结束，无需动作
    }

    private void startGroupOwner() {
        isGroupCreateActionFiltered = false;
        String deviceName = Build.MODEL != null ? Build.MODEL : "SonyFTP";
        radio.configureIdentity(deviceName, deviceName, deviceName);
        RadioWrapper.GroupConfig live = radio.getLiveGroup();
        int netId = live != null ? live.networkId : RadioWrapper.NET_ID_PERSISTENT_GO;
        if (!radio.startGo(netId)) {
            handleGroupCreateFailure(-3);
        }
    }

    private void delayFatalCheck() {
        handler.postDelayed(delayedFatalCheck, DELAY_FATAL_CHECK_MS);
    }

    /**
     * 发出 Direct 使能命令。命令级失败（超时/拒绝）不再误入组创建路径：
     * 第一次走 5 秒后重发，仍失败才报致命；期间若 DIRECT_STATE_ENABLED
     * 广播到达（慢应答），重试会被自动撤销。
     */
    private void issueDirectEnable() {
        boolean acked = false;
        try { acked = radio.setDirectEnabled(true); } catch (Throwable t) {}
        if (acked) return;
        if (!isDirectEnableRetrying) {
            isDirectEnableRetrying = true;
            handler.postDelayed(delayedDirectEnableRetry, DELAY_WIFI_ENABLE_MS);
        } else {
            String detail = radio.getLastError();
            goFatal("无法启用Direct模式" + (detail != null && detail.length() > 0 ? "\n" + detail : ""));
        }
    }

    private void goFatal(String reason) {
        errorMsg = reason;
        phase = PH_FAILED;
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        // 官方遇致命错误仅切界面；我们按既定纪律补一次安静回滚，避免污染系统
        if (radio != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { radio.setDirectEnabled(false); } catch (Throwable t) {}
                    try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}
                }
            }, "FtpRollback").start();
        }
        updateStatus();
    }

    // ===== 有序退出（官方 invokeFinishProcess 移植 + 我们的确认环节）=====

    private void beginOrderlyExit() {
        if (shuttingDown) return;
        shuttingDown = true;
        isDisablingForFinish = true;
        confirmingExit = false;
        phase = PH_CLOSING;
        phaseEnterTime = now();
        handler.removeCallbacks(delayedWifiEnabler);
        handler.removeCallbacks(delayedFatalCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        handler.removeCallbacks(stalledStartupCheck);
        try { unregisterReceiver(receiver); } catch (Throwable t) {}
        updateStatus();   // 立即切换到「状态：正在关闭」
        new Thread(new Runnable() {
            @Override public void run() { runShutdownSequence(); }
        }, "FtpShutdown").start();
    }

    private void runShutdownSequence() {
        // ① 停 FTP、强拆全部会话 → 立即释放 SD 卡上的文件/目录句柄
        stopFtpServer();

        // ② 同步关 Direct（内部等服务应答）
        try { radio.setDirectEnabled(false); } catch (Throwable t) {}
        // ③ 关 WiFi 总开关
        try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}

        // ④ 确认二者都回到关闭态（超时尽力）
        long deadline = SystemClock.elapsedRealtime() + EXIT_CONFIRM_CAP_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (radiosConfirmedDown()) break;
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }

        // ⑤ 全部恢复后才允许收尾：恢复自动关机 → 通知相机退出
        restoreAutoPowerOff();
        handler.post(new Runnable() {
            @Override public void run() {
                phase = PH_CLOSED;
                updateStatus();
                daFinishOnly();
            }
        });
    }

    private boolean radiosConfirmedDown() {
        boolean wifiDown;
        try {
            wifiDown = wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED;
        } catch (Throwable t) {
            wifiDown = false;
        }
        return wifiDown && !radio.isDirectEnabled();
    }

    private void daFinishOnly() {
        // 官方退出只有这一句：整个反编译树里找不到任何 finishComp 调用
        try {
            DAConnectionManager mgr = new DAConnectionManager(getApplicationContext());
            mgr.finish();
        } catch (Throwable t) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        shuttingDown = true;
        handler.removeCallbacks(delayedWifiEnabler);
        handler.removeCallbacks(delayedFatalCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        handler.removeCallbacks(stalledStartupCheck);
        stopFtpServer();
        restoreAutoPowerOff();
        try { radio.setDirectEnabled(false); } catch (Throwable t) {}
        try { wifiManager.setWifiEnabled(false); } catch (Throwable t) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_ENTER:
                if (phase == PH_CLOSING || phase == PH_CLOSED) return true;
                if (confirmingExit) {
                    confirmingExit = false;
                    beginOrderlyExit();
                } else if (phase == PH_FAILED) {
                    initAndStartWifiCycle(true);
                }
                return true;
            case ScalarInput.ISV_KEY_MENU:
                if (phase == PH_CLOSING || phase == PH_CLOSED) return true;
                confirmingExit = !confirmingExit;
                updateStatus();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== FTP =====

    private void startFtpServer() {
        if (ftpServer != null) return;
        File root = getRootDir();
        try {
            FtpServer s = new FtpServer(root, FTP_PORT, null);
            s.start();
            ftpServer = s;
            ftpBoundPort = s.getPort();
        } catch (IOException e) {
            ftpServer = null;
        }
    }

    private void stopFtpServer() {
        if (ftpServer != null) {
            try { ftpServer.stop(); } catch (Throwable t) {}
            ftpServer = null;
        }
    }

    /**
     * FTP 根目录按系统版本分流（用户要求）：
     * SDK≤10 老机型挂载点为 /android/mnt/sdcard/；
     * SDK≥16 为 /android/storage/sdcard0/（沿用至今）。
     */
    private File getRootDir() {
        String preferred = Build.VERSION.SDK_INT <= 10
                ? "/android/mnt/sdcard"
                : "/android/storage/sdcard0";
        File f = new File(preferred);
        if (f.exists() && f.isDirectory()) return f;

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists() && ext.isDirectory()) return ext;

        return f;
    }

    // ===== 保持开机 =====

    private boolean autoPowerOffDisabled = false;

    private void disableAutoPowerOff() {
        if (autoPowerOffDisabled) return;
        sendBroadcast(buildApoIntent("APO/NO"));
        autoPowerOffDisabled = true;
    }

    private void restoreAutoPowerOff() {
        if (!autoPowerOffDisabled) return;
        sendBroadcast(buildApoIntent("APO/NORMAL"));
        autoPowerOffDisabled = false;
    }

    private Intent buildApoIntent(String mode) {
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        return intent;
    }

    // ===== 界面 =====

    private long now() { return SystemClock.elapsedRealtime(); }

    private void updateStatus() {
        if (confirmingExit) {
            statusView.setText("退出应用程序吗？\n\n[确定键] 退出\n[MENU键] 取消\n");
            setQrVisible(false);
            return;
        }

        StringBuilder sb = new StringBuilder();
        String statusLine;
        int statusColor;
        switch (phase) {
            case PH_RUNNING:
                statusLine = "状态：运行中";
                statusColor = 0xFF4CAF50;
                sb.append(statusLine).append("\n\n");
                sb.append("SSID:  ").append(ssid != null ? ssid : "—").append("\n");
                sb.append("密码:  ").append(password != null ? password : "—").append("\n\n");
                sb.append("地址:  ftp://").append(MY_IP_ADDRESS).append(":").append(ftpBoundPort).append("/\n");
                sb.append("FTP用户名和密码均留空\n");
                break;
            case PH_FAILED:
                statusLine = "状态：启动失败";
                statusColor = 0xFFF44336;
                sb.append(statusLine).append("\n").append(errorMsg != null ? errorMsg : "").append("\n[确定键] 重试\n");
                break;
            case PH_CLOSING:
                statusLine = "状态：正在关闭";
                statusColor = 0xFFFFFFFF;
                sb.append(statusLine).append("\n");
                break;
            case PH_CLOSED:
                statusLine = "状态：服务已关闭";
                statusColor = 0xFF9E9E9E;
                sb.append(statusLine).append("\n");
                break;
            default:
                statusLine = "状态：正在启动...";
                statusColor = 0xFFFFFFFF;
                sb.append(statusLine).append("\n");
                break;
        }
        sb.append("\n").append(getString(R.string.hint_keys));

        SpannableString sp = new SpannableString(sb.toString());
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, statusLine.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(statusColor), 0, statusLine.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusView.setText(sp);
        updateQrCode();
    }

    private void updateQrCode() {
        if (phase == PH_RUNNING && ssid != null && password != null) {
            String wifi = "WIFI:T:WPA;S:" + ssid + ";P:" + password + ";;";
            qrView.setImageBitmap(renderQr(QrCode.encode(wifi), 6, 8, 4));
            setQrVisible(true);
        } else {
            qrView.setImageBitmap(null);
            setQrVisible(false);
        }
    }

    private void setQrVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        qrView.setVisibility(v);
        qrCaption.setVisibility(v);
    }

    private Bitmap renderQr(boolean[][] modules, int scaleX, int scaleY, int quiet) {
        int n = modules.length;
        int width = (n + 2 * quiet) * scaleX;
        int height = (n + 2 * quiet) * scaleY;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!modules[r][c]) continue;
                for (int dy = 0; dy < scaleY; dy++) {
                    int y = (quiet + r) * scaleY + dy;
                    for (int dx = 0; dx < scaleX; dx++) {
                        int x = (quiet + c) * scaleX + dx;
                        pixels[y * width + x] = 0xFF000000;
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
