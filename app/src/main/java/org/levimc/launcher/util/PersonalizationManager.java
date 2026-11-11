package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import org.levimc.launcher.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class PersonalizationManager {
    private static final String PREFS_NAME = "personalization_prefs";
    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_BG_IMAGE_PATH = "bg_image_path";
    private static final String KEY_BG_MEDIA_TYPE = "bg_media_type";
    private static final String KEY_BG_POSTER_PATH = "bg_poster_path";
    private static final String KEY_BG_IMAGE_BLUR = "bg_image_blur";
    private static final String KEY_BG_IMAGE_BRIGHTNESS = "bg_image_brightness";
    private static final String KEY_ACCENT_LIGHT = "accent_color_light";
    private static final String KEY_ACCENT_DARK = "accent_color_dark";
    private static final String KEY_BG_FIT_MODE = "bg_fit_mode";
    private static final String KEY_BG_OPACITY = "bg_opacity";
    private static final String KEY_BG_FOLDER = "bg_folder_uri";
    private static final String KEY_BG_PLAY_ORDER = "bg_play_order";
    private static final String KEY_BG_FOLDER_CURRENT = "bg_folder_current";

    // Ranges shown by LeviLauncher for Windows (Settings > Personalization > Wallpaper).
    public static final int BG_BLUR_MIN = 0;
    public static final int BG_BLUR_MAX = 50;
    public static final int BG_BRIGHTNESS_MIN = 20;
    public static final int BG_BRIGHTNESS_MAX = 100;
    public static final int BG_BRIGHTNESS_DEFAULT = 100;
    public static final int BG_OPACITY_DEFAULT = 100;

    public static final String FIT_SMART = "smart";
    public static final String FIT_CENTER = "center";
    public static final String FIT_FIT = "fit";
    public static final String FIT_STRETCH = "stretch";
    public static final String FIT_TILE = "tile";
    public static final String FIT_TOP_LEFT = "top_left";
    public static final String FIT_TOP_RIGHT = "top_right";
    public static final String[] FIT_MODES = {FIT_SMART, FIT_CENTER, FIT_FIT, FIT_STRETCH, FIT_TILE,
            FIT_TOP_LEFT, FIT_TOP_RIGHT};
    public static final String ORDER_RANDOM = "random";
    public static final String ORDER_SEQUENTIAL = "sequential";

    /** View tag that keeps a view's own background when the wallpaper turns surfaces into glass. */
    public static final String TAG_KEEP_BACKGROUND = "levi_keep_background";

    private static final AtomicInteger sChangeGeneration = new AtomicInteger();
    private static final AtomicInteger sBackgroundEffectGeneration = new AtomicInteger();

    private final SharedPreferences prefs;
    private final Context context;



    public PersonalizationManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String type = prefs.getString(KEY_BG_MEDIA_TYPE, "image");
        if (!"image".equals(type) && !"animation".equals(type)) {
            String previous = prefs.getString(KEY_BG_IMAGE_PATH, null);
            String poster = prefs.getString(KEY_BG_POSTER_PATH, null);
            SharedPreferences.Editor editor = prefs.edit();
            if (poster != null && new File(poster).exists()) {
                editor.putString(KEY_BG_IMAGE_PATH, poster).putString(KEY_BG_MEDIA_TYPE, "image");
            } else {
                editor.remove(KEY_BG_IMAGE_PATH).remove(KEY_BG_POSTER_PATH).remove(KEY_BG_MEDIA_TYPE);
            }
            editor.apply();
            if (previous != null && !previous.equals(poster)) deleteBackgroundFile(previous);
            sChangeGeneration.incrementAndGet();
        }
    }

    /** Theme color of the mode in use. Light and dark modes keep their own color, as on Windows. */
    public int getAccentColor() {
        return getAccentColor(isDarkActive(context));
    }

    public int getAccentColor(boolean dark) {
        String key = dark ? KEY_ACCENT_DARK : KEY_ACCENT_LIGHT;
        if (prefs.contains(key)) return prefs.getInt(key, ThemePalette.DEFAULT_ACCENT);
        // Before per-mode colors there was one color for both modes.
        if (prefs.contains(KEY_ACCENT_COLOR)) return prefs.getInt(KEY_ACCENT_COLOR, ThemePalette.DEFAULT_ACCENT);
        return ThemePalette.DEFAULT_ACCENT;
    }

    public void setAccentColor(int color) {
        setAccentColor(isDarkActive(context), color);
    }

    public void setAccentColor(boolean dark, int color) {
        prefs.edit().putInt(dark ? KEY_ACCENT_DARK : KEY_ACCENT_LIGHT, color | 0xFF000000).apply();
        sChangeGeneration.incrementAndGet();
    }

    public boolean hasCustomAccent() {
        return prefs.contains(KEY_ACCENT_LIGHT) || prefs.contains(KEY_ACCENT_DARK)
                || (prefs.contains(KEY_ACCENT_COLOR) && prefs.getInt(KEY_ACCENT_COLOR, 0) != 0);
    }

    public void clearAccentColor() {
        prefs.edit().remove(KEY_ACCENT_COLOR).remove(KEY_ACCENT_LIGHT).remove(KEY_ACCENT_DARK).apply();
        sChangeGeneration.incrementAndGet();
    }

    /** Page title color for the current theme color (Windows brand-heading). */
    public int getHeadingColor(Context ctx) {
        boolean dark = isDarkMode(ctx);
        return ThemePalette.heading(getAccentColor(dark), dark);
    }

    /**
     * Dark mode as the launcher applies it: AppCompat's night mode, or the system setting when
     * following the system. The application context does not carry AppCompat's night mode.
     */
    public static boolean isDarkActive(Context context) {
        int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return false;
        int night = context.getApplicationContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static void bumpChange() {
        sChangeGeneration.incrementAndGet();
    }

    public static void bumpBackgroundEffects() {
        sBackgroundEffectGeneration.incrementAndGet();
    }

    public String getBackgroundFitMode() {
        String mode = prefs.getString(KEY_BG_FIT_MODE, FIT_SMART);
        for (String m : FIT_MODES) if (m.equals(mode)) return m;
        return FIT_SMART;
    }

    public void setBackgroundFitMode(String mode) {
        if (mode.equals(getBackgroundFitMode())) return;
        prefs.edit().putString(KEY_BG_FIT_MODE, mode).apply();
        sBackgroundEffectGeneration.incrementAndGet();
    }

    public int getBackgroundOpacity() {
        return clamp(prefs.getInt(KEY_BG_OPACITY, BG_OPACITY_DEFAULT), 0, 100);
    }

    public void setBackgroundOpacity(int opacity) {
        int clamped = clamp(opacity, 0, 100);
        int previous = getBackgroundOpacity();
        if (clamped == previous) return;
        prefs.edit().putInt(KEY_BG_OPACITY, clamped).apply();
        sBackgroundEffectGeneration.incrementAndGet();
        if ((previous == 0) != (clamped == 0)) sChangeGeneration.incrementAndGet();
    }

    /** Folder the wallpaper is picked from (a document tree URI), or null for a single image. */
    public String getBackgroundFolder() {
        return prefs.getString(KEY_BG_FOLDER, null);
    }

    public void setBackgroundFolder(String treeUri) {
        SharedPreferences.Editor editor = prefs.edit();
        if (treeUri == null) editor.remove(KEY_BG_FOLDER).remove(KEY_BG_FOLDER_CURRENT);
        else editor.putString(KEY_BG_FOLDER, treeUri);
        editor.apply();
    }

    public String getBackgroundPlayOrder() {
        return ORDER_SEQUENTIAL.equals(prefs.getString(KEY_BG_PLAY_ORDER, ORDER_RANDOM))
                ? ORDER_SEQUENTIAL : ORDER_RANDOM;
    }

    public void setBackgroundPlayOrder(String order) {
        prefs.edit().putString(KEY_BG_PLAY_ORDER,
                ORDER_SEQUENTIAL.equals(order) ? ORDER_SEQUENTIAL : ORDER_RANDOM).apply();
    }

    /** Document id of the folder image shown now, used to pick the next one. */
    public String getBackgroundFolderCurrent() {
        return prefs.getString(KEY_BG_FOLDER_CURRENT, null);
    }

    public void setBackgroundFolderCurrent(String documentId) {
        prefs.edit().putString(KEY_BG_FOLDER_CURRENT, documentId).apply();
    }

    public String getBackgroundImagePath() {
        return prefs.getString(KEY_BG_IMAGE_PATH, null);
    }

    public boolean hasBackgroundImage() {
        String path = getBackgroundImagePath();
        if (path == null) return false;
        return new File(path).exists();
    }

    public int getBackgroundImageBlur() {
        return clamp(prefs.getInt(KEY_BG_IMAGE_BLUR, BG_BLUR_MIN), BG_BLUR_MIN, BG_BLUR_MAX);
    }

    public void setBackgroundImageBlur(int blurRadius) {
        int clamped = clamp(blurRadius, BG_BLUR_MIN, BG_BLUR_MAX);
        if (getBackgroundImageBlur() == clamped) return;
        prefs.edit().putInt(KEY_BG_IMAGE_BLUR, clamped).apply();
        sBackgroundEffectGeneration.incrementAndGet();
    }

    public int getBackgroundImageBrightness() {
        return clamp(prefs.getInt(KEY_BG_IMAGE_BRIGHTNESS, BG_BRIGHTNESS_DEFAULT),
                BG_BRIGHTNESS_MIN, BG_BRIGHTNESS_MAX);
    }

    public void setBackgroundImageBrightness(int brightnessPercent) {
        int clamped = clamp(brightnessPercent, BG_BRIGHTNESS_MIN, BG_BRIGHTNESS_MAX);
        if (getBackgroundImageBrightness() == clamped) return;
        prefs.edit().putInt(KEY_BG_IMAGE_BRIGHTNESS, clamped).apply();
        sBackgroundEffectGeneration.incrementAndGet();
    }

    public boolean hasAnimatedBackground() {
        return hasBackgroundImage() && "animation".equals(prefs.getString(KEY_BG_MEDIA_TYPE, "image"));
    }

    public boolean canBlurBackground() {
        return !hasAnimatedBackground() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public boolean setBackgroundImage(Uri sourceUri, Context activityContext) {
        File media = null;
        File poster = null;
        Bitmap bitmap = null;
        boolean installed = false;
        try {
            if (LauncherBackgroundController.isMinecraftBlocked()) return false;
            File directory = new File(context.getFilesDir(), "personalization");
            if (!directory.exists() && !directory.mkdirs()) return false;
            String name = "background_" + UUID.randomUUID();
            media = new File(directory, name + ".media");
            poster = new File(directory, name + ".jpg");
            try (InputStream input = activityContext.getContentResolver().openInputStream(sourceUri);
                 FileOutputStream output = new FileOutputStream(media)) {
                if (input == null) return false;
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    ensureImportActive();
                    output.write(buffer, 0, count);
                }
            }
            ensureImportActive();
            boolean[] animated = {false};
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(media),
                    (decoder, info, source) -> {
                        animated[0] = info.isAnimated();
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        float scale = Math.min(1f, (animated[0] ? 720f : 2048f)
                                / Math.max(width, height));
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            String type = animated[0] ? "animation" : "image";
            ensureImportActive();
            try (FileOutputStream output = new FileOutputStream(poster)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) return false;
            }
            ensureImportActive();
            String previousMedia = getBackgroundImagePath();
            String previousPoster = prefs.getString(KEY_BG_POSTER_PATH, null);
            File selected = "image".equals(type) ? poster : media;
            prefs.edit().putString(KEY_BG_IMAGE_PATH, selected.getAbsolutePath())
                    .putString(KEY_BG_POSTER_PATH, poster.getAbsolutePath())
                    .putString(KEY_BG_MEDIA_TYPE, type).apply();
            installed = true;
            sChangeGeneration.incrementAndGet();
            if ("image".equals(type)) media.delete();
            deleteBackgroundFile(previousMedia);
            deleteBackgroundFile(previousPoster);
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (!installed) {
                if (media != null) media.delete();
                if (poster != null) poster.delete();
            }
        }
    }

    private static void ensureImportActive() throws IOException {
        if (Thread.currentThread().isInterrupted() || LauncherBackgroundController.isMinecraftBlocked()) {
            throw new IOException("Background import cancelled");
        }
    }

    private void deleteBackgroundFile(String path) {
        if (path == null) return;
        File file = new File(path);
        File directory = new File(context.getFilesDir(), "personalization");
        if (directory.equals(file.getParentFile())) file.delete();
    }

    public void clearBackgroundImage() {
        LauncherBackgroundController.backgroundCleared();
        String path = getBackgroundImagePath();
        String poster = prefs.getString(KEY_BG_POSTER_PATH, null);
        prefs.edit().remove(KEY_BG_IMAGE_PATH).remove(KEY_BG_POSTER_PATH)
                .remove(KEY_BG_MEDIA_TYPE).apply();
        deleteBackgroundFile(path);
        deleteBackgroundFile(poster);
        sChangeGeneration.incrementAndGet();
    }

    public Bitmap loadBackgroundBitmap() {
        String path = prefs.getString(KEY_BG_POSTER_PATH, getBackgroundImagePath());
        if (path == null || !new File(path).exists()) return null;
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Exception e) {
            return null;
        }
    }



    public static int getChangeGeneration() {
        return sChangeGeneration.get();
    }

    public static int getBackgroundEffectGeneration() {
        return sBackgroundEffectGeneration.get();
    }

    public void applyToActivity(Activity activity) {
        if (activity instanceof org.levimc.launcher.ui.activities.SplashActivity) return;

        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        int accent = getAccentColor();
        boolean hasBg = hasBackgroundImage();

        if (hasBg && !LauncherBackgroundController.isMinecraftBlocked()
                && !(activity instanceof org.levimc.launcher.core.minecraft.MinecraftLoadingActivity)
                && !(activity instanceof org.levimc.launcher.core.minecraft.LauncherRestartActivity)
                && !(activity instanceof org.levimc.launcher.core.minecraft.MinecraftActivity)) {
            applyBackgroundImage(activity, rootView);
        }

        if (accent != 0) {
            applyAccentColorRecursive(rootView, accent, activity);
            applyNavBarAccent(activity, accent);
        }
    }

    private void applyNavBarAccent(Activity activity, int accent) {
        TextView appName = activity.findViewById(R.id.nav_app_name);
        if (appName != null) {
            applySolidAccentText(appName, accent);
        }

        View signInBtn = activity.findViewById(R.id.nav_sign_in_button);
        if (signInBtn instanceof Button) {
            Button btn = (Button) signInBtn;
            btn.setBackgroundTintList(ColorStateList.valueOf(accent));
            btn.setTextColor(Color.WHITE);
        }
    }

    public void applySolidAccentText(TextView textView, int accentColor) {
        textView.getPaint().setShader(null);
        textView.setTextColor(accentColor);
        textView.invalidate();
    }

    private void applyBackgroundImage(Activity activity, ViewGroup rootView) {
        View background = rootView.findViewWithTag("personalization_bg");
        boolean animated = hasAnimatedBackground();
        if (background != null && (animated != (background instanceof FrameLayout))) {
            rootView.removeView(background);
            background = null;
        }
        ImageView bgView;
        if (animated) {
            FrameLayout holder;
            if (background == null) {
                holder = new FrameLayout(activity);
                holder.setTag("personalization_bg");
                rootView.addView(holder, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                bgView = new ImageView(activity);
                bgView.setTag("personalization_poster");
                bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.addView(bgView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                FrameLayout media = new FrameLayout(activity);
                media.setTag("personalization_media");
                media.setClickable(false);
                media.setFocusable(false);
                holder.addView(media, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                background = holder;
            } else {
                bgView = ((FrameLayout) background).findViewWithTag("personalization_poster");
            }
        } else {
            if (background == null) {
                bgView = new ImageView(activity);
                bgView.setTag("personalization_bg");
                bgView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                rootView.addView(bgView, 0);
                background = bgView;
            } else bgView = (ImageView) background;
        }
        if (!applyBackgroundImageToView(bgView)) return;

        boolean isDark = isDarkMode(activity);
        View baseView = rootView.findViewWithTag("personalization_base");
        if (baseView == null) {
            baseView = new View(activity);
            baseView.setTag("personalization_base");
            rootView.addView(baseView, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (rootView.indexOfChild(baseView) > rootView.indexOfChild(background)) {
            // The wallpaper view was re-created (image <-> animation); keep the base under it.
            rootView.removeView(baseView);
            rootView.addView(baseView, 0);
        }
        applyBaseLayer(baseView, isDark);

        View overlayView = rootView.findViewWithTag("personalization_overlay");
        if (overlayView == null) {
            overlayView = new View(activity);
            overlayView.setTag("personalization_overlay");
            overlayView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            int bgIndex = rootView.indexOfChild(background);
            rootView.addView(overlayView, bgIndex + 1);
        }

        applyOverlay(overlayView, isDark);

        makeChildBackgroundsTranslucent(rootView, getWallpaperSurfaceColor());
    }

    /** Solid layer under the wallpaper (Windows "Base mode"): none, the theme color, or a custom color. */
    private void applyBaseLayer(View baseView, boolean isDark) {
        int page = ContextCompat.getColor(baseView.getContext(), R.color.background);
        String mode = UiSettings.getBaseMode(context, isDark);
        if (UiSettings.BASE_NONE.equals(mode)) {
            baseView.setBackgroundColor(page);
            return;
        }
        int color = UiSettings.BASE_THEME.equals(mode)
                ? ThemePalette.shade(getAccentColor(isDark), isDark ? 900 : 50)
                : UiSettings.getBaseColor(context, isDark);
        baseView.setBackgroundColor(ThemePalette.mix(page, color, UiSettings.getBaseOpacity(context, isDark) / 100f));
    }

    private void applyOverlay(View overlayView, boolean isDark) {
        int alpha = isDark ? 100 : 80;
        overlayView.setBackgroundColor(isDark ? Color.argb(alpha, 0, 0, 0) : Color.argb(alpha, 255, 255, 255));
        overlayView.setVisibility(View.VISIBLE);
    }

    public void refreshBackgroundEffects(Activity activity) {
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        boolean isDark = isDarkMode(activity);
        View baseView = rootView.findViewWithTag("personalization_base");
        if (baseView != null) applyBaseLayer(baseView, isDark);
        View overlayView = rootView.findViewWithTag("personalization_overlay");
        if (overlayView != null) applyOverlay(overlayView, isDark);
        ImageView bgView = findBackgroundPoster(rootView);
        if (bgView != null) refreshBackgroundImageView(bgView);
        LauncherBackgroundController.refreshEffects();
    }

    public void refreshBackgroundColorEffects(Activity activity) {
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        ImageView bgView = findBackgroundPoster(rootView);
        if (bgView != null) applyBackgroundImageEffects(bgView);
        LauncherBackgroundController.refreshEffects();
    }

    private ImageView findBackgroundPoster(ViewGroup rootView) {
        View background = rootView.findViewWithTag("personalization_bg");
        if (background instanceof ImageView) return (ImageView) background;
        return rootView.findViewWithTag("personalization_poster");
    }

    public boolean supportsRealtimeBackgroundBlur() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public boolean applyBackgroundImageToView(ImageView bgView) {
        if (hasAnimatedBackground() && LauncherBackgroundController.isMinecraftBlocked()) return false;
        String path = prefs.getString(KEY_BG_POSTER_PATH, getBackgroundImagePath());
        int blur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canBlurBackground()
                ? getBackgroundImageBlur() : 0;
        String contentKey = path + ":" + blur;
        if (contentKey.equals(bgView.getTag(R.id.bg_image_preview)) && bgView.getDrawable() != null) {
            applyBackgroundImageEffects(bgView);
            return true;
        }
        Bitmap bmp = loadBackgroundBitmap();
        if (bmp == null) return false;

        Bitmap displayBitmap = bmp;
        if (blur > 0) {
            Bitmap blurredBitmap = createBlurredBitmap(bmp, blur);
            if (blurredBitmap != null) {
                displayBitmap = blurredBitmap;
                if (displayBitmap != bmp) {
                    bmp.recycle();
                }
            }
        }

        bgView.setImageBitmap(displayBitmap);
        bgView.setTag(R.id.bg_image_preview, contentKey);
        applyBackgroundImageEffects(bgView);
        return true;
    }

    public void refreshBackgroundImageView(ImageView bgView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            applyBackgroundImageToView(bgView);
        } else {
            applyBackgroundImageEffects(bgView);
        }
    }

    public void applyBackgroundImageEffects(ImageView bgView) {
        applyFitMode(bgView);
        bgView.setAlpha(getBackgroundOpacity() / 100f);
        int blurRadius = getBackgroundImageBlur();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blurRadius > 0) {
                bgView.setRenderEffect(RenderEffect.createBlurEffect(
                        blurRadius, blurRadius, Shader.TileMode.CLAMP));
            } else {
                bgView.setRenderEffect(null);
            }
        }

        float brightness = getBackgroundImageBrightness() / 100f;
        if (Math.abs(brightness - 1f) < 0.001f) {
            bgView.clearColorFilter();
            return;
        }

        ColorMatrix matrix = new ColorMatrix(new float[]{
                brightness, 0, 0, 0, 0,
                0, brightness, 0, 0, 0,
                0, 0, brightness, 0, 0,
                0, 0, 0, 1, 0
        });
        bgView.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    /** Windows fit modes: smart (cover), center, fit (contain), stretch, tile, top left, top right. */
    private void applyFitMode(ImageView bgView) {
        String fit = getBackgroundFitMode();
        Drawable d = bgView.getDrawable();
        if (FIT_TILE.equals(fit) && d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.drawable.BitmapDrawable tile = new android.graphics.drawable.BitmapDrawable(
                    bgView.getResources(), ((android.graphics.drawable.BitmapDrawable) d).getBitmap());
            tile.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            bgView.setBackground(tile);
            bgView.setImageAlpha(0);
            return;
        }
        if (bgView.getBackground() instanceof android.graphics.drawable.BitmapDrawable) bgView.setBackground(null);
        bgView.setImageAlpha(255);
        switch (fit) {
            case FIT_CENTER:
                bgView.setScaleType(ImageView.ScaleType.CENTER);
                break;
            case FIT_FIT:
                bgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                break;
            case FIT_STRETCH:
                bgView.setScaleType(ImageView.ScaleType.FIT_XY);
                break;
            case FIT_TOP_LEFT:
            case FIT_TOP_RIGHT:
                bgView.setScaleType(ImageView.ScaleType.MATRIX);
                bgView.post(() -> {
                    Drawable drawable = bgView.getDrawable();
                    if (drawable == null) return;
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    if (FIT_TOP_RIGHT.equals(getBackgroundFitMode())) {
                        m.setTranslate(bgView.getWidth() - drawable.getIntrinsicWidth(), 0);
                    }
                    bgView.setImageMatrix(m);
                });
                break;
            default:
                bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
    }

    private Bitmap createBlurredBitmap(Bitmap source, int radius) {
        if (source == null || radius <= 0) return source;
        try {
            int maxDim = 720;
            int width = source.getWidth();
            int height = source.getHeight();
            float scale = Math.min(1f, (float) maxDim / Math.max(width, height));
            int scaledWidth = Math.max(1, Math.round(width * scale));
            int scaledHeight = Math.max(1, Math.round(height * scale));
            Bitmap working = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
                    .copy(Bitmap.Config.ARGB_8888, true);

            int w = working.getWidth();
            int h = working.getHeight();
            int[] pixels = new int[w * h];
            int[] temp = new int[w * h];
            int[] output = new int[w * h];
            working.getPixels(pixels, 0, w, 0, 0, w, h);

            int windowSize = radius * 2 + 1;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int i = -radius; i <= radius; i++) {
                    int color = pixels[row + clamp(i, 0, w - 1)];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                for (int x = 0; x < w; x++) {
                    temp[row + x] = Color.argb(a / windowSize, r / windowSize, g / windowSize, b / windowSize);

                    int removeColor = pixels[row + clamp(x - radius, 0, w - 1)];
                    int addColor = pixels[row + clamp(x + radius + 1, 0, w - 1)];
                    a += Color.alpha(addColor) - Color.alpha(removeColor);
                    r += Color.red(addColor) - Color.red(removeColor);
                    g += Color.green(addColor) - Color.green(removeColor);
                    b += Color.blue(addColor) - Color.blue(removeColor);
                }
            }

            for (int x = 0; x < w; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int i = -radius; i <= radius; i++) {
                    int color = temp[clamp(i, 0, h - 1) * w + x];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                for (int y = 0; y < h; y++) {
                    output[y * w + x] = Color.argb(a / windowSize, r / windowSize, g / windowSize, b / windowSize);

                    int removeColor = temp[clamp(y - radius, 0, h - 1) * w + x];
                    int addColor = temp[clamp(y + radius + 1, 0, h - 1) * w + x];
                    a += Color.alpha(addColor) - Color.alpha(removeColor);
                    r += Color.red(addColor) - Color.red(removeColor);
                    g += Color.green(addColor) - Color.green(removeColor);
                    b += Color.blue(addColor) - Color.blue(removeColor);
                }
            }

            working.setPixels(output, 0, w, 0, 0, w, h);
            return working;
        } catch (Exception e) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getGlassColor(boolean isDark) {
        // A fully transparent wallpaper counts as no wallpaper (Windows): solid surfaces.
        if (getBackgroundOpacity() == 0) return isDark ? 0xFF151518 : 0xFFF1F8E9;
        int alpha = 50;
        return isDark ? Color.argb(alpha, 25, 25, 25) : Color.argb(alpha, 255, 255, 255);
    }

    private int getWallpaperSurfaceColor() {
        return getGlassColor(isDarkActive(context));
    }

    public int getChromeGlassColor(Context ctx) {
        boolean isDark = isDarkMode(ctx);
        if (getBackgroundOpacity() == 0) return ContextCompat.getColor(ctx, R.color.ui_chrome);
        int alpha = isDark ? 90 : 110;
        return isDark ? Color.argb(alpha, 25, 25, 25) : Color.argb(alpha, 255, 255, 255);
    }

    private void makeChildBackgroundsTranslucent(ViewGroup parent, int glassColor) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if ("personalization_bg".equals(child.getTag()) || "personalization_overlay".equals(child.getTag())
                    || "personalization_base".equals(child.getTag())) continue;
            applyGlassToView(child, glassColor);
            if (child instanceof ViewGroup) {
                makeChildBackgroundsTranslucent((ViewGroup) child, glassColor);
            }
        }
    }

    private boolean isNearBlack(int color) {
        return Color.red(color) < 20 && Color.green(color) < 20 && Color.blue(color) < 20 && Color.alpha(color) > 200;
    }

    private boolean isNearWhite(int color) {
        return Color.red(color) > 235 && Color.green(color) > 235 && Color.blue(color) > 235 && Color.alpha(color) > 200;
    }

    public void applyAccentColorRecursive(View view, int accentColor, Activity activity) {
        applyAccentColorRecursive(view, accentColor, (Context) activity);
    }

    public void applyAccentColorRecursive(View view, int accentColor, Context ctx) {
        int defaultPrimary = ContextCompat.getColor(ctx, R.color.primary);
        int defaultSecondary = ContextCompat.getColor(ctx, R.color.secondary);
        int defaultTertiary = ContextCompat.getColor(ctx, R.color.tertiary);
        int defaultAccentText = ContextCompat.getColor(ctx, R.color.accent_text);

        if (view instanceof com.google.android.material.switchmaterial.SwitchMaterial) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    (com.google.android.material.switchmaterial.SwitchMaterial) view;
            try {
                applySwitchColors(sw, accentColor, ctx);
            } catch (Exception ignored) {}
        }

        if (view instanceof Switch && !(view instanceof com.google.android.material.switchmaterial.SwitchMaterial)) {
            Switch sw = (Switch) view;
            try {
                applySwitchColors(sw, accentColor, ctx);
            } catch (Exception ignored) {}
        }

        if (view instanceof androidx.appcompat.widget.SwitchCompat && !(view instanceof com.google.android.material.switchmaterial.SwitchMaterial)) {
            androidx.appcompat.widget.SwitchCompat sw = (androidx.appcompat.widget.SwitchCompat) view;
            try {
                applySwitchColors(sw, accentColor, ctx);
            } catch (Exception ignored) {}
        }

        if (view instanceof SeekBar) {
            SeekBar sb = (SeekBar) view;
            try {
                sb.setProgressTintList(ColorStateList.valueOf(accentColor));
                sb.setThumbTintList(ColorStateList.valueOf(accentColor));
            } catch (Exception ignored) {}
        }

        if (view instanceof ProgressBar) {
            ProgressBar pb = (ProgressBar) view;
            try {
                pb.setProgressTintList(ColorStateList.valueOf(accentColor));
                pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))));
                pb.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
            } catch (Exception ignored) {}
        }

        if (view instanceof Button) {
            Button btn = (Button) view;
            try {
                if (btn.getBackgroundTintList() != null) {
                    int currentTint = btn.getBackgroundTintList().getDefaultColor();
                    if (currentTint == defaultPrimary || currentTint == defaultSecondary || currentTint == defaultTertiary) {
                        int label = ThemePalette.onAccent(accentColor);
                        btn.setBackgroundTintList(ColorStateList.valueOf(accentColor));
                        btn.setTextColor(label);
                        if (btn instanceof com.google.android.material.button.MaterialButton) {
                            ((com.google.android.material.button.MaterialButton) btn)
                                    .setIconTint(ColorStateList.valueOf(label));
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (btn.getCurrentTextColor() == defaultPrimary || btn.getCurrentTextColor() == defaultAccentText) {
                int fg = ThemePalette.foreground(accentColor, isDarkMode(ctx));
                btn.setTextColor(fg);
                if (btn instanceof com.google.android.material.button.MaterialButton) {
                    com.google.android.material.button.MaterialButton mb =
                            (com.google.android.material.button.MaterialButton) btn;
                    mb.setIconTint(ColorStateList.valueOf(fg));
                    if (mb.getStrokeColor() != null && mb.getStrokeColor().getDefaultColor() == defaultPrimary) {
                        mb.setStrokeColor(ColorStateList.valueOf(accentColor));
                    }
                }
            }

            Drawable bg = btn.getBackground();
            if (bg instanceof GradientDrawable) {
                GradientDrawable gd = (GradientDrawable) bg;
                try {
                    if (gd.getColor() != null) {
                        int gdColor = gd.getColor().getDefaultColor();
                        if (gdColor == defaultPrimary || gdColor == defaultSecondary || gdColor == defaultTertiary) {
                            gd.setColor(accentColor);
                            btn.setTextColor(Color.WHITE);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        if (view instanceof TextView && !(view instanceof Button)) {
            TextView tv = (TextView) view;
            int textColor = tv.getCurrentTextColor();

            if (textColor == defaultPrimary) {
                tv.setTextColor(accentColor);
            } else if (textColor == defaultAccentText) {
                // Accent text and page titles use the readable variant (Windows brand-foreground/heading).
                boolean dark = isDarkMode(ctx);
                float sp = tv.getTextSize() / ctx.getResources().getDisplayMetrics().scaledDensity;
                tv.setTextColor(sp >= 20 ? ThemePalette.heading(accentColor, dark)
                        : ThemePalette.foreground(accentColor, dark));
            }

            try {
                if (tv.getCompoundDrawableTintList() != null) {
                    int tintColor = tv.getCompoundDrawableTintList().getDefaultColor();
                    if (tintColor == defaultPrimary) {
                        tv.setCompoundDrawableTintList(ColorStateList.valueOf(accentColor));
                    }
                }
            } catch (Exception ignored) {}
        }

        if (view instanceof ImageView && !(view instanceof Button)) {
            ImageView iv = (ImageView) view;
            if (iv.getImageTintList() != null) {
                int tint = iv.getImageTintList().getDefaultColor();
                if (tint == defaultPrimary) {
                    iv.setImageTintList(ColorStateList.valueOf(accentColor));
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAccentColorRecursive(group.getChildAt(i), accentColor, ctx);
            }
        }
    }

    /** Windows (HeroUI) switch: white thumb, theme-color track when on, grey track when off. */
    private void applySwitchColors(android.widget.CompoundButton sw, int accentColor, Context ctx) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        int off = isDarkMode(ctx) ? 0xFF52525B : 0xFFD4D4D8;
        ColorStateList thumb = new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
        ColorStateList track = new ColorStateList(states, new int[]{accentColor, off});
        if (sw instanceof androidx.appcompat.widget.SwitchCompat) {
            androidx.appcompat.widget.SwitchCompat c = (androidx.appcompat.widget.SwitchCompat) sw;
            c.setThumbTintList(thumb);
            c.setTrackTintList(track);
            c.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        } else if (sw instanceof Switch) {
            Switch c = (Switch) sw;
            c.setThumbTintList(thumb);
            c.setTrackTintList(track);
            c.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    /** Glass pass for views inflated after the page was set up (fragments, late content). */
    public void applyGlassToTree(View root, Activity activity) {
        if (!hasBackgroundImage() || LauncherBackgroundController.isMinecraftBlocked()) return;
        int glassColor = getWallpaperSurfaceColor();
        applyGlassToView(root, glassColor);
        if (root instanceof ViewGroup) makeChildBackgroundsTranslucent((ViewGroup) root, glassColor);
    }

    public void applyAccentToView(View view, Context context) {
        int accent = getAccentColor();
        if (accent == 0) return;
        applyAccentColorRecursive(view, accent, context);
    }

    private boolean isDarkMode(Activity activity) {
        return isDarkMode((Context) activity);
    }

    public boolean isDarkMode(Context ctx) {
        if (!(ctx instanceof Activity)) return isDarkActive(ctx);
        int nightModeFlags = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public int getEffectiveSurfaceColor() {
        if (hasBackgroundImage()) {
            return getWallpaperSurfaceColor();
        }
        return ContextCompat.getColor(context, R.color.surface);
    }

    public void applyGlassToView(View view) {
        if (!hasBackgroundImage()) return;
        applyGlassToView(view, getWallpaperSurfaceColor());
    }

    private void applyGlassToView(View view, int glassColor) {
        if (TAG_KEEP_BACKGROUND.equals(view.getTag())) return;
        Context ctx = view.getContext();
        if (view instanceof CardView) {
            CardView card = (CardView) view;
            int color = card.getCardBackgroundColor().getDefaultColor();
            if (isSurfaceColor(color, ctx)) {
                card.setCardBackgroundColor(glassColor);
            }
            return;
        }

        ColorStateList tint = view.getBackgroundTintList();
        if (tint != null) {
            if (isSurfaceColor(tint.getDefaultColor(), ctx)) {
                view.setBackgroundTintList(ColorStateList.valueOf(glassColor));
            }
            return;
        }
        Drawable background = view.getBackground();
        if (background != null) applyGlassToDrawable(background.mutate(), ctx, glassColor);
    }

    private boolean isSurfaceColor(int color, Context ctx) {
        if (Color.alpha(color) <= 200) {
            return color == ContextCompat.getColor(ctx, R.color.ui_card_surface);
        }
        return color == ContextCompat.getColor(ctx, R.color.background)
                || color == ContextCompat.getColor(ctx, R.color.surface)
                || color == ContextCompat.getColor(ctx, R.color.surface_variant)
                || color == ContextCompat.getColor(ctx, R.color.ui_chrome)
                || color == ContextCompat.getColor(ctx, R.color.ui_default)
                || color == ContextCompat.getColor(ctx, R.color.ui_input)
                || color == ContextCompat.getColor(ctx, R.color.ui_strip)
                || isNearBlack(color) || isNearWhite(color);
    }

    private void applyGlassToDrawable(Drawable drawable, Context ctx, int glassColor) {
        if (drawable instanceof ColorDrawable) {
            ColorDrawable colorDrawable = (ColorDrawable) drawable;
            int color = colorDrawable.getColor();
            if (isSurfaceColor(color, ctx)) {
                colorDrawable.setColor(color == ContextCompat.getColor(ctx, R.color.background) || isNearBlack(color)
                        ? Color.TRANSPARENT : glassColor);
            }
        } else if (drawable instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) drawable;
            ColorStateList colors = gradient.getColor();
            if (colors != null && isSurfaceColor(colors.getDefaultColor(), ctx)) {
                gradient.setColor(glassColor);
            }
        } else if (drawable instanceof StateListDrawable) {
            StateListDrawable states = (StateListDrawable) drawable;
            for (int i = 0; i < states.getStateCount(); i++) {
                Drawable state = states.getStateDrawable(i);
                if (state != null) applyGlassToDrawable(state, ctx, glassColor);
            }
            states.invalidateSelf();
        } else if (drawable instanceof InsetDrawable) {
            Drawable inset = ((InsetDrawable) drawable).getDrawable();
            if (inset != null) applyGlassToDrawable(inset, ctx, glassColor);
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (layers.getId(i) != android.R.id.mask) {
                    applyGlassToDrawable(layers.getDrawable(i), ctx, glassColor);
                }
            }
        } else if (drawable instanceof com.google.android.material.shape.MaterialShapeDrawable) {
            com.google.android.material.shape.MaterialShapeDrawable shape =
                    (com.google.android.material.shape.MaterialShapeDrawable) drawable;
            ColorStateList fill = shape.getFillColor();
            if (fill != null && isSurfaceColor(fill.getDefaultColor(), ctx)) {
                shape.setFillColor(ColorStateList.valueOf(glassColor));
            }
        }
    }

    private float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    public static final int[] PRESET_COLORS = ThemePalette.PRESETS;

    public static final int[] MORE_COLORS = ThemePalette.MORE;
}
