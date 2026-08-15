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
    private void checkForUpdates() {
        DatabaseReference updateRef = FirebaseDatabase.getInstance().getReference("update");
        updateRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Long remoteVersionCode = snapshot.child("versionCode").getValue(Long.class);
                    String apkUrl = snapshot.child("apkUrl").getValue(String.class);

                    if (remoteVersionCode != null && apkUrl != null) {
                        int currentVersionCode = getCurrentVersionCode();

                        if (remoteVersionCode > currentVersionCode) {
                            showUpdateDialog(apkUrl);
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

    private void showUpdateDialog(final String apkUrl) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Доступно обновление");
                builder.setMessage("Вышла новая версия приложения. Рекомендуем обновиться для продолжения работы.");
                builder.setCancelable(false);

                builder.setPositiveButton("Обновить", (dialog, which) -> downloadAndInstallApk(apkUrl));
                builder.setNegativeButton("Позже", (dialog, which) -> dialog.dismiss());

                builder.show();
            }
        });
    }

    private void downloadAndInstallApk(String url) {
        try {
            Toast.makeText(this, "Скачивание обновления...", Toast.LENGTH_LONG).show();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Обновление X5 Planer");
            request.setDescription("Загрузка новой версии приложения...");

            // Скрываем загрузку из верхней шторки
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

            String fileName = "update_" + System.currentTimeMillis() + ".apk";
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            final DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                downloadId = manager.enqueue(request);

                BroadcastReceiver onComplete = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (downloadId == id) {
                            installApk(manager, id);
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
        }
    }

    private void installApk(DownloadManager manager, long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        Cursor cursor = manager.query(query);

        if (cursor != null && cursor.moveToFirst()) {
            int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            if (columnIndex != -1) {
                String downloadUriString = cursor.getString(columnIndex);
                cursor.close();

                if (downloadUriString != null) {
                    Uri uri = Uri.parse(downloadUriString);
                    File file = new File(uri.getPath());

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
                        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
                    }

                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                cursor.close();
            }
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

    public static void cancelTodayReminders(Context ctx) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        String today = fmt.format(new java.util.Date());
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