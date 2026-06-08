package com.lazy.anywhere.utils;

import android.content.Context;
import android.location.Location;

import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;

public class GCJ02LocationProvider implements IMyLocationProvider {

    private final GpsMyLocationProvider mProvider;
    private IMyLocationConsumer mConsumer;

    public GCJ02LocationProvider(Context context) {
        mProvider = new GpsMyLocationProvider(context);
    }

    @Override
    public boolean startLocationProvider(IMyLocationConsumer myLocationConsumer) {
        mConsumer = myLocationConsumer;
        return mProvider.startLocationProvider(new IMyLocationConsumer() {
            @Override
            public void onLocationChanged(Location location, IMyLocationProvider source) {
                if (location != null) {
                    double[] gcj02 = MapUtils.wgs84togcj02(location.getLongitude(), location.getLatitude());
                    Location convertedLocation = new Location(location);
                    convertedLocation.setLongitude(gcj02[0]);
                    convertedLocation.setLatitude(gcj02[1]);
                    if (mConsumer != null) {
                        mConsumer.onLocationChanged(convertedLocation, GCJ02LocationProvider.this);
                    }
                }
            }
        });
    }

    @Override
    public void stopLocationProvider() {
        mProvider.stopLocationProvider();
    }

    @Override
    public Location getLastKnownLocation() {
        Location location = mProvider.getLastKnownLocation();
        if (location != null) {
            double[] gcj02 = MapUtils.wgs84togcj02(location.getLongitude(), location.getLatitude());
            Location convertedLocation = new Location(location);
            convertedLocation.setLongitude(gcj02[0]);
            convertedLocation.setLatitude(gcj02[1]);
            return convertedLocation;
        }
        return null;
    }

    @Override
    public void destroy() {
        mProvider.destroy();
    }
}
