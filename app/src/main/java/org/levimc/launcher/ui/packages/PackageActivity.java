package org.levimc.launcher.ui.packages;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.Readme;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.activities.BaseActivity;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class PackageActivity extends BaseActivity {
    public static final String EXTRA_PACKAGE_ID = "package_id";
    public static final String EXTRA_VERSION = "version";
    /** Result extra: the owner the Browse tab that opened this page lists ("More by owner"). */
    public static final String RESULT_AUTHOR = "author";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Cancel ctx = new Cancel();

    private String identifier = "";
    @Nullable
    private GameVersion preferredInstance;
    @Nullable
    private GameVersion chosenInstance;
    @Nullable
    private CatalogModels.Pkg pkg;
    @Nullable
    private CatalogModels.Variant activeVariant;
    private List<CatalogModels.Variant> variants = new ArrayList<>();
    private LeviLamina.Db db;
    private boolean mappingReady;
    private String selectedGame = PackageActivity.VersionGrouping.ALL;
    private int loadRequest;
    private boolean bindingVariants;
    private int accent;

    private View content;
    private ProgressBar loading;
    private View errorView;
    private TextView errorText;
    private TextView tabDetails;
    private TextView tabVersions;
    private View detailsPane;
    private View versionsPane;
    private PackageActivity.ReadmeView readme;
    private TextView readmeEmpty;
    private ProgressBar readmeLoading;
    private View variantRow;
    private Spinner variantSpinner;
    private RadioGroup gameChips;
    private View gameScroll;
    private TextView noInstances;
    private RecyclerView versionList;
    private TextView versionsEmpty;
    private PackageActivity.VersionAdapter versionAdapter;

    public static Intent intent(Context context, String identifier, @Nullable GameVersion instance) {
        Intent intent = new Intent(context, PackageActivity.class);
        intent.putExtra(EXTRA_PACKAGE_ID, identifier);
        if (instance != null) {
            intent.putExtra(EXTRA_VERSION, instance);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_package);
        String id = getIntent().getStringExtra(EXTRA_PACKAGE_ID);
        identifier = id == null ? "" : id.trim();
        preferredInstance = getIntent().getParcelableExtra(EXTRA_VERSION);
        accent = PkgUi.accent(this);

        content = findViewById(R.id.pkg_pkg_content);
        loading = findViewById(R.id.pkg_pkg_loading);
        errorView = findViewById(R.id.pkg_pkg_error);
        errorText = findViewById(R.id.pkg_pkg_error_text);
        tabDetails = findViewById(R.id.pkg_pkg_tab_details);
        tabVersions = findViewById(R.id.pkg_pkg_tab_versions);
        detailsPane = findViewById(R.id.pkg_pkg_details);
        versionsPane = findViewById(R.id.pkg_pkg_versions);
        readme = findViewById(R.id.pkg_pkg_readme);
        readmeEmpty = findViewById(R.id.pkg_pkg_readme_empty);
        readmeLoading = findViewById(R.id.pkg_pkg_readme_loading);
        variantRow = findViewById(R.id.pkg_pkg_variant_row);
        variantSpinner = findViewById(R.id.pkg_pkg_variant_spinner);
        gameChips = findViewById(R.id.pkg_pkg_game_chips);
        gameScroll = findViewById(R.id.pkg_pkg_game_scroll);
        noInstances = findViewById(R.id.pkg_pkg_no_instances);
        versionList = findViewById(R.id.pkg_pkg_version_list);
        versionsEmpty = findViewById(R.id.pkg_pkg_versions_empty);

        versionAdapter = new PackageActivity.VersionAdapter(this::onInstall, PackageService.rid());
        versionList.setLayoutManager(new LinearLayoutManager(this));
        versionList.setItemAnimator(null);
        versionList.setAdapter(versionAdapter);

        tabDetails.setOnClickListener(v -> selectTab(false));
        tabVersions.setOnClickListener(v -> selectTab(true));
        findViewById(R.id.pkg_pkg_btn_retry).setOnClickListener(v -> loadPackage(true));
        findViewById(R.id.pkg_pkg_btn_back).setOnClickListener(v -> finish());

        MaterialButton install = findViewById(R.id.pkg_pkg_btn_install);
        install.setBackgroundTintList(ColorStateList.valueOf(accent));
        int onAccent = Color.luminance(accent) > 0.42f ? Color.BLACK : Color.WHITE;
        install.setTextColor(onAccent);
        install.setIconTint(ColorStateList.valueOf(onAccent));
        install.setOnClickListener(v -> selectTab(true));
        findViewById(R.id.pkg_pkg_btn_project).setOnClickListener(v -> {
            if (pkg != null) {
                PkgUi.openUrl(this, pkg.projectUrl);
            }
        });
        findViewById(R.id.pkg_pkg_btn_share).setOnClickListener(v -> share());
        DynamicAnim.applyPressScale(install);

        selectTab(false);
        if (identifier.isEmpty()) {
            showError(getString(R.string.pkg_package_empty), false);
            return;
        }
        loadPackage(false);
        loadDb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readme != null) {
            readme.onResume();
        }
        updateInstallAvailability();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (readme != null) {
            readme.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        loadRequest++;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        if (readme != null) {
            ViewGroup parent = (ViewGroup) readme.getParent();
            if (parent != null) {
                parent.removeView(readme);
            }
            readme.destroy();
            readme = null;
        }
        super.onDestroy();
    }

    // #region Loading

    private void loadPackage(boolean refresh) {
        int request = ++loadRequest;
        content.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        String id = identifier;
        PackageService.EXECUTOR.execute(() -> {
            CatalogModels.Pkg found = null;
            PackageService.Failure error = null;
            try {
                if (refresh) {
                    try {
                        Catalog.get().load(ctx, true);
                    } catch (PackageService.Failure e) {
                        if (ctx.isCancelled()) throw e;
                        PackageService.warn("package page: catalogue refresh failed: " + PackageService.message(e));
                    }
                }
                found = Catalog.get().findPackage(ctx, id);
            } catch (PackageService.Failure e) {
                error = e;
            } catch (RuntimeException e) {
                error = new PackageService.Failure(PackageService.ERR_PKG_INDEX_UNAVAILABLE, String.valueOf(e), e);
            }
            CatalogModels.Pkg p = found;
            PackageService.Failure err = error;
            main.post(() -> {
                if (request != loadRequest || isDestroyed()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                if (p == null) {
                    PackageService.warn("package page: " + id + ": " + (err == null ? "" : PackageService.message(err)));
                    String detail = err == null ? "" : PkgUi.ErrorText.describe(this, PackageService.code(err),
                            PackageService.message(err));
                    showError(getString(R.string.pkg_package_load_failed) + (detail.isEmpty() ? "" : "\n" + detail),
                            true);
                    return;
                }
                showPackage(p);
            });
        });
    }

    private void showError(String message, boolean retry) {
        loading.setVisibility(View.GONE);
        content.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorText.setText(message);
        findViewById(R.id.pkg_pkg_btn_retry).setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void loadDb() {
        PackageService.EXECUTOR.execute(() -> {
            LeviLamina.Db d = null;
            try {
                d = LeviLamina.DbClient.get().load(ctx, false).db;
            } catch (PackageService.Failure | RuntimeException e) {
                PackageService.warn("package page: LeviLamina database unavailable: " + e.getMessage());
            }
            LeviLamina.Db loaded = d;
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                db = loaded;
                mappingReady = true;
                renderVersions();
            });
        });
    }

    private void loadReadme(CatalogModels.Pkg p) {
        readmeLoading.setVisibility(View.VISIBLE);
        readmeEmpty.setVisibility(View.GONE);
        readme.setVisibility(View.INVISIBLE);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        PackageService.EXECUTOR.execute(() -> {
            String text = "";
            try {
                text = Readme.forPackage(ctx, p.identifier, p.projectUrl, locale);
            } catch (PackageService.Failure | RuntimeException e) {
                PackageService.warn("package page: README of " + p.identifier + " unavailable: " + e.getMessage());
            }
            String markdown = text == null || text.trim().isEmpty() ? p.fullDescription : text;
            String html = markdown == null || markdown.trim().isEmpty()
                    ? "" : Readme.Markdown.toHtml(markdown, Readme.Markdown.repoRef(p.projectUrl));
            main.post(() -> {
                if (isDestroyed() || readme == null || pkg != p) {
                    return;
                }
                readmeLoading.setVisibility(View.GONE);
                if (html.isEmpty()) {
                    readmeEmpty.setVisibility(View.VISIBLE);
                    readme.setVisibility(View.INVISIBLE);
                } else {
                    readme.setVisibility(View.VISIBLE);
                    readme.showHtml(html);
                }
            });
        });
    }

    // #endregion

    // #region Header

    private void showPackage(CatalogModels.Pkg p) {
        pkg = p;
        content.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);

        TextView title = findViewById(R.id.pkg_pkg_title);
        TextView byline = findViewById(R.id.pkg_pkg_byline);
        TextView meta = findViewById(R.id.pkg_pkg_meta_line);
        TextView availability = findViewById(R.id.pkg_pkg_availability);
        TextView idChip = findViewById(R.id.pkg_pkg_identifier);
        TextView description = findViewById(R.id.pkg_pkg_description_text);
        ImageView avatar = findViewById(R.id.pkg_pkg_avatar);
        ChipGroup tags = findViewById(R.id.pkg_pkg_tag_group);

        title.setText(p.name);
        // The author is the repository owner, linked to their GitHub page.
        boolean hasOwner = !p.author.trim().isEmpty();
        byline.setText(getString(R.string.pkg_by_author, hasOwner ? p.author : getString(R.string.pkg_unknown)));
        byline.setTextColor(hasOwner ? accent : getColor(R.color.text_secondary));
        byline.setClickable(hasOwner);
        byline.setOnClickListener(hasOwner ? v -> PkgUi.openUrl(this, p.ownerUrl()) : null);
        byline.setContentDescription(hasOwner ? getString(R.string.pkg_open_owner, p.author) : null);
        // "More by owner" (the author filter), back on the Browse tab that opened this page for a result.
        TextView moreByOwner = findViewById(R.id.pkg_pkg_more_by_owner);
        boolean canList = hasOwner && getCallingActivity() != null;
        moreByOwner.setVisibility(canList ? View.VISIBLE : View.GONE);
        if (canList) {
            moreByOwner.setText(getString(R.string.pkg_more_by_owner, p.author));
            moreByOwner.setTextColor(accent);
            moreByOwner.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(RESULT_AUTHOR, p.author.trim()));
                finish();
            });
        }
        meta.setText(PkgUi.metaLine(PkgUi.formatDate(this, p.updated),
                String.format(Locale.getDefault(), "★ %d", (long) p.stars), PkgUi.license(this, p)));
        PkgUi.bindBadge(availability, p.availability(PackageService.rid()));
        idChip.setText(getString(R.string.pkg_identifier, p.identifier));
        idChip.setOnLongClickListener(v -> {
            copyToClipboard(p.identifier);
            return true;
        });
        description.setText(p.fullDescription.isEmpty() ? getString(R.string.pkg_no_description) : p.fullDescription);
        // Featured tags (sprite, name, raw tags in grey), then the other tags.
        PkgUi.bindTags(this, tags, p.tags, 0);
        if (!p.avatarUrl.isEmpty()) {
            Glide.with(this).load(p.avatarUrl).centerCrop().placeholder(R.drawable.ic_minecraft_cube)
                    .error(R.drawable.ic_minecraft_cube).into(avatar);
        }
        findViewById(R.id.pkg_pkg_btn_project).setVisibility(
                Readme.Markdown.isOpenableExternal(p.projectUrl) ? View.VISIBLE : View.GONE);

        variants = p.variants;
        activeVariant = initialVariant(p);
        selectedGame = PackageActivity.VersionGrouping.ALL;
        bindVariantSpinner();
        renderVersions();
        loadReadme(p);
    }

    private CatalogModels.Variant initialVariant(CatalogModels.Pkg p) {
        String routeVariant = PackagesActivity.Links.routeVariant(identifier);
        if (!routeVariant.isEmpty()) {
            for (CatalogModels.Variant v : variants) {
                if (v.key.trim().equalsIgnoreCase(routeVariant)) {
                    return v;
                }
            }
        }
        return p.defaultVariant();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("package", text));
        Toast.makeText(this, R.string.pkg_identifier_copied, Toast.LENGTH_SHORT).show();
    }

    private void share() {
        if (pkg == null) {
            return;
        }
        String url = PackagesActivity.Links.pageUrl(pkg.identifier);
        if (url.isEmpty()) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, pkg.name);
        send.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.pkg_share_title)));
        } catch (android.content.ActivityNotFoundException e) {
            copyToClipboard(url);
        }
    }

    // #endregion

    // #region Tabs and versions

    private void selectTab(boolean versions) {
        styleTab(tabDetails, !versions);
        styleTab(tabVersions, versions);
        detailsPane.setVisibility(versions ? View.GONE : View.VISIBLE);
        versionsPane.setVisibility(versions ? View.VISIBLE : View.GONE);
    }

    private void styleTab(TextView tab, boolean selected) {
        if (selected) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(accent);
            gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
            tab.setBackground(gd);
            tab.setTextColor(Color.luminance(accent) > 0.42f ? Color.BLACK : Color.WHITE);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            tab.setBackgroundResource(R.drawable.bg_tab_unselected);
            tab.setTextColor(getColor(R.color.text_secondary));
            tab.setTypeface(Typeface.DEFAULT);
        }
    }

    static String variantLabel(Context c, CatalogModels.Variant v) {
        String key = v.key.trim();
        return key.isEmpty() ? c.getString(R.string.pkg_variant_default) : key;
    }

    private void bindVariantSpinner() {
        if (variants.size() <= 1) {
            variantRow.setVisibility(View.GONE);
            return;
        }
        variantRow.setVisibility(View.VISIBLE);
        List<String> labels = new ArrayList<>();
        for (CatalogModels.Variant v : variants) {
            labels.add(variantLabel(this, v) + "  ·  " + v.packageIdentifier);
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bindingVariants = true;
        variantSpinner.setAdapter(a);
        variantSpinner.setSelection(Math.max(0, variants.indexOf(activeVariant)), false);
        bindingVariants = false;
        variantSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingVariants || position < 0 || position >= variants.size()) {
                    return;
                }
                CatalogModels.Variant v = variants.get(position);
                if (v == activeVariant) {
                    return;
                }
                activeVariant = v;
                selectedGame = PackageActivity.VersionGrouping.ALL;
                renderVersions();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void renderVersions() {
        if (pkg == null) {
            return;
        }
        boolean mappingAvailable = db != null && !db.isEmpty();
        List<PackageActivity.VersionGrouping.FileState> states = PackageActivity.VersionGrouping.fileStates(activeVariant,
                mappingAvailable ? db : null);
        List<String> games = PackageActivity.VersionGrouping.games(states);
        selectedGame = PackageActivity.VersionGrouping.activeGame(games, selectedGame);
        bindGameChips(games);
        List<PackageActivity.VersionGrouping.Group> groups = PackageActivity.VersionGrouping.groups(states, selectedGame);
        versionAdapter.setGroups(pkg.name, groups, mappingReady, mappingAvailable);
        boolean empty = states.isEmpty();
        versionsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        versionList.setVisibility(empty ? View.GONE : View.VISIBLE);
        gameScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateInstallAvailability();
    }

    private void updateInstallAvailability() {
        if (versionAdapter == null || pkg == null) {
            return;
        }
        boolean none = PackagesActivity.Instances.all(this).isEmpty();
        noInstances.setVisibility(none ? View.VISIBLE : View.GONE);
        versionAdapter.setInstallDisabled(none);
    }

    private void bindGameChips(List<String> games) {
        gameChips.setOnCheckedChangeListener(null);
        gameChips.removeAllViews();
        List<String> all = new ArrayList<>();
        all.add(PackageActivity.VersionGrouping.ALL);
        all.addAll(games);
        int onSurface = getColor(R.color.on_surface);
        int surfaceVariant = getColor(R.color.surface_variant);
        int textOnAccent = Color.luminance(accent) > 0.42f ? Color.BLACK : Color.WHITE;
        ColorStateList chipText = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{textOnAccent, onSurface});
        float d = getResources().getDisplayMetrics().density;
        for (String game : all) {
            RadioButton chip = new RadioButton(this);
            chip.setId(View.generateViewId());
            chip.setTag(game);
            chip.setText(PackageActivity.VersionGrouping.ALL.equals(game) ? getString(R.string.pkg_game_all_versions) : game);
            chip.setTextColor(chipText);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            chip.setButtonDrawable(android.R.color.transparent);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(Math.round(28 * d));
            chip.setMinimumWidth(0);
            chip.setPadding(Math.round(10 * d), Math.round(4 * d), Math.round(10 * d), Math.round(4 * d));
            GradientDrawable checked = new GradientDrawable();
            checked.setCornerRadius(12 * d);
            checked.setColor(accent);
            GradientDrawable unchecked = new GradientDrawable();
            unchecked.setCornerRadius(12 * d);
            unchecked.setColor(surfaceVariant);
            StateListDrawable state = new StateListDrawable();
            state.addState(new int[]{android.R.attr.state_checked}, checked);
            state.addState(new int[]{}, unchecked);
            chip.setBackground(state);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Math.round(28 * d));
            lp.setMarginEnd(Math.round(6 * d));
            gameChips.addView(chip, lp);
            if (game.equals(selectedGame)) {
                chip.setChecked(true);
            }
        }
        gameChips.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v == null || v.getTag() == null) {
                return;
            }
            String game = (String) v.getTag();
            if (game.equals(selectedGame)) {
                return;
            }
            selectedGame = game;
            boolean mappingAvailable = db != null && !db.isEmpty();
            List<PackageActivity.VersionGrouping.FileState> states = PackageActivity.VersionGrouping.fileStates(activeVariant,
                    mappingAvailable ? db : null);
            versionAdapter.setGroups(pkg.name, PackageActivity.VersionGrouping.groups(states, selectedGame), mappingReady,
                    mappingAvailable);
        });
    }

    public void onInstall(CatalogModels.PackageFile file) {
        if (pkg == null || activeVariant == null) {
            return;
        }
        InstallDialog.show(this, pkg, activeVariant, file.version, chosenInstance, preferredInstance,
                v -> chosenInstance = v);
    }

    // #endregion

    public final static class VersionGrouping {
        public static final String ALL = "all";

        private VersionGrouping() {
        }

        public static final class FileState {
            public final CatalogModels.PackageFile file;
            public final List<String> supportedGameVersions;
            public final boolean hasLLRequirement;

            public FileState(CatalogModels.PackageFile file, List<String> supportedGameVersions, boolean hasLLRequirement) {
                this.file = file;
                this.supportedGameVersions = Collections.unmodifiableList(new ArrayList<>(supportedGameVersions));
                this.hasLLRequirement = hasLLRequirement;
            }
        }

        public static final class Group {
            public final String key;
            public final String game;
            public final List<String> llRanges;
            public final boolean hasLLRequirement;
            public final List<FileState> files;

            Group(String key, String game, List<String> llRanges, boolean hasLLRequirement) {
                this.key = key;
                this.game = game;
                this.llRanges = Collections.unmodifiableList(llRanges);
                this.hasLLRequirement = hasLLRequirement;
                this.files = new ArrayList<>();
            }
        }

        public static List<FileState> fileStates(CatalogModels.Variant variant, LeviLamina.Db db) {
            List<FileState> out = new ArrayList<>();
            if (variant == null) {
                return out;
            }
            for (CatalogModels.PackageFile f : variant.files) {
                boolean hasLL = !f.llRanges.isEmpty();
                List<String> games = hasLL && db != null && !db.isEmpty()
                        ? CatalogModels.Versions.supportedGameVersions(f.llRanges, db)
                        : Collections.emptyList();
                out.add(new FileState(f, games, hasLL));
            }
            return out;
        }

        public static List<String> games(List<FileState> files) {
            Set<String> set = new LinkedHashSet<>();
            for (FileState s : files) {
                set.addAll(s.supportedGameVersions);
            }
            List<String> out = new ArrayList<>(set);
            out.sort((a, b) -> CatalogModels.Versions.compareVersions(b, a));
            return out;
        }

        public static String activeGame(List<String> games, String selected) {
            return selected != null && games.contains(selected) ? selected : ALL;
        }

        public static List<Group> groups(List<FileState> files, String activeGame) {
            String active = activeGame == null ? ALL : activeGame;
            Map<String, Group> result = new LinkedHashMap<>();
            for (FileState state : files) {
                List<String> ranges = new ArrayList<>(new TreeSet<>(state.file.llRanges));
                List<String> targets = state.supportedGameVersions.isEmpty()
                        ? Collections.singletonList("")
                        : state.supportedGameVersions;
                for (String game : targets) {
                    if (!ALL.equals(active) && !game.equals(active) && state.hasLLRequirement) {
                        continue;
                    }
                    String key = game + "\u0000" + String.join("\u0001", ranges);
                    Group g = result.get(key);
                    if (g == null) {
                        g = new Group(key, game, ranges, state.hasLLRequirement);
                        result.put(key, g);
                    }
                    g.files.add(state);
                }
            }
            List<Group> out = new ArrayList<>(result.values());
            out.sort((a, b) -> {
                if (!a.game.equals(b.game)) {
                    if (a.game.isEmpty()) {
                        return 1;
                    }
                    if (b.game.isEmpty()) {
                        return -1;
                    }
                    return CatalogModels.Versions.compareVersions(b.game, a.game);
                }
                return CatalogModels.Versions.compareVersions(b.files.get(0).file.version, a.files.get(0).file.version);
            });
            return out;
        }

        public enum NoGameLabel {
            UNRESTRICTED,
            LOADING,
            UNKNOWN,
            UNAVAILABLE
        }

        public static NoGameLabel noGameLabel(Group group, boolean mappingReady, boolean mappingAvailable) {
            if (!group.hasLLRequirement) {
                return NoGameLabel.UNRESTRICTED;
            }
            if (!mappingReady) {
                return NoGameLabel.LOADING;
            }
            return mappingAvailable ? NoGameLabel.UNKNOWN : NoGameLabel.UNAVAILABLE;
        }
    }

    public static class VersionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public interface Listener {
            void onInstall(CatalogModels.PackageFile file);
        }

        private static final int TYPE_GROUP = 0;
        private static final int TYPE_ROW = 1;

        private final Listener listener;
        private final String rid;
        private String packageName = "";
        private List<PackageActivity.VersionGrouping.Group> groups = new ArrayList<>();
        private final Set<String> expanded = new HashSet<>();
        private final Set<String> depsOpen = new HashSet<>();
        private final List<Object> items = new ArrayList<>();
        private boolean mappingReady;
        private boolean mappingAvailable;
        private boolean installDisabled;

        public VersionAdapter(Listener listener, String rid) {
            this.listener = listener;
            this.rid = rid;
        }

        public void setGroups(String packageName, List<PackageActivity.VersionGrouping.Group> groups, boolean mappingReady,
                              boolean mappingAvailable) {
            this.packageName = packageName == null ? "" : packageName;
            this.groups = new ArrayList<>(groups);
            this.mappingReady = mappingReady;
            this.mappingAvailable = mappingAvailable;
            expanded.clear();
            depsOpen.clear();
            if (!this.groups.isEmpty()) {
                expanded.add(this.groups.get(0).key);
            }
            rebuild();
        }

        public void setInstallDisabled(boolean disabled) {
            if (installDisabled != disabled) {
                installDisabled = disabled;
                notifyDataSetChanged();
            }
        }

        private void rebuild() {
            items.clear();
            for (PackageActivity.VersionGrouping.Group g : groups) {
                items.add(g);
                if (expanded.contains(g.key)) {
                    for (PackageActivity.VersionGrouping.FileState s : g.files) {
                        items.add(new Row(g, s));
                    }
                }
            }
            notifyDataSetChanged();
        }

        private static final class Row {
            final PackageActivity.VersionGrouping.Group group;
            final PackageActivity.VersionGrouping.FileState state;

            Row(PackageActivity.VersionGrouping.Group group, PackageActivity.VersionGrouping.FileState state) {
                this.group = group;
                this.state = state;
            }

            String key() {
                return group.key + "\u0002" + state.file.version;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof PackageActivity.VersionGrouping.Group ? TYPE_GROUP : TYPE_ROW;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_GROUP) {
                return new GroupHolder(inflater.inflate(R.layout.item_pkg_version_group, parent, false));
            }
            return new RowHolder(inflater.inflate(R.layout.item_pkg_version_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (holder instanceof GroupHolder) {
                ((GroupHolder) holder).bind((PackageActivity.VersionGrouping.Group) item);
            } else {
                ((RowHolder) holder).bind((Row) item);
            }
        }

        final class GroupHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView ranges;
            final TextView count;
            final ImageView chevron;

            GroupHolder(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.pkg_group_title);
                ranges = v.findViewById(R.id.pkg_group_ranges);
                count = v.findViewById(R.id.pkg_group_count);
                chevron = v.findViewById(R.id.pkg_group_chevron);
            }

            void bind(PackageActivity.VersionGrouping.Group g) {
                Context ctx = itemView.getContext();
                title.setText(groupTitle(ctx, g));
                if (g.llRanges.isEmpty()) {
                    ranges.setVisibility(View.GONE);
                } else {
                    ranges.setVisibility(View.VISIBLE);
                    ranges.setText(ctx.getString(R.string.pkg_ll_ranges, String.join(", ", g.llRanges)));
                }
                count.setText(ctx.getResources().getQuantityString(R.plurals.pkg_n_versions, g.files.size(),
                        g.files.size()));
                boolean open = expanded.contains(g.key);
                chevron.setRotation(open ? 180f : 0f);
                itemView.setOnClickListener(v -> {
                    if (!expanded.remove(g.key)) {
                        expanded.add(g.key);
                    }
                    rebuild();
                });
            }
        }

        private String groupTitle(Context ctx, PackageActivity.VersionGrouping.Group g) {
            if (!g.game.isEmpty()) {
                return ctx.getString(R.string.pkg_group_minecraft, g.game);
            }
            switch (PackageActivity.VersionGrouping.noGameLabel(g, mappingReady, mappingAvailable)) {
                case UNRESTRICTED:
                    return ctx.getString(R.string.pkg_game_versions_unrestricted);
                case LOADING:
                    return ctx.getString(R.string.pkg_game_versions_loading);
                case UNKNOWN:
                    return ctx.getString(R.string.pkg_game_versions_unknown);
                default:
                    return ctx.getString(R.string.pkg_game_versions_unavailable);
            }
        }

        final class RowHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView badge;
            final TextView ranges;
            final TextView depsToggle;
            final TextView deps;
            final MaterialButton install;

            RowHolder(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.pkg_row_title);
                badge = v.findViewById(R.id.pkg_row_badge);
                ranges = v.findViewById(R.id.pkg_row_ranges);
                depsToggle = v.findViewById(R.id.pkg_row_deps_toggle);
                deps = v.findViewById(R.id.pkg_row_deps);
                install = v.findViewById(R.id.pkg_row_install);
            }

            void bind(Row row) {
                Context ctx = itemView.getContext();
                CatalogModels.PackageFile f = row.state.file;
                title.setText(ctx.getString(R.string.pkg_file_title, packageName, f.version));
                PkgUi.bindBadge(badge, f.availability(rid));
                ranges.setText(f.llRanges.isEmpty()
                        ? ctx.getString(R.string.pkg_game_versions_unrestricted)
                        : ctx.getString(R.string.pkg_ll_ranges, String.join(", ", f.llRanges)));
                Map<String, String> other = f.otherDependencies;
                if (other.isEmpty()) {
                    depsToggle.setVisibility(View.GONE);
                    deps.setVisibility(View.GONE);
                } else {
                    String key = row.key();
                    boolean open = depsOpen.contains(key);
                    depsToggle.setVisibility(View.VISIBLE);
                    depsToggle.setText((open ? "▾ " : "▸ ") + ctx.getResources().getQuantityString(
                            R.plurals.pkg_n_dependencies, other.size(), other.size()));
                    StringBuilder b = new StringBuilder();
                    for (Map.Entry<String, String> e : other.entrySet()) {
                        if (b.length() > 0) {
                            b.append('\n');
                        }
                        b.append(e.getKey()).append(": ").append(e.getValue());
                    }
                    deps.setText(b.toString());
                    deps.setVisibility(open ? View.VISIBLE : View.GONE);
                    depsToggle.setOnClickListener(v -> {
                        if (!depsOpen.remove(key)) {
                            depsOpen.add(key);
                        }
                        int pos = getBindingAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos);
                        }
                    });
                }
                boolean disabled = installDisabled || (row.state.hasLLRequirement && !mappingAvailable);
                install.setEnabled(!disabled);
                install.setAlpha(disabled ? 0.5f : 1f);
                install.setContentDescription(ctx.getString(R.string.pkg_install) + " " + packageName + " " + f.version);
                install.setOnClickListener(v -> listener.onInstall(f));
            }
        }
    }

    public static class ReadmeView extends WebView {
        static final String BASE_URL = "https://readme.invalid/";

        public ReadmeView(Context context) {
            super(context);
            init();
        }

        public ReadmeView(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        public ReadmeView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            init();
        }

        @SuppressLint("SetJavaScriptEnabled")
        private void init() {
            setBackgroundColor(Color.TRANSPARENT);
            WebSettings s = getSettings();
            s.setJavaScriptEnabled(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setDomStorageEnabled(false);
            s.setDatabaseEnabled(false);
            s.setGeolocationEnabled(false);
            s.setSupportMultipleWindows(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setLoadsImagesAutomatically(true);
            s.setMediaPlaybackRequiresUserGesture(true);
            s.setTextZoom(100);
            s.setSupportZoom(false);
            setHorizontalScrollBarEnabled(false);
            setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return handle(request.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handle(Uri.parse(url));
                }
            });
        }

        private boolean handle(@Nullable Uri uri) {
            if (uri == null) {
                return true;
            }
            String url = uri.toString();
            if (url.startsWith(BASE_URL)) {
                // An in-page anchor scrolls; a relative link with no repository to resolve it goes nowhere.
                return uri.getFragment() == null || !url.startsWith(BASE_URL + "#");
            }
            PkgUi.openUrl(getContext(), url);
            return true;
        }

        public void showHtml(String body) {
            Context c = getContext();
            Readme.Markdown.Theme theme = new Readme.Markdown.Theme(
                    hex(c.getColor(R.color.on_surface)),
                    hex(c.getColor(R.color.text_secondary)),
                    hex(PkgUi.accent(c)),
                    hex(c.getColor(R.color.surface_variant)),
                    hex(c.getColor(R.color.divider)));
            loadDataWithBaseURL(BASE_URL, Readme.Markdown.document(body, theme), "text/html", "utf-8", null);
        }

        private static String hex(int color) {
            return String.format("#%06X", 0xFFFFFF & color);
        }
    }
}
