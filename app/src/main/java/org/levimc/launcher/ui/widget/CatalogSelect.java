package org.levimc.launcher.ui.widget;

import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CatalogSelect {
    public interface OnPick {
        void onPick(int index);
    }

    private final TextView trigger;
    private CharSequence title;
    private List<String> items = Collections.emptyList();
    private int selected;
    @Nullable
    private OnPick listener;

    public CatalogSelect(TextView trigger, CharSequence title) {
        this.trigger = trigger;
        this.title = title;
        trigger.setOnClickListener(v -> open());
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setItems(List<String> items, int selected) {
        this.items = new ArrayList<>(items == null ? Collections.emptyList() : items);
        setSelected(selected);
    }

    public void setSelected(int index) {
        selected = items.isEmpty() ? 0 : Math.max(0, Math.min(index, items.size() - 1));
        trigger.setText(items.isEmpty() ? "" : items.get(selected));
    }

    public int getSelected() {
        return selected;
    }

    public void setOnPick(@Nullable OnPick listener) {
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        trigger.setEnabled(enabled);
        trigger.setAlpha(enabled ? 1f : 0.5f);
    }

    private void open() {
        if (items.isEmpty() || !trigger.isEnabled()) return;
        List<String> options = new ArrayList<>(items);
        LauncherPopup.select(trigger, title, options, selected, index -> {
            if (!trigger.isEnabled() || index < 0 || index >= options.size()) return;
            int current = options.equals(items) ? index : items.indexOf(options.get(index));
            if (current < 0 || current == selected) return;
            setSelected(current);
            if (listener != null) listener.onPick(current);
        });
    }
}
