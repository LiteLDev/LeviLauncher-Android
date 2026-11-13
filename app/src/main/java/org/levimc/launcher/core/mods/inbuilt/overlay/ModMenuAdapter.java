package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.UnifiedMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModMenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_GROUP = 0;
    private static final int VIEW_TYPE_MOD = 1;
    private static final int VIEW_TYPE_MOD_COMPACT = 2;

    private final List<MenuItem> items = new ArrayList<>();
    private final Map<String, Boolean> toggleStates = new HashMap<>();
    private final Map<String, Boolean> favoriteStates = new HashMap<>();
    private OnModActionListener listener;
    private ModMenuTheme palette;
    private boolean compactMode;

    public ModMenuAdapter() {
        setHasStableIds(true);
    }

    public interface OnModActionListener {
        void onToggle(UnifiedMod mod, boolean enabled);
        void onConfig(UnifiedMod mod);
        void onFavoriteChanged(UnifiedMod mod, boolean favorite);
    }

    public void refreshTheme() { palette = null; notifyDataSetChanged(); }

    private ModMenuTheme theme(android.content.Context context) {
        if (palette == null) palette = new ModMenuTheme(context);
        return palette;
    }

    public void setOnModActionListener(OnModActionListener listener) {
        this.listener = listener;
    }

    public void setCompactMode(boolean compactMode) {
        if (this.compactMode == compactMode) return;
        this.compactMode = compactMode;
        items.clear();
        toggleStates.clear();
        favoriteStates.clear();
        notifyDataSetChanged();
    }

    public void updateMods(List<UnifiedMod> mods, Set<String> favoriteKeys) {
        List<MenuItem> nextItems = new ArrayList<>();
        Map<String, Boolean> nextToggleStates = new HashMap<>();
        Map<String, Boolean> nextFavoriteStates = new HashMap<>();
        String lastGroupId = null;
        for (UnifiedMod mod : mods) {
            boolean firstInGroup = !Objects.equals(mod.getGroupId(), lastGroupId);
            if (!compactMode && firstInGroup) {
                nextItems.add(MenuItem.group(mod.getGroupId(), mod.getGroupName()));
            }
            boolean showGroupLabel = compactMode
                && firstInGroup
                && mod.getSource() == UnifiedMod.Source.EXTERNAL;
            nextItems.add(MenuItem.mod(mod, showGroupLabel));
            lastGroupId = mod.getGroupId();
            nextToggleStates.put(mod.getStableKey(), mod.isEnabled());
            nextFavoriteStates.put(mod.getStableKey(),
                favoriteKeys != null && favoriteKeys.contains(mod.getStableKey()));
        }

        List<MenuItem> oldItems = new ArrayList<>(items);
        Map<String, Boolean> oldToggleStates = new HashMap<>(toggleStates);
        Map<String, Boolean> oldFavoriteStates = new HashMap<>(favoriteStates);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return nextItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).stableKey()
                    .equals(nextItems.get(newItemPosition).stableKey());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                MenuItem oldItem = oldItems.get(oldItemPosition);
                MenuItem newItem = nextItems.get(newItemPosition);
                if (oldItem.isGroup() || newItem.isGroup()) {
                    return oldItem.isGroup() == newItem.isGroup()
                        && Objects.equals(oldItem.groupName, newItem.groupName);
                }
                String oldKey = oldItem.mod.getStableKey();
                String newKey = newItem.mod.getStableKey();
                return Objects.equals(oldItem.mod.getName(), newItem.mod.getName())
                    && Objects.equals(oldItem.mod.getDescription(), newItem.mod.getDescription())
                    && oldItem.mod.hasConfig() == newItem.mod.hasConfig()
                    && oldItem.showGroupLabel == newItem.showGroupLabel
                    && oldToggleStates.getOrDefault(oldKey, false)
                        .equals(nextToggleStates.getOrDefault(newKey, false))
                    && oldFavoriteStates.getOrDefault(oldKey, false)
                        .equals(nextFavoriteStates.getOrDefault(newKey, false));
            }
        }, false);

        items.clear();
        items.addAll(nextItems);
        toggleStates.clear();
        toggleStates.putAll(nextToggleStates);
        favoriteStates.clear();
        favoriteStates.putAll(nextFavoriteStates);
        diff.dispatchUpdatesTo(this);
    }

    public boolean isGroupHeader(int position) {
        return position >= 0 && position < items.size() && items.get(position).isGroup();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_GROUP) {
            TextView title = new TextView(parent.getContext());
            float density = parent.getResources().getDisplayMetrics().density;
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (24 * density));
            params.setMargins((int) (8 * density), (int) (2 * density),
                (int) (8 * density), 0);
            title.setLayoutParams(params);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding((int) (6 * density), 0, (int) (6 * density), 0);
            title.setTextColor(theme(parent.getContext()).secondary);
            title.setTextSize(10);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            return new GroupViewHolder(title);
        }

        int layout = viewType == VIEW_TYPE_MOD_COMPACT
            ? R.layout.item_mod_menu_compact
            : R.layout.item_mod_menu_card;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ModViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MenuItem item = items.get(position);
        ModMenuTheme theme = theme(holder.itemView.getContext());
        if (item.isGroup()) {
            GroupViewHolder group = (GroupViewHolder) holder;
            group.title.setText(item.groupName);
            group.title.setTextColor(theme.secondary);
            group.title.setTextSize(11 * ModMenuTheme.textRatio(group.title.getContext()));
            return;
        }
        ModViewHolder row = (ModViewHolder) holder;
        UnifiedMod mod = item.mod;
        row.name.setTextSize(12 * ModMenuTheme.textRatio(row.name.getContext()));
        row.description.setTextSize(10.5f * ModMenuTheme.textRatio(row.description.getContext()));
        row.name.setText(mod.getName());
        row.name.setTextColor(theme.text);
        row.description.setText(mod.getDescription() == null || mod.getDescription().isEmpty() ? mod.getGroupName() : mod.getDescription());
        row.description.setTextColor(theme.secondary);
        if (row.groupText != null) {
            row.groupText.setText(mod.getGroupName());
            row.groupText.setTextColor(theme.secondary);
            row.groupText.setTextSize(10 * ModMenuTheme.textRatio(row.groupText.getContext()));
            row.groupText.setVisibility(item.showGroupLabel ? View.VISIBLE : View.GONE);
        }
        row.icon.setImageResource(mod.getSource() == UnifiedMod.Source.INBUILT ? ModIconHelper.getModIcon(mod.getId()) : R.drawable.ic_modmenu_layers);
        row.icon.setImageTintList(mod.getSource() == UnifiedMod.Source.EXTERNAL ? ColorStateList.valueOf(theme.secondary) : null);
        row.icon.setColorFilter(null);
        row.configBtn.setImageTintList(ColorStateList.valueOf(theme.secondary));
        row.configBtn.setBackground(theme.touch(android.graphics.Color.TRANSPARENT, 5, false));
        row.favoriteBtn.setBackground(theme.touch(android.graphics.Color.TRANSPARENT, 5, false));
        row.configBtn.setContentDescription(mod.getName() + ": " + row.itemView.getContext().getString(R.string.mm_edit_configuration));
        row.itemView.setContentDescription(mod.getName());
        row.toggle.setPalette(theme);
        row.toggle.setContentDescription(mod.getName());
        row.toggle.setOnCheckedChangeListener(null);
        row.toggle.setChecked(toggleStates.getOrDefault(mod.getStableKey(), false));
        row.toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled == toggleStates.getOrDefault(mod.getStableKey(), false)) return;
            toggleStates.put(mod.getStableKey(), enabled);
            updateStatusView(row, enabled);
            if (listener != null) listener.onToggle(mod, enabled);
        });
        updateStatusView(row, row.toggle.isChecked());
        updateFavoriteView(row, favoriteStates.getOrDefault(mod.getStableKey(), false));
        row.itemView.setOnClickListener(v -> row.toggle.setChecked(!row.toggle.isChecked()));
        row.itemView.setOnLongClickListener(v -> {
            String description = mod.getDescription();
            ModMenuDialogs.info(v, mod.getName(), description == null || description.isEmpty() ? mod.getGroupName() : description,
                    mod.hasConfig() ? () -> { if (listener != null) listener.onConfig(mod); } : null);
            return true;
        });
        row.statusText.setOnClickListener(v -> row.toggle.setChecked(!row.toggle.isChecked()));
        row.icon.setOnClickListener(v -> row.toggle.setChecked(!row.toggle.isChecked()));
        row.favoriteBtn.setOnClickListener(v -> {
            boolean favorite = !favoriteStates.getOrDefault(mod.getStableKey(), false);
            favoriteStates.put(mod.getStableKey(), favorite);
            updateFavoriteView(row, favorite);
            if (listener != null) listener.onFavoriteChanged(mod, favorite);
        });
        row.configBtn.setVisibility(mod.hasConfig() ? View.VISIBLE : View.GONE);
        row.configBtn.setOnClickListener(v -> { if (listener != null) listener.onConfig(mod); });
    }

    @Override
    public int getItemViewType(int position) {
        if (items.get(position).isGroup()) return VIEW_TYPE_GROUP;
        return compactMode ? VIEW_TYPE_MOD_COMPACT : VIEW_TYPE_MOD;
    }

    private void updateFavoriteView(ModViewHolder holder, boolean favorite) {
        ModMenuTheme theme = theme(holder.itemView.getContext());
        holder.favoriteBtn.setImageResource(favorite ? R.drawable.ic_star : R.drawable.ic_modmenu_star);
        holder.favoriteBtn.setImageTintList(ColorStateList.valueOf(favorite ? theme.text : theme.secondary));
        holder.favoriteBtn.setAlpha(1f);
        holder.favoriteBtn.setSelected(favorite);
        holder.favoriteBtn.setContentDescription(holder.favoriteBtn.getContext().getString(
                favorite ? R.string.mod_menu_unfavorite : R.string.mod_menu_favorite));
    }

    private void updateStatusView(ModViewHolder holder, boolean enabled) {
        holder.statusText.setText(enabled ? R.string.mod_status_enabled : R.string.mod_status_disabled);
        holder.toggle.setSelected(enabled);
        if (android.os.Build.VERSION.SDK_INT >= 30) holder.toggle.setStateDescription(holder.itemView.getContext().getString(enabled ? R.string.mod_status_enabled : R.string.mod_status_disabled));
        updateCardState(holder, enabled);
    }

    private void updateCardState(ModViewHolder holder, boolean enabled) {
        ModMenuTheme theme = theme(holder.itemView.getContext());
        holder.itemView.setAlpha(1f);
        holder.icon.setAlpha(1f);
        holder.name.setTextColor(theme.text);
        holder.itemView.setBackground(theme.touch(theme.raised, new ModMenuPreferences(holder.itemView.getContext()).elementRadius(), true));
        holder.itemView.setStateListAnimator(null);
        holder.itemView.setSelected(enabled);
    }


    @Override
    public long getItemId(int position) {
        String key = items.get(position).stableKey();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static class MenuItem {
        final String groupId;
        final String groupName;
        final UnifiedMod mod;
        final boolean showGroupLabel;

        private MenuItem(String groupId, String groupName, UnifiedMod mod, boolean showGroupLabel) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.mod = mod;
            this.showGroupLabel = showGroupLabel;
        }

        static MenuItem group(String groupId, String groupName) {
            return new MenuItem(groupId, groupName, null, false);
        }

        static MenuItem mod(UnifiedMod mod, boolean showGroupLabel) {
            return new MenuItem(null, null, mod, showGroupLabel);
        }

        boolean isGroup() {
            return mod == null;
        }

        String stableKey() {
            return isGroup() ? "group:" + groupId : "mod:" + mod.getStableKey();
        }
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        TextView title;

        GroupViewHolder(View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }
    }

    static class ModViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView description;
        ModMenuToggle toggle;
        TextView statusText;
        TextView groupText;
        ImageButton favoriteBtn;
        ImageButton configBtn;

        ModViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.mod_card_icon);
            name = itemView.findViewById(R.id.mod_card_name);
            description = itemView.findViewById(R.id.mod_card_description);
            toggle = itemView.findViewById(R.id.mod_card_toggle);
            statusText = itemView.findViewById(R.id.mod_card_status);
            groupText = itemView.findViewById(R.id.mod_card_group);
            favoriteBtn = itemView.findViewById(R.id.mod_card_favorite);
            configBtn = itemView.findViewById(R.id.mod_card_config);
        }
    }
}
