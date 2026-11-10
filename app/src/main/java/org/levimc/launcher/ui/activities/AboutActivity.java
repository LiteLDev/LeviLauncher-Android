package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.widget.UiKit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends BaseActivity {

    private static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/62042544?v=4";
    private static final String URL_AFDIAN = "https://afdian.com/a/DreamGuXiang";
    private static final String URL_PATREON = "https://www.patreon.com/c/DreamGuXiang";
    private static final String URL_REPO = "https://github.com/LiteLDev/LeviLauncher-Android";
    private static final String URL_ORG = "https://github.com/LiteLDev";
    private static final String URL_ISSUES = "https://github.com/LiteLDev/LeviLauncher-Android/issues";
    private static final String MAINTAINER_AVATAR_URL = "https://yt3.googleusercontent.com/ft4khqXZ_fQn-DbSLg91kQy3_JUQ_73rbg18nOcmMtunX5bq25jzrThWQAk9YsFkTKFesUL7sg8=s160-c-k-c0x00ffffff-no-rj";
    private static final String URL_YOUTUBE = "https://www.youtube.com/c/mrpokeg";
    private static final String URL_LICENSE = "https://github.com/LiteLDev/LeviLauncher-Android/blob/HEAD/LICENSE";
    private static final String URL_RHYMC = "https://www.rhymc.com/";

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
        if (authorBadge != null) UiKit.tintSoftAccent(authorBadge, 7);
        if (maintainerBadge != null) UiKit.tintSoftAccent(maintainerBadge, 7);

        tintChip(R.id.about_authors_chip, R.id.about_authors_icon, UiKit.accent(this));
        tintChip(R.id.about_thanks_chip, R.id.about_thanks_icon, UiKit.AMBER);
        tintChip(R.id.about_rhymc_chip, R.id.about_rhymc_icon, UiKit.BLUE);
        tintChip(R.id.about_sponsors_chip, R.id.about_sponsors_icon, UiKit.ROSE);
        tintChip(R.id.about_source_chip, R.id.about_source_icon, UiKit.BLUE);

        // Brand icons keep their own colors (the YouTube logo has a white play mark).
        clearIconTint(R.id.btn_afdian);
        clearIconTint(R.id.btn_patreon);
        clearIconTint(R.id.btn_youtube_maintainer);

        MaterialButton starFork = findViewById(R.id.btn_star_fork);
        if (starFork != null) UiKit.applyPrimary(starFork);
    }

    private void tintChip(int chipId, int iconId, int color) {
        View chip = findViewById(chipId);
        ImageView icon = findViewById(iconId);
        if (chip != null) UiKit.tintIconChip(chip, icon, color);
    }

    private void clearIconTint(int buttonId) {
        MaterialButton button = findViewById(buttonId);
        if (button != null) button.setIconTint((ColorStateList) null);
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_about);
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
        setupLinkButton(R.id.btn_license, URL_LICENSE);
        setupLinkButton(R.id.btn_rhymc_website, URL_RHYMC);
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
}
