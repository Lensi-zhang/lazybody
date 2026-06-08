<div align="center">

# LazyBody

**Android 虚拟定位工具**

[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v0.1-brightgreen.svg)](https://githup.com/Lensi-zhang/lazybody/releases)

一款基于 Android 系统模拟定位服务的虚拟定位应用，无需 ROOT 权限即可使用。

</div>

---

## 功能特性

- **虚拟定位** - 在地图上自由选择虚拟位置
- **摇杆控制** - 通过悬浮摇杆实时控制移动方向和速度
- **路线模拟** - 支持沿路线自动移动模拟
- **历史记录** - 保存常用位置，一键切换
- **高德地图** - 使用高德地图瓦片，显示更精准
- **方向传感器** - 支持真实手机方向感应
- **隐藏检测** - 内置 Xposed 模块，隐藏模拟定位状态

## 系统要求

- Android 8.0 (API 26) 及以上
- 开启开发者选项中的"允许模拟位置"

## 使用方法

1. 在系统设置中开启 **开发者选项**
2. 打开 **允许模拟位置**
3. 安装并打开 LazyBody
4. 在地图上长按选择目标位置
5. 点击 **开始** 按钮启动虚拟定位

## 技术架构

```
├── app/
│   ├── src/main/java/com/lazybody/
│   │   ├── MainActivity.java          # 主界面
│   │   ├── HistoryActivity.java       # 历史记录
│   │   ├── SettingsActivity.java      # 设置页面
│   │   ├── joystick/                  # 悬浮摇杆控制
│   │   ├── service/                   # 模拟定位服务
│   │   ├── database/                  # 数据库操作
│   │   ├── utils/                     # 工具类
│   │   └── xposed/                    # Xposed 隐藏模块
│   └── src/main/res/                  # 资源文件
├── test-detector/                     # 检测工具模块
└── build.gradle                       # 构建配置
```

## 核心技术

- **osmdroid** - 开源地图引擎
- **高德地图瓦片** - 国内地图数据源
- **Xposed Framework** - 系统级 Hook 隐藏
- **LocationManager** - Android 模拟定位 API
- **GCJ-02 坐标转换** - 适配国内地图坐标系

## 权限说明

| 权限 | 用途 |
|------|------|
| ACCESS_FINE_LOCATION | 获取精确定位 |
| ACCESS_COARSE_LOCATION | 获取粗略定位 |
| SYSTEM_ALERT_WINDOW | 悬浮窗权限 |
| FOREGROUND_SERVICE | 前台服务 |

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
