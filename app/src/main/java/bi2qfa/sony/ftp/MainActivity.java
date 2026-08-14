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
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.sony.scalar.sysutil.ScalarInput;
import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

public class MainActivity extends Activity {

    public static final String MY_IP_ADDRESS = "192.168.122.1";
    public static final int FTP_PORT = 21;
    public static final int FTP_PORT_FALLBACK = 2121;

    private TextView statusView;
    private ImageView qrView;
    private TextView qrCaption;

    private WifiManager wifiManager;
    private DirectManager wifiDirectManager;   // 旧机型 API（wifi-direct）
    private WifiP2pManager wifiP2pManager;     // 新机型 API（wifip2p，本机走这个）
    private WifiP2pManager.Channel p2pChannel;
    private FtpServer ftpServer;

    private boolean serverRunning = false;
    private boolean wifiEnabled = false;
    private boolean autoPowerOffDisabled = false;
    private boolean directEnableDone = false;
    private boolean wifiReadyDone = false;

    private String ssid = null;
    private String password = null;
    private String errorMsg = null;
    private int groupInfoRetry = 0;
    private boolean autoStarted = false;
    private boolean confirmingExit = false;

    private BroadcastReceiver wifiStateReceiver;
    private BroadcastReceiver wifiDirectStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;
    private BroadcastReceiver groupCreateFailureReceiver;

    private final WifiP2pManager.ActionListener NOOP = new WifiP2pManager.ActionListener() {
        @Override public void onSuccess() {}
        @Override public void onFailure(int reason) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = (TextView) findViewById(R.id.statusView);
        qrView = (ImageView) findViewById(R.id.qrView);
        qrCaption = (TextView) findViewById(R.id.qrCaption);

        Context ctx = getApplicationContext();
        wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        wifiDirectManager = getDirectManager(ctx);
        wifiP2pManager = getWifiP2pManager(ctx);

        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_ENABLED && wifiEnabled && !serverRunning) {
                    wifiReady();
                }
            }
        };
        wifiDirectStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN);
                if (state == DirectManager.DIRECT_STATE_ENABLED) {
                    wifiDirectEnabled();
                }
            }
        };
        groupCreateSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                DirectConfiguration cfg = (DirectConfiguration) intent.getParcelableExtra(DirectManager.EXTRA_DIRECT_CONFIG);
                groupCreated(cfg);
            }
        };
        groupCreateFailureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                setError("热点创建失败 (DirectManager)");
            }
        };

        updateStatus();
    }

    private DirectManager getDirectManager(Context ctx) {
        try {
            return (DirectManager) ctx.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private WifiP2pManager getWifiP2pManager(Context ctx) {
        try {
            return (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(wifiStateReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        registerReceiver(wifiDirectStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
        registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));
        registerReceiver(groupCreateFailureReceiver, new IntentFilter(DirectManager.GROUP_CREATE_FAILURE_ACTION));
        notifyAppInfo();
        // 进入软件直接启动 FTP 服务
        if (!autoStarted && !serverRunning) {
            autoStarted = true;
            startServer();
        }
    }

    // 向相机 DAConnectionManagerService 注册本应用
    private void notifyAppInfo() {
        try {
            Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            intent.putExtra("package_name", getComponentName().getPackageName());
            intent.putExtra("class_name", getComponentName().getClassName());
            sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    // 退出：通知相机 DA 系统结束组件、返回取景界面（不调用 Activity.finish，交给相机接管）
    private void doExit() {
        try {
            DAConnectionManager mgr = new DAConnectionManager(getApplicationContext());
            mgr.finishComp();
            mgr.finish();
        } catch (Throwable t) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(wifiStateReceiver);
        unregisterReceiver(wifiDirectStateReceiver);
        unregisterReceiver(groupCreateSuccessReceiver);
        unregisterReceiver(groupCreateFailureReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFtpServer();
        disableWifi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_ENTER:
                if (confirmingExit) {
                    doExit();
                }
                return true;
            case ScalarInput.ISV_KEY_MENU:
                confirmingExit = !confirmingExit;
                updateStatus();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== 启动 =====

    private void startServer() {
        if (serverRunning) return;
        ssid = null;
        password = null;
        errorMsg = null;
        wifiReadyDone = false;
        groupInfoRetry = 0;
        wifiEnabled = true;
        updateStatus();
        // 10 秒启动超时
        statusView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wifiEnabled && !serverRunning && errorMsg == null) {
                    setError("启动失败，请重启相机后再试");
                }
            }
        }, 10000);
        resetWifiThenStart();
    }

    private void resetWifiThenStart() {
        // 先彻底关闭 Direct 模式和 WiFi，再重新开启（解决「关一次后不能再启动」的问题）
        try {
            if (wifiP2pManager != null && p2pChannel != null) {
                invokeSetDirectEnabled(false, NOOP);
            }
            if (wifiDirectManager != null) {
                wifiDirectManager.setDirectEnabled(false);
            }
            wifiManager.setWifiEnabled(false);
        } catch (Throwable t) {}
        // 等 2 秒让 WiFi 关干净，再重新开启
        statusView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (serverRunning) return;
                wifiReadyDone = false;
                wifiManager.setWifiEnabled(true);
                if (wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
                    wifiReady();
                }
            }
        }, 2000);
    }

    private void wifiReady() {
        if (serverRunning || wifiReadyDone) return;
        wifiReadyDone = true;
        if (wifiP2pManager != null) {
            startWifiP2pGroup();
        } else if (wifiDirectManager != null) {
            wifiDirectManager.setDirectEnabled(true);
        } else {
            setError("相机不支持 WiFi Direct");
        }
    }

    // ===== 新机型：WifiP2pManager =====

    private final WifiP2pManager.ActionListener directEnableListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            directEnableDone = true;
            doCreateGroup();
        }
        @Override
        public void onFailure(int reason) {
            directEnableDone = true;
            doCreateGroup();
        }
    };

    private final WifiP2pManager.ActionListener groupListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            startFtpServer();
            disableAutoPowerOff();
            // 延迟 1 秒等热点组完全建立后再读取名称密码
            statusView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (serverRunning) requestGroupInfoWithRetry();
                }
            }, 1000);
            updateStatus();
        }
        @Override
        public void onFailure(int reason) {
            setError("热点创建失败 (reason=" + reason + ")");
        }
    };

    private void requestGroupInfoWithRetry() {
        try {
            wifiP2pManager.requestGroupInfo(p2pChannel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null) {
                        String name = group.getNetworkName();
                        String pwd = group.getPassphrase();
                        if (name != null) ssid = name;
                        if (pwd != null) password = pwd;
                    }
                    if (ssid == null && groupInfoRetry < 3 && serverRunning) {
                        groupInfoRetry++;
                        statusView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (serverRunning) requestGroupInfoWithRetry();
                            }
                        }, 1000);
                    } else {
                        updateStatus();
                    }
                }
            });
        } catch (Throwable t) {
            updateStatus();
        }
    }

    private void startWifiP2pGroup() {
        try {
            // 每次启动都重新初始化 Channel，避免残留旧 Channel
            p2pChannel = wifiP2pManager.initialize(getApplicationContext(), getMainLooper(), null);
            directEnableDone = false;
            // 先启用索尼的 Direct 模式，再创建热点组（直接 createGroup 会 BUSY）
            if (invokeSetDirectEnabled(true, directEnableListener)) {
                // 兜底：3 秒后回调仍未到（可能 Direct 模式本就处于开启状态），直接尝试 createGroup
                statusView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!directEnableDone && !serverRunning) {
                            directEnableDone = true;
                            doCreateGroup();
                        }
                    }
                }, 3000);
            } else {
                doCreateGroup();
            }
        } catch (Throwable t) {
            setError("开启热点异常");
        }
    }

    private void doCreateGroup() {
        try {
            wifiP2pManager.createGroup(p2pChannel, groupListener);
        } catch (Throwable t) {
            setError("创建热点异常");
        }
    }

    // 反射调用索尼 WifiP2pExtManager.setDirectEnabled(Channel, boolean, ActionListener)
    private boolean invokeSetDirectEnabled(boolean enable, WifiP2pManager.ActionListener listener) {
        try {
            Method m = wifiP2pManager.getClass().getMethod("setDirectEnabled",
                    WifiP2pManager.Channel.class, boolean.class, WifiP2pManager.ActionListener.class);
            m.invoke(wifiP2pManager, p2pChannel, enable, listener);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 旧机型：DirectManager =====

    private void wifiDirectEnabled() {
        List<DirectConfiguration> configs = wifiDirectManager.getConfigurations();
        if (configs == null || configs.isEmpty()) {
            setError("未找到 WiFi Direct 配置");
            return;
        }
        DirectConfiguration last = configs.get(configs.size() - 1);
        wifiDirectManager.startGo(last.getNetworkId());
    }

    private void groupCreated(DirectConfiguration cfg) {
        if (cfg != null) {
            ssid = cfg.getSsid();
            password = cfg.getPreSharedKey();
        }
        startFtpServer();
        disableAutoPowerOff();
        updateStatus();
    }

    // ===== 关闭 =====

    private void stopServer() {
        stopFtpServer();
        disableWifi();
        updateStatus();
    }

    private void stopFtpServer() {
        if (ftpServer != null) {
            ftpServer.stop();
            ftpServer = null;
        }
        serverRunning = false;
    }

    private void disableWifi() {
        if (!wifiEnabled) {
            restoreAutoPowerOff();
            return;
        }
        wifiEnabled = false;
        try {
            if (wifiP2pManager != null && p2pChannel != null) {
                invokeSetDirectEnabled(false, NOOP);
            }
            if (wifiDirectManager != null) {
                wifiDirectManager.setDirectEnabled(false);
            }
            wifiManager.setWifiEnabled(false);
        } catch (Throwable t) {}
        restoreAutoPowerOff();
    }

    // ===== FTP =====

    private void startFtpServer() {
        if (ftpServer != null) return;

        File root = getRootDir();
        if (root == null) {
            setError("找不到 SD 卡目录");
            return;
        }

        ftpServer = new FtpServer(root, FTP_PORT, null);
        try {
            ftpServer.start();
            serverRunning = true;
        } catch (IOException e) {
            ftpServer = null;
            ftpServer = new FtpServer(root, FTP_PORT_FALLBACK, null);
            try {
                ftpServer.start();
                serverRunning = true;
            } catch (IOException e2) {
                ftpServer = null;
                setError("FTP 启动失败");
            }
        }
    }

    private File getRootDir() {
        File sdcard0 = new File("/android/storage/sdcard0");
        if (sdcard0.exists() && sdcard0.isDirectory()) {
            return sdcard0;
        }
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            return ext;
        }
        return sdcard0;
    }

    // ===== 保持开机 =====

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

    private void setError(String msg) {
        errorMsg = msg;
        updateStatus();
    }

    private void updateStatus() {
        if (confirmingExit) {
            statusView.setText("退出应用程序吗？\n\n[确定键] 确定退出\n[MENU键] 取消\n");
            setQrVisible(false);
            return;
        }

        StringBuilder sb = new StringBuilder();
        String statusLine;
        int statusColor;
        if (serverRunning) {
            statusLine = "状态：运行中";
            statusColor = 0xFF4CAF50;
            sb.append(statusLine).append("\n\n");
            sb.append("SSID:  ").append(ssid != null ? ssid : "—").append("\n");
            sb.append("密码:  ").append(password != null ? password : "—").append("\n\n");
            sb.append("地址:  ftp://").append(MY_IP_ADDRESS).append(":")
              .append(ftpServer != null ? ftpServer.getPort() : FTP_PORT).append("/\n");
            sb.append("FTP用户名和密码均留空\n");
        } else if (errorMsg != null) {
            statusLine = "状态：启动失败";
            statusColor = 0xFFF44336;
            sb.append(statusLine).append("\n").append(errorMsg).append("\n");
        } else if (wifiEnabled) {
            statusLine = "状态：正在启动...";
            statusColor = 0xFFFFFFFF;
            sb.append(statusLine).append("\n");
        } else {
            statusLine = "状态：未启动";
            statusColor = 0xFF9E9E9E;
            sb.append(statusLine).append("\n");
        }
        sb.append("\n").append(getString(R.string.hint_keys));

        SpannableString sp = new SpannableString(sb.toString());
        // 状态行：加粗 + 上色（运行绿 / 失败红 / 未启动灰）
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, statusLine.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(statusColor), 0, statusLine.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusView.setText(sp);
        updateQrCode();
    }

    // ===== 二维码 =====

    private void updateQrCode() {
        if (serverRunning && ssid != null && password != null) {
            String wifi = "WIFI:T:WPA;S:" + ssid + ";P:" + password + ";;";
            // scaleX/scaleY=6/8（3:4 竖版），16:9 屏横拉 4/3 后正好恢复正方形
            qrView.setImageBitmap(renderQr(QrCode.encode(wifi), 6, 8, 4));
            setQrVisible(true);
        } else {
            qrView.setImageBitmap(null);
            setQrVisible(false);
        }
    }

    // 二维码与提示小字一起显示/隐藏
    private void setQrVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        qrView.setVisibility(v);
        qrCaption.setVisibility(v);
    }

    // 生成二维码位图（白底黑模块，含静区）。scaleX/scaleY 支持非等比（16:9 屏预压缩）
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
