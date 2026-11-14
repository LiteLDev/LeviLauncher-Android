package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.widget.Toast;
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
import androidx.recyclerview.widget.DiffUtil;

import com.google.android.material.card.MaterialCardView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.news.NewsItem;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.ui.widget.UiKit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.Holder> {
    private final List<NewsItem> items = new ArrayList<>();
    private final Set<String> unreadIds = new HashSet<>();
    void submit(List<NewsItem> news, Set<String> unread) {
        List<NewsItem> previous = new ArrayList<>(items);
        Set<String> previousUnread = new HashSet<>(unreadIds);
        List<NewsItem> next = news == null ? new ArrayList<>() : new ArrayList<>(news);
        Set<String> nextUnread = unread == null ? new HashSet<>() : new HashSet<>(unread);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() { return previous.size(); }
            @Override
            public int getNewListSize() { return next.size(); }
            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return Objects.equals(previous.get(oldPosition).id, next.get(newPosition).id);
            }
            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                NewsItem before = previous.get(oldPosition);
                NewsItem after = next.get(newPosition);
                return Objects.equals(before.title, after.title) && Objects.equals(before.summary, after.summary)
                        && Objects.equals(before.category, after.category) && Objects.equals(before.url, after.url)
                        && Objects.equals(before.publishedAt, after.publishedAt) && before.important == after.important
                        && previousUnread.contains(before.id) == nextUnread.contains(after.id);
            }
        });
        items.clear();
        items.addAll(next);
        unreadIds.clear();
        unreadIds.addAll(nextUnread);
        diff.dispatchUpdatesTo(this);
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
        holder.category.setText(TextUtils.isEmpty(item.category) ? holder.itemView.getContext().getString(R.string.news_all) : item.category);
        holder.date.setText(formatDate(item.publishedAt));
        holder.unread.setVisibility(unreadIds.contains(item.id) ? View.VISIBLE : View.GONE);
        holder.important.setVisibility(item.important ? View.VISIBLE : View.GONE);
        boolean canOpen = isWebUrl(item.url);
        holder.action.setVisibility(canOpen ? View.VISIBLE : View.GONE);
        holder.card.setClickable(canOpen);
        holder.card.setFocusable(canOpen);
        new PersonalizationManager(holder.itemView.getContext()).applyGlassToView(holder.card);
        UiKit.tintSoftAccent(holder.category, 6);
        android.graphics.drawable.GradientDrawable notice = new android.graphics.drawable.GradientDrawable();
        notice.setColor(UiKit.alpha(UiKit.RED, 28));
        notice.setCornerRadius(UiKit.dp(holder.itemView.getContext(), 6));
        holder.important.setBackground(notice);
        styleReadMore(holder.action);

        View.OnClickListener open = v -> {
            if (!isWebUrl(item.url)) return;
            Uri uri = Uri.parse(item.url);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                holder.itemView.getContext().startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(holder.itemView.getContext(), R.string.ms_no_browser, Toast.LENGTH_SHORT).show();
            }
        };
        holder.card.setOnClickListener(canOpen ? open : null);
        holder.card.setClickable(canOpen);
        holder.action.setOnClickListener(open);

    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatDate(String value) {
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                    .format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static boolean isWebUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        return !TextUtils.isEmpty(uri.getHost()) && ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()));
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
