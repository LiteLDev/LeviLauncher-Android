package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.news.NewsItem;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.widget.UiKit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.Holder> {
    private final List<NewsItem> items = new ArrayList<>();
    private final Set<String> unreadIds = new HashSet<>();
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    void submit(List<NewsItem> news, Set<String> unread) {
        items.clear();
        if (news != null) items.addAll(news);
        unreadIds.clear();
        if (unread != null) unreadIds.addAll(unread);
        notifyDataSetChanged();
    }

    int indexOf(String id) {
        if (TextUtils.isEmpty(id)) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_news, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        NewsItem item = items.get(position);
        holder.title.setText(item.title);
        holder.summary.setText(item.summary);
        holder.summary.setVisibility(TextUtils.isEmpty(item.summary) ? View.GONE : View.VISIBLE);
        holder.category.setText(TextUtils.isEmpty(item.category) ? "News" : item.category);
        holder.date.setText(formatDate(item.publishedAt));
        holder.unread.setVisibility(unreadIds.contains(item.id) ? View.VISIBLE : View.GONE);
        holder.important.setVisibility(item.important ? View.VISIBLE : View.GONE);
        holder.action.setVisibility(TextUtils.isEmpty(item.url) ? View.GONE : View.VISIBLE);
        styleReadMore(holder.action);

        View.OnClickListener open = v -> {
            if (TextUtils.isEmpty(item.url)) return;
            Uri uri = Uri.parse(item.url);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            holder.itemView.getContext().startActivity(intent);
        };
        holder.card.setOnClickListener(open);
        holder.action.setOnClickListener(open);
        DynamicAnim.applyPressScale(holder.card);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatDate(String value) {
        try {
            return dateFormat.format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static int dp(View view, int value) {
        return (int) (value * view.getResources().getDisplayMetrics().density);
    }

    private static void styleReadMore(TextView action) {
        int color = UiKit.accentText(action.getContext());
        action.setTextColor(color);
        Drawable icon = ContextCompat.getDrawable(action.getContext(), R.drawable.ic_ui_open_in_new);
        if (icon != null) {
            icon = icon.mutate();
            int size = dp(action, 16);
            icon.setBounds(0, 0, size, size);
            icon.setTint(color);
        }
        action.setCompoundDrawablesRelative(null, null, icon, null);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView title;
        final TextView summary;
        final TextView category;
        final TextView date;
        final TextView important;
        final TextView action;
        final View unread;

        Holder(View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.news_card);
            title = itemView.findViewById(R.id.news_item_title);
            summary = itemView.findViewById(R.id.news_item_summary);
            category = itemView.findViewById(R.id.news_item_category);
            date = itemView.findViewById(R.id.news_item_date);
            important = itemView.findViewById(R.id.news_item_important);
            action = itemView.findViewById(R.id.news_item_action);
            unread = itemView.findViewById(R.id.news_item_unread);
        }
    }
}
