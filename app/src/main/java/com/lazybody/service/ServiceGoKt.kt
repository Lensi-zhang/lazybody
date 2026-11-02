package com.lazybody.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import com.lazybody.R
import com.lazybody.bridge.EnvironmentConfig
import com.lazybody.bridge.EnvironmentConfigStore
import com.lazybody.bridge.StateBridge
import com.lazybody.bridge.SimulationState
import com.lazybody.joystick.JoyStick
import com.lazybody.route.RouteEngine
import com.lazybody.route.RouteMode
import com.lazybody.route.SpeedTier
import com.lazybody.route.Waypoint
import com.lazybody.track.TrackRecorder
import com.lazybody.utils.MapUtils
import com.lazybody.utils.MockFlagClearer
import java.util.Random

@Suppress("DEPRECATION")
class ServiceGoKt : Service(), SensorEventListener {

    companion object {
        const val DEFAULT_LAT = 36.667662
        const val DEFAULT_LNG = 117.027707
        const val DEFAULT_ALT = 5.0
        const val DEFAULT_BEA = 0.0f

        private const val HANDLER_MSG_ID = 0
        private const val SERVICE_GO_HANDLER_NAME = "ServiceGoLocation"
        private const val SERVICE_GO_NOTE_ID = 1
        private const val SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick"
        private const val SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick"
        private const val SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE"
        private const val SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE"
    }

    private var mCurLat = DEFAULT_LAT
    private var mCurLng = DEFAULT_LNG
    private var mCurAlt = DEFAULT_ALT
    private var mCurBea = DEFAULT_BEA
    private var mSpeed = SpeedTier.WALK

    /** 路线引擎实例；null 表示未配置路线。 */
    private var mRouteEngine: RouteEngine? = null
    /** 路线驱动中 —— 为 true 时由引擎控制位置/方位，摇杆仅作为覆盖。 */
    private var mRouteActive: Boolean = false

    /** 跨进程状态桥：供 Xposed 模块读取当前模拟快照。 */
    private lateinit var mStateBridge: StateBridge

    /** WiFi / Cell 环境配置持久化存储。 */
    private lateinit var mEnvStore: EnvironmentConfigStore
    private lateinit var mTrackRecorder: TrackRecorder
    private var mLastTickNanos: Long = 0L

    private lateinit var mLocManager: LocationManager
    private lateinit var mSensorManager: SensorManager
    private lateinit var mLocHandlerThread: HandlerThread
    private lateinit var mLocHandler: Handler
    private var isStop = false

    private lateinit var mActReceiver: NoteActionReceiver
    private lateinit var mJoyStick: JoyStick

    private val mBinder = ServiceGoBinder()

    private var mSensorAcc: Sensor? = null
    private var mSensorMag: Sensor? = null
    private var mAccValues = FloatArray(3)
    private var mMagValues = FloatArray(3)
    private val mR = FloatArray(9)
    private val mDirectionValues = FloatArray(3)
    private var mRealBearing = 0.0f

    private val mRandom = Random()

    override fun onBind(intent: Intent): IBinder = mBinder

    override fun onCreate() {
        super.onCreate()

        @Suppress("UNCHECKED_CAST")
        mLocManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        @Suppress("UNCHECKED_CAST")
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        initSensors()

        removeTestProviderNetwork()
        addTestProviderNetwork()

        removeTestProviderGPS()
        addTestProviderGPS()

        removeTestProviderFused()
        addTestProviderFused()

        initNotification()

        initJoyStick()

        mStateBridge = StateBridge(this)
        mEnvStore = EnvironmentConfigStore(this)
        mTrackRecorder = TrackRecorder(this)

        // 最后启动定位线程，避免 Handler 在依赖字段初始化前执行。
        initGoLocation()
    }

    private fun initSensors() {
        mSensorAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mSensorMag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        mSensorAcc?.let {
            mSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        mSensorMag?.let {
            mSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            mCurLng = it.getDoubleExtra("LNG_VALUE", DEFAULT_LNG)
            mCurLat = it.getDoubleExtra("LAT_VALUE", DEFAULT_LAT)
            mCurAlt = it.getDoubleExtra("ALT_VALUE", DEFAULT_ALT)
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt)
        }
        mTrackRecorder.begin(mCurLat, mCurLng, mCurAlt)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isStop = true
        mLocHandler.removeMessages(HANDLER_MSG_ID)
        mLocHandlerThread.quit()
        mTrackRecorder.finish(mCurLat, mCurLng, mCurAlt, mCurBea, mSpeed.toFloat())

        mStateBridge.deactivate()

        mSensorManager.unregisterListener(this)

        mJoyStick.destroy()

        removeTestProviderNetwork()
        removeTestProviderGPS()
        removeTestProviderFused()

        unregisterReceiver(mActReceiver)
        stopForeground(STOP_FOREGROUND_REMOVE)

        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> mAccValues = event.values
            Sensor.TYPE_MAGNETIC_FIELD -> mMagValues = event.values
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues)
        SensorManager.getOrientation(mR, mDirectionValues)
        var azimuth = Math.toDegrees(mDirectionValues[0].toDouble()).toFloat()
        if (azimuth < 0) {
            azimuth += 360
        }
        mRealBearing = azimuth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initNotification() {
        mActReceiver = NoteActionReceiver()
        val filter = IntentFilter().apply {
            addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)
            addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)
        }
        ContextCompat.registerReceiver(this, mActReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val mChannel = NotificationChannel(
            SERVICE_GO_NOTE_CHANNEL_ID,
            SERVICE_GO_NOTE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(mChannel)

        val clickIntent = Intent(this, com.lazybody.MainActivity::class.java)
        val clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE)
        val showIntent = Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW).apply {
            setPackage(packageName)
        }
        val showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE)
        val hideIntent = Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE).apply {
            setPackage(packageName)
        }
        val hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
            .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
            .setContentTitle(resources.getString(R.string.app_name))
            .setContentText(resources.getString(R.string.app_service_tips))
            .setContentIntent(clickPI)
            .addAction(0, resources.getString(R.string.note_show), showPendingPI)
            .addAction(0, resources.getString(R.string.note_hide), hidePendingPI)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(SERVICE_GO_NOTE_ID, notification)
    }

    private fun initJoyStick() {
        mJoyStick = JoyStick(this)
        mJoyStick.setListener(object : JoyStick.JoyStickClickListener {
            override fun onMoveInfo(speed: Double, disLng: Double, disLat: Double, angle: Double) {
                mSpeed = speed
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180))
                mCurLat += disLat / 110.574
                mCurBea = angle.toFloat()
            }

            override fun onPositionInfo(lng: Double, lat: Double, alt: Double) {
                mCurLng = lng
                mCurLat = lat
                mCurAlt = alt
            }
        })

        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val isJoyStickEnabled = sp.getBoolean("setting_joystick_state", false)
        if (isJoyStickEnabled) {
            mJoyStick.show()
        }
    }

    private fun initGoLocation() {
        mLocHandlerThread = HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND)
        mLocHandlerThread.start()
        mLocHandler = object : Handler(mLocHandlerThread.looper) {
            override fun handleMessage(msg: Message) {
                try {
                    // ===== 路线引擎推进 =====
                    advanceRoute()

                    Thread.sleep(100)

                    if (!isStop) {
                        setLocationNetwork()
                        setLocationGPS()
                        setLocationFused()

                        // ===== 写入跨进程状态快照 =====
                        publishState()

                        sendEmptyMessage(HANDLER_MSG_ID)
                    }
                } catch (e: InterruptedException) {
                    XLog.e("SERVICEGO: ERROR - handleMessage")
                    Thread.currentThread().interrupt()
                }
            }
        }

        mLastTickNanos = SystemClock.elapsedRealtimeNanos()
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
    }

    /**
     * 如果路线引擎活跃，按当前速度推进一小步。
     * 推进结果同时更新 mCurLat/mCurLng/mCurBea/mSpeed。
     */
    private fun advanceRoute() {
        val engine = mRouteEngine ?: return
        if (!mRouteActive) return
        if (engine.finished) return

        val now = SystemClock.elapsedRealtimeNanos()
        val dtSec = ((now - mLastTickNanos).coerceAtLeast(0L)).toDouble() / 1_000_000_000.0
        mLastTickNanos = now

        // 速度同步（路线模式下忽略摇杆速度，以引擎为准）
        engine.speedMps = mSpeed

        val (next, bearing) = engine.advance(dtSec) ?: return
        mCurLat = next.lat
        mCurLng = next.lng
        mCurBea = bearing.toFloat()
    }

    /**
     * 读取并对外发布当前模拟快照到 SharedPreferences，
     * 供 Xposed 模块在目标 App 内读取并做 hook 决策。
     */
    private fun publishState() {
        // 路线引擎推进时 lastTickNanos 已刷新；非路线模式也要刷新
        if (mRouteEngine == null || !mRouteActive) {
            mLastTickNanos = SystemClock.elapsedRealtimeNanos()
        }
        val state = SimulationState(
            active = true,
            lat = mCurLat,
            lng = mCurLng,
            alt = mCurAlt,
            bearing = mCurBea,
            speed = mSpeed.toFloat(),
            accuracy = 5.0f,
            timeMillis = 0L,                // 写入时会被 StateBridge 覆盖
            elapsedRealtimeNanos = mLastTickNanos,
            steps = 0.0,
            cadenceSpm = 0.0
        )
        mStateBridge.write(state)
        mTrackRecorder.sample(mCurLat, mCurLng, mCurAlt, mCurBea, mSpeed.toFloat())
    }

    private fun removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS")
        }
    }

    @SuppressLint("WrongConstant")
    private fun addTestProviderGPS() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE
                )
            } else {
                mLocManager.addTestProvider(
                    LocationManager.GPS_PROVIDER, false, true, false,
                    false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
                )
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            }
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS")
        }
    }

    private fun setLocationGPS() {
        try {
            val noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0

            val wgs84 = MapUtils.gcj02towgs84(mCurLng, mCurLat)

            XLog.d("ServiceGo: setLocationGPS - RealBearing: $mRealBearing")

            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                accuracy = Criteria.ACCURACY_FINE.toFloat()
                altitude = mCurAlt + noiseAlt
                bearing = if (mRouteActive && mRouteEngine != null) mCurBea else mRealBearing
                latitude = wgs84[1] + noiseLat
                longitude = wgs84[0] + noiseLng
                time = System.currentTimeMillis()
                speed = mSpeed.toFloat()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = Bundle().apply { putInt("satellites", 7) }
            }

            MockFlagClearer.clearMockFlag(loc)
            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS")
        }
    }

    private fun removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork")
        }
    }

    @SuppressLint("WrongConstant")
    private fun addTestProviderNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE
                )
            } else {
                mLocManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE
                )
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            }
        } catch (e: SecurityException) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork")
        }
    }

    private fun setLocationNetwork() {
        try {
            val noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0

            val wgs84 = MapUtils.gcj02towgs84(mCurLng, mCurLat)

            val loc = Location(LocationManager.NETWORK_PROVIDER).apply {
                accuracy = Criteria.ACCURACY_COARSE.toFloat()
                altitude = mCurAlt + noiseAlt
                bearing = if (mRouteActive && mRouteEngine != null) mCurBea else mRealBearing
                latitude = wgs84[1] + noiseLat
                longitude = wgs84[0] + noiseLng
                time = System.currentTimeMillis()
                speed = mSpeed.toFloat()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }

            MockFlagClearer.clearMockFlag(loc)
            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc)
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork")
        }
    }

    private fun removeTestProviderFused() {
        try {
            val providerName = "fused"
            if (mLocManager.isProviderEnabled(providerName)) {
                mLocManager.setTestProviderEnabled(providerName, false)
                mLocManager.removeTestProvider(providerName)
            }
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderFused")
        }
    }

    @SuppressLint("WrongConstant")
    private fun addTestProviderFused() {
        try {
            val providerName = "fused"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(
                    providerName, false, false, false,
                    false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
                )
            } else {
                mLocManager.addTestProvider(
                    providerName, false, false, false,
                    false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
            }
            if (!mLocManager.isProviderEnabled(providerName)) {
                mLocManager.setTestProviderEnabled(providerName, true)
            }
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - addTestProviderFused")
        }
    }

    private fun setLocationFused() {
        try {
            val noiseLat = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseLng = (mRandom.nextDouble() - 0.5) * 0.00004
            val noiseAlt = (mRandom.nextDouble() - 0.5) * 1.0

            val wgs84 = MapUtils.gcj02towgs84(mCurLng, mCurLat)

            val loc = Location("fused").apply {
                accuracy = Criteria.ACCURACY_FINE.toFloat()
                altitude = mCurAlt + noiseAlt
                bearing = if (mRouteActive && mRouteEngine != null) mCurBea else mRealBearing
                latitude = wgs84[1] + noiseLat
                longitude = wgs84[0] + noiseLng
                time = System.currentTimeMillis()
                speed = mSpeed.toFloat()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = Bundle().apply { putInt("satellites", 7) }
            }

            MockFlagClearer.clearMockFlag(loc)
            mLocManager.setTestProviderLocation("fused", loc)
        } catch (e: Exception) {
            XLog.e("SERVICEGO: ERROR - setLocationFused")
        }
    }

    inner class NoteActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW) {
                mJoyStick.show()
            } else if (action == SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE) {
                mJoyStick.hide()
            }
        }
    }

    inner class ServiceGoBinder : Binder() {
        fun setPosition(lng: Double, lat: Double, alt: Double) {
            mLocHandler.removeMessages(HANDLER_MSG_ID)
            mCurLng = lng
            mCurLat = lat
            mCurAlt = alt
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt)
        }

        fun getCurLat(): Double = mCurLat
        fun getCurLng(): Double = mCurLng
        fun getCurAlt(): Double = mCurAlt
        fun getCurBearing(): Float = mRealBearing
        fun getSpeed(): Double = mSpeed

        // ===== 路线引擎 =====

        /** 设置路线并启用。若 waypoints 少于 2 个，会静默忽略。 */
        fun setRoute(waypoints: List<Pair<Double, Double>>, mode: String = "once", speedLabel: String = "walk") {
            if (waypoints.size < 2) {
                clearRoute()
                return
            }
            val wps = waypoints.map { (lat, lng) -> Waypoint(lat, lng) }
            val routeMode = when (mode.lowercase()) {
                "loop" -> RouteMode.LOOP
                else -> RouteMode.ONCE
            }
            mRouteEngine = RouteEngine(wps, routeMode, SpeedTier.mpsOf(speedLabel))
            mSpeed = SpeedTier.mpsOf(speedLabel)
            mRouteActive = true
            mLastTickNanos = SystemClock.elapsedRealtimeNanos()
            XLog.d("SERVICEGO: route set, ${waypoints.size} waypoints, mode=$mode, speed=$speedLabel")
        }

        /** 清除路线，回到摇杆/单点模式。 */
        fun clearRoute() {
            mRouteEngine = null
            mRouteActive = false
            XLog.d("SERVICEGO: route cleared")
        }

        /** 路线模式下切换速度档位（walk / run / drive）。 */
        fun setRouteSpeed(label: String) {
            mSpeed = SpeedTier.mpsOf(label)
        }

        fun isRouteActive(): Boolean = mRouteActive
        fun isRouteFinished(): Boolean = mRouteEngine?.finished ?: false
        fun routeProgress(): Double = mRouteEngine?.progress() ?: 0.0

        // ===== 环境配置（WiFi / Cell）=====

        fun setEnvironment(config: EnvironmentConfig) {
            mEnvStore.save(config)
            XLog.d("SERVICEGO: environment saved, wifiMode=${config.wifiMode}, cellMode=${config.cellMode}")
        }

        fun getEnvironment(): EnvironmentConfig = mEnvStore.load()

        fun setWifiMode(mode: String) {
            val m = try { EnvironmentConfig.Mode.valueOf(mode.uppercase()) } catch (_: Throwable) { EnvironmentConfig.Mode.PASSTHROUGH }
            mEnvStore.setWifiMode(m)
            XLog.d("SERVICEGO: wifiMode -> $mode")
        }

        fun setCellMode(mode: String) {
            val m = try { EnvironmentConfig.Mode.valueOf(mode.uppercase()) } catch (_: Throwable) { EnvironmentConfig.Mode.PASSTHROUGH }
            mEnvStore.setCellMode(m)
            XLog.d("SERVICEGO: cellMode -> $mode")
        }
    }
}
