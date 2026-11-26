package com.example.x5planer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONObject;

// Гео+: что видит GPS-приёмник прямо сейчас — спутники (сколько в небе и сколько реально в расчёте),
// сила сигнала и погрешность. По ним видно глушение: спутников мало / сигнал слабый / погрешность > 75 м.
// Источников двое: служба «Я на линии» и открытая в приложении карта (режим предпросмотра).
final class GnssMonitor {

    static final float JAM_ACCURACY_M = 75f;   // хуже 75 м — похоже на глушение
    static final int JAM_MIN_USED = 4;         // меньше 4 спутников в расчёте — координату не посчитать нормально

    static volatile int inView = 0;
    static volatile int usedInFix = 0;
    static volatile float avgCn0 = 0f;         // средняя сила сигнала 4 лучших спутников, дБ-Гц
    static volatile float accuracy = -1f;      // радиус погрешности последней точки, м
    static volatile long statusTs = 0, fixTs = 0;
    private static volatile String satsJson = "[]";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static GnssStatus.Callback callback;
    private static int refs = 0;
    private static LocationListener previewListener;

    private GnssMonitor() {}

    static synchronized void start(Context ctx) {
        if (refs++ > 0) return;
        LocationManager lm = (LocationManager) ctx.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        callback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) { update(status); }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lm.registerGnssStatusCallback(ctx.getMainExecutor(), callback);
            else lm.registerGnssStatusCallback(callback, main);
        } catch (SecurityException ignored) {}
    }

    static synchronized void stop(Context ctx) {
        if (refs == 0 || --refs > 0) return;
        LocationManager lm = (LocationManager) ctx.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && callback != null) lm.unregisterGnssStatusCallback(callback);
        callback = null;
    }

    // Открыта карта в приложении — держим GPS включённым, чтобы спутники обновлялись каждую секунду
    static synchronized void startPreview(Context ctx) {
        if (previewListener != null) return;
        start(ctx);
        LocationManager lm = (LocationManager) ctx.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        previewListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { onLocation(l); }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, previewListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    static synchronized void stopPreview(Context ctx) {
        if (previewListener == null) return;
        LocationManager lm = (LocationManager) ctx.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(previewListener);
        previewListener = null;
        stop(ctx);
    }

    static void onLocation(Location l) {
        if (l == null) return;
        if (l.hasAccuracy()) accuracy = l.getAccuracy();
        fixTs = System.currentTimeMillis();
    }

    private static void update(GnssStatus st) {
        int n = st.getSatelliteCount(), used = 0;
        float[] cn0 = new float[n];
        JSONArray arr = new JSONArray();
        for (int i = 0; i < n; i++) {
            cn0[i] = st.getCn0DbHz(i);
            boolean u = st.usedInFix(i);
            if (u) used++;
            try {
                arr.put(new JSONObject()
                        .put("c", st.getConstellationType(i))
                        .put("id", st.getSvid(i))
                        .put("az", Math.round(st.getAzimuthDegrees(i)))
                        .put("el", Math.round(st.getElevationDegrees(i)))
                        .put("s", Math.round(cn0[i]))
                        .put("u", u));
            } catch (Exception ignored) {}
        }
        java.util.Arrays.sort(cn0);
        float sum = 0; int k = 0;
        for (int i = n - 1; i >= 0 && k < 4; i--, k++) sum += cn0[i];
        inView = n;
        usedInFix = used;
        avgCn0 = k > 0 ? sum / k : 0;
        statusTs = System.currentTimeMillis();
        satsJson = arr.toString();
    }

    // Свежие данные (не старше 2 минут) и признаки глушения
    static boolean looksJammed() {
        long now = System.currentTimeMillis();
        boolean badAcc = now - fixTs < 120000 && accuracy > JAM_ACCURACY_M;
        boolean fewSats = now - statusTs < 120000 && inView > 0 && usedInFix < JAM_MIN_USED;
        return badAcc || fewSats;
    }

    static String snapshotJson() {
        try {
            return new JSONObject()
                    .put("inView", inView).put("used", usedInFix)
                    .put("cn0", Math.round(avgCn0 * 10) / 10.0)
                    .put("acc", accuracy < 0 ? JSONObject.NULL : Math.round(accuracy))
                    .put("statusTs", statusTs).put("fixTs", fixTs)
                    .put("jammed", looksJammed())
                    .put("sats", new JSONArray(satsJson))
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
