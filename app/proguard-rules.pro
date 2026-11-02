-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }
-dontwarn org.apache.james.mime4j.**
-keep class org.apache.james.mime4j.** { *; }

# ========== Xposed：保持入口类和 reflection 相关代码不被混淆 ==========
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# 唯一的 Xposed 模块入口；类名必须与 xposed_init 一致
-keep class com.lazybody.xposed.XposedMain { *; }
-keep class com.lazybody.xposed.XposedMain$* { *; }

# SharedPreferences 桥 —— 键名和 JSON 字段不能被 R8 改名
-keep class com.lazybody.bridge.** { *; }
-keepclassmembers class com.lazybody.bridge.StateBridge { *; }
-keepclassmembers class com.lazybody.bridge.SimulationState { *; }

# Location 反射字段 —— Xposed 钩子通过名字访问
-keepclassmembers class android.location.Location {
    private ** mMock;
    private ** mIsFromMockProvider;
}

# WiFi / Telephony 反射字段 —— ScanResult / WifiInfo / CellInfo
-keepclassmembers class android.net.wifi.ScanResult {
    public *;
    private ** BSSID;
    private ** SSID;
    private ** capabilities;
    private ** level;
    private ** frequency;
    private ** timestamp;
}
-keepclassmembers class android.net.wifi.WifiInfo {
    public *;
}
-keepclassmembers class android.net.wifi.ScanResult$Builder {
    public *;
}
-keepclassmembers class android.net.wifi.WifiInfo$Builder {
    public *;
}
-keepclassmembers class android.telephony.CellInfo {
    public *;
}
-keepclassmembers class android.telephony.CellInfoLte {
    public *;
    private ** mRegistered;
    private ** mCi;
    private ** mMcc;
    private ** mMnc;
    private ** mTac;
    private ** mPci;
    private ** mDbm;
    private ** mRssi;
    private ** mRsrp;
    private ** mRsrq;
}
-keepclassmembers class android.telephony.CellInfoWcdma {
    public *;
    private ** mRegistered;
    private ** mMcc;
    private ** mMnc;
    private ** mCid;
    private ** mLac;
    private ** mPsc;
}
-keepclassmembers class android.telephony.CellInfoGsm {
    public *;
    private ** mRegistered;
}
-keepclassmembers class android.telephony.CellInfoNr {
    public *;
}
-keepclassmembers class android.telephony.gsm.GsmCellLocation {
    private ** mLac;
    private ** mCid;
    private ** mPsc;
}

# 路线引擎（Kotlin data class + companion object）
-keep class com.lazybody.route.** { *; }

# CellInfoNr / Tdscdma 等都在上面统一 keep

# GnssStatus.Builder / addSatellite 不能被混淆
-keep class android.location.GnssStatus$Builder { *; }
-keepclassmembers class android.location.GnssStatus$Builder {
    public ** addSatellite(...);
    public android.location.GnssStatus build();
}

# WiFi / Cell / Env config
-keep class com.lazybody.bridge.** { *; }

# Service / Binder：Service 类名由 Manifest 引用，Binder 由 UI 通过类型访问
-keep class com.lazybody.service.ServiceGoKt { *; }
-keep class com.lazybody.service.ServiceGoKt$ServiceGoBinder { *; }

# Any Xposed hook helper classes that might be accessed via Class.forName
-keepclassmembers class * {
    @de.robv.android.xposed.annotations.* <methods>;
}
