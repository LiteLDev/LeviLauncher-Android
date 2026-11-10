package org.levimc.launcher.ui.adapter;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.modcatalog.GithubUrls;
import org.levimc.launcher.core.modcatalog.ModCatalog;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExternalModsAdapter extends RecyclerView.Adapter<ExternalModsAdapter.Holder> {
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

        holder.name.setText(mod.name);
        holder.author.setText(holder.itemView.getContext().getString(R.string.external_mods_by_author, mod.author));
        holder.description.setText(mod.description);
        holder.version.setText(release == null ? "" : holder.itemView.getContext().getString(
                R.string.external_mods_version, release.version));
        holder.versions.setText(release == null ? "" : holder.itemView.getContext().getString(
                R.string.external_mods_minecraft_versions, TextUtils.join(", ", release.minecraftVersions)));
        holder.tags.setText(mod.tags == null ? "" : TextUtils.join("  •  ", mod.tags));
        holder.tags.setVisibility(mod.tags == null || mod.tags.isEmpty() ? View.GONE : View.VISIBLE);

        if (release == null) {
            holder.downloadType.setVisibility(View.GONE);
        } else {
            holder.downloadType.setVisibility(View.VISIBLE);
            if (release.isAdDownload()) {
                holder.downloadType.setText(R.string.external_mods_ad_download);
                holder.downloadType.setTextColor(holder.itemView.getContext().getColor(R.color.external_mod_ad));
            } else if (release.opensInBrowser()) {
                holder.downloadType.setText(R.string.external_mods_external_link);
                holder.downloadType.setTextColor(holder.itemView.getContext().getColor(R.color.tertiary));
            } else {
                holder.downloadType.setText(R.string.external_mods_direct_download);
                holder.downloadType.setTextColor(holder.itemView.getContext().getColor(R.color.primary));
            }
        }

        Object iconSource = TextUtils.isEmpty(mod.iconUrl)
                ? R.drawable.ic_leaf_logo
                : mod.iconUrl;
        String proxiedIcon = TextUtils.isEmpty(mod.iconUrl) ? null : GithubUrls.proxied(githubProxy, mod.iconUrl);
        if (proxiedIcon != null) {
            // Through the GitHub proxy, then directly.
            Glide.with(holder.icon)
                    .load(proxiedIcon)
                    .placeholder(R.drawable.ic_leaf_logo)
                    .error(Glide.with(holder.icon).load(iconSource).error(R.drawable.ic_leaf_logo))
                    .into(holder.icon);
        } else {
            Glide.with(holder.icon)
                    .load(iconSource)
                    .placeholder(R.drawable.ic_leaf_logo)
                    .error(R.drawable.ic_leaf_logo)
                    .into(holder.icon);
        }

        boolean canInstall = release != null && compatible != null && !minecraftVersion.isEmpty() && !isBusy;
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
        int buttonColor = holder.itemView.getContext().getColor(canInstall ? R.color.primary : R.color.outline);
        holder.action.setBackgroundTintList(ColorStateList.valueOf(buttonColor));
        holder.action.setOnClickListener(canInstall && listener != null
                ? v -> listener.onClick(mod, release) : null);
        DynamicAnim.applyPressScale(holder.action);
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
        final MaterialCardView card;
        final ImageView icon;
        final TextView name;
        final TextView author;
        final TextView description;
        final TextView version;
        final TextView versions;
        final TextView tags;
        final TextView downloadType;
        final Button action;

        Holder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.external_mod_card);
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
