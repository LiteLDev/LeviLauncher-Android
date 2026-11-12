package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.res.ResourcesCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.util.ThemePalette;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LauncherPopup {
    public interface OnSelect {
        void onSelect(int id);
    }

    private static final int HEADING = 0;
    private static final int ACTION = 1;
    private static final int DIVIDER = 2;
    private static final int GROUP = 3;
    private final View anchor;
    private final Context context;
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final Typeface font;
    private final ListPopupWindow popup;
    private final RowsAdapter adapter = new RowsAdapter();
    private OnSelect listener;
    private int widthDp = 280;
    private CharSequence searchTitle;

    private static final class Row {
        int kind;
        int id;
        int icon;
        CharSequence label;
        CharSequence detail;
        boolean checkable;
        boolean checked;
        int[] ids;
        CharSequence[] options;
        int selected;
    }

    public LauncherPopup(View anchor) {
        this.anchor = anchor;
        context = anchor.getContext();
        font = ResourcesCompat.getFont(context, R.font.misans);
        popup = new ListPopupWindow(context);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        popup.setDropDownGravity(Gravity.END);
        popup.setVerticalOffset(dp(6));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        GradientDrawable background = shape(UiKit.color(context, R.color.surface_variant), 12);
        background.setStroke(Math.max(1, dp(1)), UiKit.color(context, R.color.ui_border));
        popup.setBackgroundDrawable(background);
        popup.setAdapter(adapter);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= shown.size()) return;
            Row row = shown.get(position);
            if (row.kind != ACTION) return;
            popup.dismiss();
            if (listener != null) listener.onSelect(row.id);
        });
    }

    public LauncherPopup width(int widthDp) {
        this.widthDp = widthDp;
        return this;
    }

    public LauncherPopup section(CharSequence title) {
        Row row = new Row();
        row.kind = HEADING;
        row.label = title;
        rows.add(row);
        return this;
    }

    public LauncherPopup divider() {
        Row row = new Row();
        row.kind = DIVIDER;
        rows.add(row);
        return this;
    }

    public LauncherPopup action(int id, CharSequence label, int icon) {
        return action(id, label, null, icon);
    }

    public LauncherPopup action(int id, CharSequence label, CharSequence detail, int icon) {
        Row row = new Row();
        row.kind = ACTION;
        row.id = id;
        row.label = label;
        row.detail = detail;
        row.icon = icon;
        rows.add(row);
        return this;
    }

    public LauncherPopup choice(int id, CharSequence label, boolean checked) {
        action(id, label, 0);
        Row row = rows.get(rows.size() - 1);
        row.checkable = true;
        row.checked = checked;
        return this;
    }

    public LauncherPopup group(CharSequence title, int[] ids, CharSequence[] labels, int selected) {
        if (ids.length == 0 || ids.length != labels.length) throw new IllegalArgumentException("Invalid popup choices");
        Row row = new Row();
        row.kind = GROUP;
        row.label = title;
        row.ids = ids.clone();
        row.options = labels.clone();
        row.selected = Math.max(0, Math.min(selected, ids.length - 1));
        rows.add(row);
        return this;
    }

    public LauncherPopup onSelect(OnSelect listener) {
        this.listener = listener;
        return this;
    }

    public void show() {
        if (!anchor.isAttachedToWindow() || !anchor.isEnabled() || rows.isEmpty()) return;
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int available = frame.width() > 0 ? frame.width() : context.getResources().getDisplayMetrics().widthPixels;
        popup.setWidth(Math.max(1, Math.min(dp(widthDp), available - dp(24))));
        if (searchTitle != null) setupSearch();
        filter("");
        View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                popup.dismiss();
            }
        };
        anchor.addOnAttachStateChangeListener(attachment);
        popup.setOnDismissListener(() -> anchor.removeOnAttachStateChangeListener(attachment));
        popup.show();
        if (popup.getListView() != null) {
            popup.getListView().setDivider(null);
            popup.getListView().setItemsCanFocus(true);
            popup.getListView().setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            popup.getListView().setVerticalScrollBarEnabled(false);
            popup.getListView().setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).checked) {
                    popup.getListView().setSelection(i);
                    break;
                }
            }
        }
    }

    public static void select(View anchor, CharSequence title, List<String> items, int selected, OnSelect listener) {
        if (items == null || items.isEmpty()) return;
        LauncherPopup menu = new LauncherPopup(anchor).onSelect(listener);
        if (items.size() > 8) menu.searchTitle = title;
        else menu.section(title);
        for (int i = 0; i < items.size(); i++) menu.choice(i, items.get(i), i == selected);
        menu.show();
    }

    private void setupSearch() {
        LinearLayout prompt = new LinearLayout(context);
        prompt.setOrientation(LinearLayout.VERTICAL);
        prompt.setPadding(dp(12), dp(10), dp(12), dp(6));
        TextView title = text(searchTitle, 12, UiKit.secondaryText(context), true);
        prompt.addView(title);
        EditText search = new EditText(context);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        search.setTypeface(font);
        search.setTextColor(UiKit.color(context, R.color.text_primary));
        search.setHintTextColor(UiKit.secondaryText(context));
        search.setHint(R.string.search_hint);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setBackground(shape(UiKit.color(context, R.color.ui_input), 8));
        search.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        searchParams.topMargin = dp(8);
        prompt.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
                if (popup.isShowing()) popup.show();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        popup.setPromptPosition(ListPopupWindow.POSITION_PROMPT_ABOVE);
        popup.setPromptView(prompt);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
    }

    private void filter(String query) {
        String key = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Row row : rows) {
            if (key.isEmpty() || row.label != null && row.label.toString().toLowerCase(Locale.ROOT).contains(key)) shown.add(row);
        }
        adapter.notifyDataSetChanged();
    }

    private TextView text(CharSequence label, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private RippleDrawable feedback(int background) {
        return new RippleDrawable(ColorStateList.valueOf(UiKit.alpha(UiKit.accent(context), 35)),
                shape(background, 8), shape(Color.WHITE, 8));
    }

    private int dp(float value) {
        return Math.round(UiKit.dp(context, value));
    }

    private final class RowsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return Math.max(1, shown.size());
        }

        @Override
        public Object getItem(int position) {
            return position < shown.size() ? shown.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return position < shown.size() && shown.get(position).kind == ACTION;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = position < shown.size() ? shown.get(position) : null;
            if (row == null || row.kind == HEADING) {
                TextView heading = text(row == null ? context.getString(R.string.popup_no_matches) : row.label,
                        11, UiKit.secondaryText(context), true);
                heading.setPadding(dp(16), dp(12), dp(16), dp(7));
                return heading;
            }
            if (row.kind == DIVIDER) {
                FrameLayout frame = new FrameLayout(context);
                frame.setPadding(dp(12), dp(4), dp(12), dp(4));
                View line = new View(context);
                line.setBackgroundColor(UiKit.color(context, R.color.divider));
                frame.addView(line, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
                return frame;
            }
            if (row.kind == GROUP) return groupView(row);
            return actionView(row);
        }

        private View groupView(Row row) {
            LinearLayout group = new LinearLayout(context);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setPadding(dp(12), dp(10), dp(12), dp(6));
            group.addView(text(row.label, 11, UiKit.secondaryText(context), true));
            LinearLayout choices = new LinearLayout(context);
            choices.setBaselineAligned(false);
            LinearLayout.LayoutParams choicesParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            choicesParams.topMargin = dp(8);
            group.addView(choices, choicesParams);
            for (int i = 0; i < row.ids.length; i++) {
                int index = i;
                boolean selected = i == row.selected;
                int accent = UiKit.accent(context);
                TextView option = text(row.options[i], 11, selected ? ThemePalette.onAccent(accent)
                        : UiKit.color(context, R.color.text_primary), selected);
                option.setGravity(Gravity.CENTER);
                option.setMinHeight(dp(34));
                option.setMaxLines(2);
                option.setPadding(dp(5), dp(5), dp(5), dp(5));
                option.setBackground(feedback(selected ? accent : UiKit.color(context, R.color.ui_default)));
                option.setSelected(selected);
                option.setClickable(true);
                option.setFocusable(true);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i > 0) params.setMarginStart(dp(4));
                choices.addView(option, params);
                option.setOnClickListener(v -> {
                    row.selected = index;
                    notifyDataSetChanged();
                    if (listener != null) listener.onSelect(row.ids[index]);
                });
            }
            return group;
        }

        private View actionView(Row row) {
            FrameLayout inset = new FrameLayout(context);
            inset.setPadding(dp(6), dp(3), dp(6), dp(3));
            LinearLayout content = new LinearLayout(context);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setMinimumHeight(dp(row.detail == null ? 40 : 50));
            content.setPadding(dp(10), dp(8), dp(10), dp(8));
            int labelColor = row.checked ? UiKit.accentText(context) : UiKit.color(context, R.color.text_primary);
            content.setBackground(feedback(row.checked ? UiKit.accentSoft(context) : Color.TRANSPARENT));
            content.setDuplicateParentStateEnabled(true);
            inset.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (row.icon != 0) {
                ImageView icon = new ImageView(context);
                icon.setImageResource(row.icon);
                icon.setImageTintList(ColorStateList.valueOf(labelColor));
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
                iconParams.setMarginEnd(dp(12));
                content.addView(icon, iconParams);
            }
            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            content.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView label = text(row.label, 13, labelColor, row.checked);
            label.setMaxLines(2);
            labels.addView(label);
            if (row.detail != null) {
                TextView detail = text(row.detail, 11, UiKit.secondaryText(context), false);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                detailParams.topMargin = dp(4);
                labels.addView(detail, detailParams);
            }
            if (row.checkable) {
                ImageView check = new ImageView(context);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(UiKit.accentText(context)));
                check.setVisibility(row.checked ? View.VISIBLE : View.INVISIBLE);
                LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(18), dp(18));
                checkParams.setMarginStart(dp(10));
                content.addView(check, checkParams);
            }
            return inset;
        }
    }
}
