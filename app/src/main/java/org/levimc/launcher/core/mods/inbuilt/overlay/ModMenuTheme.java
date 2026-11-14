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
    static final String[] PRESETS = {"Graphite", "Midnight", "Ocean", "Forest", "Amethyst", "Rose", "Ember", "Sandstone", "Monokai"};
    static final String[] PRESET_IDS = {"graphite", "midnight", "ocean", "forest", "amethyst", "rose", "ember", "sandstone", "monokai"};
    private static final int[] PRESET_NAMES = {R.string.mm_palette_graphite, R.string.mm_palette_midnight,
            R.string.mm_palette_ocean, R.string.mm_palette_forest, R.string.mm_palette_amethyst,
            R.string.mm_palette_rose, R.string.mm_palette_ember, R.string.mm_palette_sandstone,
            R.string.mm_palette_monokai};
    private static final int[][] DARK_COLORS = {
            {0xFF101010, 0xFF121215, 0xFF18181B, 0xFF222226, 0xFF39393E, 0xFFFFFFFF, 0xFFA1A1AA, 0xFF52525B},
            {0xFF0A1020, 0xFF070C18, 0xFF121D35, 0xFF1C2D4C, 0xFF33496E, 0xFFE9F0FF, 0xFFA4B6D6, 0xFF7DA7FF},
            {0xFF071E23, 0xFF06171C, 0xFF10303A, 0xFF184450, 0xFF2E626D, 0xFFE3FAFF, 0xFF9CBFC7, 0xFF45D4D2},
            {0xFF112017, 0xFF0C1810, 0xFF1C3222, 0xFF2B4630, 0xFF49654B, 0xFFF0F5E8, 0xFFAFBEA6, 0xFF62C990},
            {0xFF1A1029, 0xFF120B20, 0xFF2B1D3F, 0xFF3D2B57, 0xFF634B80, 0xFFF6EDFF, 0xFFC0ADCF, 0xFFBE92F1},
            {0xFF28131D, 0xFF1D0D16, 0xFF3C202E, 0xFF542B3F, 0xFF794559, 0xFFFFEFF5, 0xFFD3ACBC, 0xFFF391B2},
            {0xFF26150F, 0xFF1D0E09, 0xFF3B2419, 0xFF553524, 0xFF7A5438, 0xFFFFF3E7, 0xFFD2B09B, 0xFFF7AA70},
            {0xFF252319, 0xFF1C1B13, 0xFF373425, 0xFF4B4632, 0xFF6C654A, 0xFFFAF5DD, 0xFFC7BFA0, 0xFFD9C36B},
            {0xFF24251F, 0xFF191B16, 0xFF303229, 0xFF414437, 0xFF60644D, 0xFFF8F8EC, 0xFFB8BD9D, 0xFFA8D65A}
    };
    private static final int[][] LIGHT_COLORS = {
            {0xFFEDEDEE, 0xFFF1F2F5, 0xFFFFFFFF, 0xFFF2F2F4, 0xFFD1D1D5, 0xFF18181B, 0xFF62626B, 0xFF52525B},
            {0xFFECF1FF, 0xFFDEE7FC, 0xFFF9FAFF, 0xFFD6E2FF, 0xFFA8BCDF, 0xFF182B4C, 0xFF4F6485, 0xFF416CD7},
            {0xFFEAF9F8, 0xFFD5EEED, 0xFFF8FEFD, 0xFFCBEBE8, 0xFF92BEBE, 0xFF123A40, 0xFF49737A, 0xFF117D87},
            {0xFFEFF6E9, 0xFFDFEBD3, 0xFFFCFEF8, 0xFFDCEACA, 0xFFADC19D, 0xFF253C28, 0xFF5C7459, 0xFF217B4C},
            {0xFFF4EEFC, 0xFFE9DFF5, 0xFFFEFBFF, 0xFFE5D6F5, 0xFFBBA3D2, 0xFF3D2855, 0xFF79658C, 0xFF8351B8},
            {0xFFFFF0F4, 0xFFF9E0E9, 0xFFFFFAFC, 0xFFF8D6E2, 0xFFD8A4B8, 0xFF552438, 0xFF8F5D72, 0xFFB94873},
            {0xFFFFF2E6, 0xFFF6E2CE, 0xFFFFFCF8, 0xFFF5D7B8, 0xFFD9B38C, 0xFF532B17, 0xFF92694B, 0xFFB75A26},
            {0xFFF8F4E4, 0xFFECE5CC, 0xFFFFFDF5, 0xFFEBE1B9, 0xFFC7B881, 0xFF463D22, 0xFF7B7052, 0xFF94752A},
            {0xFFF3F5E7, 0xFFE4E8D0, 0xFFFEFFF6, 0xFFDDE5BD, 0xFFB0BA8A, 0xFF333A23, 0xFF707957, 0xFF6A852C}
    };
    final boolean dark;
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
        int[] palette = colors(index, dark);
        surface = palette[0];
        sidebar = palette[1];
        raised = palette[2];
        selected = palette[3];
        buttonColor = palette[3];
        outline = palette[4];
        text = palette[5];
        secondary = palette[6];
        int seed = prefs.accentSource() == 1 ? prefs.customAccent() : palette[7];
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
        return shape((surface & 0xFFFFFF) | alpha << 24, radius, true);
    }

    static int[] colors(int index, boolean dark) {
        return (dark ? DARK_COLORS : LIGHT_COLORS)[ModMenuGeometry.clamp(index, 0, PRESET_IDS.length - 1)];
    }

    static String presetName(Context context, int index) {
        return context.getString(PRESET_NAMES[ModMenuGeometry.clamp(index, 0, PRESET_NAMES.length - 1)]);
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
