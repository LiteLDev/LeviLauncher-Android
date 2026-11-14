package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModMenuSettingsView {
    private final Context context;
    private final ModMenuTheme theme;
    private final ModMenuPreferences prefs;
    private final Runnable changed;
    private final Runnable preview;
    private final java.util.function.Consumer<String> live;
    private final List<LinearLayout> pages = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private LinearLayout page;

    ModMenuSettingsView(Context context, LinearLayout host, Runnable changed, java.util.function.Consumer<String> live, Runnable preview) {
        this.context = context;
        theme = new ModMenuTheme(context);
        prefs = new ModMenuPreferences(context);
        this.changed = changed;
        this.preview = preview;
        this.live = live;
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new LinearLayout(context);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(strip);
        host.addView(scroll, new LinearLayout.LayoutParams(-1, theme.dp(40)));
        int[] titles = {R.string.mm_appearance, R.string.mm_layout, R.string.mm_floating_button, R.string.mm_notifications, R.string.mm_controls};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TextView tab = theme.label(context.getString(titles[i]), 11, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(theme.dp(10), 0, theme.dp(10), 0);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-2, theme.dp(36));
            tabParams.setMarginEnd(theme.dp(3));
            strip.addView(tab, tabParams);
            tabs.add(tab);
            tab.setOnClickListener(v -> {
                select(index);
                if (host.getParent() instanceof android.widget.ScrollView) ((android.widget.ScrollView) host.getParent()).scrollTo(0, 0);
            });
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            host.addView(content, new LinearLayout.LayoutParams(-1, -2));
            pages.add(content);
        }
        appearance(); layout(); floatingButton(); notifications(); controls();
        int selected = prefs.number("settings_page", 0, 0, 4);
        select(selected);
        strip.post(() -> scroll.scrollTo(Math.max(0, tabs.get(selected).getLeft() - theme.dp(8)), 0));
    }

    private void select(int index) {
        prefs.put("settings_page", index);
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs.get(i).setTextColor(i == index ? theme.text : theme.secondary);
            tabs.get(i).setBackground(theme.touch(i == index ? theme.selected : Color.TRANSPARENT, 6, false));
            tabs.get(i).setSelected(i == index);
        }
    }

    private void appearance() {
        page = pages.get(0);
        modeControl();
        LinearLayout paletteRow = row(R.string.mw_palette, R.string.mm_palette_full_hint);
        paletteRow.setId(R.id.setting_menu_palette);
        TextView paletteValue = control(paletteRow, ModMenuTheme.presetName(context, prefs.palette()));
        paletteValue.setId(R.id.menu_palette_value);
        paletteValue.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_modmenu_chevron, 0);
        paletteValue.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.secondary));
        paletteValue.setCompoundDrawablePadding(theme.dp(6));
        View.OnClickListener openPalette = v -> ModMenuDialogs.palettes(paletteValue, prefs.palette(), value -> {
            if (value == prefs.palette()) return;
            prefs.setPalette(value);
            changed.run();
        });
        paletteRow.setOnClickListener(openPalette);
        paletteValue.setOnClickListener(openPalette);
        choose(R.string.mm_accent_source, 0, strings(R.string.mw_palette_color, R.string.mm_custom_color), prefs.accentSource(), value -> {
            prefs.put("accent_source", value); changed.run();
        });
        LinearLayout row = row(R.string.mm_accent_color, 0);
        TextView custom = control(row, String.format(Locale.ROOT, "#%06X", prefs.customAccent() & 0xFFFFFF));
        custom.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_modmenu_palette, 0, 0, 0);
        custom.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.secondary));
        custom.setOnClickListener(v -> ModMenuDialogs.color(v, context.getString(R.string.mm_accent_color), prefs.customAccent(), false, color -> {
            prefs.put("accent", color); prefs.put("accent_source", 1); changed.run();
        }));
        LinearLayout swatches = new LinearLayout(context);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.setPadding(theme.dp(4), theme.dp(3), theme.dp(4), theme.dp(5));
        int[] colors = {0xFF737380, 0xFF487CE8, 0xFF9254EC, 0xFFF05F88, 0xFFD3A64C, 0xFF18B1CF};
        for (int color : colors) {
            TextView swatch = theme.label(prefs.accentSource() == 1 && prefs.customAccent() == color ? "✓" : "", 12, true);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextColor(Color.WHITE);
            swatch.setContentDescription(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
            swatch.setBackground(theme.touch(color, 6, false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, theme.dp(32), 1);
            params.setMarginEnd(theme.dp(6));
            swatches.addView(swatch, params);
            swatch.setOnClickListener(v -> { prefs.put("accent", color); prefs.put("accent_source", 1); changed.run(); });
        }
        page.addView(swatches);
        slider(R.string.mm_text_size, 0, "text_scale", prefs.textScale(), 85, 135, "%");
        slider(R.string.mm_corners, 0, "radius", prefs.radius(), 6, 22, " dp");
        slider(R.string.mw_element_corners, 0, "element_radius", prefs.elementRadius(), 3, 14, " dp");
        boundSlider(R.string.mod_menu_opacity, R.string.mm_opacity_hint, R.id.seekbar_mod_menu_opacity, R.id.text_mod_menu_opacity, 70, 100);
    }

    private void modeControl() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, theme.dp(4), 0, theme.dp(5));
        box.addView(labels(R.string.mm_color_mode, R.string.mm_color_mode_hint), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout options = new LinearLayout(context);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, -2);
        optionsParams.topMargin = theme.dp(8);
        box.addView(options, optionsParams);
        int[] names = {R.string.mm_system, R.string.mm_dark, R.string.mm_light};
        int[] ids = {R.id.menu_mode_system, R.id.menu_mode_dark, R.id.menu_mode_light};
        for (int i = 0; i < names.length; i++) {
            int mode = i;
            boolean selected = prefs.mode() == mode;
            TextView option = theme.label(context.getString(names[i]), 12, selected);
            option.setId(ids[i]);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(theme.dp(40));
            option.setPadding(theme.dp(6), theme.dp(6), theme.dp(6), theme.dp(6));
            option.setTextColor(selected ? Color.WHITE : theme.text);
            option.setBackground(theme.touch(selected ? theme.primary : theme.raised, theme.corner, !selected));
            option.setSelected(selected);
            option.setFocusable(true);
            option.setContentDescription(context.getString(R.string.mm_color_mode) + ": " + context.getString(names[i]));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) params.setMarginStart(theme.dp(6));
            options.addView(option, params);
            option.setOnClickListener(v -> {
                if (prefs.mode() == mode) return;
                prefs.put("theme", mode);
                changed.run();
            });
        }
        page.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private void layout() {
        page = pages.get(1);
        toggle(R.string.mm_list_layout, R.string.mw_compact_hint, R.id.switch_compact_mod_menu);
        boolean compact = InbuiltModManager.getInstance(context).isModMenuCompact();
        slider(R.string.mm_menu_width, 0, compact ? "list_width" : "width", prefs.width(compact), 35, 100, "%");
        slider(R.string.mm_menu_height, 0, "height", prefs.height(), 55, 100, "%");
        slider(R.string.mm_backdrop, 0, "dim", prefs.dim(), 0, 60, "%");
        LinearLayout row = row(R.string.mm_window_position, R.string.mm_move_hint);
        TextView reset = control(row, context.getString(R.string.reset));
        reset.setOnClickListener(v -> { prefs.resetPosition(); changed.run(); });
    }

    private void floatingButton() {
        page = pages.get(2);
        slider(R.string.mm_button_size, 0, "button_size", prefs.buttonSize(), 32, 76, " dp");
        choose(R.string.mm_button_shape, 0, strings(R.string.mm_rounded, R.string.mm_circle, R.string.mm_minimal), prefs.buttonShape(), value -> {
            prefs.put("button_shape", value); changed.run();
        });
        boundSlider(R.string.mod_menu_button_opacity, 0, R.id.seekbar_mod_menu_button_opacity, R.id.text_mod_menu_button_opacity, 0, 100);
        toggle(R.string.mm_pause_only, R.string.mm_pause_only_hint, R.id.switch_pause_menu_only);
    }

    private void notifications() {
        page = pages.get(3);
        toggle(R.string.mm_notifications, 0, R.id.switch_notifications);
        choose(R.string.mm_notification_position, 0,
                strings(R.string.mm_top_left, R.string.mm_top_center, R.string.mm_top_right,
                        R.string.mm_bottom_left, R.string.mm_bottom_center, R.string.mm_bottom_right), prefs.notificationPosition(), value -> {
                    prefs.put("notification_position", value); changed.run();
                });
        slider(R.string.mm_duration, 0, "notification_duration", prefs.notificationDuration(), 1000, 6000, "ms");
        slider(R.string.mm_stack_limit, 0, "notification_limit", prefs.notificationLimit(), 1, 4, "");
        settingToggle(R.string.mm_compact_alerts, "notification_compact", prefs.notificationCompact());
        settingToggle(R.string.mm_progress, "notification_progress", prefs.notificationProgress());
        settingToggle(R.string.mm_notify_disabled, "notification_disabled", prefs.notifyDisabled());
        LinearLayout row = row(R.string.mw_preview, 0);
        TextView button = control(row, context.getString(R.string.mm_preview_notification));
        button.setOnClickListener(v -> preview.run());
    }

    private void controls() {
        page = pages.get(4);
        LinearLayout keybind = row(R.string.mod_menu_keybind, R.string.mm_keybind_hint);
        keybind.setId(R.id.setting_mod_menu_keybind);
        TextView value = control(keybind, context.getString(R.string.mod_menu_keybind_unbound));
        value.setId(R.id.mod_menu_keybind_value);
        value.setClickable(false);
        LinearLayout row = row(R.string.mm_reset_appearance, R.string.mm_reset_hint);
        TextView reset = control(row, context.getString(R.string.reset));
        reset.setOnClickListener(v -> {
            prefs.reset();
            InbuiltModManager manager = InbuiltModManager.getInstance(context);
            manager.setModMenuOpacity(100); manager.setModMenuButtonOpacity(100);
            changed.run();
        });
    }

    private LinearLayout row(int title, int description) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(theme.dp(10), theme.dp(7), theme.dp(9), theme.dp(7));
        row.setMinimumHeight(theme.dp(48));
        row.setBackground(theme.shape(theme.raised, theme.corner, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, theme.dp(3), 0, theme.dp(3));
        page.addView(row, params);
        row.addView(labels(title, description), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout labels(int title, int description) {
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(0, 0, theme.dp(8), 0);
        labels.addView(theme.label(context.getString(title), 12, true));
        if (description != 0) {
            TextView desc = theme.label(context.getString(description), 10, false);
            desc.setPadding(0, theme.dp(3), 0, 0);
            labels.addView(desc);
        }
        return labels;
    }

    private CompoundButton toggle(int title, int description, int id) {
        LinearLayout row = row(title, description);
        ModMenuToggle control = new ModMenuToggle(context);
        control.setId(id);
        control.setContentDescription(context.getString(title));
        theme.toggle(control);
        row.addView(control, new LinearLayout.LayoutParams(theme.dp(44), theme.dp(40)));
        row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        return control;
    }

    private void settingToggle(int title, String key, boolean value) {
        CompoundButton control = toggle(title, 0, View.generateViewId());
        control.setChecked(value);
        control.setOnCheckedChangeListener((button, checked) -> { prefs.put(key, checked); live.accept(key); });
    }

    private void slider(int title, int description, String key, int value, int min, int max, String unit) {
        LinearLayout box = sliderBox(title, description);
        LinearLayout heading = (LinearLayout) box.getChildAt(0);
        TextView amount = theme.label(value(value, unit), 10, false);
        heading.addView(amount);
        SeekBar seek = new SeekBar(context);
        seek.setMin(min); seek.setMax(max); seek.setProgress(value);
        seek.setContentDescription(context.getString(title));
        theme.seek(seek);
        box.addView(seek, new LinearLayout.LayoutParams(-1, theme.dp(36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                amount.setText(value(progress, unit));
                prefs.put(key, progress);
                live.accept(key);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private LinearLayout sliderBox(int title, int description) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(theme.dp(3), theme.dp(8), theme.dp(3), 0);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(labels(title, description), new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        page.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void boundSlider(int title, int description, int seekId, int textId, int min, int max) {
        LinearLayout box = sliderBox(title, description);
        TextView amount = theme.label("", 10, false);
        amount.setId(textId);
        ((LinearLayout) box.getChildAt(0)).addView(amount);
        SeekBar seek = new SeekBar(context);
        seek.setId(seekId); seek.setMin(min); seek.setMax(max);
        seek.setContentDescription(context.getString(title));
        theme.seek(seek);
        box.addView(seek, new LinearLayout.LayoutParams(-1, theme.dp(36)));
    }

    private void choose(int title, int description, String[] items, int selected, ModMenuDialogs.Selection listener) {
        LinearLayout row = row(title, description);
        TextView value = control(row, items[selected]);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_modmenu_chevron, 0);
        value.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.secondary));
        value.setCompoundDrawablePadding(theme.dp(6));
        View.OnClickListener open = v -> ModMenuDialogs.choices(value, context.getString(title), items, selected, null, listener);
        value.setOnClickListener(open);
        row.setOnClickListener(open);
    }

    private TextView control(LinearLayout row, CharSequence text) {
        TextView value = theme.label(text, 11, false);
        theme.button(value, false);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.dp(140), -2);
        params.setMarginStart(theme.dp(8));
        row.addView(value, params);
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(theme.dp(168), Math.max(theme.dp(96), Math.round((r - l) * 0.42f)));
            LinearLayout.LayoutParams current = (LinearLayout.LayoutParams) value.getLayoutParams();
            if (current.width != width) { current.width = width; value.setLayoutParams(current); }
        });
        return value;
    }

    private String[] strings(int... ids) {
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) labels[i] = context.getString(ids[i]);
        return labels;
    }

    private String value(int number, String unit) {
        return "ms".equals(unit) ? String.format(Locale.ROOT, "%.1f s", number / 1000f) : number + unit;
    }
}
