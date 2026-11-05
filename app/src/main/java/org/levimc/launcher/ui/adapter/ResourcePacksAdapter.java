package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ResourcePackItem;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.ui.views.ContentActionPopup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ResourcePacksAdapter extends RecyclerView.Adapter<ResourcePacksAdapter.ResourcePackViewHolder> {

    private final List<ResourcePackItem> resourcePacks = new ArrayList<>();
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private OnResourcePackActionListener onResourcePackActionListener;
    private OnSelectionChangedListener onSelectionChangedListener;
    private boolean selectionMode;

    public interface OnResourcePackActionListener {
        void onResourcePackDelete(ResourcePackItem pack);
        void onResourcePackTransfer(ResourcePackItem pack);
        void onResourcePackExport(ResourcePackItem pack);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public void setOnResourcePackActionListener(OnResourcePackActionListener listener) {
        this.onResourcePackActionListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.onSelectionChangedListener = listener;
    }

    public void updateResourcePacks(List<ResourcePackItem> packs) {
        resourcePacks.clear();
        if (packs != null) resourcePacks.addAll(packs);
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public ArrayList<String> getSelectedPaths() {
        return new ArrayList<>(selectedPaths);
    }

    public void restoreSelection(List<String> paths, boolean active) {
        selectedPaths.clear();
        if (paths != null) selectedPaths.addAll(paths);
        selectionMode = active;
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public List<ResourcePackItem> getSelectedItems(List<ResourcePackItem> source) {
        List<ResourcePackItem> result = new ArrayList<>();
        if (source == null) return result;
        for (ResourcePackItem pack : source) {
            if (selectedPaths.contains(pathOf(pack))) result.add(pack);
        }
        return result;
    }

    public void selectAllVisible() {
        selectionMode = true;
        for (ResourcePackItem pack : resourcePacks) selectedPaths.add(pathOf(pack));
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearSelection() {
        selectedPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void retainSelections(List<ResourcePackItem> source) {
        Set<String> valid = new HashSet<>();
        if (source != null) {
            for (ResourcePackItem pack : source) valid.add(pathOf(pack));
        }
        if (selectedPaths.retainAll(valid)) {
            notifyDataSetChanged();
            notifySelectionChanged();
        }
    }

    @NonNull
    @Override
    public ResourcePackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_resource_pack, parent, false);
        return new ResourcePackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ResourcePackViewHolder holder, int position) {
        ResourcePackItem pack = resourcePacks.get(position);
        holder.boundItem = pack;
        boolean selected = selectedPaths.contains(pathOf(pack));
        String version = pack.getVersion() == null || pack.getVersion().isEmpty() ? "?" : pack.getVersion();

        holder.packName.setText(pack.getPackName());
        holder.packMeta.setText(holder.itemView.getContext().getString(R.string.pack_meta, version, pack.getFormattedSize()));
        java.lang.ref.WeakReference<ResourcePackViewHolder> sizeHolder = new java.lang.ref.WeakReference<>(holder);
        pack.loadSizeAsync(() -> {
            ResourcePackViewHolder bound = sizeHolder.get();
            return bound != null && bound.boundItem == pack;
        }, () -> {
            ResourcePackViewHolder bound = sizeHolder.get();
            if (bound != null && bound.boundItem == pack) {
                bound.packMeta.setText(bound.itemView.getContext().getString(R.string.pack_meta, version, pack.getFormattedSize()));
            }
        });
        holder.packDescription.setText(pack.getDescription());
        holder.itemView.setActivated(selected);
        holder.exportButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.overflowButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.selectionIndicator.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.selectionIndicator.setAlpha(selected ? 1f : 0.28f);

        int fallback = fallbackIcon(pack);
        File icon = pack.getIconFile();
        Glide.with(holder.packIcon)
                .load(icon != null ? icon : fallback)
                .transform(new RoundedCorners(dp(holder.itemView, 8)))
                .error(fallback)
                .into(holder.packIcon);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) toggleSelection(pack);
        });
        holder.itemView.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleSelection(pack);
            return true;
        });
        holder.exportButton.setOnClickListener(v -> {
            if (onResourcePackActionListener != null) onResourcePackActionListener.onResourcePackExport(pack);
        });
        holder.overflowButton.setOnClickListener(v -> showOverflow(holder.overflowButton, pack));

        PersonalizationManager pm = new PersonalizationManager(holder.itemView.getContext());
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    @Override
    public void onViewRecycled(@NonNull ResourcePackViewHolder holder) {
        holder.boundItem = null;
        super.onViewRecycled(holder);
    }

    private void showOverflow(View anchor, ResourcePackItem pack) {
        ContentActionPopup.show(anchor, pack.getPackName(), Arrays.asList(
                new ContentActionPopup.Action(R.drawable.ic_export, R.string.export, false, () -> {
                    if (onResourcePackActionListener != null) onResourcePackActionListener.onResourcePackExport(pack);
                }),
                new ContentActionPopup.Action(R.drawable.ic_transfer, R.string.transfer, false, () -> {
                    if (onResourcePackActionListener != null) onResourcePackActionListener.onResourcePackTransfer(pack);
                }),
                new ContentActionPopup.Action(R.drawable.ic_delete, R.string.delete, true, () -> {
                    if (onResourcePackActionListener != null) onResourcePackActionListener.onResourcePackDelete(pack);
                })
        ));
    }

    private int fallbackIcon(ResourcePackItem pack) {
        if (pack.isBehaviorPack()) return R.drawable.ic_behavior;
        if (pack.isSkinPack()) return R.drawable.ic_tshirt;
        return R.drawable.ic_photo;
    }

    private void toggleSelection(ResourcePackItem pack) {
        String path = pathOf(pack);
        if (!selectedPaths.add(path)) selectedPaths.remove(path);
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (onSelectionChangedListener != null) onSelectionChangedListener.onSelectionChanged(selectedPaths.size());
    }

    private String pathOf(ResourcePackItem pack) {
        return pack == null ? "" : pack.getStablePath();
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return resourcePacks.size();
    }

    static class ResourcePackViewHolder extends RecyclerView.ViewHolder {
        volatile ResourcePackItem boundItem;
        final ImageView packIcon;
        final TextView packName;
        final TextView packDescription;
        final TextView packMeta;
        final ImageButton exportButton;
        final ImageButton overflowButton;
        final ImageView selectionIndicator;

        ResourcePackViewHolder(@NonNull View itemView) {
            super(itemView);
            packIcon = itemView.findViewById(R.id.pack_icon);
            packName = itemView.findViewById(R.id.pack_name);
            packDescription = itemView.findViewById(R.id.pack_description);
            packMeta = itemView.findViewById(R.id.pack_meta);
            exportButton = itemView.findViewById(R.id.pack_export_button);
            overflowButton = itemView.findViewById(R.id.pack_overflow_button);
            selectionIndicator = itemView.findViewById(R.id.pack_selection_indicator);
        }
    }
}
