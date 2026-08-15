package com.example.x5planer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class GeoTrackingService extends Service {

    private LocationManager locationManager;
    private LocationListener locationListener;
    private static final int SERVICE_NOTIFICATION_ID = 5000;
    private static final int ZONE_WARNING_NOTIFICATION_ID = 5001;
    private static final String SERVICE_CHANNEL_ID = "geo_tracking_service";

    // Та же геозона (Дериглазова), что и в index.html — если полигон поменяется, обновляйте в обоих местах
    private static final double[][] WORK_ZONE_POLYGON = {
            {51.80749199050242, 36.144140101259815}, {51.80685521311555, 36.145062781087596},
            {51.80642405665246, 36.1459532744098}, {51.80518239754816, 36.147402003054445},
            {51.80367544538965, 36.1498689647891}, {51.80264908946671, 36.15133210969046},
            {51.80169832464945, 36.15257263145909}, {51.80071338653635, 36.153925805799545},
            {51.799559047339905, 36.1554352201124}, {51.798736396773684, 36.15655101897391},
            {51.79758473529325, 36.158173083803725}, {51.796612147289885, 36.159689874190775},
            {51.79532870966425, 36.16143464994365}, {51.793991458434405, 36.163575388498394},
            {51.79268768797227, 36.16531848857675}, {51.79130248552187, 36.167579592071704},
            {51.790312104303155, 36.168365478109756}, {51.79076456474893, 36.172114536117235},
            {51.79106845917259, 36.17437362622809}, {51.79137377791515, 36.17690563162852},
            {51.78972996966831, 36.1794966455493}, {51.79007502289635, 36.18127763219361},
            {51.79069876626353, 36.18325173787171}, {51.791652419367104, 36.18503808925936},
            {51.79274078535188, 36.18484497034162}, {51.79307254520284, 36.185746192499},
            {51.79331141078438, 36.187613009825}, {51.79360344675194, 36.18922233532603},
            {51.793844175631925, 36.19155786929497}, {51.794250098985216, 36.194844245058015},
            {51.8129623591352, 36.17939069295548}, {51.81562704987379, 36.179644168927965},
            {51.81864651461349, 36.17652207788276}, {51.81910242170012, 36.170605123956456},
            {51.8200730755862, 36.163955927027324}, {51.82210217852677, 36.161933538971454},
            {51.825157264071166, 36.16098135222749}, {51.82486972870627, 36.157894133665415},
            {51.82375567529608, 36.15325927654567}, {51.822342492659175, 36.14851444786576},
            {51.821160523782204, 36.14453941311192}, {51.816301613470614, 36.148023604835096},
            {51.811418718089726, 36.1464571948949}, {51.80859858359683, 36.14496186217825}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createServiceChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) { stopSelf(); return; }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                boolean inZone = isPointInPolygon(location.getLatitude(), location.getLongitude(), WORK_ZONE_POLYGON);
                if (!inZone) {
                    showZoneWarningNotification();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
        } catch (SecurityException e) {
            stopSelf();
        }
    }

    private boolean isPointInPolygon(double lat, double lng, double[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon[i][0], yi = polygon[i][1];
            double xj = polygon[j][0], yj = polygon[j][1];
            boolean intersect = ((yi > lng) != (yj > lng)) &&
                    (lat < (xj - xi) * (lng - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private void showZoneWarningNotification() {
        boolean canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!canNotify) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("X5 Planer")
                .setContentText("⚠️ Вы покинули рабочую зону или произошел сбой GPS")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(ZONE_WARNING_NOTIFICATION_ID, builder.build());
    }

    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    SERVICE_CHANNEL_ID, "Отслеживание смены", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("X5 Planer")
                .setContentText("Отслеживание вашего местоположения на смене")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}