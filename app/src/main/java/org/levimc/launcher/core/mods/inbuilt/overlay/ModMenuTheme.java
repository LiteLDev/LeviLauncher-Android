package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.levimc.launcher.R;

final class ModMenuTheme {
    static final String[] PRESETS = {"Dark", "Light", "Rose", "Plant", "Red", "Indigo", "Sky", "Violet", "Amber", "Emerald", "Midnight", "Crimson", "Monokai Pro", "Cotton Candy", "Mellowsi", "Rainbow"};
    private static final int[][] COLORS = {
            {0xFF101010, 0xFF121215, 0xFF18181b, 0xFF222226, 0xFF39393e, 0xFFffffff, 0xFFa1a1aa, 0xFF52525b},
            {0xFFededee, 0xFFf1f2f5, 0xFFffffff, 0xFFf2f2f4, 0xFFd1d1d5, 0xFF18181b, 0xFF707078, 0xFF487ce8},
            {0xFF200b15, 0xFF1b0b13, 0xFF311924, 0xFF3b222d, 0xFF59343e, 0xFFffeafa, 0xFFbd9fac, 0xFFf05f88},
            {0xFF0c1811, 0xFF0a160f, 0xFF12271b, 0xFF1b3022, 0xFF294736, 0xFFdcf8e3, 0xFF8ba998, 0xFF34bf79},
            {0xFF1c0e10, 0xFF1b0a0d, 0xFF2d171b, 0xFF362025, 0xFF513036, 0xFFfbe9ea, 0xFFc1a5a7, 0xFFed454e},
            {0xFF111127, 0xFF0d0e25, 0xFF1c1f3e, 0xFF24274c, 0xFF34365b, 0xFFe9eaff, 0xFFa8abd1, 0xFF6768ec},
            {0xFF081d25, 0xFF09202a, 0xFF13313d, 0xFF1a3a48, 0xFF2c4c58, 0xFFe4f9ff, 0xFFa3c5d2, 0xFF18b1cf},
            {0xFF180c2c, 0xFF160b2b, 0xFF271c43, 0xFF30204b, 0xFF473060, 0xFFf2eaff, 0xFFafa4c8, 0xFF9254ec},
            {0xFF1f170e, 0xFF1b140c, 0xFF322719, 0xFF3b2c1b, 0xFF51422a, 0xFFfbeed6, 0xFFb8ad91, 0xFFd3a64c},
            {0xFF071d17, 0xFF061912, 0xFF112a20, 0xFF18392b, 0xFF2b5140, 0xFFe1fff3, 0xFF9cbcae, 0xFF00a77f},
            {0xFF0a1022, 0xFF091124, 0xFF121b36, 0xFF1a2541, 0xFF303955, 0xFFeff2ff, 0xFF9da9c5, 0xFF245dd5},
            {0xFF240d16, 0xFF1e0b12, 0xFF34151f, 0xFF3f1d2a, 0xFF56313a, 0xFFffeaf0, 0xFFc6a0ad, 0xFFd64069},
            {0xFF18171e, 0xFF15141a, 0xFF23222b, 0xFF2d2a34, 0xFF44404c, 0xFFfaf9f3, 0xFFb7b4c1, 0xFFa2cf70},
            {0xFF282136, 0xFF231d30, 0xFF3b304d, 0xFF463d57, 0xFF61546c, 0xFFfff4fb, 0xFFd1bed7, 0xFFf395c3},
            {0xFF201812, 0xFF1e1711, 0xFF30281b, 0xFF3f3020, 0xFF534332, 0xFFffefe2, 0xFFc4b1a1, 0xFFcfaa63},
            {0xFF141820, 0xFF131921, 0xFF242732, 0xFF2a2d3b, 0xFF454759, 0xFFeff6ff, 0xFFaeb6d3, 0xFF45b6f3}
    };
    final boolean dark;
    final boolean rainbow;
    final int accent;
    final int primary;
    final int surface;
    final int sidebar;
    final int raised;
    final int selected;
    final int buttonColor;
    final int text;
    final int secondary;
    final int outline;
    final int radius;
    final int corner;
    private final Context context;

    ModMenuTheme(Context context) {
        this.context = context;
        ModMenuPreferences prefs = new ModMenuPreferences(context);
        int night = (prefs.mode() == 0 ? android.content.res.Resources.getSystem().getConfiguration()
                : context.getResources().getConfiguration()).uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = prefs.mode() == 1 || prefs.mode() == 0 && night == Configuration.UI_MODE_NIGHT_YES;
        int index = prefs.palette();
        int[] palette = COLORS[dark ? index : 1];
        rainbow = dark && index == COLORS.length - 1;
        surface = palette[0];
        sidebar = palette[1];
        raised = palette[2];
        selected = palette[3];
        buttonColor = palette[3];
        outline = palette[4];
        text = palette[5];
        secondary = palette[6];
        int seed = prefs.accentSource() == 1 ? prefs.customAccent() : COLORS[dark ? index : index == 0 ? 1 : index][7];
        primary = readable(seed, Color.WHITE, 4.5, Color.BLACK);
        accent = readable(seed, raised, 3.0, dark ? Color.WHITE : Color.BLACK);
        radius = prefs.radius();
        corner = prefs.elementRadius();
    }

    static Context wrap(Context source) {
        if (source instanceof MenuContext) return source;
        ModMenuPreferences prefs = new ModMenuPreferences(source);
        Configuration config = new Configuration(source.getResources().getConfiguration());
        int night = prefs.mode() == 0
                ? android.content.res.Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK
                : prefs.mode() == 1 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        config.fontScale *= prefs.textScale() / 100f;
        return new MenuContext(source, config, prefs.textScale());
    }

    private static final class MenuContext extends ContextThemeWrapper {
        final int scale;
        MenuContext(Context source, Configuration configuration, int scale) {
            super(source, R.style.Theme_Levi_ModMenu);
            this.scale = scale;
            applyOverrideConfiguration(configuration);
        }
    }

    static float textRatio(Context context) {
        return new ModMenuPreferences(context).textScale() / (float) (context instanceof MenuContext ? ((MenuContext) context).scale : 100);
    }

    static void round(android.graphics.drawable.Drawable drawable, int radius) {
        if (drawable instanceof GradientDrawable) ((GradientDrawable) drawable).setCornerRadius(radius);
        else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) round(layers.getDrawable(i), radius);
        }
    }

    static int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
                Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
                Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount));
    }

    private static double luminance(int color) {
        double result = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        for (int i = 0; i < 3; i++) {
            double value = channels[i] / 255.0;
            result += weights[i] * (value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4));
        }
        return result;
    }

    static int readable(int color, int against, double minimum, int toward) {
        int result = color;
        double base = luminance(against);
        for (int i = 0; i <= 100; i++) {
            double current = luminance(result);
            if ((Math.max(base, current) + 0.05) / (Math.min(base, current) + 0.05) >= minimum) break;
            result = mix(color, toward, i / 100f);
        }
        return result;
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    GradientDrawable shape(int color, float radiusDp, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (border) drawable.setStroke(dp(1), outline);
        return drawable;
    }

    GradientDrawable panel(int opacity) {
        int alpha = Math.round(Math.max(0, Math.min(100, opacity)) * 2.55f);
        if (!rainbow) return shape((surface & 0xFFFFFF) | alpha << 24, radius, true);
        int[] colors = {0xFFCE1553, 0xFFD68C2C, 0xFFA8BB2B, 0xFF219F72, 0xFF197CAD, 0xFF4D41C8};
        for (int i = 0; i < colors.length; i++) colors[i] = colors[i] & 0xFFFFFF | alpha << 24;
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), outline);
        return drawable;
    }

    RippleDrawable touch(int color, float radiusDp, boolean border) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x18FFFFFF : 0x13000000),
                shape(color, radiusDp, border), shape(Color.WHITE, radiusDp, false));
    }

    TextView label(CharSequence value, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size * textRatio(context));
        view.setTextColor(bold ? text : secondary);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    void button(TextView view, boolean filled) {
        view.setTextColor(filled ? Color.WHITE : text);
        view.setBackgroundTintList(null);
        view.setBackground(touch(filled ? primary : buttonColor, corner, !filled));
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
        view.setMinHeight(dp(36));
        view.setMinimumHeight(dp(36));
        view.setTextSize(12 * textRatio(context));
        view.setIncludeFontPadding(false);
        if (view instanceof Button) {
            ((Button) view).setAllCaps(false);
            ((Button) view).setStateListAnimator(null);
            ((Button) view).setMinWidth(0);
            ((Button) view).setMinimumWidth(0);
        }
    }

    void input(EditText input) {
        input.setTextColor(text);
        input.setHintTextColor(secondary);
        input.setBackgroundTintList(null);
        input.setBackground(shape(buttonColor, corner, true));
        input.setPadding(dp(9), dp(5), dp(9), dp(5));
        input.setMinHeight(dp(38));
        input.setMinimumHeight(dp(38));
        input.setTextSize(12 * textRatio(context));
        input.setIncludeFontPadding(false);
    }

    void seek(SeekBar view) {
        view.setProgressTintList(null);
        view.setProgressBackgroundTintList(null);
        view.setThumbTintList(null);
        GradientDrawable track = shape(buttonColor, 3, false);
        GradientDrawable fill = shape(accent, 3, false);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{track,
                new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerHeight(0, dp(3));
        layers.setLayerHeight(1, dp(3));
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        view.setProgressDrawable(layers);
        int range = view.getMax() - view.getMin();
        layers.findDrawableByLayerId(android.R.id.progress).setLevel(range > 0
                ? Math.round((view.getProgress() - view.getMin()) * 10000f / range) : 0);
        GradientDrawable thumb = shape(dark ? 0xFFE1E1E7 : 0xFF555563, 8, false);
        thumb.setSize(dp(16), dp(16));
        view.setThumb(thumb);
        view.setThumbOffset(dp(8));
        view.setSplitTrack(false);
        view.setMinHeight(dp(36));
        view.setMinimumHeight(dp(36));
        view.setPadding(dp(12), 0, dp(12), 0);
    }

    ColorStateList checked(int on, int off) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{on, off});
    }

    void toggle(CompoundButton view) {
        view.setTextColor(text);
        view.setTextSize(12 * textRatio(context));
        view.setMinHeight(dp(40));
        view.setMinimumHeight(dp(40));
        if (view instanceof ModMenuToggle) ((ModMenuToggle) view).setPalette(this);
        else view.setButtonTintList(checked(accent, secondary));
    }

    void controls(View view) {
        if (view instanceof EditText) input((EditText) view);
        else if (view instanceof Button && !(view instanceof CompoundButton)) button((TextView) view, false);
        else if (view instanceof CompoundButton) toggle((CompoundButton) view);
        else if (view instanceof SeekBar) seek((SeekBar) view);
        else if (view instanceof TextView) ((TextView) view).setTextColor(text);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) controls(group.getChildAt(i));
        }
    }

    static MaterialAlertDialogBuilder dialog(Context context) {
        return new MaterialAlertDialogBuilder(wrap(context));
    }
}
