package com.example.vpu.ui.vpu;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class VpuActivity extends AppCompatActivity {

    // UI
    private ViewPager2 viewPager;
    private TextView statusTextView;
    private TextView addressTextView;
    private TextView pageTextView;
    private TextView manualTimerTextView;
    private TextView activePhaseTimerTextView;
    private SwitchMaterial manualToggle;
    private SwitchMaterial[] phaseCallSwitches;
    private ImageView wifiIcon;
    private MaterialButton[] phaseButtons;

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
    private long lastCalledPhaseStartedAtMs = 0;
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
            updatePhaseButtonsUi();
            updateManualTimerUi(); // покажет "—"
            Toast.makeText(this, "РУ отключено по таймеру", Toast.LENGTH_SHORT).show();
        }
    };

    // Обновление таймера на экране раз в секунду
    private final Runnable ruTickerRunnable = new Runnable() {
        @Override
        public void run() {
            updateManualTimerUi();
            updateActivePhaseTimerUi();
            // тикер крутится всегда, но дешево: просто раз в секунду обновляет текст
            ruHandler.postDelayed(this, 1000);
        }
    };

    // Colors
    private static final int COLOR_OK = 0xFF2EE59D;       // green
    private static final int COLOR_BAD = 0xFFFF6B6B;      // red
    private static final int COLOR_PHASE_DIM = 0xFF1D7A56;
    private static final int COLOR_VIEWING_STROKE = 0xFFFFD54F;
    private static final int COLOR_TEXT_DARK = 0xFF0B1220;
    private static final int COLOR_SURFACE_DIM = 0xFF5C6A82;
    private static final int COLOR_TEXT_LIGHT = 0xFFE8EEF9;

    private boolean manualToggleInternalUpdate = false;
    private boolean phaseSwitchesInternalUpdate = false;
    private int activePhaseCallSwitch = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpu);

        if (savedInstanceState != null) {
            activePhaseCallSwitch = savedInstanceState.getInt(STATE_ACTIVE_PHASE_CALL_SWITCH, 0);
        }

        objectId = getIntent().getIntExtra("objectId", -1);
        imageUrls = getIntent().getStringArrayListExtra("images");
        address = getIntent().getStringExtra("address");
        if (address == null || address.trim().isEmpty()) {
            address = "Адрес не указан";
        }

        if (objectId == -1 || imageUrls == null || imageUrls.isEmpty()) {
            finish();
            return;
        }

        // UI
        statusTextView = findViewById(R.id.statusTextView);
        addressTextView = findViewById(R.id.addressTextView);
        viewPager = findViewById(R.id.viewPager);
        pageTextView = findViewById(R.id.pageTextView);
        manualTimerTextView = findViewById(R.id.manualTimerTextView);
        activePhaseTimerTextView = findViewById(R.id.activePhaseTimerTextView);
        manualToggle = findViewById(R.id.manualToggle);
        wifiIcon = findViewById(R.id.wifiIcon);
        phaseButtons = new MaterialButton[] {
                findViewById(R.id.phaseButton1),
                findViewById(R.id.phaseButton2),
                findViewById(R.id.phaseButton3),
                findViewById(R.id.phaseButton4),
                findViewById(R.id.phaseButton5),
                findViewById(R.id.phaseButton6),
                findViewById(R.id.phaseButton7),
                findViewById(R.id.phaseButton8)
        };
        phaseCallSwitches = new SwitchMaterial[] {
                findViewById(R.id.phaseCallSwitch1),
                findViewById(R.id.phaseCallSwitch2),
                findViewById(R.id.phaseCallSwitch3),
                findViewById(R.id.phaseCallSwitch4),
                findViewById(R.id.phaseCallSwitch5),
                findViewById(R.id.phaseCallSwitch6),
                findViewById(R.id.phaseCallSwitch7),
                findViewById(R.id.phaseCallSwitch8)
        };

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
        updatePhaseButtonsUi();
        updateManualTimerUi();
        updateActivePhaseTimerUi();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // reset таймера уже будет через onUserInteraction(), но оставим обновление UI
                updatePageText(position);
                updateStatusText(position + 1, currentPhaseFromPi);
                updatePhaseButtonsUi();
            }
        });

        manualToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (manualToggleInternalUpdate) return;
            onManualToggled(isChecked);
        });
        initPhaseButtons();
        initPhaseCallSwitches();
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ACTIVE_PHASE_CALL_SWITCH, activePhaseCallSwitch);
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
        activePhaseCallSwitch = 0;
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
                    if (st.address != null && !st.address.trim().isEmpty()) {
                        address = st.address;
                    }

                    setWifiConnected(true);

                    // если контроллер запретил РУ — сбрасываем желание + стоп таймер
                    if (!manualAllowed) {
                        wantManualRequest = false;
                        activePhaseCallSwitch = 0;
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

                    updatePhaseButtonsUi();
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
        currentPhaseFromPi = 0;
        activePhaseCallSwitch = 0;

        stopRuTimer();
        updateManualTimerUi();
        boolean optimisticActive = wantManualRequest && (System.currentTimeMillis() < manualOptimisticUntilMs);
        boolean shownManualActive = optimisticActive || (wantManualRequest && manualRequestActive);

        setManualButtonState(manualAllowed, shownManualActive);
        updatePhaseButtonsUi();
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
            manualTimerTextView.setText("ВЫКЛ:(-:-)");
            return;
        }

        long leftMs = ruExpireAtMs - System.currentTimeMillis();
        if (leftMs < 0) leftMs = 0;

        long totalSec = leftMs / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;

        manualTimerTextView.setText(String.format(Locale.getDefault(),
                "ВЫКЛ:(%02d:%02d)", mm, ss));
    }

    private void updateActivePhaseTimerUi() {
        if (activePhaseTimerTextView == null) return;

        if (lastCalledPhaseStartedAtMs <= 0) {
            activePhaseTimerTextView.setText("Фаза активна: --:--");
            return;
        }

        long elapsedMs = Math.max(0, System.currentTimeMillis() - lastCalledPhaseStartedAtMs);
        long totalSec = elapsedMs / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;

        activePhaseTimerTextView.setText(String.format(Locale.getDefault(),
                "Фаза активна: %02d:%02d", mm, ss));
    }

    // ---------------- UI helpers ----------------

    private void setWifiConnected(boolean connected) {
        if (wifiIcon == null) return;
        wifiIcon.setColorFilter(connected ? COLOR_OK : COLOR_BAD);
    }

    private void setManualButtonState(boolean allowed, boolean requestActive) {
        if (manualToggle == null) return;

        manualToggleInternalUpdate = true;
        manualToggle.setChecked(allowed && requestActive);
        manualToggleInternalUpdate = false;

        int thumbColor = allowed ? COLOR_OK : COLOR_BAD;
        int trackColor = allowed ? COLOR_PHASE_DIM : COLOR_SURFACE_DIM;

        manualToggle.setThumbTintList(ColorStateList.valueOf(thumbColor));
        manualToggle.setTrackTintList(ColorStateList.valueOf(trackColor));
        manualToggle.setTextColor(allowed ? COLOR_TEXT_LIGHT : COLOR_SURFACE_DIM);
        manualToggle.setAlpha(allowed ? 1.0f : 0.9f);
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
        statusTextView.setText("Объект: " + objectId +
                " • Текущая: " + pi);
        addressTextView.setText(address);
    }

    private void initPhaseButtons() {
        for (int i = 0; i < phaseButtons.length; i++) {
            final int phase = i + 1;
            phaseButtons[i].setOnClickListener(v -> onPhaseButtonClicked(phase));
        }
    }

    private void initPhaseCallSwitches() {
        for (int i = 0; i < phaseCallSwitches.length; i++) {
            final int phase = i + 1;
            phaseCallSwitches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (phaseSwitchesInternalUpdate) return;

                if (!isChecked) {
                    onPhaseSwitchDeactivated(phase);
                    return;
                }

                onPhaseSwitchActivated(phase);
            });
        }
    }

    private void updatePhaseButtonsUi() {
        if (phaseButtons == null) return;

        int availablePhases = Math.min(imageUrls.size(), Math.min(phaseButtons.length, phaseCallSwitches.length));
        int shownPhase = viewPager.getCurrentItem() + 1;
        boolean enabled = !commandInProgress && consecutiveFails < 3;
        boolean phaseCallsEnabled = manualAllowed && wantManualRequest && enabled;
        for (int i = 0; i < phaseButtons.length; i++) {
            MaterialButton button = phaseButtons[i];
            SwitchMaterial phaseSwitch = phaseCallSwitches[i];
            boolean phaseAvailableForCall = i < availablePhases;

            button.setVisibility(phaseAvailableForCall ? View.VISIBLE : View.GONE);
            View switchContainer = (View) phaseSwitch.getParent();
            switchContainer.setVisibility(phaseAvailableForCall ? View.VISIBLE : View.GONE);

            if (!phaseAvailableForCall) {
                button.setEnabled(false);
                phaseSwitch.setEnabled(false);
                continue;
            }

            int phase = i + 1;
            boolean active = currentPhaseFromPi == phase;
            boolean shown = shownPhase == phase;
            int color = active ? COLOR_OK : COLOR_PHASE_DIM;
            boolean phaseSwitchActive = manualAllowed && wantManualRequest && activePhaseCallSwitch == phase;

            button.setEnabled(true);
            button.setBackgroundTintList(ColorStateList.valueOf(color));
            button.setTextColor(active ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT);
            button.setStrokeWidth(shown ? 4 : 0);
            button.setStrokeColor(ColorStateList.valueOf(COLOR_VIEWING_STROKE));
            button.setAlpha(active || shown ? 1.0f : 0.7f);

            phaseSwitchesInternalUpdate = true;
            phaseSwitch.setChecked(phaseSwitchActive);
            phaseSwitchesInternalUpdate = false;

            phaseSwitch.setEnabled(phaseCallsEnabled);
            phaseSwitch.setThumbTintList(ColorStateList.valueOf(phaseSwitchActive ? COLOR_OK : COLOR_BAD));
            phaseSwitch.setTrackTintList(ColorStateList.valueOf(phaseSwitchActive ? COLOR_PHASE_DIM : COLOR_BAD));
            phaseSwitch.setAlpha(phaseCallsEnabled ? 1.0f : 0.45f);
        }
    }

    // ---------------- Actions ----------------

    private void onManualToggled(boolean enabled) {

        if (!enabled) {
            // выключаем
            wantManualRequest = false;
            activePhaseCallSwitch = 0;
            stopRuTimer();
            updateManualTimerUi();

            manualOffInProgress = true;
            manualOffGuardUntilMs = System.currentTimeMillis() + MANUAL_OFF_GUARD_MS;

            safeManualOff(); // async
            setManualButtonState(true, false);
            updatePhaseButtonsUi();
            return;
        }
        manualOptimisticUntilMs = System.currentTimeMillis() + MANUAL_OPTIMISTIC_MS;

        wantManualRequest = true;
        manualOffInProgress = false;
        manualOffGuardUntilMs = 0;

        safeManualOn();
        resetRuTimerIfNeeded();
        setManualButtonState(true, true);
        updatePhaseButtonsUi();
    }

    private void onPhaseButtonClicked(int phaseToActivate) {
        resetRuTimerIfNeeded();

        if (phaseToActivate - 1 < imageUrls.size()) {
            viewPager.setCurrentItem(phaseToActivate - 1, true);
        }
    }

    private void onPhaseSwitchDeactivated(int phaseToDeactivate) {
        if (!manualAllowed || !wantManualRequest || commandInProgress) return;
        if (activePhaseCallSwitch != phaseToDeactivate) return;

        activePhaseCallSwitch = 0;
        if (pendingPhase == phaseToDeactivate) {
            pendingPhase = -1;
            commandInProgress = false;
        }
        updatePhaseButtonsUi();
    }

    private void onPhaseSwitchActivated(int phaseToActivate) {
        resetRuTimerIfNeeded();

        if (!manualAllowed || !wantManualRequest || commandInProgress) return;

        activePhaseCallSwitch = phaseToActivate;
        updatePhaseButtonsUi();
        showPhaseCallConfirmationDialog(phaseToActivate);
    }

    private void showPhaseCallConfirmationDialog(int phaseToActivate) {
        new AlertDialog.Builder(this)
                .setTitle("Подтверждение")
                .setMessage("Вызвать фазу " + phaseToActivate + "?")
                .setPositiveButton("Вызвать", (dialog, which) -> {
                    lastCalledPhaseStartedAtMs = System.currentTimeMillis();
                    updateActivePhaseTimerUi();
                    sendActivate(phaseToActivate);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void sendActivate(int phase) {
        commandInProgress = true;
        pendingPhase = phase;
        pendingUntilMs = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        updatePhaseButtonsUi();

        Toast.makeText(this, "Команда отправлена, фаза будет вызвана по истечении времени безопасности.", Toast.LENGTH_LONG).show();

        piApi.activate(new ActivateRequest(phase)).enqueue(new Callback<ActivateResponse>() {
            @Override
            public void onResponse(Call<ActivateResponse> call, Response<ActivateResponse> response) {
                if (!response.isSuccessful() || response.body() == null || !response.body().accepted) {
                    commandInProgress = false;
                    pendingPhase = -1;
                    updatePhaseButtonsUi();
                }
            }

            @Override
            public void onFailure(Call<ActivateResponse> call, Throwable t) {
                commandInProgress = false;
                pendingPhase = -1;
                updatePhaseButtonsUi();
            }
        });
    }

    private void checkConfirmation() {
        if (!commandInProgress || pendingPhase <= 0) return;

        if (currentPhaseFromPi == pendingPhase) {
            commandInProgress = false;
            pendingPhase = -1;
            updatePhaseButtonsUi();
            return;
        }

        if (System.currentTimeMillis() > pendingUntilMs) {
            commandInProgress = false;
            pendingPhase = -1;
            updatePhaseButtonsUi();
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
