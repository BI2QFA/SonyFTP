package bi2qfa.sony.ftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;

import com.sony.wifi.direct.DirectManager;

import java.lang.reflect.Method;

/**
 * 相机退出应用时发送 com.android.server.DAConnectionManagerService.ExitCompleted 广播，
 * 此时 onDestroy 不一定被调用，需在这里关闭 WiFi / Direct 模式。
 */
public class ExitCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context ctx = context.getApplicationContext();
        try {
            // 1. 关闭新机型 Direct 模式（WifiP2pExtManager.setDirectEnabled(false)）
            WifiP2pManager p2p = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
            if (p2p != null) {
                WifiP2pManager.Channel ch = p2p.initialize(ctx, Looper.getMainLooper(), null);
                try {
                    Method m = p2p.getClass().getMethod("setDirectEnabled",
                            WifiP2pManager.Channel.class, boolean.class, WifiP2pManager.ActionListener.class);
                    m.invoke(p2p, ch, false, new WifiP2pManager.ActionListener() {
                        public void onSuccess() {}
                        public void onFailure(int reason) {}
                    });
                } catch (Throwable t) {}
            }
            // 2. 关闭旧机型 Direct 模式（DirectManager）
            try {
                DirectManager dm = (DirectManager) ctx.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
                if (dm != null) dm.setDirectEnabled(false);
            } catch (Throwable t) {}
            // 3. 彻底关闭 WiFi
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wm.setWifiEnabled(false);
            // 4. 恢复自动关机
            Intent apo = new Intent("com.android.server.DAConnectionManagerService.apo");
            apo.putExtra("apo_info", "APO/NORMAL");
            ctx.sendBroadcast(apo);
        } catch (Throwable t) {}
        // 5. 杀进程，彻底释放资源（FTP 端口等）
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
