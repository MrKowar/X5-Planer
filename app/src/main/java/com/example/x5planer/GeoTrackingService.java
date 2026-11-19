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
                if (!isInZone(location)) {
                    // Вне зоны — сначала перезапускаем определение местоположения, потом перепроверяем
                    restartLocationAndRecheck();
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        if (!registerUpdates()) stopSelf();
    }

    private boolean registerUpdates() {
        if (stopped) return true;
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
        if (incidentStartTs == 0) startIncident();
        incidentCycles++;
        saveOpenIncident();
        showRestartNotification("🔄 Перезапуск геолокации… (попытка " + incidentCycles + ")", 0);
        for (int step = 1; step <= RESTART_TOGGLES; step++) {
            final boolean turnOn = step % 2 == 0;
            handler.postDelayed(() -> {
                if (turnOn) registerUpdates();
                else locationManager.removeUpdates(locationListener);
            }, (step - 1) * RESTART_STEP_MS);
        }
        handler.postDelayed(this::requestFreshFix, RESTART_TOGGLES * RESTART_STEP_MS);
    }

    @SuppressWarnings("deprecation")
    private void requestFreshFix() {
        if (stopped) return;
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
        if (verifyListener != null) {
            locationManager.removeUpdates(verifyListener);
            verifyListener = null;
        }
        if (location != null) incidentHadFix = true;
        if (inZone) {
            restartInProgress = false;
            finishIncident(true);
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
    }

    private void saveOpenIncident() {
        getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit()
                .putLong("open_start", incidentStartTs)
                .putInt("open_cycles", incidentCycles)
                .putBoolean("open_had_fix", incidentHadFix)
                .putLong("last_seen", System.currentTimeMillis())
                .apply();
    }

    private void finishIncident(boolean resolved) {
        if (incidentStartTs == 0) return;
        writeIncident(this, incidentStartTs, System.currentTimeMillis(), incidentCycles, incidentHadFix, resolved);
        incidentStartTs = 0;
        incidentCycles = 0;
        incidentWarned = false;
        getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE).edit()
                .remove("open_start").remove("open_cycles").remove("open_had_fix").apply();
    }

    // Сбой, который остался незакрытым (приложение убила система) — дописываем при следующем запуске
    private void flushStaleIncident() {
        SharedPreferences prefs = getSharedPreferences(INCIDENT_PREFS, MODE_PRIVATE);
        long start = prefs.getLong("open_start", 0);
        if (start == 0) return;
        long end = prefs.getLong("last_seen", start);
        writeIncident(this, start, Math.max(end, start), prefs.getInt("open_cycles", 0),
                prefs.getBoolean("open_had_fix", false), false);
        prefs.edit().remove("open_start").remove("open_cycles").remove("open_had_fix").apply();
    }

    private static void writeIncident(Context ctx, long start, long end, int cycles, boolean hadFix, boolean resolved) {
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