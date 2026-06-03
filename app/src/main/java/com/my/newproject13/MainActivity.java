package com.my.newproject13; // Убедись, что пакет совпадает с твоим проектом!

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String API_BASE = "https://api.internal.temp-mail.io/api/v3";
    private static final String TAG = "TempMailApp";
    private static final String PREFS_NAME = "TempMailPrefs";

    private TextView tvEmail, tvStatus;
    // Используем обычные нативные Button вместо MaterialButton
    private Button btnCreate, btnCopy, btnDelete; 
    private LinearLayout llMessages;

    private String currentEmail = null;
    private Set<String> seenMessageIds = new HashSet<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private SharedPreferences prefs;

    @Override    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        tvEmail = findViewById(R.id.tvEmail);
        tvStatus = findViewById(R.id.tvStatus);
        btnCreate = findViewById(R.id.btnCreate);
        btnCopy = findViewById(R.id.btnCopy);
        btnDelete = findViewById(R.id.btnDelete);
        llMessages = findViewById(R.id.llMessages);

        currentEmail = prefs.getString("saved_email", null);
        if (currentEmail != null && !currentEmail.isEmpty()) {
            tvEmail.setText(currentEmail);
            tvStatus.setText("Статус: Восстановлено. Ожидание писем...");
            btnCreate.setVisibility(View.GONE);
            btnCopy.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);
            startPolling();
        } else {
            btnCopy.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
            btnCreate.setVisibility(View.VISIBLE);
        }

        btnCreate.setOnClickListener(v -> createNewEmail());
        
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("temp_email", currentEmail);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Адрес скопирован!", Toast.LENGTH_SHORT).show();
        });

        btnDelete.setOnClickListener(v -> {
            prefs.edit().remove("saved_email").apply();
            currentEmail = null;
            seenMessageIds.clear();
            llMessages.removeAllViews();
            if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
            
            tvEmail.setText("Нажмите кнопку ниже");
            tvStatus.setText("Статус: Ожидание...");
            btnCreate.setVisibility(View.VISIBLE);
            btnCopy.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
        });
    }
    private void createNewEmail() {
        tvStatus.setText("Статус: Создание почты...");
        btnCreate.setEnabled(false);

        new Thread(() -> {
            try {
                URL url = new URL(API_BASE + "/email/new");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(10000);
                conn.setDoOutput(true);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes());
                }

                String response = readResponse(conn);
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    currentEmail = json.getString("email");
                    prefs.edit().putString("saved_email", currentEmail).apply();

                    runOnUiThread(() -> {
                        tvEmail.setText(currentEmail);
                        tvStatus.setText("Статус: Ожидание новых писем...");
                        btnCreate.setVisibility(View.GONE);
                        btnCopy.setVisibility(View.VISIBLE);
                        btnDelete.setVisibility(View.VISIBLE);
                        startPolling();
                    });
                } else {
                    throw new Exception("Пустой ответ от сервера");
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка создания", e);
                runOnUiThread(() -> {
                    tvStatus.setText("Статус: Ошибка сети");
                    btnCreate.setEnabled(true);
                    Toast.makeText(this, "Проверьте интернет", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startPolling() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);

        pollRunnable = new Runnable() {            @Override
            public void run() {
                checkMessages();
                handler.postDelayed(this, 10000);
            }
        };
        handler.post(pollRunnable);
    }

    private void checkMessages() {
        if (currentEmail == null) return;

        new Thread(() -> {
            try {
                URL url = new URL(API_BASE + "/email/" + currentEmail + "/messages");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);

                String response = readResponse(conn);
                if (response != null) {
                    JSONArray messages = new JSONArray(response);
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject m = messages.getJSONObject(i);
                        String id = m.optString("id", "");
                        
                        if (!id.isEmpty() && !seenMessageIds.contains(id)) {
                            seenMessageIds.add(id);
                            String from = m.optString("from", "Неизвестно");
                            String subject = m.optString("subject", "(без темы)");
                            String createdAt = m.optString("created_at", "").replace("T", " ").substring(0, 16);
                            String bodyHtml = m.optString("body_html", "");
                            String bodyText = m.optString("body_text", "");
                            
                            String cleanBody = cleanHtml(bodyHtml.isEmpty() ? bodyText : bodyHtml);

                            runOnUiThread(() -> addMessageToUI(id, from, subject, createdAt, cleanBody));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка проверки", e);
            }
        }).start();
    }

    private void addMessageToUI(String id, String from, String subject, String date, String body) {
        // 1. Создаем обычный LinearLayout вместо MaterialCardView
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);        card.setPadding(24, 24, 24, 24);
        
        // 2. Программно рисуем красивый фон: белый цвет, закругленные углы, фиолетовая обводка
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(0xFFFFFFFF); // Белый фон
        background.setCornerRadius(24f); // Сильное закругление углов
        background.setStroke(3, 0xFF6750A4); // Фиолетовая обводка (3px)
        card.setBackground(background);
        
        // 3. Добавляем тень (Elevation) для объема
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(12f);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 20); // Отступ между карточками
        card.setLayoutParams(params);

        // Заголовок (Тема и От кого)
        TextView tvHeader = new TextView(this);
        tvHeader.setText(parseHtml("<b>" + subject + "</b><br><font color='#6750A4'>" + from + "</font>"));
        tvHeader.setTextSize(16);
        tvHeader.setTextColor(0xFF1C1B1F);
        
        // Дата
        TextView tvDate = new TextView(this);
        tvDate.setText(date);
        tvDate.setTextSize(12);
        tvDate.setTextColor(0xFF49454F);
        tvDate.setGravity(Gravity.END);

        // Текст письма
        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextSize(14);
        tvBody.setTextColor(0xFF1C1B1F);
        tvBody.setPadding(0, 16, 0, 0);

        card.addView(tvHeader);
        card.addView(tvDate);
        card.addView(tvBody);

        llMessages.addView(card, 0); 
        tvStatus.setText("Статус: Получено новое письмо!");
    }
    // Безопасный парсер HTML для любых версий Android
    private CharSequence parseHtml(String html) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        } else {
            return Html.fromHtml(html);
        }
    }

    private String cleanHtml(String html) {
        if (html == null || html.isEmpty()) return "Пустое сообщение";
        String text = parseHtml(html).toString();
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(lines.length, 10);
        for (int i = 0; i < limit; i++) {
            if (!lines[i].trim().isEmpty()) {
                sb.append(lines[i].trim()).append("\n");
            }
        }
        return sb.toString();
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()
        ));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return status >= 200 && status < 300 ? response.toString() : null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
    }
}