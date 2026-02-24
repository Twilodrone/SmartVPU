package com.example.vpu.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

import com.example.vpu.R;
import com.example.vpu.ui.vpu.VpuActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText usernameEditText;
    private TextInputEditText passwordEditText;
    private MaterialButton loginButton;
    private ProgressBar progressBar;

    // тестовые креды
    private static final String DEMO_USER = "admin";
    private static final String DEMO_PASS = "admin";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usernameEditText = findViewById(R.id.usernameEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        progressBar = findViewById(R.id.progressBar);

        progressBar.setVisibility(View.GONE);

        loginButton.setOnClickListener(v -> onLoginClicked());
    }

    private void onLoginClicked() {
        String user = safeText(usernameEditText);
        String pass = safeText(passwordEditText);

        if (user.isEmpty() || pass.isEmpty()) {
            showSnackbar("Введите логин и пароль");
            return;
        }

        // “Загрузка” для вида (можно убрать вообще)
        progressBar.setVisibility(View.VISIBLE);
        loginButton.setEnabled(false);

        boolean ok = DEMO_USER.equals(user) && DEMO_PASS.equals(pass);

        progressBar.setVisibility(View.GONE);
        loginButton.setEnabled(true);

        if (!ok) {
            showSnackbar("Неверный логин или пароль");
            return;
        }

        openVpuDemo();
    }

    private void openVpuDemo() {
        // ТЕСТОВЫЕ ДАННЫЕ
        int objectId = 3649;

        ArrayList<String> images = new ArrayList<>();

        String base = "http://192.168.1.10:8001/files/3649/2026.02.19/";
        images.add(base + "1.jpg");
        images.add(base + "2.jpg");
        images.add(base + "3.jpg");

        Intent intent = new Intent(this, VpuActivity.class);
        intent.putExtra("objectId", objectId);
        intent.putStringArrayListExtra("images", images);
        startActivity(intent);
    }

    private String safeText(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_LONG).show();
    }
}
