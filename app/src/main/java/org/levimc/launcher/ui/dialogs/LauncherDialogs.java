package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.ThemePalette;

public final class LauncherDialogs {
    private static final WeakHashMap<Dialog, Binding> BINDINGS = new WeakHashMap<>();
    private LauncherDialogs() {}

    public static void bind(Dialog dialog, int maxWidthDp) {
        bind(dialog, maxWidthDp, true);
    }

    public static void bind(Dialog dialog, int maxWidthDp, boolean blur) {
        if (BINDINGS.containsKey(dialog) || dialog.getWindow() == null) return;
        Binding binding = new Binding(dialog, maxWidthDp, blur);
        BINDINGS.put(dialog, binding);
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(binding);
    }

    public static void style(Dialog dialog, int maxWidthDp) {
        bind(dialog, maxWidthDp);
        Binding binding = BINDINGS.get(dialog);
        if (binding != null) binding.apply();
    }

    public static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    public static int surface(Context context) { return UiKit.isDark(context) ? 0xFF17191D : Color.WHITE; }
    public static int raised(Context context) { return UiKit.isDark(context) ? 0xFF25292F : 0xFFF3F5F6; }
    public static int text(Context context) { return UiKit.isDark(context) ? 0xFFF4F4F5 : 0xFF202329; }
    public static int secondary(Context context) { return UiKit.isDark(context) ? 0xFFB5BEC8 : 0xFF59636E; }
    public static int border(Context context) { return UiKit.isDark(context) ? 0xFF3B424B : 0xFFDCE1E6; }

    public static GradientDrawable shape(Context context, int color, int radius, boolean stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (stroke) shape.setStroke(dp(context, 1), border(context));
        return shape;
    }

    public static void button(Button button, boolean primary) {
        Context context = button.getContext();
        int accent = UiKit.accent(context);
        for (int step = 0; step < 20 && ThemePalette.contrast(accent, Color.WHITE) < 4.5; step++) accent = ThemePalette.mix(accent, Color.BLACK, 0.07f);
        int fill = primary ? accent : raised(context);
        int label = primary ? Color.WHITE : text(context);
        int[][] states = {{android.R.attr.state_enabled}, {}};
        ColorStateList fills = new ColorStateList(states, new int[]{fill, ThemePalette.withAlpha(fill, 0.35f)});
        if (button instanceof MaterialButton) {
            MaterialButton material = (MaterialButton) button;
            material.setShapeAppearanceModel(material.getShapeAppearanceModel().toBuilder().setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dp(context, 10)).build());
            material.setCornerRadius(dp(context, 10));
            material.setStrokeWidth(primary ? 0 : dp(context, 1));
            material.setStrokeColor(ColorStateList.valueOf(border(context)));
            material.setInsetTop(0); material.setInsetBottom(0);
            material.setIconTint(ColorStateList.valueOf(label));
        } else {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x28FFFFFF : 0x20808080),
                    shape(context, fill, 10, !primary), shape(context, Color.WHITE, 10, false)));
        }
        button.setBackgroundTintList(fills);
        button.setTextColor(new ColorStateList(states, new int[]{label, ThemePalette.withAlpha(label, 0.45f)}));
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        button.setEllipsize(null);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setStateListAnimator(null);
        ViewGroup.LayoutParams layout = button.getLayoutParams();
        if (layout != null && layout.height > 0 && layout.height < dp(context, 48)) { layout.height = ViewGroup.LayoutParams.WRAP_CONTENT; button.setLayoutParams(layout); }
    }

    public static void styleContent(View view) {
        Context context = view.getContext();
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTextSize(14); input.setTextColor(text(context)); input.setHintTextColor(secondary(context));
            input.setMinHeight(dp(context, 44));
            if (!insideTextInput(input)) {
                input.setBackground(shape(context, raised(context), 10, true));
                input.setBackgroundTintList(null);
                input.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            }
        } else if (view instanceof TextInputLayout) {
            TextInputLayout input = (TextInputLayout) view;
            input.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            input.setBoxCornerRadii(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
            input.setBoxStrokeColor(UiKit.accent(context));
            input.setDefaultHintTextColor(ColorStateList.valueOf(secondary(context)));
        } else if (view instanceof TextView && !(view instanceof Button)) {
            TextView label = (TextView) view;
            String name = resourceName(view);
            boolean title = name.equals("tv_title") || name.equals("title") || name.endsWith("_title") || name.equals("alertTitle");
            float size = label.getTextSize() / context.getResources().getDisplayMetrics().scaledDensity;
            if (title) {
                label.setTextSize(17); label.setTextColor(text(context));
                label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else if (size > 14) label.setTextSize(13.5f);
            label.setIncludeFontPadding(false);
            if (name.equals("message") || name.equals("tv_message")) label.setTextColor(secondary(context));
        }
        if (view instanceof CompoundButton) ((CompoundButton) view).setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{UiKit.accent(context), secondary(context)}));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) styleContent(((ViewGroup) view).getChildAt(i));
    }

    private static boolean insideTextInput(View view) {
        android.view.ViewParent parent = view.getParent();
        for (int i = 0; i < 3 && parent != null; i++, parent = parent.getParent()) if (parent instanceof TextInputLayout) return true;
        return false;
    }

    private static String resourceName(View view) {
        if (view.getId() <= 0) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static final class Binding implements View.OnAttachStateChangeListener {
        final WeakReference<Dialog> dialog;
        final int maxWidth;
        final boolean blur;
        DialogBackdrop backdrop;
        boolean decorated;
        Binding(Dialog dialog, int width, boolean blur) { this.dialog = new WeakReference<>(dialog); maxWidth = width; this.blur = blur; }
        @Override public void onViewAttachedToWindow(View view) { view.post(this::apply); }
        @Override public void onViewDetachedFromWindow(View view) { if (backdrop != null) backdrop.release(); backdrop = null; }

        void apply() {
            Dialog current = dialog.get();
            if (current == null || !current.isShowing() || current.getWindow() == null) return;
            Context context = current.getContext();
            Window window = current.getWindow();
            ViewGroup content = current.findViewById(android.R.id.content);
            if (!decorated && content != null && content.getChildCount() > 0) {
                View root = content.getChildAt(0);
                boolean alert = current instanceof AlertDialog;
                window.setBackgroundDrawable(alert ? shape(context, surface(context), 18, true) : new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                if (!alert) {
                    root.setBackground(shape(context, surface(context), 18, true));
                    if (!(root instanceof LauncherDialogLayout)) {
                        ViewGroup.LayoutParams previous = root.getLayoutParams();
                        content.removeView(root);
                        LauncherDialogLayout frame = new LauncherDialogLayout(context);
                        frame.setOrientation(LinearLayout.VERTICAL);
                        frame.addView(root, new LinearLayout.LayoutParams(-1, -2, 1));
                        content.addView(frame, previous);
                        root.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 14));
                    }
                }
                styleContent(root);
                if (!(current instanceof CustomAlertDialog)) styleButtons(root);
                decorated = true;
            }
            WindowManager.LayoutParams params = window.getAttributes();
            Rect visible = new Rect();
            window.getDecorView().getWindowVisibleDisplayFrame(visible);
            int width = visible.width() > 0 ? visible.width() : context.getResources().getDisplayMetrics().widthPixels;
            if (params.width <= 0) params.width = Math.min(width - dp(context, 32), dp(context, maxWidth));
            params.gravity = Gravity.CENTER; params.x = 0; params.y = 0;
            params.horizontalMargin = 0; params.verticalMargin = 0; params.dimAmount = 0.5f;
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            if (hasInput(content)) params.flags &= ~WindowManager.LayoutParams.FLAG_FULLSCREEN;
            else params.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            window.setAttributes(params);
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            if (blur && backdrop == null) backdrop = DialogBackdrop.acquire(context);
            if (current instanceof AlertDialog) {
                AlertDialog alert = (AlertDialog) current;
                for (int id : new int[]{Dialog.BUTTON_NEGATIVE, Dialog.BUTTON_NEUTRAL, Dialog.BUTTON_POSITIVE}) {
                    Button button = alert.getButton(id);
                    if (button != null) button(button, id == Dialog.BUTTON_POSITIVE);
                }
            }
        }

        private boolean hasInput(View view) {
            if (view instanceof EditText) return true;
            if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasInput(((ViewGroup) view).getChildAt(i))) return true;
            return false;
        }

        private void styleButtons(View view) {
            if (view instanceof Button && !(view instanceof CompoundButton)) {
                String name = resourceName(view);
                boolean primary = name.equals("btn_install") || name.contains("btn_primary") || name.contains("btn_main") || name.contains("btn_positive");
                button((Button) view, primary);
            }
            if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) styleButtons(((ViewGroup) view).getChildAt(i));
        }
    }
}
