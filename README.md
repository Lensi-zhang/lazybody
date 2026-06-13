<div align="center">

# LazyBody

**Android 虚拟定位工具**

[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/badge/Release-v1.0-brightgreen.svg)](https://github.com/Lensi-zhang/lazybody/releases)

一款基于 Android 系统模拟定位服务的虚拟定位应用，无需 ROOT 权限即可使用。

</div>

---

## 功能特性

- **虚拟定位** - 在地图上自由选择虚拟位置
- **摇杆控制** - 通过悬浮摇杆实时控制移动方向和速度
- **位置搜索** - 支持通过地名搜索定位
- **坐标输入** - 支持手动输入经纬度精确定位
- **IP定位** - 支持通过IP地址获取位置
- **历史记录** - 保存常用位置和搜索历史，一键切换
- **位置分享** - 支持一键分享当前位置
- **高德地图** - 使用高德地图瓦片，显示更精准
- **方向传感器** - 支持真实手机方向感应
- **日夜模式** - 支持明暗主题切换
- **隐藏检测** - 内置 Xposed 模块，隐藏模拟定位状态
- **活动边界** - 设定活动范围，到达边界自动返回

## v1.0 更新内容

### 架构重构
- 迁移至 MVVM 架构模式
- 引入 ViewModel + LiveData 管理 UI 状态
- 使用 Repository 模式抽象数据层
- ServiceGo 迁移至 Kotlin

### 新增功能
- **活动边界功能**
  - 可视化边界圆圈显示
  - 滑动条调节边界半径（10-1000米）
  - 到达边界自动随机方向返回
  - 摇杆方向自动同步
  - 滑出/收起动画效果

### 优化改进
- 代码结构优化，模块职责清晰
- 统一通知样式
- 改进边界返回算法，平滑移动

## 系统要求

- Android 8.0 (API 26) 及以上
- 开启开发者选项中的"允许模拟位置"

## 使用方法

### 基础使用
1. 在系统设置中开启 **开发者选项**
2. 在开发者选项中打开 **允许模拟位置**，并选择 LazyBody 为模拟位置应用
3. 安装并打开 LazyBody
4. 在地图上点击选择目标位置，或使用搜索功能查找地点
5. 点击右下角的 **飞行** 按钮启动虚拟定位

### 活动边界
1. 启动模拟定位后，点击 **边界按钮**（圆形图标）
2. 拖动滑动条设置活动半径（10-1000米）
3. 地图上显示蓝色圆圈表示边界范围
4. 使用摇杆移动，到达边界时自动返回
5. 点击地图任意位置收起滑动条
6. 再次点击边界按钮关闭功能

### 其他功能
- **位置搜索**：点击顶部搜索图标，输入地名进行搜索
- **坐标输入**：点击坐标输入按钮，手动输入经纬度或通过IP定位
- **历史记录**：点击侧边栏历史记录，查看和快速切换到之前使用的位置
- **位置保存**：点击地图标记后，可保存当前位置到历史记录
- **位置分享**：可一键复制或分享当前位置坐标
- **切换主题**：在侧边栏底部切换日夜主题

## 技术架构

```
├── app/
│   ├── src/main/java/com/lazybody/
│   │   ├── MainActivity.java          # 主界面
│   │   ├── HistoryActivity.java       # 历史记录
│   │   ├── SettingsActivity.java      # 设置页面
│   │   ├── joystick/                  # 悬浮摇杆控制
│   │   │   ├── JoyStick.java          # 摇杆主控件
│   │   │   ├── RockerView.java        # 摇杆视图
│   │   │   └── ButtonView.java        # 按钮视图
│   │   ├── service/                   # 模拟定位服务
│   │   │   └── ServiceGoKt.kt         # Kotlin 版服务
│   │   ├── database/                  # 数据库操作
│   │   ├── utils/                     # 工具类
│   │   │   ├── BoundarySimulator.kt   # 边界模拟器
│   │   │   ├── MapUtils.java          # 地图工具
│   │   │   └── MockFlagClearer.java   # Mock 标记清除
│   │   └── xposed/                    # Xposed 隐藏模块
│   │       ├── HideMockHook.java      # 隐藏 Mock 检测
│   │       ├── LocationHook.kt        # 定位 Hook
│   │       └── GnssStatusHook.kt      # GNSS 状态 Hook
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
- **Kotlin** - 现代 Android 开发语言
- **MVVM** - 架构模式

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
