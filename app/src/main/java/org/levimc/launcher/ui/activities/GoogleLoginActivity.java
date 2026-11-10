package org.levimc.launcher.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.play.PlayClient;
import org.levimc.launcher.core.play.PlayException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Signs in to the Google account that owns Minecraft, through Google's own sign-in page.
 * The page leaves a one-time oauth_token cookie once the user is signed in; it is exchanged
 * for a long-lived account token and the cookie jar is cleared again. The password never
 * passes through the launcher.
 */
public class GoogleLoginActivity extends BaseActivity {
    private static final String SIGN_IN_URL = "https://accounts.google.com/EmbeddedSetup";
    private static final String OAUTH_COOKIE = "oauth_token";
    /** The signed-in page marks the account it shows with data-email. */
    private static final String READ_EMAIL_JS =
            "(function(){var e=document.querySelector('[data-email]');return e?e.getAttribute('data-email'):'';})();";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private View working;
    private boolean exchanging;

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_google_login);

        findViewById(R.id.google_login_back).setOnClickListener(v -> finish());
        working = findViewById(R.id.google_login_working);
        ProgressBar progress = findViewById(R.id.google_login_progress);
        webView = findViewById(R.id.google_login_web);

        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setIndeterminate(newProgress == 0);
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String oauthToken = cookieValue(CookieManager.getInstance().getCookie(url), OAUTH_COOKIE);
                if (oauthToken == null || exchanging) return;
                view.evaluateJavascript(READ_EMAIL_JS, result -> {
                    String email = result == null ? "" : result.replace("\"", "").trim();
                    if ("null".equals(email)) email = "";
                    exchange(email, oauthToken);
                });
            }
        });
        webView.loadUrl(SIGN_IN_URL);
    }

    private void exchange(String email, String oauthToken) {
        if (exchanging) return;
        exchanging = true;
        working.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        executor.execute(() -> {
            try {
                String accountEmail = PlayClient.signIn(this, email, oauthToken);
                runOnUiThread(() -> {
                    clearCookies();
                    Toast.makeText(this, getString(R.string.game_download_signed_in_as, accountEmail), Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (PlayException e) {
                runOnUiThread(() -> {
                    clearCookies();
                    Toast.makeText(this, getString(R.string.game_download_sign_in_failed,
                            TextUtils.isEmpty(e.getMessage()) ? e.kind.name() : e.getMessage()), Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        });
    }

    private static void clearCookies() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    private static String cookieValue(String cookies, String name) {
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf('=');
            if (separator > 0 && trimmed.substring(0, separator).equals(name)) {
                String value = trimmed.substring(separator + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
