package com.example.x5planer;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;

public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int hour = intent.getIntExtra("hour", -1);

        SharedPreferences prefs = context.getSharedPreferences("Reminders", Context.MODE_PRIVATE);
        String sentDate = prefs.getString("report_sent_date", "");
        // Напоминание в 00:00 относится к смене прошедшего дня
        String shiftDate = MainActivity.getMoscowDateStr(hour == 0 ? -1 : 0);

        // Показываем уведомление только если в этот день у пользователя смена и отчет еще не отправлен
        if (hasShiftOn(prefs, shiftDate) && !sentDate.equals(shiftDate)) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info) // Стандартная системная иконка
                    .setContentTitle("X5 Planer")
                    .setContentText("Не забудьте отправить отчет за смену!")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            // Проверяем, дал ли пользователь разрешение на уведомления (нужно для Android 13+)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(3000 + hour, builder.build());
            }
        }

        // Переносим этот же слот на завтра, чтобы цепочка напоминаний не прерывалась
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null && hour >= 0) {
            MainActivity.scheduleSingleReminder(context, am, hour);
        }
    }

    private static boolean hasShiftOn(SharedPreferences prefs, String date) {
        try {
            JSONArray dates = new JSONArray(prefs.getString("shift_dates", "[]"));
            for (int i = 0; i < dates.length(); i++) {
                if (date.equals(dates.optString(i))) return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}