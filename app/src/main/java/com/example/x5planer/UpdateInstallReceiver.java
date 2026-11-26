package com.example.x5planer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

// Результат установки обновления от PackageInstaller (см. MainActivity.installApk)
public class UpdateInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        MainActivity activity = MainActivity.current();

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android просит подтвердить установку — показываем его окно
            Intent confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (activity != null) {
                activity.runOnUiThread(() -> activity.onInstallConfirmationRequired(confirm));
            } else {
                try {
                    context.startActivity(confirm);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        // При успехе Android перезапускает приложение уже новой версии — делать ничего не нужно
        if (status == PackageInstaller.STATUS_SUCCESS) return;

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (activity != null) {
            activity.runOnUiThread(() -> activity.onInstallFailed(status, message));
        }
    }
}
