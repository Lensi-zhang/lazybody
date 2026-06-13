# LazyBody 架构重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 LazyBody 项目，解决 MainActivity 过于臃肿、缺少 ViewModel、数据库操作耦合、线程管理原始等架构问题。

**Architecture:** 采用 MVVM 架构模式，引入 ViewModel + LiveData 管理 UI 状态，Repository 模式抽象数据层，协程管理异步任务。

**Tech Stack:** Android Jetpack (ViewModel, LiveData, Coroutines), Room (替代原生 SQLite), Hilt (依赖注入)

---

## 文件结构规划

```
app/src/main/java/com/lazybody/
├── data/
│   ├── database/
│   │   ├── AppDatabase.kt
│   │   ├── dao/
│   │   │   ├── LocationHistoryDao.kt
│   │   │   └── SearchHistoryDao.kt
│   │   └── entity/
│   │       ├── LocationHistoryEntity.kt
│   │       └── SearchHistoryEntity.kt
│   └── repository/
│       ├── LocationRepository.kt
│       └── SearchRepository.kt
├── ui/
│   ├── main/
│   │   ├── MainActivity.kt
│   │   ├── MainViewModel.kt
│   │   └── MapFragment.kt
│   ├── history/
│   │   ├── HistoryActivity.kt
│   │   └── HistoryViewModel.kt
│   └── settings/
│       └── SettingsActivity.kt
├── service/
│   └── ServiceGo.kt
├── xposed/
│   ├── HideMockHook.kt
│   ├── LocationHook.kt
│   └── GnssStatusHook.kt
├── joystick/
│   ├── JoyStick.kt
│   └── RockerView.kt
└── utils/
    ├── MapUtils.kt
    ├── GCJ02LocationProvider.kt
    └── ShareUtils.kt
```

---

## Task 1: 项目配置 - 添加依赖

**Covers:** 基础设施

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: 添加 Room、ViewModel、Coroutines 依赖**

```gradle
dependencies {
    // 现有依赖保持不变...
    
    // Room Database
    implementation "androidx.room:room-runtime:2.6.1"
    annotationProcessor "androidx.room:room-compiler:2.6.1"
    
    // ViewModel & LiveData
    implementation "androidx.lifecycle:lifecycle-viewmodel:2.7.0"
    implementation "androidx.lifecycle:lifecycle-livedata:2.7.0"
    
    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"
    
    // RecyclerView (for lists)
    implementation "androidx.recyclerview:recyclerview:1.3.2"
}
```

- [ ] **Step 2: 同步 Gradle**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle
git commit -m "chore: add Room, ViewModel, Coroutines dependencies"
```

---

## Task 2: 创建 Room 数据库实体

**Covers:** 数据层抽象

**Files:**
- Create: `app/src/main/java/com/lazybody/data/database/entity/LocationHistoryEntity.kt`
- Create: `app/src/main/java/com/lazybody/data/database/entity/SearchHistoryEntity.kt`

- [ ] **Step 1: 创建 LocationHistoryEntity**

```kotlin
package com.lazybody.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_history")
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "location")
    val location: String,
    
    @ColumnInfo(name = "longitude_wgs84")
    val longitudeWgs84: String,
    
    @ColumnInfo(name = "latitude_wgs84")
    val latitudeWgs84: String,
    
    @ColumnInfo(name = "longitude_custom")
    val longitudeCustom: String,
    
    @ColumnInfo(name = "latitude_custom")
    val latitudeCustom: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
```

- [ ] **Step 2: 创建 SearchHistoryEntity**

```kotlin
package com.lazybody.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "key")
    val key: String,
    
    @ColumnInfo(name = "description")
    val description: String,
    
    @ColumnInfo(name = "is_location")
    val isLocation: Int,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    
    @ColumnInfo(name = "longitude_custom")
    val longitudeCustom: String?,
    
    @ColumnInfo(name = "latitude_custom")
    val latitudeCustom: String?,
    
    @ColumnInfo(name = "longitude_wgs84")
    val longitudeWgs84: String?,
    
    @ColumnInfo(name = "latitude_wgs84")
    val latitudeWgs84: String?
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lazybody/data/database/entity/
git commit -m "feat: add Room entities for location and search history"
```

---

## Task 3: 创建 DAO 接口

**Covers:** 数据层抽象

**Files:**
- Create: `app/src/main/java/com/lazybody/data/database/dao/LocationHistoryDao.kt`
- Create: `app/src/main/java/com/lazybody/data/database/dao/SearchHistoryDao.kt`

- [ ] **Step 1: 创建 LocationHistoryDao**

```kotlin
package com.lazybody.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.lazybody.data.database.entity.LocationHistoryEntity

@Dao
interface LocationHistoryDao {
    
    @Query("SELECT * FROM location_history ORDER BY timestamp DESC")
    fun getAllLocations(): LiveData<List<LocationHistoryEntity>>
    
    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationHistoryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationHistoryEntity): Long
    
    @Update
    suspend fun updateLocation(location: LocationHistoryEntity)
    
    @Delete
    suspend fun deleteLocation(location: LocationHistoryEntity)
    
    @Query("DELETE FROM location_history")
    suspend fun deleteAllLocations()
}
```

- [ ] **Step 2: 创建 SearchHistoryDao**

```kotlin
package com.lazybody.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.lazybody.data.database.entity.SearchHistoryEntity

@Dao
interface SearchHistoryDao {
    
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    fun getAllSearches(): LiveData<List<SearchHistoryEntity>>
    
    @Query("SELECT * FROM search_history WHERE `key` = :key LIMIT 1")
    suspend fun getSearchByKey(key: String): SearchHistoryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity): Long
    
    @Delete
    suspend fun deleteSearch(search: SearchHistoryEntity)
    
    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun deleteSearchByKey(key: String)
    
    @Query("DELETE FROM search_history")
    suspend fun deleteAllSearches()
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lazybody/data/database/dao/
git commit -m "feat: add DAO interfaces for location and search history"
```

---

## Task 4: 创建 Room Database

**Covers:** 数据层抽象

**Files:**
- Create: `app/src/main/java/com/lazybody/data/database/AppDatabase.kt`

- [ ] **Step 1: 创建 AppDatabase**

```kotlin
package com.lazybody.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lazybody.data.database.dao.LocationHistoryDao
import com.lazybody.data.database.dao.SearchHistoryDao
import com.lazybody.data.database.entity.LocationHistoryEntity
import com.lazybody.data.database.entity.SearchHistoryEntity

@Database(
    entities = [
        LocationHistoryEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lazybody_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/lazybody/data/database/AppDatabase.kt
git commit -m "feat: add Room Database configuration"
```

---

## Task 5: 创建 Repository 层

**Covers:** 数据层抽象

**Files:**
- Create: `app/src/main/java/com/lazybody/data/repository/LocationRepository.kt`
- Create: `app/src/main/java/com/lazybody/data/repository/SearchRepository.kt`

- [ ] **Step 1: 创建 LocationRepository**

```kotlin
package com.lazybody.data.repository

import androidx.lifecycle.LiveData
import com.lazybody.data.database.dao.LocationHistoryDao
import com.lazybody.data.database.entity.LocationHistoryEntity

class LocationRepository(private val locationHistoryDao: LocationHistoryDao) {
    
    val allLocations: LiveData<List<LocationHistoryEntity>> = locationHistoryDao.getAllLocations()
    
    suspend fun getLatestLocation(): LocationHistoryEntity? {
        return locationHistoryDao.getLatestLocation()
    }
    
    suspend fun insert(location: LocationHistoryEntity): Long {
        return locationHistoryDao.insertLocation(location)
    }
    
    suspend fun update(location: LocationHistoryEntity) {
        locationHistoryDao.updateLocation(location)
    }
    
    suspend fun delete(location: LocationHistoryEntity) {
        locationHistoryDao.deleteLocation(location)
    }
    
    suspend fun deleteAll() {
        locationHistoryDao.deleteAllLocations()
    }
}
```

- [ ] **Step 2: 创建 SearchRepository**

```kotlin
package com.lazybody.data.repository

import androidx.lifecycle.LiveData
import com.lazybody.data.database.dao.SearchHistoryDao
import com.lazybody.data.database.entity.SearchHistoryEntity

class SearchRepository(private val searchHistoryDao: SearchHistoryDao) {
    
    val allSearches: LiveData<List<SearchHistoryEntity>> = searchHistoryDao.getAllSearches()
    
    suspend fun getSearchByKey(key: String): SearchHistoryEntity? {
        return searchHistoryDao.getSearchByKey(key)
    }
    
    suspend fun insert(search: SearchHistoryEntity): Long {
        return searchHistoryDao.insertSearch(search)
    }
    
    suspend fun delete(search: SearchHistoryEntity) {
        searchHistoryDao.deleteSearch(search)
    }
    
    suspend fun deleteByKey(key: String) {
        searchHistoryDao.deleteSearchByKey(key)
    }
    
    suspend fun deleteAll() {
        searchHistoryDao.deleteAllSearches()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lazybody/data/repository/
git commit -m "feat: add Repository layer for data access"
```

---

## Task 6: 创建 MainViewModel

**Covers:** UI 状态管理

**Files:**
- Create: `app/src/main/java/com/lazybody/ui/main/MainViewModel.kt`

- [ ] **Step 1: 创建 MainViewModel**

```kotlin
package com.lazybody.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lazybody.data.database.AppDatabase
import com.lazybody.data.database.entity.LocationHistoryEntity
import com.lazybody.data.repository.LocationRepository
import com.lazybody.data.repository.SearchRepository
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val locationRepository: LocationRepository
    private val searchRepository: SearchRepository
    
    // UI State
    private val _currentMarkerPosition = MutableLiveData<GeoPoint?>()
    val currentMarkerPosition: LiveData<GeoPoint?> = _currentMarkerPosition
    
    private val _currentMarkerName = MutableLiveData<String?>()
    val currentMarkerName: LiveData<String?> = _currentMarkerName
    
    private val _isMockServiceRunning = MutableLiveData(false)
    val isMockServiceRunning: LiveData<Boolean> = _isMockServiceRunning
    
    private val _mapZoomLevel = MutableLiveData(16.0)
    val mapZoomLevel: LiveData<Double> = _mapZoomLevel
    
    // History Data
    val locationHistory: LiveData<List<LocationHistoryEntity>>
    
    init {
        val database = AppDatabase.getDatabase(application)
        locationRepository = LocationRepository(database.locationHistoryDao())
        searchRepository = SearchRepository(database.searchHistoryDao())
        
        locationHistory = locationRepository.allLocations
    }
    
    fun setMarkerPosition(geoPoint: GeoPoint) {
        _currentMarkerPosition.value = geoPoint
    }
    
    fun setMarkerName(name: String?) {
        _currentMarkerName.value = name
    }
    
    fun setMockServiceRunning(running: Boolean) {
        _isMockServiceRunning.value = running
    }
    
    fun setMapZoomLevel(zoom: Double) {
        _mapZoomLevel.value = zoom
    }
    
    fun saveLocation(lng: Double, lat: Double, name: String = "Unknown Location") {
        viewModelScope.launch {
            val entity = LocationHistoryEntity(
                location = name,
                longitudeWgs84 = lng.toString(),
                latitudeWgs84 = lat.toString(),
                longitudeCustom = lng.toString(),
                latitudeCustom = lat.toString(),
                timestamp = System.currentTimeMillis() / 1000
            )
            locationRepository.insert(entity)
        }
    }
    
    suspend fun getLatestLocation(): LocationHistoryEntity? {
        return locationRepository.getLatestLocation()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/lazybody/ui/main/MainViewModel.kt
git commit -m "feat: add MainViewModel with LiveData for UI state"
```

---

## Task 7: 重构 MainActivity - 提取地图逻辑

**Covers:** MainActivity 拆分

**Files:**
- Modify: `app/src/main/java/com/lazybody/MainActivity.java`
- Create: `app/src/main/java/com/lazybody/ui/main/MapHelper.kt`

- [ ] **Step 1: 创建 MapHelper 辅助类**

```kotlin
package com.lazybody.ui.main

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

class MapHelper(private val context: Context) {
    
    private var mapView: MapView? = null
    private var currentMarker: Marker? = null
    
    fun initialize(mapView: MapView) {
        this.mapView = mapView
        
        Configuration.getInstance().load(context, 
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
        
        mapView.setTileSource(MapTileSource.GAODE_ROAD)
        mapView.getZoomController().visibility = org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        mapView.setMultiTouchControls(true)
    }
    
    fun setNightModeFilter() {
        mapView?.let { map ->
            val nightMatrix = floatArrayOf(
                -1.0f, 0f, 0f, 0f, 255f,
                0f, -1.0f, 0f, 0f, 255f,
                0f, 0f, -1.0f, 0f, 255f,
                0f, 0f, 0f, 1.0f, 0f
            )
            map.overlayManager.tilesOverlay.colorFilter = 
                android.graphics.ColorMatrixColorFilter(nightMatrix)
        }
    }
    
    fun setMapClickListener(onClick: (GeoPoint) -> Unit) {
        mapView?.let { map ->
            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onClick(p)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            map.overlays.add(0, eventsOverlay)
        }
    }
    
    fun markPosition(geoPoint: GeoPoint, drawableId: Int) {
        mapView?.let { map ->
            currentMarker?.let { map.overlays.remove(it) }
            
            currentMarker = Marker(map).apply {
                position = geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.core.content.ContextCompat.getDrawable(context, drawableId)
                title = "Selected Location"
            }
            map.overlays.add(currentMarker)
            map.invalidate()
        }
    }
    
    fun clearMarker() {
        mapView?.let { map ->
            currentMarker?.let { map.overlays.remove(it) }
            currentMarker = null
            map.invalidate()
        }
    }
    
    fun setCenter(geoPoint: GeoPoint) {
        mapView?.controller?.setCenter(geoPoint)
    }
    
    fun animateTo(geoPoint: GeoPoint) {
        mapView?.controller?.animateTo(geoPoint)
    }
    
    fun setZoom(zoom: Double) {
        mapView?.controller?.setZoom(zoom)
    }
    
    fun zoomIn() {
        mapView?.controller?.zoomIn()
    }
    
    fun zoomOut() {
        mapView?.controller?.zoomOut()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/lazybody/ui/main/MapHelper.kt
git commit -m "refactor: extract map logic to MapHelper"
```

---

## Task 8: 重构 MainActivity - 提取搜索逻辑

**Covers:** MainActivity 拆分

**Files:**
- Create: `app/src/main/java/com/lazybody/ui/main/SearchHelper.kt`

- [ ] **Step 1: 创建 SearchHelper**

```kotlin
package com.lazybody.ui.main

import android.content.Context
import android.widget.SearchView
import androidx.appcompat.widget.SearchView as AppCompatSearchView
import com.lazybody.data.repository.SearchRepository
import com.lazybody.data.database.entity.SearchHistoryEntity
import com.lazybody.utils.GoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.location.GeocoderNominatim
import android.location.Address
import java.util.Locale

class SearchHelper(
    private val context: Context,
    private val searchRepository: SearchRepository,
    private val onLocationFound: (String, String, Double, Double) -> Unit
) {
    
    private val geocoder = GeocoderNominatim(Locale.getDefault(), context.packageName)
    
    fun performSearch(query: String, callback: (List<Address>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = geocoder.getFromLocationName(query, 10)
                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        callback(addresses)
                        saveSearchHistory(query, "搜索关键字", 0)
                    } else {
                        GoUtils.DisplayToast(context, "未找到结果")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    GoUtils.DisplayToast(context, "搜索失败: ${e.message}")
                }
            }
        }
    }
    
    fun reverseGeocode(lat: Double, lng: Double, callback: (Address?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                withContext(Dispatchers.Main) {
                    callback(addresses?.firstOrNull())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }
    
    private suspend fun saveSearchHistory(key: String, description: String, isLocation: Int) {
        searchRepository.insert(
            SearchHistoryEntity(
                key = key,
                description = description,
                isLocation = isLocation,
                timestamp = System.currentTimeMillis() / 1000
            )
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/lazybody/ui/main/SearchHelper.kt
git commit -m "refactor: extract search logic to SearchHelper"
```

---

## Task 9: 简化 MainActivity

**Covers:** MainActivity 拆分

**Files:**
- Modify: `app/src/main/java/com/lazybody/MainActivity.java`

- [ ] **Step 1: 重写 MainActivity 使用新组件**

```java
// 简化后的 MainActivity 结构示意
public class MainActivity extends BaseActivity implements SensorEventListener {
    
    private MainViewModel viewModel;
    private MapHelper mapHelper;
    private SearchHelper searchHelper;
    private MapView mMapView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 初始化 ViewModel
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        
        // 初始化地图
        mMapView = findViewById(R.id.bdMapView);
        mapHelper = new MapHelper(this);
        mapHelper.initialize(mMapView);
        
        // 初始化搜索
        searchHelper = new SearchHelper(
            this,
            new SearchRepository(AppDatabase.getDatabase(this).searchHistoryDao()),
            this::onLocationSearchResult
        );
        
        // 设置地图点击监听
        mapHelper.setMapClickListener(this::onMapClick);
        
        // 观察 ViewModel
        observeViewModel();
        
        // 恢复上次位置
        restoreLastLocation();
    }
    
    private void observeViewModel() {
        viewModel.getCurrentMarkerPosition().observe(this, position -> {
            if (position != null) {
                mapHelper.markPosition(position, R.drawable.ic_marker_pin);
            }
        });
    }
    
    private void onMapClick(GeoPoint point) {
        viewModel.setMarkerPosition(point);
        viewModel.setMarkerName(null);
        mapHelper.markPosition(point, R.drawable.ic_marker_pin);
    }
    
    private void onLocationSearchResult(String name, String address, double lng, double lat) {
        GeoPoint point = new GeoPoint(lat, lng);
        viewModel.setMarkerPosition(point);
        viewModel.setMarkerName(name);
        mapHelper.setCenter(point);
        mapHelper.markPosition(point, R.drawable.ic_marker_pin);
    }
    
    // ... 其他方法大幅简化
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/lazybody/MainActivity.java
git commit -m "refactor: simplify MainActivity using ViewModel and helpers"
```

---

## Task 10: 拆分 Xposed 模块

**Covers:** Xposed 模块优化

**Files:**
- Create: `app/src/main/java/com/lazybody/xposed/LocationHook.kt`
- Create: `app/src/main/java/com/lazybody/xposed/GnssStatusHook.kt`
- Modify: `app/src/main/java/com/lazybody/xposed/HideMockHook.kt`

- [ ] **Step 1: 创建 LocationHook**

```kotlin
package com.lazybody.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class LocationHook {
    
    companion object {
        private val WHITELIST_PACKAGES = listOf(
            "com.lazybody",
            "android",
            "com.android.systemui",
            "com.android.phone"
        )
        
        fun hook(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
            if (lpparam.packageName in WHITELIST_PACKAGES) return
            
            hookLocationMethods()
            hookLocationManagerMethods()
        }
        
        private fun hookLocationMethods() {
            // Hook isFromMockProvider
            XposedHelpers.findAndHookMethod(
                Location::class.java,
                "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )
            
            // Hook isMock (API 31+)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(
                        Location::class.java,
                        "isMock",
                        XC_MethodReplacement.returnConstant(false)
                    )
                } catch (t: Throwable) {}
            }
            
            // Hook getExtras to clean mock flags
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
            // Hook getLastKnownLocation
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
            
            // Hook requestLocationUpdates
            // ... 更多 hook
        }
    }
}
```

- [ ] **Step 2: 创建 GnssStatusHook**

```kotlin
package com.lazybody.xposed

import android.os.Build
import de.robv.android.xposed.XposedHelpers

class GnssStatusHook {
    
    companion object {
        fun hook(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            
            hookGnssStatusGetters()
            hookGnssStatusCallback()
        }
        
        private fun hookGnssStatusGetters() {
            // Hook getSatelliteCount, getSvid, etc.
            // ... 实现
        }
        
        private fun hookGnssStatusCallback() {
            // Hook registerGnssStatusCallback
            // ... 实现
        }
    }
}
```

- [ ] **Step 3: 重构 HideMockHook**

```kotlin
package com.lazybody.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HideMockHook : IXposedHookLoadPackage {
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 委托给专门的 hook 类
        LocationHook.hook(lpparam)
        GnssStatusHook.hook(lpparam)
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/lazybody/xposed/
git commit -m "refactor: split HideMockHook into LocationHook and GnssStatusHook"
```

---

## Task 11: 迁移 ServiceGo 到 Kotlin

**Covers:** 代码现代化

**Files:**
- Create: `app/src/main/java/com/lazybody/service/ServiceGo.kt`
- Delete: `app/src/main/java/com/lazybody/service/ServiceGo.java`

- [ ] **Step 1: 创建 Kotlin 版本 ServiceGo**

```kotlin
package com.lazybody.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.lazybody.MainActivity
import com.lazybody.R
import com.lazybody.joystick.JoyStick
import com.lazybody.utils.MapUtils
import com.lazybody.utils.MockFlagClearer
import kotlin.random.Random

class ServiceGo : Service(), SensorEventListener {
    
    companion object {
        const val DEFAULT_LAT = 36.667662
        const val DEFAULT_LNG = 117.027707
        const val DEFAULT_ALT = 5.0
        const val DEFAULT_BEA = 0.0f
    }
    
    private var mCurLat = DEFAULT_LAT
    private var mCurLng = DEFAULT_LNG
    private var mCurAlt = DEFAULT_ALT
    private var mCurBea = DEFAULT_BEA
    private var mSpeed = 1.2
    
    private lateinit var mLocManager: LocationManager
    private lateinit var mSensorManager: SensorManager
    private lateinit var mLocHandlerThread: HandlerThread
    private lateinit var mLocHandler: Handler
    private lateinit var mJoyStick: JoyStick
    
    private var isStop = false
    private val random = Random
    
    // Sensor data
    private var mAccValues = FloatArray(3)
    private var mMagValues = FloatArray(3)
    private val mR = FloatArray(9)
    private val mDirectionValues = FloatArray(3)
    private var mRealBearing = 0.0f
    
    private val mBinder = ServiceGoBinder()
    
    override fun onBind(intent: Intent): IBinder = mBinder
    
    override fun onCreate() {
        super.onCreate()
        mLocManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        initSensors()
        initProviders()
        initGoLocation()
        initNotification()
        initJoyStick()
    }
    
    // ... 其余实现
}
```

- [ ] **Step 2: 删除旧 Java 文件**

```bash
git rm app/src/main/java/com/lazybody/service/ServiceGo.java
git add app/src/main/java/com/lazybody/service/ServiceGo.kt
git commit -m "refactor: migrate ServiceGo from Java to Kotlin"
```

---

## Task 12: 集成测试与验证

**Covers:** 验证

**Files:**
- 测试文件

- [ ] **Step 1: 运行单元测试**

```bash
./gradlew test
```

- [ ] **Step 2: 运行 Lint 检查**

```bash
./gradlew lint
```

- [ ] **Step 3: 构建 Debug APK**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "chore: verify refactoring with tests and lint"
```

---

## 执行顺序建议

1. **Task 1-5** (基础配置 + 数据层) - 独立且基础
2. **Task 6-9** (ViewModel + MainActivity 重构) - 依赖数据层
3. **Task 10** (Xposed 拆分) - 独立模块
4. **Task 11** (ServiceGo 迁移) - 独立模块
5. **Task 12** (验证) - 最后执行

## 注意事项

- 每个 Task 完成后运行测试验证
- 保持向后兼容，确保原有功能正常
- 数据库迁移时注意版本管理
- Xposed 模块修改需要重新编译测试
