package com.example.x5planer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class GeoTrackingService extends Service {

    private LocationManager locationManager;
    private LocationListener locationListener;
    private LocationListener verifyListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean restartInProgress = false;
    private boolean stopped = false;
    // Служба запущена (смена идёт) — по нему index.html восстанавливает кнопку «Я НА ЛИНИИ»
    static volatile boolean isRunning = false;
    private static final int RESTART_TOGGLES = 10;
    private static final long RESTART_STEP_MS = 270;
    // Пока курьер вне зоны, перезапуск + проверка повторяются каждые 10 с
    private static final long CYCLE_MS = 10000;
    static final String INCIDENT_PREFS = "GeoIncidents";
    private long incidentStartTs = 0;
    private int incidentCycles = 0;
    private boolean incidentHadFix = false;
    private boolean incidentWarned = false;
    private static final int SERVICE_NOTIFICATION_ID = 5000;
    private static final int ZONE_WARNING_NOTIFICATION_ID = 5001;
    private static final int RESTART_NOTIFICATION_ID = 5002;
    private static final String SERVICE_CHANNEL_ID = "geo_tracking_service";
    private static final int AUTO_END_NOTIFICATION_ID = 5003;
    // Конец рабочего дня по Москве: в 20:10 приложение само снимает с линии, сбои после 20:10 не пишутся
    static final int DAY_END_MINUTE = 20 * 60 + 10;
    private static final TimeZone MOSCOW = TimeZone.getTimeZone("Europe/Moscow");

    // Сколько минут прошло с полуночи по Москве
    static int moscowMinuteOfDay(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance(MOSCOW);
        c.setTimeInMillis(ts);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    // 20:10 того же московского дня, что и ts
    static long dayEndFor(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance(MOSCOW);
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.HOUR_OF_DAY, DAY_END_MINUTE / 60);
        c.set(java.util.Calendar.MINUTE, DAY_END_MINUTE % 60);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // Рабочий день окончен (после 20:10 по Москве) — для админов и главного админа никогда
    private boolean isDayOver() {
        if (getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).getBoolean("day_end_exempt", false)) return false;
        return moscowMinuteOfDay(System.currentTimeMillis()) >= DAY_END_MINUTE;
    }

    // ---------- ГЕО+ (платная функция по ключу; у админов — всегда) ----------
    // Проверка «10 с → перезапуск → раз в минуту», запоминание точек, где пропадало и возвращалось гео,
    // напоминания о проблемных зонах и подсказка, куда отойти.
    private static final int ZONE_NOTIFICATION_ID = 5004;
    private Location lastGood = null;          // последняя точка в рабочей зоне
    private long lastGoodTs = 0;
    private double lossLat = Double.NaN, lossLng = Double.NaN; // где пропало гео в текущем сбое
    private boolean confirmPending = false;    // Гео+: гео вернулось — один раз перепроверяем через 10 с
    private boolean rebootAdvised = false;     // Гео+: совет перезагрузить телефон уже показан в этом сбое
    private long lastAgpsReset = 0;            // Гео+: когда последний раз сбрасывали A-GPS
    private long lastJamNotice = 0;
    private static final long CONFIRM_MS = 10000;

    private boolean isGeoPlus() {
        SharedPreferences p = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        return p.getBoolean("day_end_exempt", false) || p.getLong("geo_plus_until", 0) > System.currentTimeMillis();
    }

    // Точка потери гео = последняя хорошая точка, если она свежая (иначе место неизвестно)
    private void markLossPoint() {
        if (lastGood != null && System.currentTimeMillis() - lastGoodTs < 180000) {
            lossLat = lastGood.getLatitude();
            lossLng = lastGood.getLongitude();
        } else {
            lossLat = Double.NaN;
            lossLng = Double.NaN;
        }
        SharedPreferences.Editor e = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit();
        if (Double.isNaN(lossLat)) e.remove("open_loss_lat").remove("open_loss_lng");
        else e.putString("open_loss_lat", String.valueOf(lossLat)).putString("open_loss_lng", String.valueOf(lossLng));
        e.apply();
    }

    // Замер сигнала раз в точку (обычно раз в минуту) — из них в приложении строится сетка «где глушат»
    private long lastSampleTs = 0;
    private void recordGnssSample(Location l) {
        long now = System.currentTimeMillis();
        if (now - lastSampleTs < 50000) return;
        lastSampleTs = now;
        SharedPreferences prefs = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        String userKey = prefs.getString("user_key", "");
        String base = prefs.getString("incidents_path", "");
        if (userKey.isEmpty() || !base.endsWith("geo_incidents")) return;
        String path = base.substring(0, base.length() - "geo_incidents".length()) + "gnss_samples";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(MOSCOW);
        try {
            JSONObject rec = new JSONObject()
                    .put("userKey", userKey)
                    .put("ts", now)
                    .put("lat", Math.round(l.getLatitude() * 1e5) / 1e5)
                    .put("lng", Math.round(l.getLongitude() * 1e5) / 1e5)
                    .put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : -1)
                    .put("used", GnssMonitor.usedInFix)
                    .put("view", GnssMonitor.inView)
                    .put("cn0", Math.round(GnssMonitor.avgCn0));
            MainActivity.enqueueSyncItem(this, path + "/" + fmt.format(new Date(now)) + "/" + userKey + "_" + now, rec.toString());
        } catch (Exception ignored) {}
    }

    // Похоже на глушение — сбрасываем устаревшие данные спутников (A-GPS), чтобы телефон быстрее
    // поймал настоящие спутники; не чаще раза в 5 минут, иначе он только дольше ищет
    private void checkJamming() {
        if (!GnssMonitor.looksJammed()) return;
        long now = System.currentTimeMillis();
        if (now - lastAgpsReset < 5 * 60000) return;
        lastAgpsReset = now;
        resetAgps();
        if (now - lastJamNotice > 30 * 60000) {
            lastJamNotice = now;
            showZoneNotification("📡 Похоже на глушение GPS — сбросили данные спутников, телефон ищет их заново");
        }
    }

    private void resetAgps() {
        if (locationManager == null) return;
        try {
            locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null);
            locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", new Bundle());
            locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", new Bundle());
        } catch (Exception ignored) {
            // На части телефонов команда запрещена — тогда просто продолжаем обычные перезапуски
        }
    }

    // Проблемные зоны приходят из index.html: [{id, lat, lng, r, couriers, count, hintDist, hintDir}]
    private org.json.JSONArray loadZones() {
        try {
            return new org.json.JSONArray(getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).getString("geo_zones", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    @Nullable
    private JSONObject zoneAt(double lat, double lng, double extraMeters) {
        org.json.JSONArray zones = loadZones();
        float[] d = new float[1];
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) continue;
            Location.distanceBetween(lat, lng, z.optDouble("lat"), z.optDouble("lng"), d);
            if (d[0] <= z.optDouble("r", 35) + extraMeters) return z;
        }
        return null;
    }

    private static String hintText(JSONObject z) {
        int dist = z.optInt("hintDist", 0);
        String dir = z.optString("hintDir", "");
        return dist > 0 ? "Отойдите ~" + dist + " м " + dir + " — там гео возвращалось" : "";
    }

    // Один раз в день на зону: «здесь у 2+ курьеров пропадало гео»
    private void checkZoneReminder(Location loc) {
        if (!isGeoPlus()) return;
        JSONObject z = zoneAt(loc.getLatitude(), loc.getLongitude(), 20);
        if (z == null || z.optInt("couriers", 0) < 2) return;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(MOSCOW);
        String key = "zone_notified_" + z.optString("id") + "_" + fmt.format(new Date());
        SharedPreferences p = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        if (p.getBoolean(key, false)) return;
        p.edit().putBoolean(key, true).apply();
        String hint = hintText(z);
        showZoneNotification("⚠️ Здесь у " + z.optInt("couriers") + " курьеров пропадало гео"
                + (hint.isEmpty() ? "" : ". Если пропадёт — " + hint.substring(0, 1).toLowerCase() + hint.substring(1)));
    }

    // Гео пропало в известной зоне, где есть подсказка — сразу говорим, куда отойти
    private void notifyHintIfKnownZone() {
        if (Double.isNaN(lossLat) || !isGeoPlus()) return;
        JSONObject z = zoneAt(lossLat, lossLng, 15);
        if (z == null) return;
        String hint = hintText(z);
        if (!hint.isEmpty()) showZoneNotification("📍 Тут гео часто пропадает. " + hint);
    }

    private void showZoneNotification(String text) {
        boolean canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!canNotify) return;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("X5 Planer · Гео+")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(ZONE_NOTIFICATION_ID, builder.build());
    }

    private final Runnable autoEndRunnable = this::autoEndIfDayOver;

    // 20:10 наступило — закрываем открытый сбой временем 20:10 и выходим с линии
    private void autoEndIfDayOver() {
        if (stopped) return;
        if (!isDayOver()) {
            scheduleAutoEnd();
            return;
        }
        if (incidentStartTs != 0) {
            writeIncident(this, incidentStartTs, System.currentTimeMillis(), incidentCycles, incidentHadFix, false);
            incidentStartTs = 0;
            getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit()
                    .remove("open_start").remove("open_cycles").remove("open_had_fix").apply();
        }
        showAutoEndNotification();
        stopSelf();
    }

    private void scheduleAutoEnd() {
        handler.removeCallbacks(autoEndRunnable);
        long now = System.currentTimeMillis();
        long end = dayEndFor(now);
        if (end > now) handler.postDelayed(autoEndRunnable, end - now + 1000);
    }

    private void showAutoEndNotification() {
        boolean canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!canNotify) return;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("X5 Planer")
                .setContentText("🏁 20:10 — рабочий день окончен, вы вышли с линии")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(AUTO_END_NOTIFICATION_ID, builder.build());
    }

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
        isRunning = true;
        // Свежий запуск службы — если прошлый сбой не успел закрыться, отправляем его
        if (locationListener == null && incidentStartTs == 0) flushStaleIncident();
        // Уже после 20:10 (например, система перезапустила службу вечером) — сразу снимаем с линии
        if (isDayOver()) {
            autoEndIfDayOver();
            return START_NOT_STICKY;
        }
        startLocationUpdates();
        scheduleAutoEnd();
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

        // Служба уже отслеживает — не регистрируем второй слушатель (иначе старый остаётся
        // подписанным навсегда и шлёт уведомления даже после выхода с линии)
        if (locationListener != null) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (stopped || restartInProgress) return;
                // Таймер мог «проспать» 20:10 в режиме энергосбережения — проверяем при каждой точке
                if (isDayOver()) { autoEndIfDayOver(); return; }
                GnssMonitor.onLocation(location);
                if (isGeoPlus()) { recordGnssSample(location); checkJamming(); }
                if (isInZone(location)) {
                    lastGood = location;
                    lastGoodTs = System.currentTimeMillis();
                    checkZoneReminder(location);
                } else {
                    // Вне зоны — сначала перезапускаем определение местоположения, потом перепроверяем
                    restartLocationAndRecheck();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        // Гео как в Google Картах: сервисы Google (Fused Location). Нет сервисов Google — GPS/сеть напрямую.
        try {
            if (com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
                    == com.google.android.gms.common.ConnectionResult.SUCCESS) {
                fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);
                fusedCallback = new com.google.android.gms.location.LocationCallback() {
                    @Override
                    public void onLocationResult(@androidx.annotation.NonNull com.google.android.gms.location.LocationResult result) {
                        Location l = result.getLastLocation();
                        if (l != null && locationListener != null) locationListener.onLocationChanged(l);
                    }
                };
            }
        } catch (Throwable t) {
            fused = null;
        }

        if (!registerUpdates()) { stopSelf(); return; }
        GnssMonitor.start(this);
    }

    private com.google.android.gms.location.FusedLocationProviderClient fused;
    private com.google.android.gms.location.LocationCallback fusedCallback;
    private com.google.android.gms.tasks.CancellationTokenSource freshFixCts;
    private int checkSeq = 0; // номер проверки — чтобы опоздавший ответ не закрыл следующую

    private void unregisterUpdates() {
        if (fused != null && fusedCallback != null) fused.removeLocationUpdates(fusedCallback);
        else if (locationManager != null && locationListener != null) locationManager.removeUpdates(locationListener);
    }

    @SuppressWarnings("MissingPermission")
    private boolean registerUpdates() {
        if (stopped) return true;
        if (fused != null) {
            try {
                com.google.android.gms.location.LocationRequest req =
                        new com.google.android.gms.location.LocationRequest.Builder(
                                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 60000).build();
                fused.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper());
                return true;
            } catch (SecurityException e) {
                return false;
            }
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 0, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 0, locationListener);
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean isInZone(Location location) {
        return isPointInPolygon(location.getLatitude(), location.getLongitude(), WORK_ZONE_POLYGON);
    }

    // Цикл раз в 10 с, пока курьер вне зоны: 10 переключений (выкл/вкл) за 2,7 с, последнее —
    // включение, затем ждём свежую точку до конца 10-секундного окна. В зоне — снова раз в минуту.
    private void restartLocationAndRecheck() {
        if (stopped) return;
        if (isDayOver()) { autoEndIfDayOver(); return; }
        restartInProgress = true;
        if (incidentStartTs == 0) { startIncident(); markLossPoint(); }
        incidentCycles++;
        saveOpenIncident();
        if (isGeoPlus()) {
            checkJamming();
            if (!rebootAdvised && System.currentTimeMillis() - incidentStartTs > 10 * 60000) {
                rebootAdvised = true;
                showZoneNotification("🔁 Гео не восстанавливается больше 10 минут — перезагрузите телефон");
            }
        }
        showRestartNotification("🔄 Перезапуск геолокации… (попытка " + incidentCycles + ")", 0);
        for (int step = 1; step <= RESTART_TOGGLES; step++) {
            final boolean turnOn = step % 2 == 0;
            handler.postDelayed(() -> {
                if (turnOn) registerUpdates();
                else unregisterUpdates();
            }, (step - 1) * RESTART_STEP_MS);
        }
        handler.postDelayed(this::requestFreshFix, RESTART_TOGGLES * RESTART_STEP_MS);
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void requestFreshFix() {
        if (stopped) return;
        final int seq = ++checkSeq;
        if (fused != null) {
            // Свежая точка от сервисов Google, как в Google Картах
            freshFixCts = new com.google.android.gms.tasks.CancellationTokenSource();
            try {
                fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, freshFixCts.getToken())
                        .addOnSuccessListener(loc -> {
                            if (seq == checkSeq && loc != null) finishRecheck(isInZone(loc), loc);
                        });
            } catch (SecurityException e) {
                finishRecheck(false, null);
                return;
            }
            handler.postDelayed(recheckTimeout, CYCLE_MS - RESTART_TOGGLES * RESTART_STEP_MS);
            return;
        }
        verifyListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                finishRecheck(isInZone(location), location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, verifyListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, verifyListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            finishRecheck(false, null);
            return;
        }
        // Свежая точка не пришла до конца 10-секундного окна — считаем, что сбой GPS
        handler.postDelayed(recheckTimeout, CYCLE_MS - RESTART_TOGGLES * RESTART_STEP_MS);
    }

    private final Runnable recheckTimeout = () -> finishRecheck(false, null);

    private void finishRecheck(boolean inZone, @Nullable Location location) {
        if (stopped || !restartInProgress) return;
        handler.removeCallbacks(recheckTimeout);
        checkSeq++; // ответ на эту проверку больше не ждём
        if (freshFixCts != null) { freshFixCts.cancel(); freshFixCts = null; }
        if (verifyListener != null) {
            locationManager.removeUpdates(verifyListener);
            verifyListener = null;
        }
        if (location != null) incidentHadFix = true;
        if (location != null) GnssMonitor.onLocation(location);
        if (inZone && isGeoPlus() && !confirmPending) {
            // Гео+: гео вернулось — человек встал на место; один раз перепроверяем через 10 с: на месте — дальше раз в минуту, улетело — снова перезапуски
            confirmPending = true;
            if (location != null) { lastGood = location; lastGoodTs = System.currentTimeMillis(); }
            showRestartNotification("📍 Гео вернулось — перепроверка через 10 секунд…", 0);
            handler.postDelayed(() -> { if (!stopped) requestFreshFix(); }, CONFIRM_MS);
            return;
        }
        confirmPending = false;
        if (inZone) {
            restartInProgress = false;
            if (location != null) { lastGood = location; lastGoodTs = System.currentTimeMillis(); }
            finishIncident(true, location);
            showRestartNotification("✅ Геолокация перезапущена — вы в рабочей зоне", 10000);
        } else {
            // Всё ещё вне зоны (или нет точки) — предупреждаем один раз за сбой и сразу
            // запускаем следующий 10-секундный цикл
            if (!incidentWarned) {
                incidentWarned = true;
                showZoneWarningNotification();
            }
            handler.post(this::restartLocationAndRecheck);
        }
    }

    // ---------- ЖУРНАЛ СБОЕВ ГЕО ----------
    // Сбой = с первой точки вне зоны до возвращения в зону (или выхода с линии). Запись уходит
    // в офлайн-очередь (MainActivity.enqueueSyncItem) и досылается, когда появится сеть.

    private void startIncident() {
        incidentStartTs = System.currentTimeMillis();
        incidentCycles = 0;
        incidentHadFix = false;
        incidentWarned = false;
        rebootAdvised = false;
    }

    private void saveOpenIncident() {
        getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit()
                .putLong("open_start", incidentStartTs)
                .putInt("open_cycles", incidentCycles)
                .putBoolean("open_had_fix", incidentHadFix)
                .putLong("last_seen", System.currentTimeMillis())
                .apply();
    }

    private void finishIncident(boolean resolved) { finishIncident(resolved, null); }

    // fix — точка, где гео вернулось (нужна для подсказки «куда отойти»)
    private void finishIncident(boolean resolved, @Nullable Location fix) {
        if (incidentStartTs == 0) return;
        writeIncident(this, incidentStartTs, System.currentTimeMillis(), incidentCycles, incidentHadFix, resolved,
                fix != null ? fix.getLatitude() : Double.NaN, fix != null ? fix.getLongitude() : Double.NaN);
        incidentStartTs = 0;
        incidentCycles = 0;
        incidentWarned = false;
        lossLat = Double.NaN; lossLng = Double.NaN;
        getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit()
                .remove("open_start").remove("open_cycles").remove("open_had_fix")
                .remove("open_loss_lat").remove("open_loss_lng").apply();
    }

    // Сбой, который остался незакрытым (приложение убила система) — дописываем при следующем запуске
    private void flushStaleIncident() {
        SharedPreferences prefs = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        long start = prefs.getLong("open_start", 0);
        if (start == 0) return;
        long end = prefs.getLong("last_seen", start);
        writeIncident(this, start, Math.max(end, start), prefs.getInt("open_cycles", 0),
                prefs.getBoolean("open_had_fix", false), false);
        prefs.edit().remove("open_start").remove("open_cycles").remove("open_had_fix")
                .remove("open_loss_lat").remove("open_loss_lng").apply();
    }

    private static void writeIncident(Context ctx, long start, long end, int cycles, boolean hadFix, boolean resolved) {
        writeIncident(ctx, start, end, cycles, hadFix, resolved, Double.NaN, Double.NaN);
    }

    private static void writeIncident(Context ctx, long start, long end, int cycles, boolean hadFix, boolean resolved,
                                      double fixLat, double fixLng) {
        SharedPreferences prefs = ctx.getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        String userKey = prefs.getString("user_key", "");
        String basePath = prefs.getString("incidents_path", "");
        if (userKey.isEmpty() || basePath.isEmpty()) return;
        // Сбои после 20:10 не считаются; начавшийся раньше сбой обрезаем по 20:10 (админов это не касается)
        if (!prefs.getBoolean("day_end_exempt", false)) {
            if (moscowMinuteOfDay(start) >= DAY_END_MINUTE) return;
            end = Math.min(end, dayEndFor(start));
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
        String date = fmt.format(new Date(start));
        try {
            JSONObject rec = new JSONObject();
            rec.put("userKey", userKey);
            rec.put("name", prefs.getString("user_name", userKey));
            rec.put("polygon", prefs.getString("polygon", ""));
            rec.put("start", start);
            rec.put("end", end);
            rec.put("durationSec", Math.max(0, (end - start) / 1000));
            rec.put("attempts", cycles);
            // no_fix — телефон вообще не выдал координаты; out_of_zone — координаты были, но вне зоны
            rec.put("kind", hadFix ? "out_of_zone" : "no_fix");
            rec.put("resolved", resolved);
            // Где пропало гео (последняя хорошая точка) и где вернулось — для карты проблемных зон
            String lossLatS = prefs.getString("open_loss_lat", null), lossLngS = prefs.getString("open_loss_lng", null);
            if (lossLatS != null && lossLngS != null) {
                rec.put("lossLat", Double.parseDouble(lossLatS));
                rec.put("lossLng", Double.parseDouble(lossLngS));
            }
            if (!Double.isNaN(fixLat) && !Double.isNaN(fixLng)) {
                rec.put("fixLat", fixLat);
                rec.put("fixLng", fixLng);
            }
            MainActivity.enqueueSyncItem(ctx, basePath + "/" + date + "/" + userKey + "_" + start, rec.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Тихое уведомление, чтобы было видно, что перезапуск сработал; timeoutMs = 0 — висит до замены
    private void showRestartNotification(String text, long timeoutMs) {
        boolean canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!canNotify) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("X5 Planer")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
        if (timeoutMs > 0) builder.setTimeoutAfter(timeoutMs);

        NotificationManagerCompat.from(this).notify(RESTART_NOTIFICATION_ID, builder.build());
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
        // Вышел с линии посреди сбоя — сбой закрывается без возврата в зону
        finishIncident(false);
        stopped = true;
        isRunning = false;
        restartInProgress = false;
        handler.removeCallbacksAndMessages(null);
        if (freshFixCts != null) { freshFixCts.cancel(); freshFixCts = null; }
        if (fused != null && fusedCallback != null) fused.removeLocationUpdates(fusedCallback);
        if (locationListener != null) GnssMonitor.stop(this);
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (locationManager != null && verifyListener != null) {
            locationManager.removeUpdates(verifyListener);
        }
        locationListener = null;
        verifyListener = null;
        // Вышли с линии — убираем уведомления о перезапуске и о выходе из зоны
        NotificationManagerCompat.from(this).cancel(RESTART_NOTIFICATION_ID);
        NotificationManagerCompat.from(this).cancel(ZONE_WARNING_NOTIFICATION_ID);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}