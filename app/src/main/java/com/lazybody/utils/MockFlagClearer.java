package com.lazybody.utils;

import android.location.Location;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Location 对象 Mock 标记清除工具
 * 
 * 原理：Android 的 Location 对象在通过 IPC 传递给其他进程时，
 * 会将 mIsFromMockProvider / mMock 字段一并序列化。
 * LSPosed Hook 只能在同进程生效，跨进程检测必须在源头清除。
 * 
 * 使用方法：在调用 LocationManager.setTestProviderLocation() 之前，
 * 调用 clearMockFlag(location) 清除模拟标记。
 */
public class MockFlagClearer {

    private static final String TAG = "MockFlagClearer";

    private static Field sMockField;
    private static volatile boolean sFieldResolved = false;

    /**
     * 清除 Location 对象中的所有 Mock 相关标记
     * 包括：mIsFromMockProvider / mMock / extras 中的 mockLocation
     */
    public static Location clearMockFlag(Location location) {
        if (location == null) {
            return null;
        }

        // 1. 清除字段标记 (通过反射直接修改字段，覆盖所有检测路径)
        resolveAndClearField(location);

        // 2. 清除 Bundle 中的 mockLocation key
        try {
            android.os.Bundle extras = location.getExtras();
            if (extras != null) {
                extras.remove("mockLocation");
                extras.remove("isFromMockProvider");
                extras.remove("mockProvider");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to remove mock extras", t);
        }

        return location;
    }

    private static void resolveAndClearField(Location location) {
        if (!sFieldResolved) {
            synchronized (MockFlagClearer.class) {
                if (!sFieldResolved) {
                    // API 31+ 使用 "mMock"
                    try {
                        sMockField = Location.class.getDeclaredField("mMock");
                        sMockField.setAccessible(true);
                        Log.i(TAG, "Resolved mock field: mMock (API 31+)");
                    } catch (NoSuchFieldException e) {
                        // API < 31 使用 "mIsFromMockProvider"
                        try {
                            sMockField = Location.class.getDeclaredField("mIsFromMockProvider");
                            sMockField.setAccessible(true);
                            Log.i(TAG, "Resolved mock field: mIsFromMockProvider (API < 31)");
                        } catch (NoSuchFieldException e2) {
                            Log.w(TAG, "Cannot resolve mock field on " 
                                    + Build.VERSION.SDK_INT);
                        }
                    }
                    sFieldResolved = true;
                }
            }
        }

        if (sMockField != null) {
            try {
                sMockField.set(location, false);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set mock field to false", t);
            }
        }
    }
}
