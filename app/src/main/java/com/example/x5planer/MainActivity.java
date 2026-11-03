package com.example.x5planer;

import android.widget.Toast;
import android.webkit.WebChromeClient;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import android.provider.Settings;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;

public class MainActivity extends AppCompatActivity {

    private long downloadId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Инициализация Firebase
        FirebaseApp.initializeApp(this);

        // 2. Проверка обновлений при запуске
        checkForUpdates();
        requestLocationPermissionIfNeeded();
        requestBatteryOptimizationExemption();
        // 3. Создаем WebView программно на весь экран
        WebView webView = new WebView(this);

        // --- ВОТ ЭТОГО БЛОКА НЕ ХВАТАЛО ---
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true); // Без этого не работал JS
        webSettings.setDomStorageEnabled(true); // Без этого не работал LocalStorage

        // Подключаем мост между JS и Java
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidApp");
        webSettings.setAllowFileAccess(true);

        // Подстраховка: цвет фона и перехват ошибок
        webView.setBackgroundColor(android.graphics.Color.parseColor("#f5f7fa"));
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                android.util.Log.e("X5PLANER_LOAD", "Ошибка загрузки: " + error.getDescription());
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "Ошибка: " + error.getDescription(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // ЗАГРУЖАЕМ ФАЙЛ ИЗ ПАПКИ ASSETS
        webView.loadUrl("file:///android_asset/index.html");
        // ----------------------------------

        // 4. Канал уведомлений, разрешение и планирование офлайн-напоминаний
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        requestExactAlarmPermissionIfNeeded();
        scheduleEveningReminders(this);

        setContentView(webView);
    }
    private void requestBatteryOptimizationExemption() {
        String packageName = getPackageName();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение")
                    .setMessage("Чтобы отслеживание зоны и напоминания работали даже при закрытом приложении, отключите для X5 Planer оптимизацию батареи.")
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + packageName));
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    })
                    .setNegativeButton("Позже", null)
                    .show();
        }
    }
    private void requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                        .setTitle("Нужно разрешение")
                        .setMessage("Чтобы напоминания об отчёте приходили точно по расписанию (20:00, 21:00...), разрешите точные будильники в настройках.")
                        .setPositiveButton("Открыть настройки", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Позже", null)
                        .show();
            }
        }
    }
    private void requestLocationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 102);
        }
    }
    // --- СИСТЕМА АВТООБНОВЛЕНИЯ ---
    // В Firebase узел "update": versionCode (число), apkUrl (прямая https-ссылка на APK),
    // apkSha256 (хэш APK для проверки целостности), force (true/false, по умолчанию true —
    // обновление обязательное), message (необязательный текст окна).
    private String pendingApkUrl = null;
    private String pendingApkSha256 = null;
    private String pendingUpdateMessage = null;
    private boolean pendingUpdateForced = true;
    private boolean updateInProgress = false;
    private boolean waitingInstallPermission = false;
    private AlertDialog updateDialog = null;

    private void checkForUpdates() {
        DatabaseReference updateRef = FirebaseDatabase.getInstance().getReference("update");
        // Слушаем постоянно: если обновление выложено, пока приложение открыто, окно появится сразу
        updateRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Long remoteVersionCode = snapshot.child("versionCode").getValue(Long.class);
                    String apkUrl = snapshot.child("apkUrl").getValue(String.class);
                    String apkSha256 = snapshot.child("apkSha256").getValue(String.class);
                    Boolean force = snapshot.child("force").getValue(Boolean.class);

                    if (remoteVersionCode != null && apkUrl != null) {
                        if (!apkUrl.startsWith("https://")) {
                            android.util.Log.e("X5PLANER_UPDATE", "Отклонён небезопасный URL обновления: " + apkUrl);
                            return;
                        }
                        int currentVersionCode = getCurrentVersionCode();

                        if (remoteVersionCode > currentVersionCode) {
                            pendingApkUrl = apkUrl;
                            pendingApkSha256 = apkSha256;
                            pendingUpdateForced = force == null || force;
                            pendingUpdateMessage = snapshot.child("message").getValue(String.class);
                            showUpdateDialog(apkUrl, apkSha256);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Игнорируем ошибки сети при проверке обновлений
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из настроек "Установка неизвестных приложений" — продолжаем установку
        if (waitingInstallPermission && pendingApkUrl != null) {
            waitingInstallPermission = false;
            downloadAndInstallApk(pendingApkUrl, pendingApkSha256);
            return;
        }
        // Пользователь закрыл установщик, не обновившись — обязательное обновление спрашиваем снова
        if (pendingApkUrl != null && pendingUpdateForced && !updateInProgress) {
            showUpdateDialog(pendingApkUrl, pendingApkSha256);
        }
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 1;
        }
    }

    private void showUpdateDialog(final String apkUrl, final String apkSha256) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if (updateDialog != null && updateDialog.isShowing()) return;

                String text = (pendingUpdateMessage != null && !pendingUpdateMessage.trim().isEmpty())
                        ? pendingUpdateMessage
                        : "Вышла новая версия приложения. Обновитесь, чтобы продолжить работу.";

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Доступно обновление");
                builder.setMessage(text);
                builder.setCancelable(false);

                builder.setPositiveButton("Обновить", (dialog, which) -> downloadAndInstallApk(apkUrl, apkSha256));
                if (!pendingUpdateForced) {
                    builder.setNegativeButton("Позже", (dialog, which) -> dialog.dismiss());
                }

                updateDialog = builder.show();
            }
        });
    }

    private void downloadAndInstallApk(String url, String expectedSha256) {
        if (updateInProgress) return;

        // Android 8+: нужно разрешение "Установка неизвестных приложений" для X5 Planer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение")
                    .setMessage("Чтобы установить обновление, разрешите X5 Planer устанавливать приложения. После этого вернитесь назад — установка продолжится сама.")
                    .setCancelable(false)
                    .setPositiveButton("Открыть настройки", (d, w) -> {
                        waitingInstallPermission = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .show();
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Обновление X5 Planer");
            request.setDescription("Загрузка новой версии приложения...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);

            // Папка приложения: файл принадлежит нам, FileProvider сможет отдать его установщику.
            // Старые скачанные APK удаляем, чтобы не копились.
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && dir.listFiles() != null) {
                for (File old : dir.listFiles()) {
                    if (old.getName().endsWith(".apk")) old.delete();
                }
            }
            String fileName = "update_" + System.currentTimeMillis() + ".apk";
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

            final DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                final long expectedDownloadId = manager.enqueue(request);
                downloadId = expectedDownloadId;
                updateInProgress = true;
                showDownloadProgress(manager, expectedDownloadId);

                BroadcastReceiver onComplete = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (expectedDownloadId == id) {
                            verifyAndInstallApk(manager, id, expectedSha256);
                            try {
                                unregisterReceiver(this);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            onUpdateFailed("Не удалось начать скачивание обновления");
        }
    }

    private AlertDialog progressDialog = null;

    // Окно с процентом загрузки, обновляется раз в полсекунды
    private void showDownloadProgress(DownloadManager manager, long id) {
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Обновление X5 Planer")
                .setMessage("Скачивание... 0%")
                .setCancelable(false)
                .show();

        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!updateInProgress || downloadId != id || progressDialog == null || !progressDialog.isShowing()) return;
                Cursor c = manager.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    c.close();
                    if (total > 0) progressDialog.setMessage("Скачивание... " + (done * 100 / total) + "%");
                } else if (c != null) {
                    c.close();
                }
                handler.postDelayed(this, 500);
            }
        });
    }

    private void dismissDownloadProgress() {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        progressDialog = null;
    }

    // Сообщает об ошибке и снова предлагает обновиться
    private void onUpdateFailed(String message) {
        updateInProgress = false;
        dismissDownloadProgress();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (pendingApkUrl != null) showUpdateDialog(pendingApkUrl, pendingApkSha256);
    }

    private String computeSha256(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void verifyAndInstallApk(DownloadManager manager, long id, String expectedSha256) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        Cursor cursor = manager.query(query);

        if (cursor == null) {
            onUpdateFailed("Ошибка обновления: не удалось получить статус загрузки");
            return;
        }
        if (!cursor.moveToFirst()) {
            cursor.close();
            onUpdateFailed("Ошибка обновления: загрузка не найдена");
            return;
        }
        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
        int status = statusIndex != -1 ? cursor.getInt(statusIndex) : -1;
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            cursor.close();
            onUpdateFailed("Загрузка обновления не завершилась успешно");
            return;
        }
        int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        if (columnIndex == -1) {
            cursor.close();
            onUpdateFailed("Ошибка обновления: файл не найден");
            return;
        }
        String downloadUriString = cursor.getString(columnIndex);
        cursor.close();
        if (downloadUriString == null) {
            onUpdateFailed("Ошибка обновления: файл не найден");
            return;
        }

        File file = new File(Uri.parse(downloadUriString).getPath());

        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actualSha256 = computeSha256(file);
            if (actualSha256 == null || !actualSha256.equalsIgnoreCase(expectedSha256)) {
                android.util.Log.e("X5PLANER_UPDATE", "Хэш скачанного APK не совпал с ожидаемым — установка отменена");
                file.delete();
                onUpdateFailed("Файл обновления повреждён или подменён, установка отменена");
                return;
            }
        } else {
            android.util.Log.w("X5PLANER_UPDATE", "apkSha256 не задан в Firebase — устанавливаем без проверки целостности");
        }

        updateInProgress = false;
        dismissDownloadProgress();

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            onUpdateFailed("Не удалось запустить установку обновления");
        }
    }

    // --- ПОЛНОЭКРАННЫЙ РЕЖИМ ---
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    // --- ИНТЕРФЕЙС ДЛЯ СВЯЗИ С ВЕБ-ЧАСТЬЮ ---
    public class WebAppInterface {

        @android.webkit.JavascriptInterface
        public int getAppVersion() {
            return getCurrentVersionCode();
        }

        // Выдает уникальный ID устройства для жесткой привязки
        @android.webkit.JavascriptInterface
        public String getDeviceId() {
            return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }

        // Сохраняет сессию (Имя, Фамилию, Полигон, Роль) в память телефона
        @android.webkit.JavascriptInterface
        public void saveSession(String fName, String lName, String polygon, String role) {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            String sessionData = fName + "|" + lName + "|" + polygon + "|" + role;
            prefs.edit().putString("userData", sessionData).apply();
        }

        // Читает сессию при запуске приложения, чтобы не логиниться заново
        @android.webkit.JavascriptInterface
        public String getSession() {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            return prefs.getString("userData", "");
        }

        // Удаляет сессию при нажатии "Выйти"
        @android.webkit.JavascriptInterface
        public void clearSession() {
            SharedPreferences prefs = getSharedPreferences("AppSession", MODE_PRIVATE);
            prefs.edit().clear().apply();
        }
        // Кладёт запись в нативную очередь синхронизации и планирует WorkManager
        @android.webkit.JavascriptInterface
        public void queueForSync(String path, String jsonData) {
            enqueueSyncItem(MainActivity.this, path, jsonData);
        }

        // Отменяет оставшиеся вечерние напоминания на сегодня
        @android.webkit.JavascriptInterface
        public void cancelEveningReminders() {
            cancelTodayReminders(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void startShiftTracking() {
            Intent serviceIntent = new Intent(MainActivity.this, GeoTrackingService.class);
            androidx.core.content.ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
        }

        @android.webkit.JavascriptInterface
        public void stopShiftTracking() {
            stopService(new Intent(MainActivity.this, GeoTrackingService.class));
        }

        // Сохраняет даты, в которые у пользователя стоит смена (JSON-массив "yyyy-MM-dd").
        // Напоминание об отчёте приходит только в эти дни.
        @android.webkit.JavascriptInterface
        public void setShiftDates(String jsonArray) {
            getSharedPreferences("Reminders", MODE_PRIVATE)
                    .edit().putString("shift_dates", jsonArray == null ? "[]" : jsonArray).apply();
        }
    }
    // ---------- ОФЛАЙН-ОЧЕРЕДЬ (WorkManager) ----------

    public static void enqueueSyncItem(Context ctx, String path, String jsonData) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("SyncQueue", MODE_PRIVATE);
            String raw = prefs.getString("queue", "[]");
            JSONArray arr = new JSONArray(raw);
            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("data", jsonData);
            item.put("ts", System.currentTimeMillis());
            arr.put(item);
            prefs.edit().putString("queue", arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
                "firebase_sync",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
        );
    }

    // ---------- НАПОМИНАНИЯ (AlarmManager) ----------

    private static final int[] REMINDER_HOURS = {20, 21, 22, 23, 0};
    public static final String CHANNEL_ID = "shift_reminders";

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Напоминания об отчёте", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    public static void scheduleEveningReminders(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int hour : REMINDER_HOURS) {
            scheduleSingleReminder(ctx, am, hour);
        }
    }

    public static void scheduleSingleReminder(Context ctx, AlarmManager am, int hour) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour == 0 ? 0 : hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (hour == 0 || cal.getTimeInMillis() <= System.currentTimeMillis()) {
            // 00:00 и уже прошедшие сегодня часы -> планируем на следующие сутки
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.putExtra("hour", hour);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 2000 + hour, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    // Дата по Москве (как getTodayDateStr() в веб-части), со сдвигом в днях
    public static String getMoscowDateStr(int dayOffset) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Europe/Moscow");
        fmt.setTimeZone(tz);
        Calendar cal = Calendar.getInstance(tz);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        return fmt.format(cal.getTime());
    }

    public static void cancelTodayReminders(Context ctx) {
        String today = getMoscowDateStr(0);
        ctx.getSharedPreferences("Reminders", MODE_PRIVATE)
                .edit().putString("report_sent_date", today).apply();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int hour : REMINDER_HOURS) {
            Intent intent = new Intent(ctx, ReminderReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 2000 + hour, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }
    }
}