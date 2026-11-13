package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import org.levimc.launcher.ui.widget.UiKit;

final class DialogBackdrop {
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private State state;

    private DialogBackdrop(State state) { this.state = state; }

    static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    static DialogBackdrop acquire(Context context) {
        Activity activity = activity(context);
        View root = activity == null ? null : activity.findViewById(android.R.id.content);
        if (root == null) return new DialogBackdrop(null);
        State state = STATES.get(root);
        if (state == null) { state = new State(root); STATES.put(root, state); }
        state.references++;
        if (state.references == 1) state.apply();
        return new DialogBackdrop(state);
    }

    void release() {
        State current = state;
        state = null;
        if (current == null || --current.references != 0) return;
        View root = current.root.get();
        current.clear();
        if (root != null) STATES.remove(root);
    }

    private static final class State {
        final WeakReference<View> root;
        int references;
        BitmapDrawable snapshot;
        ViewTreeObserver.OnPreDrawListener pending;
        State(View root) { this.root = new WeakReference<>(root); }

        void apply() {
            View view = root.get();
            if (view == null) return;
            if (Build.VERSION.SDK_INT >= 31) { Api31.blur(view); return; }
            if (view.getWidth() > 0 && view.getHeight() > 0) { capture(); return; }
            pending = () -> {
                View current = root.get();
                if (current != null && current.getWidth() > 0 && current.getHeight() > 0) {
                    current.getViewTreeObserver().removeOnPreDrawListener(pending);
                    pending = null;
                    capture();
                }
                return true;
            };
            view.getViewTreeObserver().addOnPreDrawListener(pending);
        }

        void capture() {
            View view = root.get();
            if (view == null || references == 0) return;
            int width = Math.max(1, view.getWidth() / 10);
            int height = Math.max(1, view.getHeight() / 10);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(UiKit.isDark(view.getContext()) ? 0xFF121416 : Color.WHITE);
            canvas.scale((float) width / view.getWidth(), (float) height / view.getHeight());
            view.draw(canvas);
            int[] pixels = new int[width * height];
            int[] buffer = new int[pixels.length];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            pass(pixels, buffer, width, height, true);
            pass(buffer, pixels, width, height, false);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            snapshot = new BitmapDrawable(view.getResources(), bitmap);
            snapshot.setFilterBitmap(true);
            snapshot.setBounds(0, 0, view.getWidth(), view.getHeight());
            view.getOverlay().add(snapshot);
        }

        void clear() {
            View view = root.get();
            if (view != null) {
                if (pending != null && view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnPreDrawListener(pending);
                if (Build.VERSION.SDK_INT >= 31) Api31.clear(view);
                if (snapshot != null) view.getOverlay().remove(snapshot);
            }
            pending = null;
            snapshot = null;
        }
    }

    private static void pass(int[] input, int[] output, int width, int height, boolean horizontal) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int red = 0, green = 0, blue = 0;
            for (int offset = -3; offset <= 3; offset++) {
                int px = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                int py = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                int color = input[py * width + px];
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color);
            }
            output[y * width + x] = Color.rgb(red / 7, green / 7, blue / 7);
        }
    }

    private static final class Api31 {
        static void blur(View view) { float radius = 12 * view.getResources().getDisplayMetrics().density; view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)); }
        static void clear(View view) { view.setRenderEffect(null); }
    }
}
