package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends BaseActivity {

    private static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/62042544?v=4";
    private static final String URL_AFDIAN = "https://afdian.com/a/DreamGuXiang";
    private static final String URL_PATREON = "https://www.patreon.com/c/DreamGuXiang";
    private static final String URL_REPO = "https://github.com/LiteLDev/LeviLaunchroid";
    private static final String URL_ORG = "https://github.com/LiteLDev";
    private static final String URL_ISSUES = "https://github.com/LiteLDev/LeviLaunchroid/issues";
    private static final String MAINTAINER_AVATAR_URL = "https://yt3.googleusercontent.com/ft4khqXZ_fQn-DbSLg91kQy3_JUQ_73rbg18nOcmMtunX5bq25jzrThWQAk9YsFkTKFesUL7sg8=s160-c-k-c0x00ffffff-no-rj";
    private static final String URL_YOUTUBE = "https://www.youtube.com/c/mrpokeg";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setupNavBar();

        loadAvatars();
        setupLinks();
        styleBadges();

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
    }

    private void styleBadges() {
        TextView authorBadge = findViewById(R.id.author_badge);
        TextView maintainerBadge = findViewById(R.id.maintainer_badge);

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(4 * getResources().getDisplayMetrics().density);
            if (authorBadge != null) {
                authorBadge.setBackground(gd);
                authorBadge.setTextColor(android.graphics.Color.WHITE);
            }
            if (maintainerBadge != null) {
                maintainerBadge.setBackground(gd);
                maintainerBadge.setTextColor(android.graphics.Color.WHITE);
            }
        }
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_about);
        findViewById(R.id.nav_tab_about).setOnClickListener(v -> {});
    }

    private void loadAvatars() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.author_avatar);
        com.microsoft.xbox.idp.toolkit.CircleImageView maintainerAvatar = findViewById(R.id.maintainer_avatar);

        executor.execute(() -> {
            try {
                if (avatar != null) {
                    Response resp = client.newCall(new Request.Builder().url(AVATAR_URL).build()).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                        runOnUiThread(() -> {
                            if (bmp != null) avatar.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {}

            try {
                if (maintainerAvatar != null) {
                    Response resp = client.newCall(new Request.Builder().url(MAINTAINER_AVATAR_URL).build()).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                        runOnUiThread(() -> {
                            if (bmp != null) maintainerAvatar.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void setupLinks() {
        setupLinkButton(R.id.btn_afdian, URL_AFDIAN);
        setupLinkButton(R.id.btn_patreon, URL_PATREON);
        setupLinkButton(R.id.btn_github_repo, URL_REPO);
        setupLinkButton(R.id.btn_github_org, URL_ORG);
        setupLinkButton(R.id.btn_issues, URL_ISSUES);
        setupLinkButton(R.id.btn_star_fork, URL_REPO);
        setupLinkButton(R.id.btn_youtube_maintainer, URL_YOUTUBE);
    }

    private void setupLinkButton(int viewId, String url) {
        TextView btn = findViewById(viewId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });
        DynamicAnim.applyPressScale(btn);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
