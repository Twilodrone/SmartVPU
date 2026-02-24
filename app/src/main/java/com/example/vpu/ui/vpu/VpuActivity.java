package com.example.vpu.ui.vpu;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.example.vpu.R;
import com.example.vpu.adapter.PhasePagerAdapter;
import com.example.vpu.network.PiApiService;
import com.example.vpu.network.PiRetrofitClient;
import com.example.vpu.network.dto.ActivateRequest;
import com.example.vpu.network.dto.ActivateResponse;
import com.example.vpu.network.dto.PiStatusResponse;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class VpuActivity extends AppCompatActivity {

    // UI
    private ViewPager2 viewPager;
    private TextView statusTextView;
    private TextView pageTextView;
    private TextView manualTimerTextView;
    private MaterialButton manualButton;
    private ImageView wifiIcon;
    private MaterialButton activateButton;

    // Data
    private int objectId;
    private String address;
    private ArrayList<String> imageUrls;

    // Raspberry API
    private PiApiService piApi;

    // Polling
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private int consecutiveFails = 0;
    private static final long POLL_INTERVAL_MS = 1000;

    // State from Pi
    private int currentPhaseFromPi = 0;
    private boolean manualAllowed = true;        // разрешение РУ от контроллера
    private boolean manualRequestActive = false;  // реально включён запрос РУ на сервере

    // User intent: пользователь включил РУ кнопкой
    private boolean wantManualRequest = false;

    // Command state
    private boolean commandInProgress = false;
    private int pendingPhase = -1;
    private long pendingUntilMs = 0;
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    // RU auto-off timer (15 минут)
    private static final long MANUAL_AUTO_OFF_MS = 15 * 60 * 1000L;
    private final Handler ruHandler = new Handler(Looper.getMainLooper());
    private long ruExpireAtMs = 0;
    private boolean manualOffInProgress = false;
    private long manualOffGuardUntilMs = 0;
    private static final long MANUAL_OFF_GUARD_MS = 3000; // 3s
    private long manualOptimisticUntilMs = 0;
    private static final long MANUAL_OPTIMISTIC_MS = 1500;


    private final Runnable ruAutoOffRunnable = () -> {
        if (wantManualRequest) {
            wantManualRequest = false;
            stopRuTimer();
            safeManualOff();
            setManualButtonState(manualAllowed, false);
            updateActivateButtonState();
            updateManualTimerUi(); // покажет "—"
            Toast.makeText(this, "РУ отключено по таймеру", Toast.LENGTH_SHORT).show();
        }
    };

    // Обновление таймера на экране раз в секунду
    private final Runnable ruTickerRunnable = new Runnable() {
        @Override
        public void run() {
            updateManualTimerUi();
            // тикер крутится всегда, но дешево: просто раз в секунду обновляет текст
            ruHandler.postDelayed(this, 1000);
        }
    };

    // Colors
    private static final int COLOR_OK = 0xFF2EE59D;       // green
    private static final int COLOR_BAD = 0xFFFF6B6B;      // red
    private static final int COLOR_NEUTRAL_BG = 0xFF263554;
    private static final int COLOR_TEXT_DARK = 0xFF0B1220;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpu);

        objectId = getIntent().getIntExtra("objectId", -1);
        imageUrls = getIntent().getStringArrayListExtra("images");
        address = "Лесной 2-й переулок, Бутырский Вал"; // пока хардкод

        if (objectId == -1 || imageUrls == null || imageUrls.isEmpty()) {
            finish();
            return;
        }

        // UI
        statusTextView = findViewById(R.id.statusTextView);
        viewPager = findViewById(R.id.viewPager);
        pageTextView = findViewById(R.id.pageTextView);
        manualTimerTextView = findViewById(R.id.manualTimerTextView);
        manualButton = findViewById(R.id.manualButton);
        wifiIcon = findViewById(R.id.wifiIcon);
        activateButton = findViewById(R.id.activateButton);

        // API
        piApi = PiRetrofitClient.getInstance().create(PiApiService.class);

        // ViewPager adapter
        PhasePagerAdapter adapter = new PhasePagerAdapter(imageUrls, position -> {
        });
        viewPager.setAdapter(adapter);

        // init UI
        updatePageText(0);
        updateStatusText(viewPager.getCurrentItem() + 1, currentPhaseFromPi);
        setWifiConnected(false);
        setManualButtonState(false, false);
        updateActivateButtonState();
        updateManualTimerUi();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // reset таймера уже будет через onUserInteraction(), но оставим обновление UI
                updatePageText(position);
                updateStatusText(position + 1, currentPhaseFromPi);
                updateActivateButtonState();
            }
        });

        manualButton.setOnClickListener(v -> onManualClicked());
        activateButton.setOnClickListener(v -> onActivateClicked());
    }

    /**
     * Самый простой способ "любое действие пользователя сбрасывает таймер".
     * Срабатывает на тап/свайп/скролл и т.д.
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetRuTimerIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startPolling();

        // запуск тикера для отображения таймера (1 раз)
        ruHandler.removeCallbacks(ruTickerRunnable);
        ruHandler.post(ruTickerRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPolling();

        //stopRuTimer();
        wantManualRequest = false;
        //safeManualOff();

        ruHandler.removeCallbacks(ruTickerRunnable);
    }

    // ---------------- Polling ----------------

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;

            piApi.getStatus().enqueue(new Callback<PiStatusResponse>() {
                @Override
                public void onResponse(Call<PiStatusResponse> call, Response<PiStatusResponse> response) {
                    if (!polling) return;

                    if (!response.isSuccessful() || response.body() == null) {
                        onPollFail();
                        scheduleNextPoll();
                        return;
                    }

                    consecutiveFails = 0;
                    PiStatusResponse st = response.body();

                    currentPhaseFromPi = st.currentPhase;
                    manualAllowed = st.manualAllowed;
                    manualRequestActive = st.manualRequestActive;

                    setWifiConnected(true);

                    // если контроллер запретил РУ — сбрасываем желание + стоп таймер
                    if (!manualAllowed) {
                        wantManualRequest = false;
                        stopRuTimer();
                        updateManualTimerUi();
                    }

                    long now = System.currentTimeMillis();
                    if (wantManualRequest && !manualOffInProgress && now >= manualOffGuardUntilMs) {
                        safeManualOn();
                    }


                    // UI
                    int shownPhase = viewPager.getCurrentItem() + 1;
                    updateStatusText(shownPhase, currentPhaseFromPi);

                    setManualButtonState(manualAllowed, wantManualRequest && manualRequestActive);

                    updateActivateButtonState();
                    checkConfirmation();

                    scheduleNextPoll();
                }

                @Override
                public void onFailure(Call<PiStatusResponse> call, Throwable t) {
                    if (!polling) return;
                    onPollFail();
                    scheduleNextPoll();
                }
            });
        }
    };

    private void scheduleNextPoll() {
        if (!polling) return;
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void onPollFail() {
        consecutiveFails++;
        setWifiConnected(false);

        manualAllowed = false;
        manualRequestActive = false;
        wantManualRequest = false;

        stopRuTimer();
        updateManualTimerUi();
        boolean optimisticActive = wantManualRequest && (System.currentTimeMillis() < manualOptimisticUntilMs);
        boolean shownManualActive = optimisticActive || (wantManualRequest && manualRequestActive);

        setManualButtonState(manualAllowed, shownManualActive);

        setManualButtonState(false, false);
        updateActivateButtonState();
    }

    // ---------------- RU Auto-off ----------------

    private void resetRuTimerIfNeeded() {
        if (!wantManualRequest) return;

        // Перезапускаем таймер (15 минут от текущего момента)
        ruExpireAtMs = System.currentTimeMillis() + MANUAL_AUTO_OFF_MS;
        ruHandler.removeCallbacks(ruAutoOffRunnable);
        ruHandler.postDelayed(ruAutoOffRunnable, MANUAL_AUTO_OFF_MS);
        updateManualTimerUi();
    }

    private void stopRuTimer() {
        ruHandler.removeCallbacks(ruAutoOffRunnable);
        ruExpireAtMs = 0;
    }

    private void updateManualTimerUi() {
        if (manualTimerTextView == null) return;

        if (!wantManualRequest || ruExpireAtMs <= 0) {
            manualTimerTextView.setText("Таймер РУ: —");
            return;
        }

        long leftMs = ruExpireAtMs - System.currentTimeMillis();
        if (leftMs < 0) leftMs = 0;

        long totalSec = leftMs / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;

        manualTimerTextView.setText(String.format(Locale.getDefault(),
                "Таймер РУ: %02d:%02d", mm, ss));
    }

    // ---------------- UI helpers ----------------

    private void setWifiConnected(boolean connected) {
        if (wifiIcon == null) return;
        wifiIcon.setColorFilter(connected ? COLOR_OK : COLOR_BAD);
    }

    private void setManualButtonState(boolean allowed, boolean requestActive) {
        if (manualButton == null) return;

        // кнопку НЕ отключаем
        manualButton.setEnabled(true);

        if (allowed) {
            manualButton.setBackgroundTintList(ColorStateList.valueOf(COLOR_OK));
            manualButton.setTextColor(COLOR_TEXT_DARK);
            manualButton.setAlpha(requestActive ? 1.0f : 0.85f);
        } else {
            manualButton.setBackgroundTintList(ColorStateList.valueOf(COLOR_BAD));
            manualButton.setTextColor(COLOR_TEXT_DARK);
            manualButton.setAlpha(1.0f);
        }
    }

    private void updatePageText(int position) {
        if (pageTextView == null) return;
        pageTextView.setText((position + 1) + " / " + imageUrls.size());
    }

    private void updateStatusText(int shownPhase, int piPhase) {
        String pi;
        if (piPhase == -1) pi = "КОНФЛИКТ";
        else if (piPhase == 0) pi = "—";
        else pi = String.valueOf(piPhase);

        if (address != null && !address.isEmpty()) {
            statusTextView.setText("Объект: " + objectId +
                    " • Выбор: " + shownPhase +
                    " • Текущая: " + pi +
                    "\n\n" + address);
        } else {
            statusTextView.setText("Объект: " + objectId +
                    " • Выбор: " + shownPhase +
                    " • Текущая: " + pi);
        }
    }

    private void updateActivateButtonState() {
        if (activateButton == null) return;

        boolean enabled = manualAllowed && wantManualRequest && !commandInProgress && consecutiveFails < 3;
        activateButton.setEnabled(enabled);

        if (enabled) {
            activateButton.setBackgroundTintList(ColorStateList.valueOf(COLOR_OK));
            activateButton.setIconTint(ColorStateList.valueOf(COLOR_TEXT_DARK));
            activateButton.setAlpha(1.0f);
        } else {
            activateButton.setBackgroundTintList(ColorStateList.valueOf(COLOR_NEUTRAL_BG));
            activateButton.setIconTint(ColorStateList.valueOf(0xFFA7B3C9));
            activateButton.setAlpha(0.95f);
        }
    }

    // ---------------- Actions ----------------

    private void onManualClicked() {

        if (wantManualRequest) {
            // выключаем
            wantManualRequest = false;
            stopRuTimer();
            updateManualTimerUi();

            manualOffInProgress = true;
            manualOffGuardUntilMs = System.currentTimeMillis() + MANUAL_OFF_GUARD_MS;

            safeManualOff(); // async
            setManualButtonState(true, false);
            updateActivateButtonState();
            return;
        }
        manualOptimisticUntilMs = System.currentTimeMillis() + MANUAL_OPTIMISTIC_MS;

        // включаем
        wantManualRequest = true;
        manualOffInProgress = false;
        manualOffGuardUntilMs = 0;

        safeManualOn();
        resetRuTimerIfNeeded();
        setManualButtonState(true, true);
        updateActivateButtonState();

        safeManualOn();
    }

    private void onActivateClicked() {
        resetRuTimerIfNeeded();

        if (!manualAllowed || !wantManualRequest) return;
        if (commandInProgress) return;

        int phaseToActivate = viewPager.getCurrentItem() + 1;

        new AlertDialog.Builder(this)
                .setTitle("Подтверждение")
                .setMessage("Включить фазу " + phaseToActivate + "?")
                .setPositiveButton("Включить", (dialog, which) -> sendActivate(phaseToActivate))
                .setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void sendActivate(int phase) {
        commandInProgress = true;
        pendingPhase = phase;
        pendingUntilMs = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        updateActivateButtonState();

        // единственное уведомление по ТЗ
        Toast.makeText(this, "Команда отправлена", Toast.LENGTH_SHORT).show();

        piApi.activate(new ActivateRequest(phase)).enqueue(new Callback<ActivateResponse>() {
            @Override
            public void onResponse(Call<ActivateResponse> call, Response<ActivateResponse> response) {
                if (!response.isSuccessful() || response.body() == null || !response.body().accepted) {
                    commandInProgress = false;
                    pendingPhase = -1;
                    updateActivateButtonState();
                }
            }

            @Override
            public void onFailure(Call<ActivateResponse> call, Throwable t) {
                commandInProgress = false;
                pendingPhase = -1;
                updateActivateButtonState();
            }
        });
    }

    private void checkConfirmation() {
        if (!commandInProgress || pendingPhase <= 0) return;

        if (currentPhaseFromPi == pendingPhase) {
            commandInProgress = false;
            pendingPhase = -1;
            updateActivateButtonState();
            return;
        }

        if (System.currentTimeMillis() > pendingUntilMs) {
            commandInProgress = false;
            pendingPhase = -1;
            updateActivateButtonState();
        }
    }

    // ---------------- API helpers ----------------

    private void safeManualOn() {
        try {
            piApi.manualOn().enqueue(new Callback<Object>() {
                @Override
                public void onResponse(Call<Object> call, Response<Object> response) {
                }

                @Override
                public void onFailure(Call<Object> call, Throwable t) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void safeManualOff() {
        try {
            piApi.manualOff().enqueue(new Callback<Object>() {
                @Override
                public void onResponse(Call<Object> call, Response<Object> response) {
                    manualOffInProgress = false;
                }

                @Override
                public void onFailure(Call<Object> call, Throwable t) {
                    manualOffInProgress = false;
                }
            });
        } catch (Exception ignored) {
            manualOffInProgress = false;
        }
    }
}
