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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private long downloadId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Инициализация Firebase
        FirebaseApp.initializeApp(this);

        // 2. Проверка обновлений при запуске
        checkForUpdates();

        // 3. Создаем WebView программно на весь экран (без XML файлов)
        WebView webView = new WebView(this);
        setContentView(webView);

        // Настройка веб-окружения
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Мост для передачи версии в HTML
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidApp");

        // Загрузка главного интерфейса приложения из assets
        webView.loadUrl("file:///android_asset/index.html");
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
    }
}