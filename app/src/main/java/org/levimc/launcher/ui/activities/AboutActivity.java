package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import com.google.android.material.button.MaterialButton;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.UiKit;

public class AboutActivity extends BaseActivity {

    private static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/62042544?v=4&s=160";
    private static final String URL_AFDIAN = "https://afdian.com/a/DreamGuXiang";
    private static final String URL_PATREON = "https://www.patreon.com/c/DreamGuXiang";
    private static final String URL_REPO = "https://github.com/LiteLDev/LeviLauncher-Android";
    private static final String URL_ORG = "https://github.com/LiteLDev";
    private static final String URL_ISSUES = "https://github.com/LiteLDev/LeviLauncher-Android/issues";
    private static final String MAINTAINER_AVATAR_URL = "https://yt3.googleusercontent.com/ft4khqXZ_fQn-DbSLg91kQy3_JUQ_73rbg18nOcmMtunX5bq25jzrThWQAk9YsFkTKFesUL7sg8=s160-c-k-c0x00ffffff-no-rj";
    private static final String URL_YOUTUBE = "https://www.youtube.com/c/mrpokeg";
    private static final String URL_LICENSE = "https://github.com/LiteLDev/LeviLauncher-Android/blob/HEAD/LICENSE";
    private static final String URL_RHYMC = "https://www.rhymc.com/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setActiveNavTab(R.id.nav_tab_about);
        setupLinks();
        styleActions();
        loadAvatars();
    }

    private void styleActions() {
        tintChip(R.id.about_authors_chip, R.id.about_authors_icon, UiKit.accent(this));
        tintChip(R.id.about_thanks_chip, R.id.about_thanks_icon, UiKit.AMBER);
        tintChip(R.id.about_source_chip, R.id.about_source_icon, UiKit.BLUE);
        clearIconTint(R.id.btn_afdian);
        clearIconTint(R.id.btn_patreon);
        clearIconTint(R.id.btn_youtube_maintainer);
        UiKit.applyPrimary(findViewById(R.id.btn_star_fork));
    }

    private void tintChip(int chipId, int iconId, int color) {
        UiKit.tintIconChip(findViewById(chipId), findViewById(iconId), color);
    }

    private void clearIconTint(int buttonId) {
        MaterialButton button = findViewById(buttonId);
        if (button != null) button.setIconTint((ColorStateList) null);
    }

    private void loadAvatars() {
        RequestManager images = Glide.with(this);
        loadAvatar(images, R.id.author_avatar, AVATAR_URL);
        loadAvatar(images, R.id.maintainer_avatar, MAINTAINER_AVATAR_URL);
    }

    private void loadAvatar(RequestManager images, int viewId, String url) {
        ImageView avatar = findViewById(viewId);
        int size = Math.round(UiKit.dp(this, 36));
        images.load(url).placeholder(R.drawable.ic_qa_account).error(R.drawable.ic_qa_account)
                .circleCrop().override(size, size).dontAnimate().into(avatar);
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
        View btn = findViewById(viewId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (android.content.ActivityNotFoundException ignored) {}
        });
    }
}
