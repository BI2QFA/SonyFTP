package com.sony.scalar.sysutil;

/**
 * Sony 私有 API 编译桩（compileOnly，不进 APK）。
 * 按键扫描码常量与相机运行时一致（已从 OpenMemories-Framework 核对）。
 */
public class ScalarInput {

    // 方向键 / 常用键（本项目用到）
    public static final int ISV_KEY_UP = 103;
    public static final int ISV_KEY_DOWN = 108;
    public static final int ISV_KEY_LEFT = 105;
    public static final int ISV_KEY_RIGHT = 106;
    public static final int ISV_KEY_ENTER = 232;
    public static final int ISV_KEY_DELETE = 595;
    public static final int ISV_KEY_MENU = 514;
    public static final int ISV_KEY_SK1 = 229;
    public static final int ISV_KEY_SK2 = 513;
    public static final int ISV_KEY_PLAY = 207;

    // 方法（桩内返回 null，运行时由相机系统提供真实实现）
    public static KeyStatus getKeyStatus(int key) { return null; }
}
