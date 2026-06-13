package com.lazybody.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.lazybody.utils.MockFlagClearer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LocationHook {

    companion object {
        private val WHITELIST_PACKAGES = listOf(
            "com.lazybody",
            "android",
            "com.android.systemui",
            "com.android.phone"
        )

        fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
            if (lpparam.packageName in WHITELIST_PACKAGES) return

            hookLocationMethods()
            hookLocationManagerMethods()
        }

        private fun hookLocationMethods() {
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )

            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(
                        Location::class.java,
                        "isMock",
                        XC_MethodReplacement.returnConstant(false)
                    )
                } catch (_: Throwable) {}
            }

            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "getExtras",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val extras = param.result as? android.os.Bundle
                        extras?.remove("mockLocation")
                        extras?.remove("isFromMockProvider")
                        extras?.remove("mockProvider")
                    }
                }
            )
        }

        private fun hookLocationManagerMethods() {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val location = param.result as? Location
                        location?.let { MockFlagClearer.clearMockFlag(it) }
                    }
                }
            )
        }
    }
}
