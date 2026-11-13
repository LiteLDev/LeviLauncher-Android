package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.modcatalog.GithubUrls;
import org.levimc.launcher.core.modcatalog.ModCatalog;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExternalModsAdapter extends RecyclerView.Adapter<ExternalModsAdapter.Holder> {
    /** Icon chip colors, picked per mod so the placeholder icons are told apart. */
    private static final int[] CHIP_COLORS = {UiKit.ROSE, UiKit.GREEN, UiKit.BLUE, UiKit.PURPLE,
            UiKit.ORANGE, UiKit.TEAL, UiKit.INDIGO, UiKit.AMBER};
    /** The ChipGroup's fixed children (versions + download type); catalog tags follow them. */
    private static final int FIXED_TAGS = 2;
    private static final int MAX_CATALOG_TAGS = 4;
    public interface OnActionClickListener {
        void onClick(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release);
    }

    private final List<ModCatalog.CatalogMod> items = new ArrayList<>();
    private final Map<String, String> installedVersions = new HashMap<>();
    private final String minecraftVersion;
    private final OnActionClickListener listener;
    private String busyModId;
    private String githubProxy;

    public ExternalModsAdapter(String minecraftVersion, OnActionClickListener listener) {
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        this.listener = listener;
    }

    public void submit(List<ModCatalog.CatalogMod> mods) {
        items.clear();
        if (mods != null) items.addAll(mods);
        notifyDataSetChanged();
    }

    public void setInstalledMods(List<Mod> mods) {
        installedVersions.clear();
        if (mods != null) {
            for (Mod mod : mods) {
                installedVersions.put(normalize(mod.getId()), value(mod.getVersion()));
                installedVersions.put(normalize(mod.getDisplayName()), value(mod.getVersion()));
            }
        }
        notifyDataSetChanged();
    }

    /** The GitHub proxy icons are loaded through (null for none); takes effect with the next {@link #submit}. */
    public void setGithubProxy(String proxy) {
        githubProxy = proxy;
    }

    public void setBusyModId(String id) {
        busyModId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_external_mod, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ModCatalog.CatalogMod mod = items.get(position);
        ModCatalog.CatalogRelease compatible = mod.compatibleRelease(minecraftVersion);
        ModCatalog.CatalogRelease release = compatible != null ? compatible : mod.latestRelease();
        boolean isBusy = mod.id != null && mod.id.equals(busyModId);
        String installedVersion = installedVersion(mod);

        Context ctx = holder.itemView.getContext();
        holder.divider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
        holder.name.setText(mod.name);
        holder.author.setVisibility(TextUtils.isEmpty(mod.author) ? View.GONE : View.VISIBLE);
        holder.author.setText(TextUtils.isEmpty(mod.author) ? ""
                : ctx.getString(R.string.mods_external_author, mod.author));
        holder.version.setText(release == null || TextUtils.isEmpty(release.version) ? ""
                : versionLabel(release.version));
        holder.description.setText(mod.description);
        holder.description.setVisibility(TextUtils.isEmpty(mod.description) ? View.GONE : View.VISIBLE);

        if (release == null || release.minecraftVersions == null || release.minecraftVersions.isEmpty()) {
            holder.versions.setVisibility(View.GONE);
        } else {
            holder.versions.setVisibility(View.VISIBLE);
            holder.versions.setText(ctx.getString(R.string.external_mods_minecraft_versions,
                    TextUtils.join(", ", release.minecraftVersions)));
            UiKit.tintSoftAccent(holder.versions, 7);
        }

        if (release == null) {
            holder.downloadType.setVisibility(View.GONE);
        } else {
            holder.downloadType.setVisibility(View.VISIBLE);
            if (release.isAdDownload()) {
                holder.downloadType.setText(R.string.external_mods_ad_download);
                holder.downloadType.setTextColor(ContextCompat.getColor(ctx, R.color.ui_warning));
            } else if (release.opensInBrowser()) {
                holder.downloadType.setText(R.string.external_mods_external_link);
                holder.downloadType.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            } else {
                holder.downloadType.setText(R.string.external_mods_direct_download);
                holder.downloadType.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            }
        }
        bindTags(holder, mod);

        int chipColor = CHIP_COLORS[Math.floorMod(value(mod.id).hashCode(), CHIP_COLORS.length)];
        UiKit.tintIconChip(holder.iconChip, null, chipColor);
        Drawable placeholder = placeholderIcon(ctx, chipColor);
        String proxiedIcon = TextUtils.isEmpty(mod.iconUrl) ? null : GithubUrls.proxied(githubProxy, mod.iconUrl);
        if (TextUtils.isEmpty(mod.iconUrl)) {
            Glide.with(holder.icon).clear(holder.icon);
            holder.icon.setImageDrawable(placeholder);
        } else if (proxiedIcon != null) {
            // Through the GitHub proxy, then directly.
            Glide.with(holder.icon)
                    .load(proxiedIcon)
                    .placeholder(placeholder)
                    .error(Glide.with(holder.icon).load(mod.iconUrl).error(placeholder))
                    .into(holder.icon);
        } else {
            Glide.with(holder.icon)
                    .load(mod.iconUrl)
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(holder.icon);
        }

        boolean canInstall = release != null && compatible != null && !minecraftVersion.isEmpty() && busyModId == null;
        if (isBusy) {
            holder.action.setText(R.string.loading);
        } else if (release == null || compatible == null || minecraftVersion.isEmpty()) {
            holder.action.setText(R.string.external_mods_incompatible);
        } else if (!installedVersion.isEmpty() && installedVersion.equals(release.version)) {
            holder.action.setText(R.string.external_mods_installed_button);
            canInstall = false;
        } else if (!installedVersion.isEmpty()) {
            holder.action.setText(R.string.external_mods_update);
        } else if (release.opensInBrowser()) {
            holder.action.setText(R.string.external_mods_open_link);
        } else {
            holder.action.setText(R.string.install);
        }

        holder.action.setEnabled(canInstall);
        if (canInstall) {
            UiKit.applyPrimary(holder.action);
        } else {
            // Disabled states use the secondary (grey) button look.
            int label = ContextCompat.getColor(ctx, R.color.text_secondary);
            holder.action.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.ui_default)));
            holder.action.setTextColor(label);
        }
        holder.action.setOnClickListener(canInstall && listener != null
                ? v -> listener.onClick(mod, release) : null);
        DynamicAnim.applyPressScale(holder.action);
        new PersonalizationManager(ctx).applyGlassToView(holder.itemView);
    }

    /** Replaces the catalog tags after the fixed version / download-type tags. */
    private void bindTags(Holder holder, ModCatalog.CatalogMod mod) {
        ViewGroup group = holder.tags;
        while (group.getChildCount() > FIXED_TAGS) {
            group.removeViewAt(group.getChildCount() - 1);
        }
        if (mod.tags == null) return;
        Context ctx = group.getContext();
        int added = 0;
        for (String tag : mod.tags) {
            if (TextUtils.isEmpty(tag)) continue;
            if (added++ >= MAX_CATALOG_TAGS) break;
            TextView view = new TextView(ctx, null, 0, R.style.Widget_Levi_Tag);
            view.setText(tag);
            group.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The puzzle icon in the chip color, inset so it sits in the middle of the 60dp chip. */
    private static Drawable placeholderIcon(Context ctx, int color) {
        Drawable icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_ui_puzzle);
        if (icon == null) return null;
        icon = icon.mutate();
        icon.setTint(color);
        int inset = Math.round(UiKit.dp(ctx, 12));
        return new InsetDrawable(icon, inset);
    }

    private static String versionLabel(String version) {
        return version.startsWith("v") || version.startsWith("V") ? version : "v" + version;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String installedVersion(ModCatalog.CatalogMod mod) {
        String version = installedVersions.get(normalize(mod.id));
        if (version == null) version = installedVersions.get(normalize(mod.name));
        return value(version);
    }

    private String normalize(String value) {
        return value(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final View divider;
        final View iconChip;
        final ImageView icon;
        final TextView name;
        final TextView author;
        final TextView description;
        final TextView version;
        final TextView versions;
        final ViewGroup tags;
        final TextView downloadType;
        final MaterialButton action;

        Holder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.external_mod_card);
            divider = itemView.findViewById(R.id.external_mod_divider);
            iconChip = itemView.findViewById(R.id.external_mod_icon_chip);
            iconChip.setClipToOutline(true);
            icon = itemView.findViewById(R.id.external_mod_icon);
            name = itemView.findViewById(R.id.external_mod_name);
            author = itemView.findViewById(R.id.external_mod_author);
            description = itemView.findViewById(R.id.external_mod_description);
            version = itemView.findViewById(R.id.external_mod_version);
            versions = itemView.findViewById(R.id.external_mod_versions);
            tags = itemView.findViewById(R.id.external_mod_tags);
            downloadType = itemView.findViewById(R.id.external_mod_download_type);
            action = itemView.findViewById(R.id.external_mod_action);
        }
    }
}
