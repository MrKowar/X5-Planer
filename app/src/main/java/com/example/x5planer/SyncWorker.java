package com.example.x5planer;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.firebase.database.FirebaseDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("SyncQueue", Context.MODE_PRIVATE);
        String raw = prefs.getString("queue", "[]");

        try {
            JSONArray queue = new JSONArray(raw);
            if (queue.length() == 0) return Result.success();

            JSONArray remaining = new JSONArray();
            boolean anyFailed = false;

            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.getJSONObject(i);
                String path = item.getString("path");
                String dataStr = item.getString("data");

                try {
                    JSONObject jsonObj = new JSONObject(dataStr);
                    // Используем наш ручной метод вместо несуществующего .toMap()
                    Map<String, Object> mapValue = jsonToMap(jsonObj);

                    FirebaseDatabase.getInstance().getReference(path).setValue(mapValue);
                } catch (Exception writeErr) {
                    remaining.put(item);
                    anyFailed = true;
                }
            }

            prefs.edit().putString("queue", remaining.toString()).apply();
            return anyFailed ? Result.retry() : Result.success();

        } catch (Exception e) {
            return Result.retry();
        }
    }

    // --- Ручной конвертер JSON в Map ---
    private Map<String, Object> jsonToMap(JSONObject json) throws JSONException {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value instanceof JSONArray) {
                value = jsonToList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = jsonToMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    private List<Object> jsonToList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = jsonToList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = jsonToMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }
}