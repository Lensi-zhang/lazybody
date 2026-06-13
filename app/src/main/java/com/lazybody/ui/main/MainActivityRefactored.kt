package com.lazybody.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import com.elvishew.xlog.XLog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.lazybody.BaseActivity
import com.lazybody.HistoryActivity
import com.lazybody.R
import com.lazybody.SettingsActivity
import com.lazybody.data.database.AppDatabase
import com.lazybody.data.database.entity.SearchHistoryEntity
import com.lazybody.data.repository.SearchRepository
import com.lazybody.service.ServiceGoKt
import com.lazybody.utils.GCJ02LocationProvider
import com.lazybody.utils.GoUtils
import com.lazybody.utils.ShareUtils
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivityRefactored : BaseActivity(), SensorEventListener {

    companion object {
        const val LAT_MSG_ID = "LAT_VALUE"
        const val LNG_MSG_ID = "LNG_VALUE"
        const val ALT_MSG_ID = "ALT_VALUE"
        const val POI_NAME = "POI_NAME"
        const val POI_ADDRESS = "POI_ADDRESS"
        const val POI_LONGITUDE = "POI_LONGITUDE"
        const val POI_LATITUDE = "POI_LATITUDE"
    }

    private lateinit var viewModel: MainViewModel
    private lateinit var mapHelper: MapHelper
    private lateinit var searchHelper: SearchHelper

    private var mMapView: MapView? = null
    private var mLocationOverlay: MyLocationNewOverlay? = null

    private lateinit var mSensorManager: SensorManager
    private var mSensorAccelerometer: Sensor? = null
    private var mSensorMagnetic: Sensor? = null
    private var mAccValues = FloatArray(3)
    private var mMagValues = FloatArray(3)
    private val mR = FloatArray(9)
    private val mDirectionValues = FloatArray(3)
    private var mCurrentDirection = 0.0f

    private var isMockServStart = false
    private var mServiceBinder: ServiceGoKt.ServiceGoBinder? = null
    private var mConnection: ServiceConnection? = null
    private lateinit var mButtonStart: FloatingActionButton
    private lateinit var sharedPreferences: android.content.SharedPreferences

    private var searchView: SearchView? = null
    private var mSearchList: ListView? = null
    private var mSearchLayout: LinearLayout? = null
    private var mSearchHistoryList: ListView? = null
    private var mHistoryLayout: LinearLayout? = null
    private var searchItem: MenuItem? = null

    private var mCurrentZoom = 16.0

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mMapView?.let {
            outState.putDouble("MAP_ZOOM", it.zoomLevelDouble)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            mCurrentZoom = savedInstanceState.getDouble("MAP_ZOOM", 16.0)
        }

        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(
            this, drawer, toolbar, R.string.nav_drawer_open, R.string.nav_drawer_close
        )
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        XLog.i("MainActivityRefactored: onCreate")

        sharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)

        mapHelper = MapHelper(this)

        val searchRepository = SearchRepository(AppDatabase.getDatabase(this).searchHistoryDao())
        searchHelper = SearchHelper(this, searchRepository) { name, address, lng, lat ->
            onLocationSearchResult(name, address, lng, lat)
        }

        initNavigationView()
        initSensors()
        initMap()
        initMapLocation(savedInstanceState != null)
        initMapButton()
        initGoBtn()
        initSearchView()

        mConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mServiceBinder = service as ServiceGoKt.ServiceGoBinder
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra("SHOW_LOCATION", false)) {
            val name = intent.getStringExtra("NAME")
            val lngStr = intent.getStringExtra("LNG")
            val latStr = intent.getStringExtra("LAT")
            if (lngStr != null && latStr != null) {
                try {
                    val lng = lngStr.toDouble()
                    val lat = latStr.toDouble()
                    val geoPoint = GeoPoint(lat, lng)
                    viewModel.setMarkerPosition(geoPoint)
                    viewModel.setMarkerName(name)

                    mMapView?.let {
                        mapHelper.setCenter(geoPoint)
                        mapHelper.markPosition(geoPoint, R.drawable.ic_marker_pin)
                    }
                } catch (e: NumberFormatException) {
                    XLog.e("Invalid coordinates in intent")
                }
            }
        }
    }

    override fun onPause() {
        XLog.i("MainActivityRefactored: onPause")
        mMapView?.onPause()
        mSensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onResume() {
        XLog.i("MainActivityRefactored: onResume")
        mMapView?.onResume()
        mSensorAccelerometer?.let {
            mSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        mSensorMagnetic?.let {
            mSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        super.onResume()
    }

    override fun onStop() {
        XLog.i("MainActivityRefactored: onStop")
        mSensorManager.unregisterListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        XLog.i("MainActivityRefactored: onDestroy")

        if (isMockServStart) {
            mConnection?.let { unbindService(it) }
            val serviceGoIntent = Intent(this, ServiceGoKt::class.java)
            stopService(serviceGoIntent)
        }

        mSensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        searchItem = menu.findItem(R.id.action_search)
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                mSearchLayout?.visibility = View.INVISIBLE
                mHistoryLayout?.visibility = View.INVISIBLE
                return true
            }

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                mSearchLayout?.visibility = View.INVISIBLE
                return true
            }
        })

        searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            isIconified = false
            onActionViewExpanded()
            setIconifiedByDefault(false)
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    searchHelper.performSearch(query) { addresses ->
                        val data = addresses.map { addr ->
                            mapOf(
                                POI_NAME to addr.featureName,
                                POI_ADDRESS to addr.getAddressLine(0),
                                POI_LONGITUDE to addr.longitude.toString(),
                                POI_LATITUDE to addr.latitude.toString()
                            )
                        }
                        val simAdapt = SimpleAdapter(
                            this@MainActivityRefactored,
                            data,
                            R.layout.search_poi_item,
                            arrayOf(POI_NAME, POI_ADDRESS, POI_LONGITUDE, POI_LATITUDE),
                            intArrayOf(R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude)
                        )
                        mSearchList?.adapter = simAdapt
                        mSearchLayout?.visibility = View.VISIBLE
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    mHistoryLayout?.visibility = View.INVISIBLE
                    return true
                }
            })
        }

        val closeButton = searchView?.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
        closeButton?.setOnClickListener {
            searchView?.let { sv ->
                val et = findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
                et?.setText("")
                sv.setQuery("", false)
            }
            mSearchLayout?.visibility = View.INVISIBLE
            mHistoryLayout?.visibility = View.VISIBLE
        }

        return true
    }

    override fun onSensorChanged(sensorEvent: SensorEvent) {
        when (sensorEvent.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> mAccValues = sensorEvent.values
            Sensor.TYPE_MAGNETIC_FIELD -> mMagValues = sensorEvent.values
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues)
        SensorManager.getOrientation(mR, mDirectionValues)
        mCurrentDirection = Math.toDegrees(mDirectionValues[0].toDouble()).toFloat()
        if (mCurrentDirection < 0) {
            mCurrentDirection += 360
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, i: Int) {}

    private fun initSensors() {
        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun initNavigationView() {
        val navigationView = findViewById<NavigationView>(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_history -> {
                    val intent = Intent(this, HistoryActivity::class.java)
                    startActivity(intent)
                }
                R.id.nav_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
            }
            val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
            drawer.closeDrawer(GravityCompat.START)
            true
        }

        val btnToggle = findViewById<MaterialButton>(R.id.btn_theme_toggle)
        btnToggle?.let {
            val currentNightMode = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (isDark) {
                it.setIconResource(R.drawable.ic_sunny)
            } else {
                it.setIconResource(R.drawable.ic_night)
            }

            it.setOnClickListener { _ ->
                if (isDark) {
                    androidx.appcompat.app.AppCompatDelegate
                        .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                } else {
                    androidx.appcompat.app.AppCompatDelegate
                        .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }
    }

    private fun initMap() {
        mMapView = findViewById(R.id.bdMapView)
        mMapView?.let { map ->
            mapHelper.initialize(map)

            val nightModeFlags = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                mapHelper.setNightModeFilter()
            }

            mapHelper.setMapClickListener { point ->
                viewModel.setMarkerPosition(point)
                viewModel.setMarkerName(null)
                mapHelper.markPosition(point, R.drawable.ic_marker_pin)
            }

            mapHelper.setZoom(mCurrentZoom)
            val startPoint = GeoPoint(39.9042, 116.4074)
            mapHelper.setCenter(startPoint)
        }
    }

    private fun initMapLocation(hasHistory: Boolean) {
        mMapView?.let { map ->
            mLocationOverlay = MyLocationNewOverlay(GCJ02LocationProvider(this), map)

            val myLocBitmap = getBitmapFromDrawable(R.drawable.ic_mylocation_dot)
            myLocBitmap?.let { bitmap ->
                mLocationOverlay?.setPersonIcon(bitmap)
                mLocationOverlay?.setDirectionIcon(bitmap)
                mLocationOverlay?.setPersonAnchor(0.5f, 0.5f)
                mLocationOverlay?.setDirectionAnchor(0.5f, 0.5f)
            }

            mLocationOverlay?.enableMyLocation()
            map.overlays.add(mLocationOverlay)

            if (!hasHistory) {
                mLocationOverlay?.runOnFirstFix {
                    runOnUiThread {
                        val myLoc = mLocationOverlay?.myLocation
                        if (myLoc != null) {
                            mapHelper.setCenter(myLoc)
                            mapHelper.animateTo(myLoc)
                            viewModel.setMarkerPosition(myLoc)
                            GoUtils.DisplayToast(this, "已成功获取当前位置")
                        }
                    }
                }
            } else {
                viewModel.currentMarkerPosition.value?.let { position ->
                    mapHelper.setCenter(position)
                    mapHelper.markPosition(position, R.drawable.ic_marker_pin)
                }
            }
        }
    }

    private fun initMapButton() {
        findViewById<ImageButton>(R.id.cur_position)?.setOnClickListener {
            resetMap()
        }

        findViewById<ImageButton>(R.id.zoom_in)?.setOnClickListener {
            mapHelper.zoomIn()
        }

        findViewById<ImageButton>(R.id.zoom_out)?.setOnClickListener {
            mapHelper.zoomOut()
        }

        findViewById<ImageButton>(R.id.input_pos)?.setOnClickListener {
            showInputDialog()
        }
    }

    private fun showInputDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("请输入经度和纬度")
        val view = layoutInflater.inflate(R.layout.location_input, null)
        builder.setView(view)
        val dialog = builder.show()

        val dialogLng = view.findViewById<EditText>(R.id.joystick_longitude)
        val dialogLat = view.findViewById<EditText>(R.id.joystick_latitude)

        val dialogIp = view.findViewById<EditText>(R.id.input_ip_address)
        val btnGetIp = view.findViewById<MaterialButton>(R.id.btn_get_ip_location)

        btnGetIp.setOnClickListener {
            val ip = dialogIp?.text.toString()
            GoUtils.getIpLocation(ip, object : GoUtils.LocationCallback {
                override fun onSuccess(lat: Double, lng: Double) {
                    runOnUiThread {
                        dialogLat?.setText(lat.toString())
                        dialogLng?.setText(lng.toString())
                        GoUtils.DisplayToast(this@MainActivityRefactored, getString(R.string.ip_location_success))
                    }
                }

                override fun onError(msg: String) {
                    runOnUiThread {
                        GoUtils.DisplayToast(this@MainActivityRefactored, getString(R.string.ip_location_error) + ": " + msg)
                    }
                }
            })
        }

        val btnGo = view.findViewById<MaterialButton>(R.id.input_position_ok)
        btnGo.setOnClickListener {
            val lngStr = dialogLng?.text.toString()
            val latStr = dialogLat?.text.toString()

            if (lngStr.isEmpty() || latStr.isEmpty()) {
                GoUtils.DisplayToast(this, getString(R.string.app_error_input))
            } else {
                try {
                    val lng = lngStr.toDouble()
                    val lat = latStr.toDouble()

                    if (lng > 180.0 || lng < -180.0) {
                        GoUtils.DisplayToast(this, getString(R.string.app_error_longitude))
                    } else if (lat > 90.0 || lat < -90.0) {
                        GoUtils.DisplayToast(this, getString(R.string.app_error_latitude))
                    } else {
                        val geoPoint = GeoPoint(lat, lng)
                        viewModel.setMarkerPosition(geoPoint)
                        viewModel.setMarkerName("手动输入的坐标")
                        mapHelper.setCenter(geoPoint)
                        mapHelper.markPosition(geoPoint, R.drawable.ic_marker_pin)
                        dialog.dismiss()
                    }
                } catch (e: NumberFormatException) {
                    GoUtils.DisplayToast(this, getString(R.string.app_error_input))
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.input_position_cancel)?.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun initGoBtn() {
        mButtonStart = findViewById(R.id.faBtnStart)
        mButtonStart.setOnClickListener { v -> doGoLocation(v) }
    }

    private fun doGoLocation(v: View) {
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getString(R.string.app_error_network))
            return
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this)
            return
        }

        if (!Settings.canDrawOverlays(applicationContext)) {
            GoUtils.showEnableFloatWindowDialog(this)
            XLog.e("无悬浮窗权限!")
            return
        }

        val markerPosition = viewModel.currentMarkerPosition.value

        if (isMockServStart) {
            if (markerPosition == null) {
                stopGoLocation()
                Snackbar.make(v, "模拟位置已终止", Snackbar.LENGTH_LONG).show()
                mButtonStart.setImageResource(R.drawable.ic_position)
            } else {
                val alt = sharedPreferences.getString("setting_altitude", "55.0")?.toDoubleOrNull() ?: 55.0
                mServiceBinder?.setPosition(markerPosition.longitude, markerPosition.latitude, alt)
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG).show()

                viewModel.saveLocation(markerPosition.longitude, markerPosition.latitude)
                mapHelper.clearMarker()
                viewModel.setMarkerPosition(GeoPoint(0.0, 0.0))
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this)
                XLog.e("无模拟位置权限!")
            } else {
                if (markerPosition == null || (markerPosition.latitude == 0.0 && markerPosition.longitude == 0.0)) {
                    Snackbar.make(v, "请先点击地图位置或者搜索位置", Snackbar.LENGTH_LONG).show()
                } else {
                    startGoLocation()
                    mButtonStart.setImageResource(R.drawable.ic_fly)
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG).show()

                    viewModel.saveLocation(markerPosition.longitude, markerPosition.latitude)
                    mapHelper.clearMarker()
                    viewModel.setMarkerPosition(GeoPoint(0.0, 0.0))
                }
            }
        }
    }

    private fun startGoLocation() {
        val markerPosition = viewModel.currentMarkerPosition.value ?: return

        val serviceGoIntent = Intent(this, ServiceGoKt::class.java)
        bindService(serviceGoIntent, mConnection!!, BIND_AUTO_CREATE)
        serviceGoIntent.putExtra(LNG_MSG_ID, markerPosition.longitude)
        serviceGoIntent.putExtra(LAT_MSG_ID, markerPosition.latitude)
        val alt = sharedPreferences.getString("setting_altitude", "55.0")?.toDoubleOrNull() ?: 55.0
        serviceGoIntent.putExtra(ALT_MSG_ID, alt)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceGoIntent)
        } else {
            startService(serviceGoIntent)
        }
        XLog.d("startForegroundService: ServiceGoKt")

        isMockServStart = true
    }

    private fun stopGoLocation() {
        mConnection?.let { unbindService(it) }
        val serviceGoIntent = Intent(this, ServiceGoKt::class.java)
        stopService(serviceGoIntent)
        isMockServStart = false
    }

    private fun resetMap() {
        val myLoc = mLocationOverlay?.myLocation
        if (myLoc != null) {
            mapHelper.animateTo(myLoc)
            viewModel.setMarkerPosition(myLoc)
        } else {
            if (!GoUtils.isGpsOpened(this)) {
                GoUtils.DisplayToast(this, getString(R.string.app_error_gps))
            } else {
                GoUtils.DisplayToast(this, "定位失败：请确保处于室外开阔地带并稍后重试")
            }
        }
    }

    private fun onLocationSearchResult(name: String, address: String, lng: Double, lat: Double) {
        val geoPoint = GeoPoint(lat, lng)
        viewModel.setMarkerPosition(geoPoint)
        viewModel.setMarkerName(name)
        mapHelper.setCenter(geoPoint)
        mapHelper.markPosition(geoPoint, R.drawable.ic_marker_pin)
    }

    private fun initSearchView() {
        mSearchLayout = findViewById(R.id.search_linear)
        mHistoryLayout = findViewById(R.id.search_history_linear)
        mSearchList = findViewById(R.id.search_list_view)
        mSearchHistoryList = findViewById(R.id.search_history_list_view)
    }

    private fun getBitmapFromDrawable(resId: Int): android.graphics.Bitmap? {
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
        val bitmap = android.graphics.Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
