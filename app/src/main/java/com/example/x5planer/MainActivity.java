package com.example.x5planer;

import android.widget.Toast;
import android.webkit.WebChromeClient;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.pm.PackageInstaller;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentRef = new java.lang.ref.WeakReference<>(this);

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
    private long pendingVersionCode = 0;
    private String pendingApkUrl = null;
    private String pendingApkSha256 = null;
    private String pendingUpdateMessage = null;
    private boolean pendingUpdateForced = true;
    private boolean updateInProgress = false;
    private boolean waitingInstallPermission = false;
    private DatabaseReference updateListenerRef = null;
    private ValueEventListener updateListener = null;

    // Экран пересоздаётся (поворот, смена темы) — снимаем слушатель старого экрана,
    // иначе окно обновления открывается дважды, одно поверх другого
    @Override
    protected void onDestroy() {
        if (updateListenerRef != null && updateListener != null) {
            updateListenerRef.removeEventListener(updateListener);
        }
        if (currentRef != null && currentRef.get() == this) currentRef = null;
        super.onDestroy();
    }

    // Открытый экран — через него UpdateInstallReceiver показывает окна установки
    private static java.lang.ref.WeakReference<MainActivity> currentRef = null;

    static MainActivity current() {
        MainActivity a = currentRef != null ? currentRef.get() : null;
        return (a != null && !a.isFinishing() && !a.isDestroyed()) ? a : null;
    }

    private void checkForUpdates() {
        DatabaseReference updateRef = FirebaseDatabase.getInstance().getReference("update");
        updateListenerRef = updateRef;
        // Слушаем постоянно: если обновление выложено, пока приложение открыто, окно появится сразу
        updateListener = updateRef.addValueEventListener(new ValueEventListener() {
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
                            pendingVersionCode = remoteVersionCode;
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

    // Скачанный и проверенный APK — при повторной попытке ставим сразу, без новой загрузки
    private File readyApkFile = null;
    private String readyApkSha256 = null;
    // Окно установки Android открыто — по возвращении проверяем, обновилось ли приложение
    private boolean installerLaunched = false;

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из настроек "Установка неизвестных приложений" — продолжаем установку сами
        if (waitingInstallPermission && pendingApkUrl != null) {
            waitingInstallPermission = false;
            downloadAndInstallApk(pendingApkUrl, pendingApkSha256);
            return;
        }
        // Вернулись из установщика, а приложение не обновилось (нажали «Отмена» или
        // «Не устанавливать» в Google Play Protect) — подсказываем, а не гоняем по кругу
        if (installerLaunched) {
            installerLaunched = false;
            if (pendingApkUrl != null && !updateInProgress) showInstallCancelled();
            return;
        }
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

    // ---------- Карточка обновления: одна большая кнопка «Обновить», всё остальное — внутри ----------
    private android.app.Dialog updateSheet = null;
    private android.widget.TextView sheetIcon, sheetTitle, sheetText, sheetLink, sheetLater;
    private android.widget.ProgressBar sheetProgress;
    private android.widget.Button sheetButton;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void ensureUpdateSheet() {
        if (updateSheet != null) return;
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(20));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.WHITE);
        bg.setCornerRadius(dp(28));
        card.setBackground(bg);
        card.setElevation(dp(12));

        sheetIcon = new android.widget.TextView(this);
        sheetIcon.setTextSize(44);
        sheetIcon.setGravity(android.view.Gravity.CENTER);
        card.addView(sheetIcon);

        sheetTitle = new android.widget.TextView(this);
        sheetTitle.setTextSize(21);
        sheetTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sheetTitle.setTextColor(android.graphics.Color.parseColor("#1F2937"));
        sheetTitle.setGravity(android.view.Gravity.CENTER);
        sheetTitle.setPadding(0, dp(10), 0, dp(8));
        card.addView(sheetTitle);

        sheetText = new android.widget.TextView(this);
        sheetText.setTextSize(15);
        sheetText.setTextColor(android.graphics.Color.parseColor("#6B7280"));
        sheetText.setGravity(android.view.Gravity.CENTER);
        sheetText.setLineSpacing(0, 1.15f);
        card.addView(sheetText);

        sheetProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        sheetProgress.setMax(100);
        sheetProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#22C55E")));
        android.widget.LinearLayout.LayoutParams pl = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pl.topMargin = dp(18);
        card.addView(sheetProgress, pl);

        sheetButton = new android.widget.Button(this);
        sheetButton.setAllCaps(false);
        sheetButton.setTextSize(18);
        sheetButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sheetButton.setTextColor(android.graphics.Color.WHITE);
        sheetButton.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{android.graphics.Color.parseColor("#22C55E"), android.graphics.Color.parseColor("#16A34A")});
        btnBg.setCornerRadius(dp(28));
        sheetButton.setBackground(btnBg);
        android.widget.LinearLayout.LayoutParams bl = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        bl.topMargin = dp(22);
        card.addView(sheetButton, bl);

        sheetLink = new android.widget.TextView(this);
        sheetLink.setTextSize(13);
        sheetLink.setTextColor(android.graphics.Color.parseColor("#2563EB"));
        sheetLink.setGravity(android.view.Gravity.CENTER);
        sheetLink.setPadding(0, dp(14), 0, 0);
        sheetLink.setPaintFlags(sheetLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        sheetLink.setText("Не получается? Скачать через браузер");
        sheetLink.setOnClickListener(v -> openUpdateInBrowser());
        card.addView(sheetLink);

        sheetLater = new android.widget.TextView(this);
        sheetLater.setTextSize(14);
        sheetLater.setTextColor(android.graphics.Color.parseColor("#9CA3AF"));
        sheetLater.setGravity(android.view.Gravity.CENTER);
        sheetLater.setPadding(0, dp(12), 0, 0);
        sheetLater.setText("Позже");
        sheetLater.setOnClickListener(v -> updateSheet.dismiss());
        card.addView(sheetLater);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setPadding(dp(20), 0, dp(20), 0);
        root.addView(card, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));

        updateSheet = new android.app.Dialog(this);
        updateSheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        updateSheet.setContentView(root);
        updateSheet.setCancelable(false);
        if (updateSheet.getWindow() != null) {
            updateSheet.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            updateSheet.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // Одна функция рисует любое состояние карточки. buttonText == null — кнопка неактивна (идёт загрузка/установка)
    private void renderUpdateSheet(String icon, String title, String text, int progress,
                                   String buttonText, Runnable onButton, boolean showBrowserLink) {
        if (isFinishing() || isDestroyed()) return;
        ensureUpdateSheet();
        sheetIcon.setText(icon);
        sheetTitle.setText(title);
        sheetText.setText(text);
        sheetProgress.setVisibility(progress >= 0 ? View.VISIBLE : View.GONE);
        if (progress >= 0) sheetProgress.setProgress(progress);
        if (buttonText != null) {
            sheetButton.setText(buttonText);
            sheetButton.setEnabled(true);
            sheetButton.setAlpha(1f);
            sheetButton.setOnClickListener(v -> onButton.run());
        } else {
            sheetButton.setText("Подождите…");
            sheetButton.setEnabled(false);
            sheetButton.setAlpha(0.6f);
        }
        sheetLink.setVisibility(showBrowserLink ? View.VISIBLE : View.GONE);
        sheetLater.setVisibility(!pendingUpdateForced && buttonText != null ? View.VISIBLE : View.GONE);
        if (!updateSheet.isShowing()) updateSheet.show();
    }

    private void startUpdate() {
        downloadAndInstallApk(pendingApkUrl, pendingApkSha256);
    }

    private void showUpdateDialog(final String apkUrl, final String apkSha256) {
        runOnUiThread(() -> {
            // Одно окно за раз: не перебиваем загрузку, установку или уже открытую карточку
            if (updateInProgress || installerLaunched) return;
            if (updateSheet != null && updateSheet.isShowing()) return;
            renderUpdateSheet("🚀", "Доступно обновление",
                    "Новая версия X5 Planer готова.\nНажмите «Обновить» — приложение всё сделает само.",
                    -1, "Обновить", this::startUpdate, false);
        });
    }

    private void showInstallCancelled() {
        renderUpdateSheet("🛡️", "Почти готово",
                "Установку отменили. Нажмите «Обновить» ещё раз.\n\n"
                        + "Если появится Google Play Protect — нажмите «Сканировать приложение», "
                        + "затем «Установить».",
                -1, "Обновить", this::startUpdate, true);
    }

    private void showUpdateErrorDialog(String reason) {
        renderUpdateSheet("⚠️", "Не получилось обновить", reason + "\nНажмите «Повторить».",
                -1, "Повторить", this::startUpdate, true);
    }

    private void downloadAndInstallApk(String url, String expectedSha256) {
        if (updateInProgress || url == null) return;

        // Android 8+: нужно разрешение "Установка неизвестных приложений" для X5 Planer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            renderUpdateSheet("🔓", "Одно разрешение",
                    "Android попросит разрешить X5 Planer устанавливать обновления.\n"
                            + "Включите переключатель и вернитесь назад — обновление продолжится само.",
                    -1, "Разрешить", () -> {
                        waitingInstallPermission = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }, true);
            return;
        }

        // Этот же файл уже скачан и проверен — не качаем заново
        if (readyApkFile != null && readyApkFile.exists() && expectedSha256 != null
                && expectedSha256.equalsIgnoreCase(readyApkSha256)) {
            installApk(readyApkFile);
            return;
        }

        // Качаем сами в папку приложения: без системного менеджера загрузок, который на части
        // телефонов при обрыве связи начинает заново с 0% или вовсе не отдаёт файл
        updateInProgress = true;
        renderUpdateSheet("⬇️", "Скачиваем обновление", "0%", 0, null, null, false);
        final File dir = new File(getCacheDir(), "updates");
        new Thread(() -> {
            File apk = null;
            String error = null;
            try {
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                apk = new File(dir, "update_" + System.currentTimeMillis() + ".apk");

                // Связь оборвалась — докачиваем с того же места (Range), а не начинаем с 0%
                long done = 0, total = -1;
                int lastPct = -1;
                for (int attempt = 1; ; attempt++) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    if (done > 0) conn.setRequestProperty("Range", "bytes=" + done + "-");
                    try {
                        int code = conn.getResponseCode();
                        boolean append = false;
                        if (code == HttpURLConnection.HTTP_PARTIAL && done > 0) {
                            append = true;
                            if (total < 0) total = done + conn.getContentLengthLong();
                        } else if (code == HttpURLConnection.HTTP_OK) {
                            done = 0; // сервер не умеет докачку — качаем заново
                            total = conn.getContentLengthLong();
                        } else {
                            throw new java.io.IOException("HTTP " + code);
                        }
                        try (InputStream in = new BufferedInputStream(conn.getInputStream());
                             OutputStream out = new FileOutputStream(apk, append)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                                done += n;
                                if (total > 0) {
                                    int pct = (int) (done * 100 / total);
                                    if (pct > lastPct) {
                                        lastPct = pct;
                                        final int p = pct;
                                        runOnUiThread(() -> renderUpdateSheet("⬇️", "Скачиваем обновление",
                                                p + "%", p, null, null, false));
                                    }
                                }
                            }
                        }
                        if (total > 0 && done < total) throw new java.io.IOException("обрыв на " + done + " из " + total);
                        break;
                    } catch (java.io.IOException e) {
                        if (attempt >= 6) throw e;
                        final int shown = Math.max(lastPct, 0);
                        runOnUiThread(() -> renderUpdateSheet("📶", "Связь прервалась",
                                "Докачиваем с " + shown + "%…", shown, null, null, false));
                        Thread.sleep(2000L * attempt);
                    } finally {
                        conn.disconnect();
                    }
                }

                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    String actual = computeSha256(apk);
                    if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
                        android.util.Log.e("X5PLANER_UPDATE", "Хэш скачанного APK не совпал с ожидаемым — установка отменена");
                        apk.delete();
                        error = "Файл скачался с ошибкой.";
                        reportUpdateProblem("sha256", -1, "mismatch: " + actual);
                    }
                } else {
                    android.util.Log.w("X5PLANER_UPDATE", "apkSha256 не задан в Firebase — устанавливаем без проверки целостности");
                }
            } catch (Exception e) {
                e.printStackTrace();
                error = "Не удалось скачать обновление — проверьте интернет.";
                reportUpdateProblem("download", -1, String.valueOf(e));
            }

            final File result = apk;
            final String err = error;
            runOnUiThread(() -> {
                updateInProgress = false;
                if (err != null) {
                    showUpdateErrorDialog(err);
                    return;
                }
                readyApkFile = result;
                readyApkSha256 = expectedSha256;
                installApk(result);
            });
        }).start();
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

    // Установка через PackageInstaller: телефон сообщает результат (UpdateInstallReceiver),
    // поэтому причина отказа видна и пользователю, и нам (update_errors в Firebase)
    private void installApk(File apk) {
        renderUpdateSheet("📦", "Устанавливаем", "Подтвердите установку в окне Android.", 100, null, null, false);
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                params.setSize(apk.length());
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent callback = new Intent(this, UpdateInstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pending = PendingIntent.getBroadcast(this, sessionId, callback, flags);
                    session.commit(pending.getIntentSender());
                }
            } catch (Exception e) {
                // Сессия не создалась — пробуем обычный установщик Android
                e.printStackTrace();
                reportUpdateProblem("session", -1, String.valueOf(e));
                runOnUiThread(() -> installViaIntent(apk));
            }
        }).start();
    }

    private void installViaIntent(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            installerLaunched = true;
            startActivity(intent);
        } catch (Exception e) {
            installerLaunched = false;
            e.printStackTrace();
            reportUpdateProblem("intent", -1, String.valueOf(e));
            showUpdateErrorDialog("Не удалось открыть установку.");
        }
    }

    // Вызывается из UpdateInstallReceiver: Android просит подтвердить установку — показываем его окно
    void onInstallConfirmationRequired(Intent confirm) {
        installerLaunched = true;
        try {
            startActivity(confirm);
        } catch (Exception e) {
            installerLaunched = false;
            reportUpdateProblem("confirm", -1, String.valueOf(e));
            showUpdateErrorDialog("Не удалось открыть установку.");
        }
    }

    // Вызывается из UpdateInstallReceiver, когда установка не прошла
    void onInstallFailed(int status, String systemMessage) {
        installerLaunched = false;
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            // Человек сам нажал «Отмена» / «Не устанавливать» — подсказываем, а не пугаем ошибкой
            showInstallCancelled();
            return;
        }
        reportUpdateProblem("install", status, systemMessage);
        String text;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                text = "Телефон заблокировал установку (Google Play Protect или защита телефона).";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                text = "Новая версия конфликтует с установленным приложением.";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                text = "На телефоне не хватает памяти — освободите место.";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                text = "Новая версия несовместима с этим телефоном.";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                text = "Файл обновления повреждён.";
                break;
            default:
                text = "Установка не удалась.";
        }
        showUpdateErrorDialog(text);
    }

    // Запасной путь, который работает всегда: скачать APK браузером и открыть файл
    private void openUpdateInBrowser() {
        if (pendingApkUrl == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingApkUrl)));
            Toast.makeText(this, "Когда файл скачается — откройте его и нажмите «Установить»", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Откройте в браузере: " + pendingApkUrl, Toast.LENGTH_LONG).show();
        }
    }

    // Причина неудачного обновления уходит в Firebase (update_errors), чтобы её было видно разработчику
    private void reportUpdateProblem(String stage, int status, String message) {
        try {
            String user = getSharedPreferences("AppSession", MODE_PRIVATE).getString("userData", "");
            String[] parts = user.split("\\|");
            java.util.Map<String, Object> rec = new java.util.HashMap<>();
            rec.put("stage", stage);
            rec.put("status", status);
            rec.put("message", message == null ? "" : message);
            rec.put("fromVersion", getCurrentVersionCode());
            rec.put("toVersion", pendingVersionCode);
            rec.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            rec.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            rec.put("user", parts.length >= 2 ? parts[0] + " " + parts[1] : "");
            rec.put("ts", com.google.firebase.database.ServerValue.TIMESTAMP);
            FirebaseDatabase.getInstance().getReference("update_errors").push().setValue(rec);
        } catch (Exception ignored) {
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

        @android.webkit.JavascriptInterface
        public boolean isShiftTrackingActive() {
            return GeoTrackingService.isRunning;
        }

        // Кто на смене и куда в Firebase писать сбои геолокации (путь уже с безопасным именем полигона)
        @android.webkit.JavascriptInterface
        public void setTrackingUser(String userKey, String name, String polygon, String incidentsPath) {
            getSharedPreferences(GeoTrackingService.INCIDENT_PREFS, MODE_PRIVATE).edit()
                    .putString("user_key", userKey)
                    .putString("user_name", name)
                    .putString("polygon", polygon)
                    .putString("incidents_path", incidentsPath)
                    .apply();
        }

        // Админы и главный админ: правило «в 20:10 выход с линии, сбои после 20:10 не пишутся» на них не действует
        @android.webkit.JavascriptInterface
        public void setTrackingDayEndExempt(boolean exempt) {
            getSharedPreferences(GeoTrackingService.INCIDENT_PREFS, MODE_PRIVATE).edit()
                    .putBoolean("day_end_exempt", exempt)
                    .apply();
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