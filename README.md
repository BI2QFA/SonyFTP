# SonyFTP —— 索尼 SD 卡无线 FTP 服务器

给**索尼相机**的安卓应用：开启相机 WiFi 热点（AP），运行一个**匿名只读 FTP 服务器**，把 SD 卡（`/android/storage/sdcard0/`，含 DCIM 照片视频）暴露出来，用手机/电脑无线浏览、下载。

## 功能

- ✅ 打开应用即自动开启相机热点（WiFi Direct GO 模式，IP `192.168.122.1`）+ 启动 FTP
- ✅ 匿名只读 FTP（可浏览、下载，**不能**上传/删除）
- ✅ 被动模式（PASV/EPSV），主流 FTP 客户端（ES 文件管理器 / FileZilla / Windows 资源管理器）都能连
- ✅ 屏幕显示 SSID / 密码 / 地址，并生成**二维码**，手机扫码即可连上热点
- ✅ 按键驱动 UI（相机 无触摸屏）：MENU 键进入退出确认，确定键退出
- ✅ 运行时阻止相机自动关机

## 目录结构

```
SonyFTP/
├── CLAUDE.md              # AI 助手工作指引
├── README.md              # 本文件
├── docs/                  # 需求 / 技术 / 设计 / 构建安装 文档
├── devlog/                # 开发日志与进度看板
└── app/                   # Android 工程（含 Gradle wrapper）
    ├── build-stubs.sh     # 重新生成 Sony 编译桩脚本
    ├── stubs-src/         # Sony 私有 API 桩源码
    └── app/
        ├── libs/stubs.jar # 预编译的 Sony 桩（compileOnly，不进 APK）
        └── src/main/      # 应用源码（Java + res）
```

## 编译环境

> 本项目用**老工具链**（相机是 Android 4.x 的 Dalvik）。请严格按下面版本，**不要升级**，否则产物无法保证在相机上运行。

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | **8**（Amazon Corretto 8 / Temurin 8） | 必须 JDK8，JDK11+ 跑不了 Gradle 4.4.1 |
| Android SDK | **platform android-15** + **build-tools 26.0.2** | 通过 SDK Manager 勾选 |
| Gradle | 4.4.1 | 工程自带 wrapper（`gradlew`），自动下载，无需手动装 |
| Android Gradle 插件 | 3.0.1 | 已写在 `build.gradle` |

## 编译步骤

```bash
# 1. 配置 JDK8
export JAVA_HOME="/你的JDK8路径"

# 2. 配置 SDK 路径（二选一）
#    a) 编辑 app/local.properties，写入：  sdk.dir=C\:\\你的SDK路径
#    b) 或设置环境变量 ANDROID_HOME

# 3. 编译（首次会自动下载 Gradle 4.4.1）
cd app
./gradlew assembleDebug        # Windows 也可用 gradlew.bat assembleDebug

# 4. 产物
# app/app/build/outputs/apk/debug/app-debug.apk
```

> 首次编译 `gradlew` 会从腾讯镜像自动下载 Gradle 4.4.1；非国内网络若下载慢，把 `app/gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 改回官方 `https://services.gradle.org/distributions/gradle-4.4.1-bin.zip` 即可。

## 安装到相机（APK → SPK）

索尼相机装的是 SPK 包（本质是带元数据的 APK）。最简单方式：

1. 打开 Sony 官方 PlayMemories Camera Apps 安装页：https://sony-pmca.appspot.com/apps
2. 上传 `app-debug.apk`，生成 `.spk`
3. 把 `.spk` 放进 SD 卡，通过相机「应用程序」菜单安装

## 使用

1. 打开本应用，自动开启热点并启动 FTP，屏幕显示 SSID / 密码 / 地址 + 二维码。
2. 手机扫码连接热点（或手动输入 SSID/密码），FTP 客户端连 `192.168.122.1:21`（匿名，用户名密码留空）。
3. 浏览 `DCIM/` 下的照片视频，下载即可。
4. 按 **MENU 键** 进入退出确认，按**确定键**退出。

## 注意事项

- **包名**：`bi2qfa.sony.ftp`。
- **Sony 私有 API 是编译桩**：`com.sony.wifi.direct.*`、`com.sony.scalar.sysutil.*`、`android.app.DAConnectionManager` 只在编译期用 `app/app/libs/stubs.jar`（`compileOnly`）提供，**不会打进 APK**，运行时由相机系统提供真实实现。改动桩源码后可跑 `app/build-stubs.sh` 重新生成。
- **只读保护**：FTP 拒绝一切写命令（STOR/DELE/MKD…），防止误删照片。
- **SD 卡路径**：`/android/storage/sdcard0/`（Android 4 挂载点），代码里有 `Environment.getExternalStorageDirectory()` 兜底。
- **端口**：默认 21，绑不上自动回退 2121。
- **16:9 屏幕变形**：相机取景器是 4:3，但 16:9 主屏会把画面横向拉宽 4/3。应用图标和二维码已按 3:4 预压缩，在 16:9 上显示为正常正方形（4:3 上会略窄，属正常取舍）。
- **WiFi 热点两条路径**：优先 Sony `DirectManager`，若为 null 回退标准 `WifiP2pManager.createGroup()`（相机 走这条）。
- 无触摸屏，UI 完全靠相机物理按键操作。
