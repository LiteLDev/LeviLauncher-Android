package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.UiKit;

import java.util.ArrayList;
import java.util.List;

public class QuickLaunchAdapter extends RecyclerView.Adapter<QuickLaunchAdapter.ViewHolder> {

    public enum ActionType {
        HOW_TO_PLAY,
        SERVERS_TAB,
        PROFILE_SCREEN,
        STORE_HOME,
        MINECOIN_OFFERS,
        MARKETPLACE_PASS,
        CONNECT_SERVER,
        ADD_SERVER,
        REALM_INVITE,
        LOAD_WORLD,
        SLASH_COMMAND,
        CUSTOM_URI
    }

    public static class QuickLaunchItem {
        public final String title;
        public final String description;
        public final ActionType actionType;

        public QuickLaunchItem(String title, String description, ActionType actionType) {
            this.title = title;
            this.description = description;
            this.actionType = actionType;
        }
    }

    public interface OnActionClickListener {
        void onActionClick(ActionType actionType);
    }

    private List<QuickLaunchItem> items = new ArrayList<>();
    private OnActionClickListener listener;

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.listener = listener;
    }

    public void updateItems(List<QuickLaunchItem> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_quick_launch, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        QuickLaunchItem item = items.get(position);
        holder.bind(item, position == items.size() - 1);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static int iconFor(ActionType type) {
        switch (type) {
            case HOW_TO_PLAY: return R.drawable.ic_ui_lightbulb;
            case SERVERS_TAB: return R.drawable.ic_ui_server;
            case PROFILE_SCREEN: return R.drawable.ic_ui_person;
            case STORE_HOME: return R.drawable.ic_ui_puzzle;
            case MINECOIN_OFFERS: return R.drawable.ic_ui_bolt;
            case MARKETPLACE_PASS: return R.drawable.ic_ui_star;
            case CONNECT_SERVER: return R.drawable.ic_ui_globe;
            case ADD_SERVER: return R.drawable.ic_ui_add;
            case REALM_INVITE: return R.drawable.ic_ui_group;
            case LOAD_WORLD: return R.drawable.ic_ui_image;
            case SLASH_COMMAND: return R.drawable.ic_ui_code;
            case CUSTOM_URI:
            default: return R.drawable.ic_ui_open_in_new;
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final View row;
        private final View iconChip;
        private final ImageView iconView;
        private final TextView titleText;
        private final TextView descriptionText;
        private final MaterialButton launchButton;
        private final View divider;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.quick_launch_row);
            iconChip = itemView.findViewById(R.id.icon_chip);
            iconView = itemView.findViewById(R.id.icon_view);
            titleText = itemView.findViewById(R.id.title_text);
            descriptionText = itemView.findViewById(R.id.description_text);
            launchButton = itemView.findViewById(R.id.launch_button);
            divider = itemView.findViewById(R.id.item_divider);
        }

        void bind(QuickLaunchItem item, boolean last) {
            titleText.setText(item.title);
            descriptionText.setText(item.description);
            iconView.setImageResource(iconFor(item.actionType));
            divider.setVisibility(last ? View.GONE : View.VISIBLE);

            Context ctx = itemView.getContext();
            int accent = UiKit.accent(ctx);
            int accentText = UiKit.accentText(ctx);
            UiKit.tintIconChip(iconChip, iconView, accent);
            launchButton.setStrokeColor(ColorStateList.valueOf(accent));
            launchButton.setTextColor(accentText);
            launchButton.setIconTint(ColorStateList.valueOf(accentText));

            View.OnClickListener click = v -> {
                if (listener != null) {
                    listener.onActionClick(item.actionType);
                }
            };
            row.setOnClickListener(click);
            launchButton.setOnClickListener(click);
        }
    }
}
