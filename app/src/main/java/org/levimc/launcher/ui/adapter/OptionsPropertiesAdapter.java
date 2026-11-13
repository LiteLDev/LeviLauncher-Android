package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.OptionsEditor.OptionProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OptionsPropertiesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PROPERTY = 1;

    private final List<Object> items = new ArrayList<>();
    private OnOptionChangedListener listener;

    public interface OnOptionChangedListener {
        void onOptionChanged(OptionProperty property, String newValue);
    }

    public void setOnOptionChangedListener(OnOptionChangedListener listener) {
        this.listener = listener;
    }

    public void setProperties(Map<String, List<OptionProperty>> grouped) {
        items.clear();
        for (Map.Entry<String, List<OptionProperty>> entry : grouped.entrySet()) {
            items.add(new HeaderItem(entry.getKey()));
            items.addAll(entry.getValue());
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof HeaderItem) return TYPE_HEADER;
        return TYPE_PROPERTY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_property_header, parent, false));
        }
        return new PropertyViewHolder(inflater.inflate(R.layout.item_option_property, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        
        if (holder instanceof HeaderViewHolder headerHolder) {
            headerHolder.bind((HeaderItem) item);
        } else if (holder instanceof PropertyViewHolder propHolder) {
            propHolder.bind((OptionProperty) item, listener);
        }

        android.content.Context ctx = holder.itemView.getContext();
        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(ctx);
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, ctx);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static String categoryLabel(Context context, String category) {
        int resource;
        switch (category) {
            case "Graphics": resource = R.string.options_category_graphics; break;
            case "Audio": resource = R.string.options_category_audio; break;
            case "Controls": resource = R.string.options_category_controls; break;
            case "Interface": resource = R.string.options_category_interface; break;
            case "Game": resource = R.string.options_category_game; break;
            case "Developer": resource = R.string.options_category_developer; break;
            case "Multiplayer": resource = R.string.options_category_multiplayer; break;
            case "VR": resource = R.string.options_category_vr; break;
            default: resource = R.string.options_category_other;
        }
        return context.getString(resource);
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PropertyViewHolder) {
            PropertyViewHolder row = (PropertyViewHolder) holder;
            if (row.watcher != null) row.propertyValue.removeTextChangedListener(row.watcher);
            row.watcher = null;
            row.propertyValue.setOnEditorActionListener(null);
            row.propertyValue.setOnFocusChangeListener(null);
        }
        super.onViewRecycled(holder);
    }

    private static class HeaderItem {
        final String title;
        HeaderItem(String title) { this.title = title; }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView headerText;

        HeaderViewHolder(View itemView) {
            super(itemView);
            headerText = itemView.findViewById(R.id.header_text);
        }

        void bind(HeaderItem item) {
            headerText.setText(categoryLabel(itemView.getContext(), item.title));
        }
    }

    static class PropertyViewHolder extends RecyclerView.ViewHolder {
        TextView propertyName;
        TextView propertyKey;
        EditText propertyValue;
        TextWatcher watcher;

        PropertyViewHolder(View itemView) {
            super(itemView);
            propertyName = itemView.findViewById(R.id.property_name);
            propertyKey = itemView.findViewById(R.id.property_key);
            propertyValue = itemView.findViewById(R.id.property_value);
        }

        void bind(OptionProperty property, OnOptionChangedListener listener) {
            propertyName.setText(property.getDisplayName());
            propertyKey.setText(property.getKey());

            propertyValue.setOnEditorActionListener(null);
            propertyValue.setOnFocusChangeListener(null);
            if (watcher != null) propertyValue.removeTextChangedListener(watcher);
            propertyValue.setText(property.getValue());
            propertyValue.setContentDescription(itemView.getContext().getString(R.string.options_editor_value_for, property.getDisplayName()));
            watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable value) {
                    if (listener != null && !value.toString().equals(property.getValue())) listener.onOptionChanged(property, value.toString());
                }
            };
            propertyValue.addTextChangedListener(watcher);

            propertyValue.setOnEditorActionListener((v, actionId, event) -> {
                if (listener != null) {
                    String newValue = propertyValue.getText().toString();
                    if (!newValue.equals(property.getValue())) {
                        listener.onOptionChanged(property, newValue);
                    }
                }
                hideKeyboard(v);
                propertyValue.clearFocus();
                return true;
            });

            propertyValue.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus && listener != null) {
                    String newValue = propertyValue.getText().toString();
                    if (!newValue.equals(property.getValue())) {
                        listener.onOptionChanged(property, newValue);
                    }
                }
            });
        }

        private void hideKeyboard(View view) {
            Context context = view.getContext();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
}
