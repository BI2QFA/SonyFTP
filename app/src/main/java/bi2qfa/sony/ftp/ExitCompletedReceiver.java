package bi2qfa.sony.ftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import bi2qfa.sony.ftp.radio.RadioWrapper;
import bi2qfa.sony.ftp.radio.RadioWrapperFactory;

/**
 * 相机退出应用时发送 com.android.server.DAConnectionManagerService.ExitCompleted 广播，
 * 此时 onDestroy 不一定被调用，这里作为有序退出的兜底。
 *
 * 清理顺序与 MainActivity.runShutdownSequence() 完全一致：
 * 停 FTP 释放 SD 句柄 → 同步关 Direct → 关 WiFi → 确认关闭 → 恢复 APO
 * → 最后才杀掉自身进程。
 */
public class ExitCompletedReceiver extends BroadcastReceiver {

    private static final long FIRST_WAIT_MS = 8000;
    private static final long RETRY_WAIT_MS = 4000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        try {
            // 步骤 0：停 FTP（Activity 正常退出时这里是幂等空操作）
            FtpServer.killActiveInstance();
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            RadioWrapper radio = RadioWrapperFactory.getInstance(ctx);

            // 步骤 1/2：同步关 Direct + WiFi，并轮询确认；没关净再补一轮
            issueRadioOff(radio, wm);
            if (!waitForRadiosDown(wm, radio, FIRST_WAIT_MS)) {
                issueRadioOff(radio, wm);
                waitForRadiosDown(wm, radio, RETRY_WAIT_MS);
            }

            // 步骤 3：无线栈恢复后，才恢复自动关机
            Intent apo = new Intent("com.android.server.DAConnectionManagerService.apo");
            apo.putExtra("apo_info", "APO/NORMAL");
            ctx.sendBroadcast(apo);
        } catch (Throwable t) {
            try {
                Intent apo = new Intent("com.android.server.DAConnectionManagerService.apo");
                apo.putExtra("apo_info", "APO/NORMAL");
                ctx.sendBroadcast(apo);
            } catch (Throwable ignored) {}
        } finally {
            // 最后一步：一切已尽力恢复，进程方可退出
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private static void issueRadioOff(RadioWrapper radio, WifiManager wm) {
        try { radio.setDirectEnabled(false); } catch (Throwable ignored) {}
        try { wm.setWifiEnabled(false); } catch (Throwable ignored) {}
    }

    /** 同步轮询直到 WiFi 明确 DISABLED 且 Direct 未启用，或超时。 */
    private static boolean waitForRadiosDown(WifiManager wm, RadioWrapper radio, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean wifiDown;
            try { wifiDown = wm.getWifiState() == WifiManager.WIFI_STATE_DISABLED; } catch (Throwable t) { wifiDown = false; }
            boolean directDown;
            try { directDown = !radio.isDirectEnabled(); } catch (Throwable t) { directDown = false; }
            if (wifiDown && directDown) return true;
            try { Thread.sleep(300); } catch (InterruptedException e) { return false; }
        }
        return false;
    }
}
