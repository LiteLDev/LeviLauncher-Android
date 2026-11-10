package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CurseForgeContentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnContentClickListener {
        void onContentClick(Content content);
    }

    public interface OnPageChangeListener {
        void onNextPage();
        void onPrevPage();
    }

    private static final int VIEW_TYPE_ITEM = 0;
    private static final int VIEW_TYPE_FOOTER = 1;

    private List<Content> contents = new ArrayList<>();
    private final OnContentClickListener listener;
    private final OnPageChangeListener pageChangeListener;
    private int currentPage = 1;
    private int totalPages = 1;

    public CurseForgeContentAdapter(OnContentClickListener listener, OnPageChangeListener pageChangeListener) {
        this.listener = listener;
        this.pageChangeListener = pageChangeListener;
    }

    public void setContents(List<Content> contents, int currentPage, int totalPages) {
        this.contents = contents;
        this.currentPage = currentPage;
        this.totalPages = totalPages;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                viewType == VIEW_TYPE_FOOTER ? R.layout.item_curseforge_footer : R.layout.item_curseforge_content,
                parent, false);
        return viewType == VIEW_TYPE_FOOTER ? new FooterViewHolder(view) : new ContentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ContentViewHolder) {
            ((ContentViewHolder) holder).bind(contents.get(position), position == contents.size() - 1, listener);
        } else if (holder instanceof FooterViewHolder) {
            ((FooterViewHolder) holder).bind(currentPage, totalPages, pageChangeListener);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == contents.size() ? VIEW_TYPE_FOOTER : VIEW_TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return contents.isEmpty() ? 0 : contents.size() + 1;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ContentViewHolder) {
            Glide.with(((ContentViewHolder) holder).icon).clear(((ContentViewHolder) holder).icon);
        }
        super.onViewRecycled(holder);
    }

    static class ContentViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;
        final TextView author;
        final TextView categoryTag;
        final TextView downloadsTag;
        final TextView metadata;
        final View viewButton;
        final View divider;

        ContentViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.mod_icon);
            title = itemView.findViewById(R.id.mod_title);
            description = itemView.findViewById(R.id.mod_description);
            author = itemView.findViewById(R.id.mod_author);
            categoryTag = itemView.findViewById(R.id.mod_tag_category);
            downloadsTag = itemView.findViewById(R.id.mod_tag_downloads);
            metadata = itemView.findViewById(R.id.mod_metadata);
            viewButton = itemView.findViewById(R.id.mod_view);
            divider = itemView.findViewById(R.id.mod_divider);
            new PersonalizationManager(itemView.getContext()).applyAccentToView(itemView, itemView.getContext());
        }

        void bind(Content content, boolean last, OnContentClickListener listener) {
            Context context = itemView.getContext();
            title.setText(content.name != null ? content.name : "");
            description.setText(content.summary != null ? content.summary : "");
            description.setVisibility(content.summary == null || content.summary.isEmpty() ? View.GONE : View.VISIBLE);

            if (content.authors != null && !content.authors.isEmpty() && content.authors.get(0) != null) {
                author.setText("| " + context.getString(R.string.pkg_by_author, content.authors.get(0).name));
                author.setVisibility(View.VISIBLE);
            } else {
                author.setText("");
                author.setVisibility(View.GONE);
            }

            if (content.categories != null && !content.categories.isEmpty() && content.categories.get(0) != null
                    && content.categories.get(0).name != null) {
                categoryTag.setText(content.categories.get(0).name);
                categoryTag.setVisibility(View.VISIBLE);
            } else {
                categoryTag.setVisibility(View.GONE);
            }
            downloadsTag.setText(context.getString(R.string.catalog_cf_downloads, formatCount(content.downloadCount)));
            if (content.dateModified != null && content.dateModified.length() >= 10) {
                metadata.setText(content.dateModified.substring(0, 10));
                metadata.setVisibility(View.VISIBLE);
            } else {
                metadata.setVisibility(View.GONE);
            }

            String url = content.logo != null ? content.logo.thumbnailUrl : null;
            if (url != null && !url.isEmpty()) {
                Glide.with(icon)
                        .load(url)
                        .centerCrop()
                        .placeholder(R.drawable.ic_minecraft_cube)
                        .error(R.drawable.ic_minecraft_cube)
                        .into(icon);
            } else {
                Glide.with(icon).clear(icon);
                icon.setImageResource(R.drawable.ic_minecraft_cube);
            }
            divider.setVisibility(last ? View.GONE : View.VISIBLE);
            View.OnClickListener open = v -> listener.onContentClick(content);
            itemView.findViewById(R.id.cf_row).setOnClickListener(open);
            viewButton.setOnClickListener(open);
        }

        private static String formatCount(double count) {
            if (count >= 1000000) {
                return String.format(Locale.getDefault(), "%.1fM", count / 1000000.0);
            } else if (count >= 1000) {
                return String.format(Locale.getDefault(), "%.1fK", count / 1000.0);
            }
            return String.valueOf((long) count);
        }
    }

    static class FooterViewHolder extends RecyclerView.ViewHolder {
        final View btnPrev;
        final View btnNext;
        final TextView tvPageInfo;

        FooterViewHolder(@NonNull View itemView) {
            super(itemView);
            btnPrev = itemView.findViewById(R.id.btn_prev_page);
            btnNext = itemView.findViewById(R.id.btn_next_page);
            tvPageInfo = itemView.findViewById(R.id.tv_page_info);
        }

        void bind(int page, int total, OnPageChangeListener listener) {
            tvPageInfo.setText(itemView.getContext().getString(
                    R.string.pagination_page_of, page, total > 0 ? String.valueOf(total) : "?"));
            boolean hasPrev = page > 1;
            boolean hasNext = total == 0 || page < total;
            btnPrev.setEnabled(hasPrev);
            btnPrev.setAlpha(hasPrev ? 1f : 0.4f);
            btnNext.setEnabled(hasNext);
            btnNext.setAlpha(hasNext ? 1f : 0.4f);
            btnPrev.setOnClickListener(v -> {
                if (listener != null) listener.onPrevPage();
            });
            btnNext.setOnClickListener(v -> {
                if (listener != null) listener.onNextPage();
            });
        }
    }
}
