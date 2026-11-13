package org.levimc.launcher.ui.dialogs;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.Locale;
import java.util.Objects;

public class LibsRepairDialog extends Dialog {
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView titleText;
    private TextView subtitleText;
    private TextView statusText;
    private TextView etaText;
    private TextView backgroundHintText;
    private Button pauseButton;
    private View iconContainer;
    private ValueAnimator progressAnimator;
    private int currentProgress;
    private boolean dismissing;
    private Runnable dismissAnimationEndListener;

    public LibsRepairDialog(Context context) {
        super(context);
        setCancelable(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_libs_repair);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 560);

        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);
        titleText = findViewById(R.id.title);
        subtitleText = findViewById(R.id.subtitle);
        statusText = findViewById(R.id.status_text);
        etaText = findViewById(R.id.eta_text);
        backgroundHintText = findViewById(R.id.background_hint_text);
        pauseButton = findViewById(R.id.pause_button);
        iconContainer = findViewById(R.id.icon_container);
        applyPersonalization();

        Window window = Objects.requireNonNull(getWindow());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.6f;

        float density = getContext().getResources().getDisplayMetrics().density;
        int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (400 * density);
        params.width = Math.min((int) (screenWidth * 0.9f), maxWidth);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);

        View content = findViewById(android.R.id.content);
        if (content != null) {
            DynamicAnim.animateDialogShow(content);
        }

    }

    public void updateProgress(int progress) {
        int targetProgress = Math.max(0, Math.min(100, progress));
        if (progressBar.isIndeterminate()) {
            progressBar.setProgress(targetProgress);
            currentProgress = targetProgress;
            updateProgressText(targetProgress);
            return;
        }
        if (currentProgress == targetProgress) return;

        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }

        if (targetProgress == 100) {
            progressBar.setProgress(targetProgress);
            currentProgress = targetProgress;
            updateProgressText(targetProgress);
            return;
        }
        progressAnimator = ValueAnimator.ofInt(currentProgress, targetProgress);
        progressAnimator.setDuration(160L);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(animation -> {
            int animatedProgress = (int) animation.getAnimatedValue();
            progressBar.setProgress(animatedProgress);
            currentProgress = animatedProgress;
            updateProgressText(animatedProgress);
        });
        progressAnimator.start();
    }

    public void setIndeterminate(boolean indeterminate) {
        progressBar.setIndeterminate(indeterminate);
    }

    private void updateProgressText(int progress) {
        progressText.setText(String.format(Locale.getDefault(), "%d%%", progress));
    }

    private void applyPersonalization() {
        try {
            PersonalizationManager pm = new PersonalizationManager(getContext());
            int accent = pm.getAccentColor();
            if (accent != 0 && progressBar != null) {
                progressBar.setProgressTintList(ColorStateList.valueOf(accent));
                progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(accent, 42)));
                progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
            }
            if (accent != 0 && progressText != null) {
                progressText.setTextColor(accent);
            }
            if (accent != 0 && titleText != null) {
                titleText.setTextColor(accent);
            }
            if (accent != 0 && pauseButton != null) {
                pauseButton.setBackgroundTintList(ColorStateList.valueOf(accent));
                pauseButton.setTextColor(Color.WHITE);
            }
            if (accent != 0 && iconContainer != null) {
                iconContainer.setBackgroundTintList(ColorStateList.valueOf(withAlpha(accent, 34)));
            }
        } catch (Exception ignored) {}
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setTitleText(String text) {
        if (titleText != null) titleText.setText(text);
    }

    public void setSubtitleText(String text) {
        if (subtitleText != null) subtitleText.setText(text);
    }

    public void setStatusText(String text) {
        if (statusText != null) statusText.setText(text);
    }

    public void setEtaText(String text) {
        if (etaText == null) return;
        etaText.setText(text);
        etaText.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setBackgroundHintText(String text) {
        if (backgroundHintText == null) return;
        backgroundHintText.setText(text);
        backgroundHintText.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void setPauseButton(String text, View.OnClickListener listener) {
        if (pauseButton == null) return;
        pauseButton.setText(text);
        pauseButton.setOnClickListener(listener);
        pauseButton.setVisibility(listener == null ? View.GONE : View.VISIBLE);
        DynamicAnim.applyPressScale(pauseButton);
    }

    public void setOnDismissAnimationEndListener(Runnable listener) {
        this.dismissAnimationEndListener = listener;
    }

    @Override
    public void show() {
        Window window = getWindow();
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        super.show();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @Override
    public void dismiss() {
        if (dismissing || !isShowing()) {
            return;
        }
        dismissing = true;
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        Window window = getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0f;
            window.setAttributes(params);
        }
        View content = findViewById(android.R.id.content);
        if (content != null) {
            DynamicAnim.animateDialogDismiss(content, () -> {
                try {
                    LibsRepairDialog.super.dismiss();
                } finally {
                    notifyDismissAnimationEnd();
                }
            });
        } else {
            try {
                super.dismiss();
            } finally {
                notifyDismissAnimationEnd();
            }
        }
    }

    private void notifyDismissAnimationEnd() {
        Runnable listener = dismissAnimationEndListener;
        dismissAnimationEndListener = null;
        if (listener != null) {
            listener.run();
        }
    }
}
