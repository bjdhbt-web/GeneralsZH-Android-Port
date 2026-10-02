package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

public class SubscriptionGateActivity extends Activity {
    private EditText username;
    private EditText password;
    private Button loginButton;
    private ProgressBar progress;
    private TextView status;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.subscription_title);
        buildUi();

        String saved = SubscriptionManager.username(this);
        if (saved != null && !saved.isEmpty()) username.setText(saved);

        if (SubscriptionManager.hasToken(this)) {
            validateExistingSession();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        InsetUtil.applySafeInsets(scroll);

        TextView title = new TextView(this);
        title.setText(R.string.subscription_title);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subscription_subtitle);
        subtitle.setTextSize(15);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        root.addView(subtitle, subLp);

        username = new EditText(this);
        username.setHint(R.string.subscription_username);
        username.setSingleLine(true);
        username.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = dp(24);
        root.addView(username, fieldLp);

        password = new EditText(this);
        password.setHint(R.string.subscription_password);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        passLp.topMargin = dp(12);
        root.addView(password, passLp);

        loginButton = new Button(this);
        loginButton.setText(R.string.subscription_login);
        loginButton.setOnClickListener(v -> login());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(18);
        root.addView(loginButton, btnLp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressLp.topMargin = dp(14);
        root.addView(progress, progressLp);

        status = new TextView(this);
        status.setText(R.string.subscription_device_notice);
        status.setTextSize(14);
        status.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(14);
        root.addView(status, statusLp);
    }

    private void validateExistingSession() {
        if (busy) return;
        setBusy(true, getString(R.string.subscription_checking));
        new Thread(() -> {
            try {
                SubscriptionApi.Result r = SubscriptionApi.validate(this);
                runOnUiThread(() -> {
                    if (r.ok) {
                        saveAndEnter(r);
                    } else {
                        SubscriptionManager.clearSession(this);
                        setBusy(false, messageFor(r));
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (SubscriptionManager.hasValidOfflineLease(this)) {
                        enterApp();
                    } else {
                        setBusy(false, getString(R.string.subscription_network_required));
                    }
                });
            }
        }, "AbodehSubscriptionValidate").start();
    }

    private void login() {
        if (busy) return;
        final String u = username.getText().toString().trim();
        final String p = password.getText().toString();
        if (u.isEmpty() || p.isEmpty()) {
            status.setText(R.string.subscription_missing_fields);
            return;
        }

        setBusy(true, getString(R.string.subscription_signing_in));
        new Thread(() -> {
            try {
                SubscriptionApi.Result challenge = SubscriptionApi.challenge(this, u);
                if (!challenge.ok || challenge.challengeId == null || challenge.challenge == null) {
                    runOnUiThread(() -> setBusy(false, messageFor(challenge)));
                    return;
                }
                SubscriptionApi.Result result = SubscriptionApi.login(
                    this, u, p, challenge.challengeId, challenge.challenge);
                runOnUiThread(() -> {
                    if (result.ok && result.token != null) {
                        saveAndEnter(result);
                    } else {
                        setBusy(false, messageFor(result));
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> setBusy(false, getString(R.string.subscription_network_error)));
            }
        }, "AbodehSubscriptionLogin").start();
    }

    private void saveAndEnter(SubscriptionApi.Result r) {
        SubscriptionManager.saveSession(
            this,
            r.token != null ? r.token : SubscriptionManager.token(this),
            r.username != null ? r.username : username.getText().toString().trim(),
            r.subscriptionExpires,
            r.offlineUntil,
            "active");
        enterApp();
    }

    private void enterApp() {
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!value);
        username.setEnabled(!value);
        password.setEnabled(!value);
        status.setText(message);
    }

    private String messageFor(SubscriptionApi.Result r) {
        if (r == null) return getString(R.string.subscription_error);
        switch (r.code) {
            case "DEVICE_MISMATCH":
                return getString(R.string.subscription_device_mismatch);
            case "EXPIRED":
                return getString(R.string.subscription_expired);
            case "BLOCKED":
                return getString(R.string.subscription_blocked);
            case "INVALID_CREDENTIALS":
                return getString(R.string.subscription_invalid_credentials);
            case "RATE_LIMITED":
                return getString(R.string.subscription_rate_limited);
            default:
                return r.message != null && !r.message.isEmpty()
                    ? r.message : getString(R.string.subscription_error);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
