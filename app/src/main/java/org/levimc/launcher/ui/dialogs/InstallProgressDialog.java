package org.levimc.launcher.ui.dialogs;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.Locale;

public class InstallProgressDialog extends Dialog {

    private ProgressBar progressBar;
    private TextView progressText;
    private TextView titleText;
    private TextView statusText;
    private CharSequence pendingTitle;
    private CharSequence pendingStatus;
    private ValueAnimator progressAnimator;
    private int currentProgress;

    public InstallProgressDialog(Context context) {
        super(context);
        setCancelable(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_install_progress);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 480);

        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.tv_progress_percent);
        titleText = findViewById(R.id.tv_title);
        statusText = findViewById(R.id.tv_progress_status);
        if (pendingTitle != null && titleText != null) {
            titleText.setText(pendingTitle);
        }
        if (pendingStatus != null && statusText != null) {
            statusText.setText(pendingStatus);
        }
        applyPersonalization();

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            float density = getContext().getResources().getDisplayMetrics().density;
            int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            int maxWidth = (int) (400 * density);
            params.width = Math.min((int) (screenWidth * 0.9f), maxWidth);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.6f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        View content = findViewById(android.R.id.content);
        if (content != null) {
            DynamicAnim.animateDialogShow(content);
        }
    }

    public void setProgress(int progress) {
        int targetProgress = Math.max(0, Math.min(100, progress));
        if (progressBar == null) {
            currentProgress = targetProgress;
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

    public void setTitleText(CharSequence title) {
        pendingTitle = title;
        if (titleText != null) {
            titleText.setText(title);
        }
    }

    public void setStatusText(CharSequence status) {
        pendingStatus = status;
        if (statusText != null) {
            statusText.setText(status);
        }
    }

    private void updateProgressText(int progress) {
        if (progressText != null) {
            progressText.setText(String.format(Locale.getDefault(), "%d%%", progress));
        }
    }

    private void applyPersonalization() {
        try {
            PersonalizationManager pm = new PersonalizationManager(getContext());
            int accent = pm.getAccentColor();
            if (accent == 0) return;

            if (titleText != null) {
                titleText.setTextColor(accent);
            }
            if (progressText != null) {
                progressText.setTextColor(accent);
            }
            if (progressBar != null) {
                progressBar.setProgressTintList(ColorStateList.valueOf(accent));
                progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(accent, 42)));
                progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
            }
        } catch (Exception ignored) {}
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public void dismiss() {
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
            DynamicAnim.animateDialogDismiss(content, () -> InstallProgressDialog.super.dismiss());
        } else {
            super.dismiss();
        }
    }
}
