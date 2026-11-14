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

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.AccountTextUtils;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.List;

public final class AccountsAdapter extends RecyclerView.Adapter<AccountsAdapter.AccountViewHolder> {
    public interface OnAccountActionListener {
        void onSetActive(MsftAccountStore.MsftAccount account);
        void onMore(MsftAccountStore.MsftAccount account, View anchor);
    }

    private final List<MsftAccountStore.MsftAccount> accounts = new ArrayList<>();
    private OnAccountActionListener listener;
    private boolean busy;

    public void setOnAccountActionListener(OnAccountActionListener listener) {
        this.listener = listener;
    }

    public void updateAccounts(List<MsftAccountStore.MsftAccount> list) {
        accounts.clear();
        if (list != null) accounts.addAll(list);
        notifyDataSetChanged();
    }

    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new AccountViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_msft_account, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull AccountViewHolder holder, int position) {
        MsftAccountStore.MsftAccount account = accounts.get(position);
        Context context = holder.itemView.getContext();
        holder.title.setText(AccountTextUtils.displayNameOrNotSigned(context, account));
        holder.subtitle.setText(account.active ? R.string.accounts_selected_description : R.string.accounts_saved_description);
        holder.use.setText(account.active ? R.string.ms_active_badge : R.string.ms_set_active);
        holder.use.setEnabled(!busy && !account.active);
        holder.more.setEnabled(!busy);
        PersonalizationManager personalization = new PersonalizationManager(context);
        holder.card.setStrokeColor(account.active ? UiKit.accent(context) : UiKit.color(context, R.color.ui_card_stroke));
        personalization.applyGlassToView(holder.card);
        if (account.active) {
            holder.use.setBackgroundTintList(ColorStateList.valueOf(UiKit.accentSoft(context)));
            holder.use.setTextColor(UiKit.accentText(context));
        } else {
            UiKit.applyPrimary(holder.use);
        }
        String url = AccountTextUtils.sanitizeUrl(account.xboxAvatarUrl);
        if (url == null) {
            if (holder.avatarRequests != null) holder.avatarRequests.clear(holder.avatar);
            holder.avatar.setTag(R.id.account_avatar, null);
            holder.avatar.setImageResource(R.drawable.ic_minecraft_cube);
        } else {
            holder.avatar.setTag(R.id.account_avatar, url);
            if (holder.avatarRequests == null) holder.avatarRequests = Glide.with(holder.itemView);
            holder.avatarRequests.load(url).placeholder(R.drawable.ic_minecraft_cube)
                    .error(R.drawable.ic_minecraft_cube).circleCrop().into(holder.avatar);
        }
        holder.use.setOnClickListener(v -> {
            if (!busy && !account.active && listener != null) listener.onSetActive(account);
        });
        holder.more.setOnClickListener(v -> {
            if (!busy && listener != null) listener.onMore(account, holder.more);
        });
    }

    @Override
    public void onViewRecycled(@NonNull AccountViewHolder holder) {
        if (holder.avatarRequests != null) holder.avatarRequests.clear(holder.avatar);
        holder.avatar.setTag(R.id.account_avatar, null);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    static final class AccountViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView avatar;
        final TextView title;
        final TextView subtitle;
        final MaterialButton use;
        final MaterialButton more;
        RequestManager avatarRequests;

        AccountViewHolder(View view) {
            super(view);
            card = view.findViewById(R.id.account_card);
            avatar = view.findViewById(R.id.account_avatar);
            title = view.findViewById(R.id.account_title);
            subtitle = view.findViewById(R.id.account_subtitle);
            use = view.findViewById(R.id.btn_use);
            more = view.findViewById(R.id.account_more);
        }
    }
}
