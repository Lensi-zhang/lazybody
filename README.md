<div align="center">

# LazyBody

**Android 虚拟定位工具**

[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v2.1-brightgreen.svg)](https://github.com/Lensi-zhang/lazybody/releases)

一款基于 Android 系统模拟定位服务的虚拟定位应用，无需 ROOT 权限即可使用。

</div>

---

## 功能特性

### 核心功能
- **虚拟定位** - 在地图上自由选择虚拟位置
- **路线模拟** - 在地图上编辑路线，支持单次/循环模式及步行、跑步、驾车速度
- **摇杆控制** - 通过悬浮摇杆实时控制移动方向和速度
- **位置搜索** - 支持通过地名搜索定位
- **坐标输入** - 支持手动输入经纬度精确定位
- **IP 定位** - 支持通过 IP 地址获取位置

### 数据管理
- **历史记录** - 保存常用位置、搜索历史和已录制轨迹
- **轨迹录制** - 自动采样模拟位置，保存轨迹点、距离和时长
- **轨迹回放** - 显示完整轨迹，支持暂停、继续、重新回放和关闭
- **位置分享** - 支持一键分享当前位置

### 地图功能
- **高德地图** - 使用高德地图瓦片，显示更精准
- **方向传感器** - 支持真实手机方向感应
- **日夜模式** - 支持明暗主题切换

### 高级功能
- **WiFi / Cell 模拟** - 支持配置 WiFi 与蜂窝网络信息
- **GNSS 星座生成** - 生成模拟卫星状态
- **传感器模拟** - 支持方向、计步和三轴加速度波形
- **Xposed 模块** - 提供跨进程状态桥、模拟状态隐藏及腾讯定位 SDK Hook

## 系统要求

| 要求 | 说明 |
|------|------|
| Android 版本 | 7.0 (API 24) 及以上 |
| 开发者选项 | 开启“允许模拟位置” |
| 悬浮窗权限 | 用于显示摇杆控件 |
| ROOT 权限 | 核心定位功能不需要；Xposed 高级功能需要可用的 Hook 环境 |

## 安装说明

1. 从 [Releases](https://github.com/Lensi-zhang/lazybody/releases) 下载对应版本的 `LazyBody_2.1_release.apk`，或按下方说明自行构建
2. 在 Android 设备上安装 APK（项目未打包原生 ABI 专用库）
3. 授予必要的权限

## 使用方法

### 基础使用

1. 在系统设置中开启 **开发者选项**
2. 在开发者选项中打开 **允许模拟位置**，并选择 LazyBody 为模拟位置应用
3. 安装并打开 LazyBody
4. 在地图上点击选择目标位置，或使用搜索功能查找地点
5. 点击右下角的 **飞行** 按钮启动虚拟定位

### 路线模拟

1. 点击右下角 **路线** 按钮进入编辑模式
2. 在地图上依次添加路线点
3. 选择单次/循环模式以及步行、跑步或驾车速度
4. 开始模拟后查看实时进度，可随时停止

### 轨迹录制与回放

1. 模拟定位期间自动录制轨迹，停止时保存
2. 在侧边栏历史记录中选择包含点数、距离和时长信息的轨迹
3. 地图显示完整轨迹并自动开始回放
4. 使用控制按钮暂停、继续、重新回放或关闭回放

### 其他功能

- **位置搜索**：点击顶部搜索图标，输入地名进行搜索
- **坐标输入**：点击坐标输入按钮，手动输入经纬度或通过 IP 定位
- **历史记录**：点击侧边栏历史记录，切换位置或打开已保存的轨迹
- **位置保存**：点击地图标记后，可保存当前位置到历史记录
- **位置分享**：可一键复制或分享当前位置坐标
- **切换主题**：在侧边栏底部切换日夜主题

## 技术架构

```
app/src/main/java/com/lazybody/
├── MainActivity.java              # 主界面、路线编辑与轨迹回放
├── HistoryActivity.java           # 位置历史与轨迹列表
├── SettingsActivity.java          # 设置页面
├── joystick/                      # 悬浮摇杆控制
│   ├── JoyStick.java              # 摇杆主控件
│   ├── RockerView.java            # 摇杆视图
│   └── ButtonView.java            # 按钮视图
├── service/
│   └── ServiceGoKt.kt             # 模拟定位、路线执行与轨迹录制服务
├── route/
│   └── RouteEngine.kt             # 路线插值、速度与进度
├── track/
│   └── TrackStore.kt              # 轨迹采样与 JSON 持久化
├── bridge/                        # 跨进程模拟状态与环境配置
├── gnss/                          # GNSS 星座生成
├── sensor/                        # 传感器与运动波形模拟
├── tencent/                       # 腾讯定位 SDK Hook
├── data/                          # Room 数据库与 Repository
├── utils/                         # 地图与定位工具
└── xposed/
    └── XposedMain.kt              # Xposed 模块统一入口
```

## 核心技术

| 技术 | 用途 |
|------|------|
| osmdroid | 开源地图引擎 |
| 高德地图瓦片 | 国内地图数据源 |
| Xposed Framework | 系统级 Hook 隐藏 |
| LocationManager | Android 模拟定位 API |
| GCJ-02 坐标转换 | 适配国内地图坐标系 |
| Java / Kotlin | 主界面、前台服务、路线引擎与 Hook |
| SharedPreferences / JSON | 配置、跨进程状态和轨迹持久化 |
| Android Handler | 定位、路线与轨迹任务调度 |

## 权限说明

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` | 获取精确定位 |
| `ACCESS_COARSE_LOCATION` | 获取粗略定位 |
| `ACCESS_BACKGROUND_LOCATION` | 后台定位兼容声明 |
| `ACCESS_MOCK_LOCATION` | 模拟定位兼容声明，实际仍需在开发者选项中选择本应用 |
| `SYSTEM_ALERT_WINDOW` | 悬浮摇杆 |
| `FOREGROUND_SERVICE` | 前台定位服务 |
| `FOREGROUND_SERVICE_LOCATION` | Android 10+ 定位前台服务类型 |
| `POST_NOTIFICATIONS` | Android 13+ 服务通知 |
| `ACCESS_WIFI_STATE` | 读取 WiFi 状态 |
| `ACCESS_NETWORK_STATE` | 读取网络状态 |
| `INTERNET` | 地图瓦片、搜索和 IP 定位 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 旧系统文件读写兼容 |
| `VIBRATE` | 操作反馈 |
| `WAKE_LOCK` | 模拟任务运行期间保持唤醒 |

## 构建说明

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 17（源码与字节码目标仍为 Java 11）
- Android SDK 34

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/Lensi-zhang/lazybody.git

# 进入项目目录
cd lazybody

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease
```

## 更新日志

### v2.1 (2026-11-02)

- 修复地图标记信息窗，接通位置保存、复制和分享
- 修复循环路线返回起点及跨周期位置跳变
- 统一 App 与 Xposed 的跨进程偏好，补齐 WiFi / Cell 配置界面
- 修复计步小数累计、方向四元数和物理传感器原始方法调用
- GNSS 支持 Executor 注册签名及 Android 7—11 状态构造
- 腾讯定位 Hook 支持 `TencentLocationListener` 回调位置注入
- 修复 Android 7 通知渠道与悬浮窗类型兼容性

### v2.0 (2026-11-02)

- 新增地图路线编辑、单次/循环模拟及速度档位
- 新增真实轨迹录制、持久化、历史展示与地图回放
- 轨迹回放支持暂停、继续、重新回放和关闭
- 新增可配置 WiFi / Cell、GNSS 星座、传感器与运动波形模拟
- 整合 Xposed 状态桥和腾讯定位 SDK Hook
- 移除活动边界功能，优化路线和回放控制界面
- 修复启动模拟定位时服务初始化顺序导致的闪退

### v1.0 (2026-06-13)

#### 架构重构
- 迁移至 MVVM 架构模式
- 引入 ViewModel + LiveData 管理 UI 状态
- 使用 Repository 模式抽象数据层
- ServiceGo 迁移至 Kotlin

#### 新增功能
- **活动边界功能**
  - 可视化边界圆圈显示
  - 滑动条调节边界半径（10-1000米）
  - 到达边界自动随机方向返回
  - 摇杆方向自动同步
  - 滑出/收起动画效果

#### 优化改进
- 代码结构优化，模块职责清晰
- 统一通知样式
- 改进边界返回算法，平滑移动

## 免责声明

本项目仅供学习和研究使用，请勿用于任何违反法律法规的用途。使用本软件所产生的一切后果由用户自行承担。

## 致谢

本项目基于 [AnyWhere](https://github.com/cxOrz/AnyWhere) 二次开发，感谢原作者 [cxOrz](https://github.com/cxOrz) 的开源贡献。

## 开源许可

本项目基于 [GPL-3.0](LICENSE) 协议开源。

---

<div align="center">

**如果觉得有用，请给个 Star 支持一下！**

</div>
