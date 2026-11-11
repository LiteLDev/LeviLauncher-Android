package org.levimc.launcher.ui.animation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

public final class DynamicAnim {
    private DynamicAnim() {}

    private static final long DIALOG_DISMISS_DURATION_MS = 160L;

    public static SpringAnimation springAlphaTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.ALPHA, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        return anim;
    }

    public static SpringAnimation springTranslationYTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        return anim;
    }

    public static SpringAnimation springTranslationXTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.TRANSLATION_X, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        return anim;
    }

    public static SpringAnimation springScaleXTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.SCALE_X, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        return anim;
    }

    public static SpringAnimation springScaleYTo(View view, float target) {
        SpringAnimation anim = new SpringAnimation(view, DynamicAnimation.SCALE_Y, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        return anim;
    }

    /**
     * Apply press-scale feedback to a view. Does not consume touch, keeping click works.
     */
    public static void applyPressScale(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    springScaleXTo(v, 0.96f).start();
                    springScaleYTo(v, 0.96f).start();
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    springScaleXTo(v, 1f).start();
                    springScaleYTo(v, 1f).start();
                    break;
                }
            }
            return false;
        });
    }

    /**
     * Dialog enter: alpha 0->1 and scale 0.94->1.
     */
    public static void animateDialogShow(View root) {
        if (root == null) return;
        root.setAlpha(0f);
        root.setScaleX(0.94f);
        root.setScaleY(0.94f);
        springAlphaTo(root, 1f).start();
        springScaleXTo(root, 1f).start();
        springScaleYTo(root, 1f).start();
    }

    /**
     * Dialog dismiss with spring; call onEnd when finished.
     */
    public static void animateDialogDismiss(View root, @Nullable Runnable onEnd) {
        if (root == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        root.animate().cancel();
        root.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(DIALOG_DISMISS_DURATION_MS)
                .setInterpolator(new AccelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        root.animate().setListener(null);
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();
    }

    public static void applyPressScaleRecursively(View root) {
        if (root == null) return;
        if (root.isClickable()) applyPressScale(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyPressScaleRecursively(vg.getChildAt(i));
            }
        }
    }

    // 对 RecyclerView 可见子项做阶梯式入场（淡入 + 上滑）
    public static void staggerRecyclerChildren(RecyclerView rv) {
        if (rv == null) return;
        rv.post(() -> {
            int count = rv.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = rv.getChildAt(i);
                if (child == null) continue;
                float dy = dp(rv.getContext(), 12f);
                child.setAlpha(0f);
                child.setTranslationY(dy);
                final int delay = i * 40; // 40ms 阶梯
                rv.postDelayed(() -> {
                    springAlphaTo(child, 1f).start();
                    springTranslationYTo(child, 0f).start();
                }, delay);
            }
        });
    }

    private static float dp(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }
}
