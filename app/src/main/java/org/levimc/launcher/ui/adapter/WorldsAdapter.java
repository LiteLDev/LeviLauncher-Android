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

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.ui.views.ContentActionPopup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WorldsAdapter extends RecyclerView.Adapter<WorldsAdapter.WorldViewHolder> {

    private final List<WorldItem> worlds = new ArrayList<>();
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private OnWorldActionListener onWorldActionListener;
    private OnSelectionChangedListener onSelectionChangedListener;
    private boolean selectionMode;

    public interface OnWorldActionListener {
        void onWorldExport(WorldItem world);
        void onWorldDelete(WorldItem world);
        void onWorldBackup(WorldItem world);
        void onWorldEdit(WorldItem world);
        void onWorldExtractStructures(WorldItem world);
        void onWorldTransfer(WorldItem world);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public void setOnWorldActionListener(OnWorldActionListener listener) {
        this.onWorldActionListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.onSelectionChangedListener = listener;
    }

    public void updateWorlds(List<WorldItem> updatedWorlds) {
        worlds.clear();
        if (updatedWorlds != null) worlds.addAll(updatedWorlds);
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

    public List<WorldItem> getSelectedItems(List<WorldItem> source) {
        List<WorldItem> result = new ArrayList<>();
        if (source == null) return result;
        for (WorldItem world : source) {
            if (selectedPaths.contains(pathOf(world))) result.add(world);
        }
        return result;
    }

    public void selectAllVisible() {
        selectionMode = true;
        for (WorldItem world : worlds) selectedPaths.add(pathOf(world));
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearSelection() {
        selectedPaths.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void retainSelections(List<WorldItem> source) {
        Set<String> valid = new HashSet<>();
        if (source != null) {
            for (WorldItem world : source) valid.add(pathOf(world));
        }
        if (selectedPaths.retainAll(valid)) {
            notifyDataSetChanged();
            notifySelectionChanged();
        }
    }

    @NonNull
    @Override
    public WorldViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_world, parent, false);
        return new WorldViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WorldViewHolder holder, int position) {
        WorldItem world = worlds.get(position);
        holder.boundItem = world;
        boolean selected = selectedPaths.contains(pathOf(world));

        holder.worldName.setText(world.getWorldName());
        holder.worldDescription.setText(holder.itemView.getContext().getString(R.string.world_meta, world.getGameMode(), world.getFormattedSize()));
        java.lang.ref.WeakReference<WorldViewHolder> sizeHolder = new java.lang.ref.WeakReference<>(holder);
        world.loadSizeAsync(() -> {
            WorldViewHolder bound = sizeHolder.get();
            return bound != null && bound.boundItem == world;
        }, () -> {
            WorldViewHolder bound = sizeHolder.get();
            if (bound != null && bound.boundItem == world) {
                bound.worldDescription.setText(bound.itemView.getContext().getString(R.string.world_meta, world.getGameMode(), world.getFormattedSize()));
            }
        });
        holder.worldLastPlayed.setText(holder.itemView.getContext().getString(R.string.world_last_played, world.getFormattedLastModified()));
        holder.itemView.setActivated(selected);
        holder.editButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.overflowButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.selectionIndicator.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.selectionIndicator.setAlpha(selected ? 1f : 0.28f);

        File icon = world.getIconFile();
        Glide.with(holder.worldIcon)
                .load(icon != null ? icon : R.drawable.ic_world)
                .error(R.drawable.ic_world)
                .into(holder.worldIcon);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) toggleSelection(world);
        });
        holder.itemView.setOnLongClickListener(v -> {
            selectionMode = true;
            toggleSelection(world);
            return true;
        });
        holder.editButton.setOnClickListener(v -> {
            if (onWorldActionListener != null) onWorldActionListener.onWorldEdit(world);
        });
        holder.overflowButton.setOnClickListener(v -> showOverflow(holder.overflowButton, world));

        PersonalizationManager pm = new PersonalizationManager(holder.itemView.getContext());
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    @Override
    public void onViewRecycled(@NonNull WorldViewHolder holder) {
        holder.boundItem = null;
        super.onViewRecycled(holder);
    }

    private void showOverflow(View anchor, WorldItem world) {
        ContentActionPopup.show(anchor, world.getWorldName(), Arrays.asList(
                new ContentActionPopup.Action(R.drawable.ic_edit, R.string.edit, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldEdit(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_export, R.string.export, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldExport(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_backup, R.string.backup, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldBackup(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_structure, R.string.extract_structures, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldExtractStructures(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_transfer, R.string.transfer, false, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldTransfer(world);
                }),
                new ContentActionPopup.Action(R.drawable.ic_delete, R.string.delete, true, () -> {
                    if (onWorldActionListener != null) onWorldActionListener.onWorldDelete(world);
                })
        ));
    }

    private void toggleSelection(WorldItem world) {
        String path = pathOf(world);
        if (!selectedPaths.add(path)) selectedPaths.remove(path);
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (onSelectionChangedListener != null) onSelectionChangedListener.onSelectionChanged(selectedPaths.size());
    }

    private String pathOf(WorldItem world) {
        return world == null ? "" : world.getStablePath();
    }

    private int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return worlds.size();
    }

    static class WorldViewHolder extends RecyclerView.ViewHolder {
        volatile WorldItem boundItem;
        final ImageView worldIcon;
        final TextView worldName;
        final TextView worldLastPlayed;
        final TextView worldDescription;
        final ImageButton editButton;
        final ImageButton overflowButton;
        final ImageView selectionIndicator;

        WorldViewHolder(@NonNull View itemView) {
            super(itemView);
            worldIcon = itemView.findViewById(R.id.world_icon);
            worldName = itemView.findViewById(R.id.world_name);
            worldLastPlayed = itemView.findViewById(R.id.world_last_played);
            worldDescription = itemView.findViewById(R.id.world_description);
            editButton = itemView.findViewById(R.id.world_edit_button);
            overflowButton = itemView.findViewById(R.id.world_overflow_button);
            selectionIndicator = itemView.findViewById(R.id.world_selection_indicator);
        }
    }
}
