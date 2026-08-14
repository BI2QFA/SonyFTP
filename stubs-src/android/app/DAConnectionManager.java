package android.app;

import android.content.Context;

/**
 * 索尼相机私有 API 编译桩（compileOnly，不进 APK）。
 * 用于通知相机退出应用到取景界面。运行时由相机系统提供真实实现。
 */
public class DAConnectionManager {

    public DAConnectionManager(Context context) {}

    // 通知相机退出应用、返回取景界面
    public void finish() {}

    // 结束某个组件（Activity）
    public void finishComp() {}

    public boolean registerCustomKeyInfo(String a, String b, String c) { return false; }
}
