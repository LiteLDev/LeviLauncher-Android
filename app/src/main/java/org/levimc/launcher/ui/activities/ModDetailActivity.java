package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.Nullable;
import android.widget.CompoundButton;
import org.levimc.launcher.ui.widget.UiKit;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.widget.ImageView;

import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.ui.packages.InstalledFragment;
import org.levimc.launcher.ui.packages.PackageActions;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

public class ModDetailActivity extends BaseActivity {

    private MainViewModel viewModel;
    private Mod currentMod;
    private int modPosition;
    private String modFilenameArg;
    private TextView modNameText;
    private TextView modAuthorText;
    private TextView modDescriptionText;
    private TextView modStatusText;
    private ImageView modIconView;
    private View iconContainer;
    private MetaRow versionRow;
    private MetaRow minecraftVersionsRow;
    private MetaRow configFilesRow;
    private CompoundButton modSwitch;
    private Button configureButton;
    private View infoContainer;
    private View actionsContainer;
    private TextView pkgChip;
    private View packageActionsContainer;
    private Button pkgUpdateButton;
    private Button pkgPromoteButton;
    private Button pkgOpenPackageButton;
    /** The package owning this mod's folder, or null. */
    private InstalledFragment.Model.Row pkgRow;
    private final Cancel pkgCancel = new Cancel();
    private int pkgRequest;
    /** Set once a delete went through: the screen closes when the mod is gone. */
    private boolean deleteRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mod_detail);

        if (getIntent().hasExtra("mod_filename") && getIntent().hasExtra("mod_position")) {
            modFilenameArg = getIntent().getStringExtra("mod_filename");
            modPosition = getIntent().getIntExtra("mod_position", -1);
            
            setupViewModel();
            setupViews();
            
            loadModDetails(modFilenameArg);
        } else {
            Toast.makeText(this, R.string.error_loading_mod, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);
    }

    private void setupViews() {
        modNameText = findViewById(R.id.mod_name_detail);
        modAuthorText = findViewById(R.id.mod_author_detail);
        modDescriptionText = findViewById(R.id.mod_description_detail);
        modStatusText = findViewById(R.id.mod_status_text);
        modIconView = findViewById(R.id.mod_icon_detail);
        iconContainer = findViewById(R.id.mod_icon_container);
        versionRow = MetaRow.bind(findViewById(R.id.mod_version_row));
        minecraftVersionsRow = MetaRow.bind(findViewById(R.id.mod_minecraft_versions_row));
        configFilesRow = MetaRow.bind(findViewById(R.id.mod_config_files_row));
        modSwitch = findViewById(R.id.mod_switch_detail);
        View heroContainer = findViewById(R.id.mod_detail_header_container);
        infoContainer = findViewById(R.id.mod_detail_info_container);
        actionsContainer = findViewById(R.id.mod_detail_actions_container);

        applyPersonalization(heroContainer);

        pkgChip = findViewById(R.id.mod_pkg_chip);
        packageActionsContainer = findViewById(R.id.mod_pkg_actions);
        pkgUpdateButton = findViewById(R.id.pkg_update_button);
        pkgPromoteButton = findViewById(R.id.pkg_promote_button);
        pkgOpenPackageButton = findViewById(R.id.pkg_open_package_button);
        pkgUpdateButton.setOnClickListener(v -> {
            GameVersion version = ModManager.getInstance().getCurrentVersion();
            if (pkgRow != null && version != null) {
                PackageActions.update(this, version, pkgRow, pkgDone(false));
            }
        });
        pkgPromoteButton.setOnClickListener(v -> {
            GameVersion version = ModManager.getInstance().getCurrentVersion();
            if (pkgRow != null && version != null) {
                PackageActions.promote(this, version, pkgRow, pkgDone(false));
            }
        });
        pkgOpenPackageButton.setOnClickListener(v -> {
            if (pkgRow != null) {
                PackageActions.openPackage(this, ModManager.getInstance().getCurrentVersion(), pkgRow);
            }
        });
        DynamicAnim.applyPressScale(pkgUpdateButton);
        DynamicAnim.applyPressScale(pkgPromoteButton);
        DynamicAnim.applyPressScale(pkgOpenPackageButton);

        Button deleteButton = findViewById(R.id.delete_mod_button);
        deleteButton.setOnClickListener(v -> confirmDeleteMod());
        DynamicAnim.applyPressScale(deleteButton);

        configureButton = findViewById(R.id.configure_mod_button);
        UiKit.applyPrimary((com.google.android.material.button.MaterialButton) configureButton);
        UiKit.applyPrimary((com.google.android.material.button.MaterialButton) pkgUpdateButton);
        configureButton.setOnClickListener(v -> openModConfig());
        DynamicAnim.applyPressScale(configureButton);

        modSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentMod != null && isChecked != currentMod.isEnabled()) {
                currentMod.setEnabled(isChecked);
                viewModel.setModEnabled(currentMod.getId(), isChecked);
                if (modStatusText != null) {
                    modStatusText.setText(isChecked ? R.string.mod_status_enabled : R.string.mod_status_disabled);
                }
                Toast.makeText(this, isChecked ? R.string.mod_enabled : R.string.mod_disabled, Toast.LENGTH_SHORT).show();
            }
        });
        DynamicAnim.applyPressScale(modSwitch);
    }

    private void loadModDetails(String modFilename) {
        if (viewModel != null) {
            viewModel.getModsLiveData().observe(this, mods -> {
                if (mods != null) {
                    boolean found = false;
                    for (Mod mod : mods) {
                        if (mod.getId().equals(modFilename)) {
                            currentMod = mod;
                            updateModUI(mod);
                            found = true;
                            break;
                        }
                    }
                    if (!found && deleteRequested) {
                        finish();
                        return;
                    }
                    loadPkgState(mods);
                }
            });
            
            viewModel.refreshMods();
        }
    }

    private void updateModUI(Mod mod) {
        if (mod != null) {
            String author = fallbackText(mod.getAuthor(), getString(R.string.mod_unknown_author));
            String version = fallbackText(mod.getVersion(), getString(R.string.mod_unknown_version));

            modNameText.setText(mod.getDisplayName());
            modAuthorText.setText(getString(R.string.mod_author_byline, author));
            modStatusText.setText(mod.isEnabled() ? R.string.mod_status_enabled : R.string.mod_status_disabled);
            modSwitch.setChecked(mod.isEnabled());
            modSwitch.setContentDescription(getString(R.string.mods_enable_mod, mod.getDisplayName()));

            String description = trimToNull(mod.getDescription());
            findViewById(R.id.mod_description_heading).setVisibility(description == null ? View.GONE : View.VISIBLE);
            if (description == null) {
                modDescriptionText.setVisibility(View.GONE);
            } else {
                modDescriptionText.setVisibility(View.VISIBLE);
                modDescriptionText.setText(description);
            }

            versionRow.set(R.string.mod_version_label, version);
            minecraftVersionsRow.set(R.string.mod_minecraft_versions_label, formatMinecraftVersions(mod));
            configFilesRow.set(R.string.mod_config_files_label, formatConfigFiles(mod));
            loadModIcon(mod);

            if (configureButton != null) {
                configureButton.setVisibility(mod.hasEditableConfig() ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void applyPersonalization(@Nullable View heroContainer) {
        PersonalizationManager personalizationManager = new PersonalizationManager(this);
        View root = findViewById(android.R.id.content);
        if (root != null) {
            personalizationManager.applyAccentToView(root, this);
        }
        View[] themedSurfaces = new View[]{heroContainer, infoContainer, actionsContainer, iconContainer};
        for (View surface : themedSurfaces) {
            if (surface != null) {
                personalizationManager.applyGlassToView(surface);
            }
        }
    }

    private void loadModIcon(Mod mod) {
        if (modIconView == null) return;
        Glide.with(this).clear(modIconView);
        String iconPath = trimToNull(mod.getIconPath());
        File iconFile = iconPath == null ? null : new File(iconPath);
        if (iconFile == null || !iconFile.isFile()) {
            iconContainer.setVisibility(View.GONE);
            modIconView.setImageDrawable(null);
            return;
        }
        iconContainer.setVisibility(View.VISIBLE);
        int radiusPx = Math.round(8f * getResources().getDisplayMetrics().density);
        Glide.with(this)
                .load(iconFile)
                .apply(RequestOptions.bitmapTransform(new RoundedCorners(radiusPx)))
                .placeholder(R.drawable.ic_ui_code)
                .error(R.drawable.ic_ui_code)
                .into(modIconView);
    }

    private String formatMinecraftVersions(Mod mod) {
        if (mod.getMinecraftVersions().isEmpty()) {
            return getString(R.string.mod_all_versions);
        }
        return String.join(", ", mod.getMinecraftVersions());
    }

    private String formatConfigFiles(Mod mod) {
        if (!mod.hasEditableConfig() || mod.getConfigFileCount() <= 0) {
            return getString(R.string.mod_no_config_files);
        }
        return getResources().getQuantityString(
                R.plurals.mod_config_files_count,
                mod.getConfigFileCount(),
                mod.getConfigFileCount()
        );
    }

    private String fallbackText(String value, String fallback) {
        String text = trimToNull(value);
        return text == null ? fallback : text;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void openModConfig() {
        if (currentMod == null || !currentMod.hasEditableConfig()) {
            return;
        }
        Intent intent = new Intent(this, ModConfigActivity.class);
        intent.putExtra(ModConfigActivity.EXTRA_MOD_NAME, currentMod.getDisplayName());
        intent.putExtra(ModConfigActivity.EXTRA_MOD_ROOT, currentMod.getModRootPath());
        startActivity(intent);
    }

    /**
     * Deletes the mod: a folder a package owns is removed by
     * uninstalling the package (or refused when the package is only a
     * dependency); PackageActions asks first. The screen closes once the
     * mod is gone from the list.
     */
    private void confirmDeleteMod() {
        GameVersion version = ModManager.getInstance().getCurrentVersion();
        if (currentMod == null || version == null) {
            return;
        }
        PackageActions.deleteModFolder(this, version, currentMod, pkgDone(true));
    }

    /**
     * After a package action: reloads the mods (after a delete the screen closes
     * once the mod is gone). Holds the activity weakly, since the action's
     * task may outlive it.
     */
    private PackageActions.Done pkgDone(boolean delete) {
        WeakReference<ModDetailActivity> self = new WeakReference<>(this);
        return changed -> {
            ModDetailActivity a = self.get();
            if (a == null || !changed || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (delete) {
                a.deleteRequested = true;
            }
            a.viewModel.reloadMods();
        };
    }

    /** Reads which package owns this mod's folder, then shows the chip and the package actions. */
    private void loadPkgState(List<Mod> mods) {
        int request = ++pkgRequest;
        InstalledFragment.Loader.loadForMods(pkgCancel, ModManager.getInstance().getCurrentVersion(), mods, rows -> {
            if (request != pkgRequest || isFinishing() || isDestroyed()) {
                return;
            }
            pkgRow = modFilenameArg == null ? null : InstalledFragment.Model.ownerOf(rows, modFilenameArg);
            renderPackage();
        });
    }

    private void renderPackage() {
        InstalledFragment.Model.Row row = pkgRow;
        if (row == null) {
            packageActionsContainer.setVisibility(View.GONE);
            pkgChip.setVisibility(View.GONE);
            pkgUpdateButton.setVisibility(View.GONE);
            pkgPromoteButton.setVisibility(View.GONE);
            pkgOpenPackageButton.setVisibility(View.GONE);
            return;
        }
        String text = getString(R.string.pkg_mod_managed, row.pkg.identity, row.pkg.version);
        if (!row.explicit()) {
            text += " · " + getString(R.string.pkg_installed_dependency);
        }
        pkgChip.setText(text);
        pkgChip.setVisibility(View.VISIBLE);
        pkgUpdateButton.setVisibility(row.canUpdate() ? View.VISIBLE : View.GONE);
        pkgUpdateButton.setText(getString(R.string.pkg_installed_update_available, row.targetVersion));
        pkgPromoteButton.setVisibility(!row.explicit() && !row.leviLamina ? View.VISIBLE : View.GONE);
        pkgOpenPackageButton.setVisibility(row.hasPackagePage() ? View.VISIBLE : View.GONE);
        packageActionsContainer.setVisibility(row.canUpdate() || (!row.explicit() && !row.leviLamina)
                || row.hasPackagePage() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        pkgCancel.cancel();
        super.onDestroy();
    }

    // Exit animation is now handled uniformly in BaseActivity.finish()

    private static final class MetaRow {
        private final TextView labelView;
        private final TextView valueView;

        private MetaRow(TextView labelView, TextView valueView) {
            this.labelView = labelView;
            this.valueView = valueView;
        }

        static MetaRow bind(View rowView) {
            return new MetaRow(
                    rowView.findViewById(R.id.meta_label),
                    rowView.findViewById(R.id.meta_value)
            );
        }

        void set(int labelResId, String value) {
            labelView.setText(labelResId);
            valueView.setText(value);
        }
    }
}
