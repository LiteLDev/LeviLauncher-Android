package org.levimc.launcher.ui.widget;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import org.levimc.launcher.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CutoutAvoider {
    private CutoutAvoider() {
    }

    /** Distance kept between the camera and a control. */
    private static final float GAP_DP = 4f;
    /**
     * Pages keep their content this far from their edge (16dp page padding + card padding), so the
     * camera only needs extra room for the part that reaches further in.
     */
    private static final float PAGE_CLEARANCE_DP = 30f;

    public static void attachToShell(View shell) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        View bar = shell.findViewById(R.id.nav_bar_root);
        View content = shell.findViewById(R.id.shell_content);
        Base base = new Base(bar, content);
        String[] applied = {null};
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            List<Rect> holes = holes(insets);
            // Insets are re-dispatched often (system bars, focus); recompute only when the camera moved.
            String signature = holes.toString();
            if (signature.equals(applied[0])) return insets;
            applied[0] = signature;
            base.restore();
            beforeNextDraw(v, () -> {
                float density = v.getResources().getDisplayMetrics().density;
                int gap = Math.round(GAP_DP * density);
                boolean changed = false;
                for (Rect hole : holes) {
                    boolean left = hole.centerX() < v.getWidth() / 2;
                    if (bar != null) changed |= avoidInBar(bar, hole, left, gap);
                    if (content != null) changed |= avoidInPage(content, hole, left, gap, Math.round(PAGE_CLEARANCE_DP * density));
                }
                return changed;
            });
            return insets;
        });
        shell.requestApplyInsets();
    }

    /** For full-screen pages without the shell: only the page's own margin on the camera side grows. */
    public static void attachToPage(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Base base = new Base(null, root);
        String[] applied = {null};
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            List<Rect> holes = holes(insets);
            String signature = holes.toString();
            if (signature.equals(applied[0])) return insets;
            applied[0] = signature;
            base.restore();
            beforeNextDraw(v, () -> {
                int gap = Math.round(GAP_DP * v.getResources().getDisplayMetrics().density);
                boolean changed = false;
                for (Rect hole : holes) {
                    changed |= avoidInPage(v, hole, hole.centerX() < v.getWidth() / 2, gap, 0);
                }
                return changed;
            });
            return insets;
        });
        root.requestApplyInsets();
    }

    public static void attachToControls(View root, View... controls) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        Base base = new Base(controls);
        java.util.concurrent.atomic.AtomicReference<List<Rect>> currentHoles =
                new java.util.concurrent.atomic.AtomicReference<>(new ArrayList<>());
        String[] applied = {null};
        Runnable update = () -> {
            if (!root.isAttachedToWindow() || root.getWidth() == 0) return;
            StringBuilder signature = new StringBuilder(currentHoles.get().toString());
            for (View control : controls) {
                if (control != null) signature.append(control.isShown()).append(windowRect(control));
            }
            String next = signature.toString();
            if (next.equals(applied[0])) return;
            applied[0] = next;
            base.restore();
            beforeNextDraw(root, () -> {
                int gap = Math.round(GAP_DP * root.getResources().getDisplayMetrics().density);
                boolean changed = false;
                for (View control : controls) {
                    if (control == null || !control.isShown() || control.getWidth() == 0) continue;
                    for (Rect hole : currentHoles.get()) {
                        changed |= avoidInPage(control, hole, hole.centerX() < root.getWidth() / 2, gap, 0);
                    }
                }
                return changed;
            });
        };
        android.view.ViewTreeObserver.OnGlobalLayoutListener layout = update::run;
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
            }
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            currentHoles.set(holes(insets));
            applied[0] = null;
            update.run();
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * Runs {@code action} once the restored (unadjusted) layout is laid out, before it is drawn. When the
     * action moved something, that draw is skipped, so the first frame shown already avoids the camera.
     */
    private static void beforeNextDraw(View v, java.util.function.BooleanSupplier action) {
        v.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                return !action.getAsBoolean();
            }
        });
        v.requestLayout();
    }

    /** Camera areas in window coordinates, tightened to the real outline where the system gives one. */
    static List<Rect> holes(WindowInsets insets) {
        List<Rect> out = new ArrayList<>();
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null) return out;
        Path path = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? cutout.getCutoutPath() : null;
        for (Rect r : cutout.getBoundingRects()) {
            if (r.isEmpty()) continue;
            Rect hole = new Rect(r);
            if (path != null) {
                Region outline = new Region();
                if (outline.setPath(path, new Region(r)) && !outline.isEmpty()) hole = outline.getBounds();
            }
            out.add(hole);
        }
        return out;
    }

    private static Rect windowRect(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }

    private static boolean overlapsVertically(Rect a, Rect b, int gap) {
        return a.top < b.bottom + gap && a.bottom + gap > b.top;
    }

    /** Moves the bar's items aside only when the camera reaches into the bar; its background stays put. */
    private static boolean avoidInBar(View bar, Rect hole, boolean left, int gap) {
        Rect b = windowRect(bar);
        if (!overlapsVertically(hole, b, 0) || !Rect.intersects(hole, b)) return false;
        if (left) {
            int need = hole.right + gap - (b.left + bar.getPaddingLeft());
            if (need <= 0) return false;
            bar.setPadding(bar.getPaddingLeft() + need, bar.getPaddingTop(), bar.getPaddingRight(), bar.getPaddingBottom());
        } else {
            int need = (b.right - bar.getPaddingRight()) - (hole.left - gap);
            if (need <= 0) return false;
            bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(), bar.getPaddingRight() + need, bar.getPaddingBottom());
        }
        return true;
    }

    /** Grows the page's margin on the camera side by the part of the camera beyond its clearance. */
    private static boolean avoidInPage(View page, Rect hole, boolean left, int gap, int clearance) {
        Rect p = windowRect(page);
        if (!overlapsVertically(hole, p, 0)) return false;
        if (left) {
            int need = hole.right + gap - (p.left + page.getPaddingLeft() + clearance);
            if (need <= 0) return false;
            page.setPadding(page.getPaddingLeft() + need, page.getPaddingTop(), page.getPaddingRight(), page.getPaddingBottom());
        } else {
            int need = (p.right - page.getPaddingRight() - clearance) - (hole.left - gap);
            if (need <= 0) return false;
            page.setPadding(page.getPaddingLeft(), page.getPaddingTop(), page.getPaddingRight() + need, page.getPaddingBottom());
        }
        return true;
    }

    private static final class Base {
        private final Map<View, int[]> paddings = new HashMap<>();

        Base(@Nullable View bar, @Nullable View content) {
            this(new View[]{bar, content});
        }

        Base(View... views) {
            for (View v : views) {
                if (v != null) paddings.put(v, new int[]{v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()});
            }
        }

        void restore() {
            for (Map.Entry<View, int[]> e : paddings.entrySet()) {
                int[] p = e.getValue();
                e.getKey().setPadding(p[0], p[1], p[2], p[3]);
            }
        }
    }
}
