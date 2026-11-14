package org.levimc.launcher.ui.activities;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.material.button.MaterialButton;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.auth.MsftAuthManager;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.CryptoUtils;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MsftLoginActivity extends BaseActivity {
    public static final String EXTRA_LOGIN_COMPLETED = "ms_login_completed";
    public static final String EXTRA_LOGIN_NAME = "ms_login_name";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicLong loginGeneration = new AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View methodPanel;
    private View devicePanel;
    private View webPanel;
    private View progressOverlay;
    private TextView methodError;
    private TextView deviceCodeText;
    private TextView deviceUrlText;
    private TextView deviceExpiryText;
    private TextView deviceStatusText;
    private TextView progressText;
    private MaterialButton copyCodeButton;
    private MaterialButton openBrowserButton;
    private WebView webView;
    private Future<?> loginTask;
    private MsaDeviceCode deviceCode;
    private String state;
    private boolean redirectHandled;
    private boolean externalBrowserOpened;
    private long deviceGeneration;
    private long webGeneration;

    private final Runnable expiryRunnable = new Runnable() {
        @Override
        public void run() {
            MsaDeviceCode current = deviceCode;
            if (current == null || completed.get() || cancelled.get() || deviceGeneration != loginGeneration.get()) {
                return;
            }
            long remainingMs = Math.max(0L, current.getExpireTimeMs() - System.currentTimeMillis());
            long totalSeconds = (remainingMs + 999L) / 1000L;
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            deviceExpiryText.setText(getString(R.string.ms_device_code_expires, minutes, seconds));
            if (remainingMs > 0L) {
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_msft_login);
        View page = findViewById(R.id.msft_login_root);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            int bottomPadding = page.getPaddingBottom();
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                int keyboard = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding + keyboard);
                return insets;
            });
            page.requestApplyInsets();
        }

        methodPanel = findViewById(R.id.msft_method_panel);
        devicePanel = findViewById(R.id.msft_device_panel);
        webPanel = findViewById(R.id.msft_web_panel);
        progressOverlay = findViewById(R.id.msft_progress_overlay);
        methodError = findViewById(R.id.msft_method_error);
        deviceCodeText = findViewById(R.id.msft_device_code);
        deviceUrlText = findViewById(R.id.msft_device_url);
        deviceExpiryText = findViewById(R.id.msft_device_expiry);
        deviceStatusText = findViewById(R.id.msft_device_status);
        progressText = findViewById(R.id.msft_progress_text);
        copyCodeButton = findViewById(R.id.msft_copy_code);
        openBrowserButton = findViewById(R.id.msft_open_browser);

        MaterialButton deviceButton = findViewById(R.id.msft_device_login_button);
        MaterialButton webButton = findViewById(R.id.msft_web_login_button);
        MaterialButton deviceCancelButton = findViewById(R.id.msft_device_cancel);
        MaterialButton webCancelButton = findViewById(R.id.msft_web_cancel);

        applyPrimaryAccent(webButton, openBrowserButton);
        DynamicAnim.applyPressScale(deviceButton);
        DynamicAnim.applyPressScale(webButton);
        DynamicAnim.applyPressScale(copyCodeButton);
        DynamicAnim.applyPressScale(openBrowserButton);
        DynamicAnim.applyPressScale(deviceCancelButton);
        DynamicAnim.applyPressScale(webCancelButton);

        deviceButton.setOnClickListener(v -> startDeviceCodeLogin());
        webButton.setOnClickListener(v -> startWebViewLogin());
        copyCodeButton.setOnClickListener(v -> copyDeviceCode());
        openBrowserButton.setOnClickListener(v -> openDeviceLoginPage());
        deviceCancelButton.setOnClickListener(v -> showMethodChooser(null));
        webCancelButton.setOnClickListener(v -> showMethodChooser(null));
        findViewById(R.id.msft_progress_cancel).setOnClickListener(v -> showMethodChooser(null));

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        showMethodChooser(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (externalBrowserOpened && deviceCode != null && isCurrentLogin(deviceGeneration)) {
            deviceStatusText.setText(R.string.ms_device_code_checking);
        }
        mainHandler.removeCallbacks(expiryRunnable);
        if (deviceCode != null) mainHandler.post(expiryRunnable);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(expiryRunnable);
        super.onPause();
    }

    private int getAccentColor() {
        int accent = new PersonalizationManager(this).getAccentColor();
        return accent != 0 ? accent : getColor(R.color.primary);
    }

    private void applyPrimaryAccent(Button... buttons) {
        int accent = getAccentColor();
        for (Button button : buttons) {
            if (button != null) {
                button.setBackgroundTintList(ColorStateList.valueOf(accent));
                button.setTextColor(Color.WHITE);
            }
        }
        ProgressBar progressBar = findViewById(R.id.msft_progress_bar);
        if (progressBar != null) {
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
        ProgressBar deviceProgress = findViewById(R.id.msft_device_progress);
        if (deviceProgress != null) {
            deviceProgress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView = new WebView(this);
        webView.setId(R.id.msft_login_webview);
        ((FrameLayout) findViewById(R.id.msft_web_container)).addView(webView,
                new FrameLayout.LayoutParams(-1, -1));
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " LeviLauncher");
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);

        webView.setBackgroundColor(getColor(R.color.background));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                return handleWebNavigation(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && !redirectHandled && isCurrentWebLogin()) {
                    String message = error != null && error.getDescription() != null
                            ? error.getDescription().toString()
                            : getString(R.string.ms_login_failed);
                    showMethodChooser(message);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (isCurrentWebLogin()) {
                    showMethodChooser(getString(R.string.ms_login_ssl_failed));
                }
            }
        });
    }

    private void startDeviceCodeLogin() {
        cancelCurrentLogin();
        long generation = loginGeneration.get();
        deviceGeneration = generation;
        externalBrowserOpened = false;
        methodError.setVisibility(View.GONE);
        methodPanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.GONE);
        devicePanel.setVisibility(View.VISIBLE);
        progressOverlay.setVisibility(View.GONE);
        deviceCode = null;
        deviceCodeText.setText(R.string.ms_device_code_waiting_code);
        deviceUrlText.setText(R.string.ms_device_code_requesting);
        deviceExpiryText.setText("");
        deviceStatusText.setText(R.string.ms_device_code_requesting);
        copyCodeButton.setEnabled(false);
        openBrowserButton.setEnabled(false);
        cancelled.set(false);

        loginTask = executor.submit(() -> {
            try {
                BedrockAuthManager authManager = MsftAuthManager.loginWithDeviceCode(
                        code -> runOnUiThread(() -> showDeviceCode(code, generation)),
                        () -> !isCurrentLogin(generation),
                        deviceState -> updateDeviceState(deviceState, generation)
                );
                if (!isCurrentLogin(generation)) {
                    return;
                }
                runOnUiThread(() -> {
                    if (isCurrentLogin(generation)) {
                        deviceStatusText.setText(R.string.ms_login_auth_xbox_device);
                        showProgress(R.string.ms_login_fetch_minecraft_identity);
                    }
                });
                MsftAccountStore.MsftAccount account = MsftAuthManager.saveAccountAndActivateWithRetry(
                        this,
                        authManager,
                        () -> !isCurrentLogin(generation),
                        () -> runOnUiThread(() -> {
                            if (isCurrentLogin(generation)) {
                                showProgress(R.string.ms_login_retrying);
                            }
                        })
                );
                completeLogin(account, generation);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (isCurrentLogin(generation)) {
                    showFailure(e, generation);
                }
            }
        });
    }

    private void showDeviceCode(MsaDeviceCode code, long generation) {
        if (!isCurrentLogin(generation) || isFinishing() || isDestroyed()) {
            return;
        }
        deviceCode = code;
        deviceCodeText.setText(code.getUserCode());
        deviceUrlText.setText(code.getVerificationUri());
        deviceStatusText.setText(R.string.ms_device_code_waiting);
        copyCodeButton.setEnabled(true);
        openBrowserButton.setEnabled(true);
        mainHandler.removeCallbacks(expiryRunnable);
        mainHandler.post(expiryRunnable);
    }

    private void updateDeviceState(MsftAuthManager.DeviceCodeState deviceState, long generation) {
        runOnUiThread(() -> {
            if (!isCurrentLogin(generation) || devicePanel.getVisibility() != View.VISIBLE) {
                return;
            }
            if (deviceState == MsftAuthManager.DeviceCodeState.RETRYING) {
                deviceStatusText.setText(R.string.ms_device_code_reconnecting);
            } else if (deviceState == MsftAuthManager.DeviceCodeState.AUTHORIZED) {
                deviceStatusText.setText(R.string.ms_device_code_authorized);
            } else {
                deviceStatusText.setText(R.string.ms_device_code_waiting);
            }
        });
    }

    private void startWebViewLogin() {
        cancelCurrentLogin();
        if (webView == null) configureWebView();
        webGeneration = loginGeneration.get();
        externalBrowserOpened = false;
        methodError.setVisibility(View.GONE);
        methodPanel.setVisibility(View.GONE);
        devicePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.VISIBLE);
        progressOverlay.setVisibility(View.GONE);
        cancelled.set(false);
        redirectHandled = false;
        state = CryptoUtils.randomString(32);
        webView.loadUrl(MsftAuthManager.buildAuthorizeUrl(state));
    }

    private boolean handleWebNavigation(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri current = Uri.parse(url);
        Uri redirect = Uri.parse(MsftAuthManager.getAppConfig().getRedirectUri());
        boolean matches = TextUtils.equals(current.getScheme(), redirect.getScheme())
                && TextUtils.equals(current.getHost(), redirect.getHost())
                && TextUtils.equals(current.getPath(), redirect.getPath());
        if (!matches) {
            return false;
        }
        if (!redirectHandled) {
            redirectHandled = true;
            handleWebRedirect(url);
        }
        return true;
    }

    private void handleWebRedirect(String url) {
        long generation = webGeneration;
        if (!isCurrentLogin(generation)) {
            return;
        }
        Uri uri = Uri.parse(url);
        String returnedState = uri.getQueryParameter("state");
        String error = uri.getQueryParameter("error");
        String errorDescription = uri.getQueryParameter("error_description");
        String code = uri.getQueryParameter("code");

        if (!TextUtils.equals(state, returnedState)) {
            showMethodChooser(getString(R.string.ms_login_state_failed));
            return;
        }
        if (!TextUtils.isEmpty(error)) {
            showMethodChooser(!TextUtils.isEmpty(errorDescription) ? errorDescription : error);
            return;
        }
        if (TextUtils.isEmpty(code)) {
            showMethodChooser(getString(R.string.ms_login_missing_code));
            return;
        }

        webView.stopLoading();
        showProgress(R.string.ms_login_exchanging);
        cancelled.set(false);
        loginTask = executor.submit(() -> {
            try {
                BedrockAuthManager authManager = MsftAuthManager.loginWithCode(code);
                if (!isCurrentLogin(generation)) {
                    return;
                }
                runOnUiThread(() -> {
                    if (isCurrentLogin(generation)) {
                        showProgress(R.string.ms_login_fetch_minecraft_identity);
                    }
                });
                MsftAccountStore.MsftAccount account = MsftAuthManager.saveAccountAndActivateWithRetry(
                        this,
                        authManager,
                        () -> !isCurrentLogin(generation),
                        () -> runOnUiThread(() -> {
                            if (isCurrentLogin(generation)) {
                                showProgress(R.string.ms_login_retrying);
                            }
                        })
                );
                completeLogin(account, generation);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (isCurrentLogin(generation)) {
                    showFailure(e, generation);
                }
            }
        });
    }

    private void showProgress(int messageRes) {
        dismissKeyboard();
        progressText.setText(messageRes);
        progressOverlay.setVisibility(View.VISIBLE);
    }

    private void completeLogin(MsftAccountStore.MsftAccount account, long generation) {
        if (!isCurrentLogin(generation)) {
            return;
        }
        String name = account != null && !TextUtils.isEmpty(account.minecraftUsername)
                ? account.minecraftUsername
                : account != null && !TextUtils.isEmpty(account.xboxGamertag)
                ? account.xboxGamertag
                : getString(R.string.not_signed_in);
        runOnUiThread(() -> {
            if (!isCurrentLogin(generation) || !completed.compareAndSet(false, true)) {
                return;
            }
            mainHandler.removeCallbacks(expiryRunnable);
            Intent result = new Intent();
            result.putExtra(EXTRA_LOGIN_COMPLETED, true);
            result.putExtra(EXTRA_LOGIN_NAME, name);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void showFailure(Throwable throwable, long generation) {
        String message = MsftAuthManager.describeError(this, throwable);
        runOnUiThread(() -> {
            if (isCurrentLogin(generation)) {
                showMethodChooser(message);
            }
        });
    }

    private void showMethodChooser(String error) {
        cancelCurrentLogin();
        dismissKeyboard();
        if (webView != null) webView.stopLoading();
        externalBrowserOpened = false;
        methodPanel.setVisibility(View.VISIBLE);
        devicePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.GONE);
        progressOverlay.setVisibility(View.GONE);
        if (TextUtils.isEmpty(error)) {
            methodError.setText("");
            methodError.setVisibility(View.GONE);
        } else {
            methodError.setText(error);
            methodError.setVisibility(View.VISIBLE);
        }
    }

    private void copyDeviceCode() {
        MsaDeviceCode current = deviceCode;
        if (current == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ms_device_code_label), current.getUserCode()));
        Toast.makeText(this, R.string.ms_device_code_copied, Toast.LENGTH_SHORT).show();
    }

    private void openDeviceLoginPage() {
        MsaDeviceCode current = deviceCode;
        if (current == null) {
            return;
        }
        Uri loginUri = Uri.parse(current.getVerificationUri()).buildUpon()
                .appendQueryParameter("otc", current.getUserCode()).build();
        if (!"https".equalsIgnoreCase(loginUri.getScheme())) {
            Toast.makeText(this, R.string.ms_login_ssl_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String browserPackage = CustomTabsClient.getPackageName(this, null);
            CustomTabsIntent customTabs = new CustomTabsIntent.Builder()
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF).setShowTitle(true).build();
            if (browserPackage != null) {
                customTabs.intent.setPackage(browserPackage);
                if (CustomTabsClient.isEphemeralBrowsingSupported(this, browserPackage)) {
                    customTabs.intent.putExtra(CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING, true);
                }
            }
            externalBrowserOpened = true;
            customTabs.launchUrl(this, loginUri);
            deviceStatusText.setText(R.string.ms_device_code_browser_opened);
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            try {
                externalBrowserOpened = true;
                startActivity(new Intent(Intent.ACTION_VIEW, loginUri).addCategory(Intent.CATEGORY_BROWSABLE));
                deviceStatusText.setText(R.string.ms_device_code_browser_opened);
            } catch (ActivityNotFoundException | IllegalArgumentException ignored) {
                externalBrowserOpened = false;
                Toast.makeText(this, R.string.ms_no_browser, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleBack() {
        if (methodPanel.getVisibility() == View.VISIBLE) {
            cancelCurrentLogin();
            setResult(RESULT_CANCELED);
            finish();
        } else {
            showMethodChooser(null);
        }
    }

    private boolean isCurrentLogin(long generation) {
        return generation == loginGeneration.get() && !cancelled.get() && !Thread.currentThread().isInterrupted();
    }

    private boolean isCurrentWebLogin() {
        return webPanel != null && webPanel.getVisibility() == View.VISIBLE && isCurrentLogin(webGeneration);
    }

    private void cancelCurrentLogin() {
        loginGeneration.incrementAndGet();
        cancelled.set(true);
        mainHandler.removeCallbacks(expiryRunnable);
        Future<?> currentTask = loginTask;
        loginTask = null;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        deviceCode = null;
    }

    private void dismissKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    @Override
    protected void hideSystemUI() {
        super.hideSystemUI();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    protected void onDestroy() {
        cancelCurrentLogin();
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.clearHistory();
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}
