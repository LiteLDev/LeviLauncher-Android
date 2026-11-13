package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.CompoundButton;
import android.widget.TextView;


import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.UnifiedMod;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModConfigView {
    public static void render(Context context, ViewGroup container, UnifiedMod mod, Runnable onConfigChanged) {
        render(context, container, mod, false, onConfigChanged);
    }

    public static void render(Context source, ViewGroup container, UnifiedMod mod, boolean compact, Runnable onConfigChanged) {
        Context context = ModMenuTheme.wrap(source);
        ModMenuTheme theme = new ModMenuTheme(context);
        RuntimeConfigView.stop(container);
        container.removeAllViews();
        if (RuntimeConfigView.render(context, container, mod, compact, onConfigChanged)) return;
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        container.addView(scroll, new ViewGroup.LayoutParams(-1, -1));
        List<UnifiedMod.ConfigEntry> configs = mod.getConfigEntries();
        Map<String, View> rows = new HashMap<>();
        Runnable dependencies = () -> {
            for (UnifiedMod.ConfigEntry cfg : configs) {
                if (cfg.dependsOn == null || cfg.dependsOn.isEmpty()) continue;
                boolean visible = false;
                for (UnifiedMod.ConfigEntry parent : configs) {
                    if (parent.key.equals(cfg.dependsOn)) {
                        visible = "true".equalsIgnoreCase(parent.currentValue) || "1".equals(parent.currentValue);
                        break;
                    }
                }
                View row = rows.get(cfg.key);
                if (row != null) row.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        };
        for (UnifiedMod.ConfigEntry cfg : configs) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(theme.dp(10), theme.dp(7), theme.dp(10), theme.dp(7));
            card.setBackground(theme.shape(theme.raised, theme.corner, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, theme.dp(3), 0, theme.dp(3));
            content.addView(card, params);
            rows.put(cfg.key, card);
            TextView title = theme.label(cfg.displayName, 11, true);
            boolean inline = cfg.type == UnifiedMod.ConfigType.RADIO || cfg.type == UnifiedMod.ConfigType.COLOR
                    || cfg.type == UnifiedMod.ConfigType.KEYBIND || cfg.type == UnifiedMod.ConfigType.TEXT;
            if (inline) {
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            } else card.addView(title);
            java.util.function.Consumer<String> update = value -> {
                mod.updateConfig(cfg, value);
                dependencies.run();
                if (onConfigChanged != null) onConfigChanged.run();
            };
            String current = cfg.currentValue == null || cfg.currentValue.isEmpty() ? cfg.defaultValue : cfg.currentValue;
            switch (cfg.type) {
                case TOGGLE: {
                    card.removeView(title);
                    LinearLayout row = new LinearLayout(context);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    CompoundButton control = new ModMenuToggle(context);
                    theme.toggle(control);
                    control.setChecked("true".equalsIgnoreCase(cfg.currentValue) || "1".equals(cfg.currentValue));
                    control.setContentDescription(cfg.displayName);
                    control.setOnCheckedChangeListener((button, checked) -> update.accept(checked ? "true" : "false"));
                    row.addView(control, new LinearLayout.LayoutParams(theme.dp(44), theme.dp(40)));
                    card.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
                    break;
                }
                case SLIDER_INT: {
                    int min = parseInt(cfg.minValue, 0);
                    int max = Math.max(min, parseInt(cfg.maxValue, 100));
                    int value = Math.max(min, Math.min(max, parseInt(cfg.currentValue, parseInt(cfg.defaultValue, min))));
                    TextView amount = amount(theme, card, String.valueOf(value));
                    SeekBar seek = seek(theme, card, Math.max(0, max - min), value - min);
                    seek.setContentDescription(cfg.displayName);
                    seek.setOnSeekBarChangeListener(listener(progress -> {
                        int next = min + progress;
                        amount.setText(String.valueOf(next));
                        update.accept(String.valueOf(next));
                    }));
                    break;
                }
                case SLIDER_FLOAT: {
                    float min = parseFloat(cfg.minValue, 0f);
                    float max = Math.max(min, parseFloat(cfg.maxValue, 1f));
                    float value = Math.max(min, Math.min(max, parseFloat(cfg.currentValue, parseFloat(cfg.defaultValue, min))));
                    TextView amount = amount(theme, card, String.format(Locale.ROOT, "%.2f", value));
                    int progress = max > min ? Math.round((value - min) * 100f / (max - min)) : 0;
                    SeekBar seek = seek(theme, card, 100, progress);
                    seek.setEnabled(max > min);
                    seek.setContentDescription(cfg.displayName);
                    seek.setOnSeekBarChangeListener(listener(next -> {
                        float number = min + (max - min) * next / 100f;
                        amount.setText(String.format(Locale.ROOT, "%.2f", number));
                        update.accept(String.valueOf(number));
                    }));
                    break;
                }
                case RADIO: {
                    String[] options = cfg.minValue == null || cfg.minValue.isEmpty() ? new String[0] : cfg.minValue.split(",");
                    TextView choice = action(theme, card, "");
                    Runnable label = () -> {
                        int index = parseInt(cfg.currentValue, parseInt(cfg.defaultValue, 0));
                        choice.setText(index >= 0 && index < options.length ? options[index] : context.getString(R.string.mm_choose_option));
                    };
                    label.run();
                    choice.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_modmenu_chevron, 0);
                    choice.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.accent));
                    choice.setOnClickListener(v -> ModMenuDialogs.choices(v, cfg.displayName, options,
                            parseInt(cfg.currentValue, parseInt(cfg.defaultValue, 0)), null, index -> {
                                update.accept(String.valueOf(index));
                                label.run();
                            }));
                    break;
                }
                case COLOR: {
                    int initial;
                    try { initial = Color.parseColor(current); } catch (Exception ignored) { initial = Color.WHITE; }
                    int[] color = {initial};
                    TextView value = action(theme, card, String.format(Locale.ROOT, "#%08X", initial));
                    value.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_modmenu_palette, 0, 0, 0);
                    value.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.accent));
                    value.setOnClickListener(v -> ModMenuDialogs.color(v, cfg.displayName, color[0], true, next -> {
                        color[0] = next;
                        String hex = String.format(Locale.ROOT, "#%08X", next);
                        value.setText(hex);
                        update.accept(hex);
                    }));
                    break;
                }
                case KEYBIND: {
                    int[] key = {parseInt(cfg.currentValue, parseInt(cfg.defaultValue, 0))};
                    TextView value = action(theme, card, keyName(key[0]));
                    value.setOnClickListener(v -> ModMenuDialogs.keybind(v, cfg.displayName, key[0], -1, code -> {
                        key[0] = code;
                        update.accept(String.valueOf(code));
                        value.setText(keyName(code));
                    }));
                    break;
                }
                case TEXT: {
                    EditText input = new EditText(context);
                    theme.input(input);
                    input.setText(current);
                    input.setContentDescription(cfg.displayName);
                    input.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                        @Override public void afterTextChanged(Editable s) { update.accept(s.toString()); }
                    });
                    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(theme.dp(140), -2);
                    inputParams.setMarginStart(theme.dp(9));
                    card.addView(input, inputParams);
                    boundControl(theme, card, input);
                    break;
                }
                case BUTTON: {
                    card.removeView(title);
                    TextView button = action(theme, card, cfg.displayName);
                    theme.button(button, true);
                    button.setOnClickListener(v -> update.accept("true"));
                    break;
                }
            }
        }
        dependencies.run();
    }

    private static LinearLayout.LayoutParams gap(ModMenuTheme theme) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = theme.dp(8);
        return params;
    }

    private static TextView action(ModMenuTheme theme, LinearLayout parent, String text) {
        TextView view = theme.label(text, 11, true);
        theme.button(view, false);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(theme.dp(140), -2) : new LinearLayout.LayoutParams(-1, -2);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) params.setMarginStart(theme.dp(9));
        else params.topMargin = theme.dp(3);
        parent.addView(view, params);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) boundControl(theme, parent, view);
        return view;
    }

    private static void boundControl(ModMenuTheme theme, LinearLayout row, View control) {
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(theme.dp(168), Math.max(theme.dp(96), Math.round((r - l) * 0.42f)));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) control.getLayoutParams();
            if (params.width != width) { params.width = width; control.setLayoutParams(params); }
        });
    }

    private static TextView amount(ModMenuTheme theme, LinearLayout parent, String text) {
        TextView view = theme.label(text, 10, false);
        View title = parent.getChildAt(0);
        parent.removeView(title);
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(view);
        parent.addView(row, 0);
        return view;
    }

    private static SeekBar seek(ModMenuTheme theme, LinearLayout parent, int max, int progress) {
        SeekBar seek = new SeekBar(parent.getContext());
        seek.setMax(max);
        seek.setProgress(progress);
        theme.seek(seek);
        parent.addView(seek, new LinearLayout.LayoutParams(-1, theme.dp(36)));
        return seek;
    }

    private static SeekBar.OnSeekBarChangeListener listener(java.util.function.IntConsumer callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) callback.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); } catch (Exception ignored) { return fallback; }
    }

    private static String keyName(int code) {
        if (code == 0) return "—";
        String label = KeyEvent.keyCodeToString(code);
        return label.startsWith("KEYCODE_") ? label.substring(8).replace('_', ' ') : label;
    }
}
