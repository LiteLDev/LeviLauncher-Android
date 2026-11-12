package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.ItemTouchHelper;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.ui.packages.InstalledFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModsAdapter extends RecyclerView.Adapter<ModsAdapter.ModViewHolder> {

    private List<Mod> mods = new ArrayList<>();
    private OnModEnableChangeListener onModEnableChangeListener;
    private OnModReorderListener onModReorderListener;
    private OnModClickListener onModClickListener;
    private ItemTouchHelper itemTouchHelper;
    private OnPkgUpdateClickListener onPkgUpdateClickListener;
    /** The packages owning mod folders (lower-case folder), from InstalledFragment.Model.byFolder. */
    private Map<String, InstalledFragment.Model.Row> pkgRows = Collections.emptyMap();
    /** False while the list is filtered or sorted: a reorder only makes sense on the full load order. */
    private boolean reorderEnabled = true;


    public interface OnModEnableChangeListener {
        void onEnableChanged(Mod mod, boolean enabled);
    }

    public interface OnModReorderListener {
        void onModsReordered(List<Mod> reorderedMods);
    }

    public interface OnModClickListener {
        void onModClick(Mod mod, int position, View sharedView);
    }

    /** The "Update to X" chip of a mod a package owns was tapped. */
    public interface OnPkgUpdateClickListener {
        void onPkgUpdateClick(Mod mod, InstalledFragment.Model.Row row);
    }

    public ModsAdapter(List<Mod> mods) {
        this.mods = mods;
    }


    public void setOnModEnableChangeListener(OnModEnableChangeListener l) {
        this.onModEnableChangeListener = l;
    }

    public void setOnModReorderListener(OnModReorderListener listener) {
        this.onModReorderListener = listener;
    }

    public void setOnModClickListener(OnModClickListener listener) {
        this.onModClickListener = listener;
    }

    public void setItemTouchHelper(ItemTouchHelper itemTouchHelper) {
        this.itemTouchHelper = itemTouchHelper;
    }

    public void setOnPkgUpdateClickListener(OnPkgUpdateClickListener listener) {
        this.onPkgUpdateClickListener = listener;
    }

    public void setReorderEnabled(boolean enabled) {
        if (reorderEnabled == enabled) return;
        reorderEnabled = enabled;
        notifyDataSetChanged();
    }

    public boolean isReorderEnabled() {
        return reorderEnabled;
    }

    /** Shows the package badge and update chip of the folders packages own (loaded asynchronously). */
    public void setPkgRows(Map<String, InstalledFragment.Model.Row> byFolder) {
        this.pkgRows = byFolder == null ? Collections.emptyMap() : byFolder;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ModViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod, parent, false);
        return new ModViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ModViewHolder holder, int position) {
        Mod mod = mods.get(position);
        holder.name.setText(mod.getDisplayName());
        holder.authorText.setText(holder.itemView.getContext().getString(
                R.string.mod_author_byline,
                fallbackText(mod.getAuthor(), holder.itemView.getContext().getString(R.string.mod_unknown_author))));
        holder.versionText.setText(
                fallbackText(mod.getVersion(), holder.itemView.getContext().getString(R.string.mod_unknown_version)));

        if (holder.divider != null) {
            holder.divider.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
        }

        if (holder.orderText != null) {
            // The position is the load order only while the full, unsorted list is shown.
            holder.orderText.setText(holder.itemView.getContext().getString(R.string.mod_load_order, position + 1));
            holder.orderText.setVisibility(reorderEnabled ? View.VISIBLE : View.GONE);
        }

        if (holder.configBadge != null) {
            holder.configBadge.setVisibility(mod.hasEditableConfig() ? View.VISIBLE : View.GONE);
        }

        bindPackage(holder, mod);
        if (holder.badgesRow != null) {
            InstalledFragment.Model.Row row = pkgRows.get(InstalledFragment.Model.folderKey(mod.getId()));
            holder.badgesRow.setVisibility(row != null && row.canUpdate() ? View.VISIBLE : View.GONE);
        }
        holder.switchBtn.setContentDescription(holder.itemView.getContext().getString(R.string.mods_enable_mod, mod.getDisplayName()));

        holder.switchBtn.setOnCheckedChangeListener(null);
        holder.switchBtn.setChecked(mod.isEnabled());

        holder.switchBtn.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked == mod.isEnabled()) return;

            mod.setEnabled(isChecked);

            if (onModEnableChangeListener != null) {
                onModEnableChangeListener.onEnableChanged(mod, isChecked);
            }
        });

        if (holder.dragHandle != null) {
            holder.dragHandle.setVisibility(reorderEnabled ? View.VISIBLE : View.GONE);
            holder.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && itemTouchHelper != null
                        && reorderEnabled) {
                    if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return false;
                    itemTouchHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }

        ViewCompat.setTransitionName(holder.itemView, "mod_card_" + mod.getId());

        holder.itemView.setOnClickListener(v -> {
            int current = holder.getBindingAdapterPosition();
            if (onModClickListener != null && current != RecyclerView.NO_POSITION) {
                onModClickListener.onModClick(mod, current, holder.itemView);
            }
        });

        android.content.Context context = holder.itemView.getContext();
        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(context);
        if (context instanceof android.app.Activity) pm.applyGlassToTree(holder.itemView, (android.app.Activity) context);
        pm.applyAccentToView(holder.itemView, context);
    }

    @Override
    public int getItemCount() {
        return mods != null ? mods.size() : 0;
    }

    public Mod getItem(int pos) {
        return mods.get(pos);
    }

    public void removeAt(int pos) {
        mods.remove(pos);
        notifyItemRemoved(pos);
    }

    public void updateMods(List<Mod> list) {
        this.mods = list;
        notifyDataSetChanged();
    }

    public void moveItem(int fromPosition, int toPosition) {
        if (mods == null || mods.isEmpty()) return;
        if (fromPosition < 0 || toPosition < 0) return;
        if (fromPosition >= mods.size() || toPosition >= mods.size()) return;
        if (fromPosition == toPosition) return;

        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(mods, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(mods, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    public void commitReorder() {
        if (onModReorderListener != null) {
            onModReorderListener.onModsReordered(new ArrayList<>(mods));
        }
    }

    private void bindPackage(ModViewHolder holder, Mod mod) {
        InstalledFragment.Model.Row row = pkgRows.get(InstalledFragment.Model.folderKey(mod.getId()));
        if (holder.pkgBadge != null) {
            holder.pkgBadge.setVisibility(row != null ? View.VISIBLE : View.GONE);
            if (row != null) {
                holder.pkgBadge.setImageTintList(android.content.res.ColorStateList.valueOf(
                        org.levimc.launcher.ui.widget.UiKit.accentText(holder.itemView.getContext())));
            }
        }
        if (holder.pkgUpdate == null) {
            return;
        }
        if (row == null || !row.canUpdate()) {
            holder.pkgUpdate.setVisibility(View.GONE);
            holder.pkgUpdate.setOnClickListener(null);
            return;
        }
        holder.pkgUpdate.setVisibility(View.VISIBLE);
        holder.pkgUpdate.setText(holder.itemView.getContext().getString(
                R.string.pkg_installed_update_available, row.targetVersion));
        holder.pkgUpdate.setOnClickListener(v -> {
            if (onPkgUpdateClickListener != null) {
                onPkgUpdateClickListener.onPkgUpdateClick(mod, row);
            }
        });
    }

    private String fallbackText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    static class ModViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView orderText;
        TextView configBadge;
        ImageView pkgBadge;
        View divider;
        View badgesRow;
        TextView pkgUpdate;
        TextView authorText;
        TextView versionText;
        CompoundButton switchBtn;
        ImageView dragHandle;

        public ModViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.mod_name);
            badgesRow = itemView.findViewById(R.id.mod_badges);
            orderText = itemView.findViewById(R.id.mod_order);
            configBadge = itemView.findViewById(R.id.mod_config_badge);
            pkgBadge = itemView.findViewById(R.id.mod_pkg_badge);
            pkgUpdate = itemView.findViewById(R.id.mod_pkg_update);
            authorText = itemView.findViewById(R.id.mod_author);
            versionText = itemView.findViewById(R.id.mod_version);
            switchBtn = itemView.findViewById(R.id.mod_switch);
            dragHandle = itemView.findViewById(R.id.drag_handle);
            divider = itemView.findViewById(R.id.mod_divider);
        }
    }
}
