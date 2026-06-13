package com.lazybody.xposed

import android.os.Build
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class GnssStatusHook {

    companion object {
        fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            hookGnssStatusGetters()
        }

        private fun hookGnssStatusGetters() {
            try {
                val gnssStatusClass = Class.forName("android.location.GnssStatus")

                XposedHelpers.findAndHookMethod(
                    gnssStatusClass,
                    "getSatelliteCount",
                    XC_MethodReplacement.returnConstant(7)
                )
            } catch (_: Throwable) {}
        }
    }
}
