package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import org.levimc.launcher.R;

import java.lang.ref.WeakReference;
import java.util.Locale;

final class ModMenuDialogs {
    interface Selection { void select(int index); }
    interface ColorSelection { void select(int color); }
    private static WeakReference<Popover> active = new WeakReference<>(null);

    static void dismissAll() {
        Popover window = active.get();
        if (window != null) window.dismiss();
        active.clear();
    }

    static void finish(AlertDialog dialog) {
        ModMenuTheme theme = new ModMenuTheme(dialog.getContext());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(theme.shape(theme.raised, 10, true));
            int available = dialog.getContext().getResources().getDisplayMetrics().widthPixels - theme.dp(24);
            dialog.getWindow().setLayout(Math.min(available, theme.dp(340)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) { button.setTextColor(theme.text); button.setTextSize(11); button.setAllCaps(false); }
        }
    }

    static void choices(Context context, CharSequence title, String[] labels, int selected, boolean[] enabled, Selection listener) {
        choices(anchor(context), title, labels, selected, enabled, listener);
    }

    static void choices(View anchor, CharSequence title, String[] labels, int selected, boolean[] enabled, Selection listener) {
        Context context = ModMenuTheme.wrap(anchor.getContext());
        ModMenuTheme theme = new ModMenuTheme(context);
        LinearLayout body = column(context);
        LinearLayout options = column(context);
        Popover[] popup = new Popover[1];
        if (labels.length > 8) {
            EditText search = new EditText(context);
            theme.input(search);
            search.setSingleLine(true);
            search.setHint(R.string.mm_search_options);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, theme.dp(36));
            searchParams.bottomMargin = theme.dp(5);
            body.addView(search, searchParams);
            watch(search, () -> {
                String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < labels.length; i++) options.getChildAt(i).setVisibility(
                        labels[i].toLowerCase(Locale.ROOT).contains(query) ? View.VISIBLE : View.GONE);
            });
        }
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView row = theme.label(labels[i], 12, i == selected);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(theme.dp(9), theme.dp(7), theme.dp(9), theme.dp(7));
            row.setMinHeight(theme.dp(40));
            row.setBackground(theme.touch(i == selected ? theme.selected : Color.TRANSPARENT, 6, false));
            if (i == selected) {
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_modmenu_check, 0);
                row.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(theme.secondary));
            }
            boolean available = enabled == null || i >= enabled.length || enabled[i];
            row.setEnabled(available);
            row.setAlpha(available ? 1f : 0.4f);
            row.setOnClickListener(v -> {
                if (!available) return;
                popup[0].dismiss();
                listener.select(index);
            });
            options.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(options);
        popup[0] = show(anchor, title, body, 260);
    }

    static void color(Context context, CharSequence title, int initial, boolean alpha, ColorSelection listener) {
        color(anchor(context), title, initial, alpha, listener);
    }

    static void color(View anchor, CharSequence title, int initial, boolean alpha, ColorSelection listener) {
        Context context = ModMenuTheme.wrap(anchor.getContext());
        ModMenuTheme theme = new ModMenuTheme(context);
        LinearLayout body = column(context);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View preview = new View(context);
        header.addView(preview, new LinearLayout.LayoutParams(theme.dp(38), theme.dp(36)));
        EditText hex = new EditText(context);
        hex.setSingleLine(true);
        theme.input(hex);
        hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setHint(alpha ? "#AARRGGBB" : "#RRGGBB");
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(0, theme.dp(36), 1);
        hexParams.setMarginStart(theme.dp(8));
        header.addView(hex, hexParams);
        body.addView(header);
        int[] current = {initial};
        boolean[] syncing = {false};
        SeekBar[] sliders = new SeekBar[alpha ? 4 : 3];
        TextView[] values = new TextView[sliders.length];
        Runnable refresh = () -> {
            preview.setBackground(theme.shape(current[0], 6, true));
            syncing[0] = true;
            for (int i = 0; i < sliders.length; i++) {
                int value = channel(current[0], i);
                sliders[i].setProgress(value);
                values[i].setText(String.valueOf(value));
            }
            syncing[0] = false;
        };
        int[] names = {R.string.mod_config_color_red_short, R.string.mod_config_color_green_short,
                R.string.mod_config_color_blue_short, R.string.mod_config_color_alpha_short};
        for (int i = 0; i < sliders.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(theme.label(context.getString(names[i]), 10, false), new LinearLayout.LayoutParams(theme.dp(18), -2));
            SeekBar slider = new SeekBar(context);
            sliders[i] = slider;
            slider.setMax(255);
            theme.seek(slider);
            row.addView(slider, new LinearLayout.LayoutParams(0, theme.dp(36), 1));
            values[i] = theme.label("", 10, false);
            values[i].setGravity(Gravity.END);
            row.addView(values[i], new LinearLayout.LayoutParams(theme.dp(26), -2));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || syncing[0]) return;
                    int shift = index == 3 ? 24 : (2 - index) * 8;
                    current[0] = (current[0] & ~(255 << shift)) | (progress << shift);
                    refresh.run();
                    hex.setText(format(current[0], alpha));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            body.addView(row);
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s.toString().trim();
                if (value.matches("#[0-9a-fA-F]{6}") || alpha && value.matches("#[0-9a-fA-F]{8}")) {
                    current[0] = Color.parseColor(value);
                    refresh.run();
                    hex.setError(null);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        hex.setText(format(initial, alpha));
        refresh.run();
        Popover[] popup = new Popover[1];
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        TextView cancel = action(theme, context.getString(android.R.string.cancel), false);
        TextView apply = action(theme, context.getString(android.R.string.ok), true);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(0, theme.dp(36), 1);
        button.setMargins(theme.dp(3), theme.dp(6), theme.dp(3), 0);
        actions.addView(cancel, button);
        actions.addView(apply, new LinearLayout.LayoutParams(button));
        body.addView(actions);
        cancel.setOnClickListener(v -> popup[0].dismiss());
        apply.setOnClickListener(v -> {
            String value = hex.getText().toString().trim();
            if (!value.matches(alpha ? "#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?" : "#[0-9a-fA-F]{6}")) {
                hex.setError(context.getString(R.string.mm_invalid_color)); return;
            }
            listener.select(alpha ? current[0] : Color.argb(Color.alpha(initial), Color.red(current[0]), Color.green(current[0]), Color.blue(current[0])));
            popup[0].dismiss();
        });
        popup[0] = show(anchor, title, body, 296);
    }

    static void keybind(View anchor, CharSequence title, int current, int reset, Selection listener) {
        Context context = ModMenuTheme.wrap(anchor.getContext());
        ModMenuTheme theme = new ModMenuTheme(context);
        LinearLayout body = column(context);
        TextView hint = theme.label(context.getString(R.string.mod_config_press_any_key), 11, false);
        hint.setPadding(0, theme.dp(4), 0, theme.dp(10));
        body.addView(hint);
        TextView key = action(theme, keyName(current), false);
        key.setGravity(Gravity.CENTER);
        body.addView(key, new LinearLayout.LayoutParams(-1, theme.dp(34)));
        LinearLayout buttons = new LinearLayout(context);
        TextView clear = action(theme, context.getString(R.string.mod_config_clear_keybind), false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, theme.dp(36), 1);
        params.setMargins(0, theme.dp(9), theme.dp(5), 0);
        buttons.addView(clear, params);
        TextView resetButton = null;
        if (reset >= 0) {
            resetButton = action(theme, context.getString(R.string.reset), false);
            buttons.addView(resetButton, new LinearLayout.LayoutParams(params));
        }
        body.addView(buttons);
        Popover popup = show(anchor, title, body, 250);
        clear.setOnClickListener(v -> { popup.dismiss(); listener.select(KeyEvent.KEYCODE_UNKNOWN); });
        if (resetButton != null) resetButton.setOnClickListener(v -> { popup.dismiss(); listener.select(reset); });
        PopupRoot root = (PopupRoot) popup.getContentView();
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.capture = (v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { popup.dismiss(); return true; }
            if (event.getRepeatCount() != 0 || code == KeyEvent.KEYCODE_UNKNOWN || code == KeyEvent.KEYCODE_HOME
                    || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
            popup.dismiss(); listener.select(code); return true;
        };
        root.requestFocus();
        root.post(root::requestFocus);
    }

    static void info(View anchor, CharSequence title, CharSequence description, Runnable configure) {
        Context context = ModMenuTheme.wrap(anchor.getContext());
        ModMenuTheme theme = new ModMenuTheme(context);
        LinearLayout body = column(context);
        TextView text = theme.label(description, 11, false);
        text.setLineSpacing(theme.dp(3), 1);
        text.setPadding(0, theme.dp(3), 0, theme.dp(9));
        body.addView(text);
        TextView open = action(theme, context.getString(configure == null ? R.string.close : R.string.mm_edit_configuration), false);
        body.addView(open, new LinearLayout.LayoutParams(-1, theme.dp(36)));
        Popover popup = show(anchor, title, body, 300);
        open.setOnClickListener(v -> { popup.dismiss(); if (configure != null) configure.run(); });
    }

    static void search(View anchor, String current, java.util.function.Consumer<String> listener) {
        Context context = ModMenuTheme.wrap(anchor.getContext());
        ModMenuTheme theme = new ModMenuTheme(context);
        LinearLayout body = column(context);
        EditText input = new EditText(context);
        theme.input(input);
        input.setSingleLine(true);
        input.setHint(R.string.mod_menu_search_hint);
        input.setText(current);
        input.setSelection(input.length());
        body.addView(input, new LinearLayout.LayoutParams(-1, theme.dp(36)));
        watch(input, () -> listener.accept(input.getText().toString()));
        Popover popup = show(anchor, context.getString(R.string.mod_menu_search_hint), body, 285);
        input.requestFocus();
        input.post(() -> ((android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
    }

    private static Popover show(View anchor, CharSequence title, LinearLayout body, int widthDp) {
        dismissAll();
        Context context = body.getContext();
        ModMenuTheme theme = new ModMenuTheme(context);
        ViewGroup host = host(anchor);
        PopupRoot root = new PopupRoot(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(10));
        root.setBackground(theme.shape(theme.raised, theme.corner, true));
        root.setElevation(theme.dp(16));
        root.setFocusableInTouchMode(true);
        root.setClickable(true);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = theme.label(title, 12.5f, true);
        heading.setMaxLines(2);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = action(theme, "×", false);
        close.setTextSize(20);
        close.setPadding(0, 0, 0, 0);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setContentDescription(context.getString(R.string.close));
        close.setBackground(theme.touch(Color.TRANSPARENT, 5, false));
        header.addView(close, new LinearLayout.LayoutParams(theme.dp(38), theme.dp(38)));
        root.addView(header, new LinearLayout.LayoutParams(-1, theme.dp(38)));
        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Popover popup = new Popover(host, anchor, root, body, widthDp);
        close.setOnClickListener(v -> popup.dismiss());
        active = new WeakReference<>(popup);
        popup.show();
        return popup;
    }

    static boolean dispatchKeyEvent(View host, KeyEvent event) {
        Popover popup = active.get();
        if (popup == null || !popup.isShowing() || popup.host != host) return false;
        PopupRoot root = popup.root;
        if (root.capture != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
            if (root.capture.onKey(root, event.getKeyCode(), event)) return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) popup.dismiss();
            return true;
        }
        root.dispatchKeyEvent(event);
        return true;
    }

    private static ViewGroup host(View anchor) {
        View current = anchor;
        while (current != null) {
            if (current instanceof ModMenuKeyRoot) return (ViewGroup) current;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        View root = anchor.getRootView();
        if (root instanceof ViewGroup) return (ViewGroup) root;
        throw new IllegalArgumentException("Menu anchor has no host");
    }

    static final class Popover {
        final ViewGroup host;
        final View anchor;
        final PopupRoot root;
        private final LinearLayout body;
        private final int widthDp;
        private final FrameLayout layer;
        private final WeakReference<View> previousFocus;
        private final int previousAccessibility;
        private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        private boolean showing;

        Popover(ViewGroup host, View anchor, PopupRoot root, LinearLayout body, int widthDp) {
            this.host = host;
            this.anchor = anchor;
            this.root = root;
            this.body = body;
            this.widthDp = widthDp;
            previousFocus = new WeakReference<>(host.findFocus());
            View panel = host.findViewById(R.id.mod_menu_container);
            previousAccessibility = panel == null ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : panel.getImportantForAccessibility();
            layer = new FrameLayout(root.getContext());
            layer.setClickable(true);
            layer.setFocusableInTouchMode(true);
            layer.setElevation(new ModMenuTheme(root.getContext()).dp(20));
            layer.setOnClickListener(v -> dismiss());
            layer.addView(root, new FrameLayout.LayoutParams(-2, -2));
            layoutListener = this::position;
        }

        void show() {
            showing = true;
            View panel = host.findViewById(R.id.mod_menu_container);
            if (panel != null) panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            host.addView(layer, new ViewGroup.LayoutParams(-1, -1));
            host.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            layer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { position(); }
                @Override public void onViewDetachedFromWindow(View v) { dismiss(); }
            });
            position();
            root.requestFocus();
            layer.post(this::position);
        }

        private void position() {
            if (!showing) return;
            ModMenuTheme theme = new ModMenuTheme(root.getContext());
            Rect bounds = bounds(anchor, theme);
            int[] origin = new int[2]; host.getLocationOnScreen(origin);
            bounds.offset(-origin[0], -origin[1]);
            int width = Math.max(1, Math.min(theme.dp(widthDp), bounds.width()));
            body.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width - theme.dp(24)), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = Math.max(1, Math.min(bounds.height(), theme.dp(56) + body.getMeasuredHeight()));
            int[] location = new int[2]; anchor.getLocationOnScreen(location);
            int x = ModMenuGeometry.clamp(location[0] - origin[0] + anchor.getWidth() - width, bounds.left, bounds.right - width);
            int y = location[1] - origin[1] + anchor.getHeight() + theme.dp(4);
            if (y + height > bounds.bottom) y = location[1] - origin[1] - height - theme.dp(4);
            y = ModMenuGeometry.clamp(y, bounds.top, bounds.bottom - height);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) root.getLayoutParams();
            if (params.width != width || params.height != height || params.leftMargin != x || params.topMargin != y) {
                params.width = width;
                params.height = height;
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.setMargins(x, y, 0, 0);
                root.setLayoutParams(params);
            }
        }

        View getContentView() { return root; }
        boolean isShowing() { return showing; }

        void dismiss() {
            if (!showing) return;
            showing = false;
            View focus = root.findFocus();
            if (focus instanceof EditText) {
                android.view.inputmethod.InputMethodManager ime = (android.view.inputmethod.InputMethodManager)
                        root.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.hideSoftInputFromWindow(root.getWindowToken(), 0);
            }
            if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            if (layer.getParent() == host) host.removeView(layer);
            View panel = host.findViewById(R.id.mod_menu_container);
            if (panel != null) panel.setImportantForAccessibility(previousAccessibility);
            if (active.get() == this) active.clear();
            View previous = previousFocus.get();
            if (previous != null && previous.isAttachedToWindow() && previous.getVisibility() == View.VISIBLE) previous.requestFocus();
            else host.requestFocus();
        }
    }

    private static final class PopupRoot extends LinearLayout {
        View.OnKeyListener capture;
        PopupRoot(Context context) { super(context); }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (capture != null && capture.onKey(this, event.getKeyCode(), event)) return true;
            return super.dispatchKeyEvent(event);
        }
    }

    private static Rect bounds(View anchor, ModMenuTheme theme) {
        View root = anchor.getRootView();
        View panel = root.findViewById(R.id.mod_menu_container);
        if (panel == null || panel.getVisibility() != View.VISIBLE) panel = root;
        int[] location = new int[2]; panel.getLocationOnScreen(location);
        int width = panel.getWidth() > 0 ? panel.getWidth() : anchor.getResources().getDisplayMetrics().widthPixels;
        int height = panel.getHeight() > 0 ? panel.getHeight() : anchor.getResources().getDisplayMetrics().heightPixels;
        Rect result = new Rect(location[0], location[1], location[0] + width, location[1] + height);
        result.inset(theme.dp(7), theme.dp(7));
        return result;
    }

    private static View anchor(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return ((Activity) current).getWindow().getDecorView();
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        throw new IllegalArgumentException("Menu context has no activity");
    }

    private static TextView action(ModMenuTheme theme, CharSequence text, boolean filled) {
        TextView view = theme.label(text, 11, true);
        theme.button(view, filled);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private static void watch(EditText input, Runnable changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private static int channel(int color, int index) {
        return index == 0 ? Color.red(color) : index == 1 ? Color.green(color) : index == 2 ? Color.blue(color) : Color.alpha(color);
    }

    private static String format(int color, boolean alpha) {
        return String.format(Locale.ROOT, alpha ? "#%08X" : "#%06X", alpha ? color : color & 0xFFFFFF);
    }

    static String keyName(int code) {
        if (code == 0) return "—";
        String label = KeyEvent.keyCodeToString(code);
        return label.startsWith("KEYCODE_") ? label.substring(8).replace('_', ' ') : label;
    }
}
