package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.regex.Pattern;

public class ApkVersionConfirmDialog extends DialogFragment {

    public interface Callback {
        void onInstallClicked(String versionName);

        void onCancelled();
    }

    private Callback callback;
    private EditText editVersionName;
    private TextView textError;
    private Button btnInstall;
    private String initialVersionName = "";

    public ApkVersionConfirmDialog setInitialVersionName(String versionName) {
        this.initialVersionName = versionName;
        return this;
    }

    public ApkVersionConfirmDialog setCallback(Callback callback) {
        this.callback = callback;
        return this;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = requireContext();
        ensureBaseDirectoryExists();

        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);


        View root = getLayoutInflater().inflate(R.layout.dialog_apk_version_confirm, null);
        dialog.setContentView(root);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(dialog, 520);

        editVersionName = root.findViewById(R.id.edit_version_name);
        textError = root.findViewById(R.id.text_version_error);
        btnInstall = root.findViewById(R.id.btn_install);
        TextView title = root.findViewById(R.id.tv_title);
        Button btnCancel = root.findViewById(R.id.btn_cancel);

        editVersionName.setText(initialVersionName);
        validateVersionName(initialVersionName);

        editVersionName.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validateVersionName(s.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        btnCancel.setOnClickListener(v -> {
            if (callback != null) callback.onCancelled();
            Window w = dialog.getWindow();
            if (w != null) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams p = w.getAttributes();
                p.dimAmount = 0f;
                w.setAttributes(p);
            }
            View content = dialog.findViewById(android.R.id.content);
            DynamicAnim.animateDialogDismiss(content, dialog::dismiss);
        });

        btnInstall.setOnClickListener(v -> {
            String versionName = editVersionName.getText().toString();
            if (isValidVersionName(versionName) && !isVersionExist(versionName)) {
                if (callback != null) callback.onInstallClicked(versionName);
                dismiss();
            } else {
                textError.setVisibility(View.VISIBLE);
            }
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f;
            
            float density = context.getResources().getDisplayMetrics().density;
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int maxWidth = (int) (400 * density);
            params.width = Math.min((int) (screenWidth * 0.9), maxWidth);
            window.setAttributes(params);
        }

        DynamicAnim.animateDialogShow(dialog.findViewById(android.R.id.content));
        DynamicAnim.applyPressScale(btnInstall);
        DynamicAnim.applyPressScale(btnCancel);

        try {
            PersonalizationManager pm = new PersonalizationManager(context);
            int accent = pm.getAccentColor();
            if (accent != 0) {
                title.setTextColor(accent);
                editVersionName.setBackground(createAccentEditTextBackground(context, accent));
                btnInstall.setBackgroundTintList(ColorStateList.valueOf(accent));
                btnInstall.setTextColor(Color.WHITE);
            }
        } catch (Exception ignored) {}

        return dialog;
    }

    private GradientDrawable createAccentEditTextBackground(Context context, int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(context.getResources().getColor(R.color.surface, context.getTheme()));
        drawable.setCornerRadius(6 * context.getResources().getDisplayMetrics().density);
        drawable.setStroke((int) (1 * context.getResources().getDisplayMetrics().density), accent);
        return drawable;
    }

    private void validateVersionName(String name) {
        boolean valid = isValidVersionName(name) && !isVersionExist(name);
        if (valid) {
            textError.setVisibility(View.GONE);
            btnInstall.setEnabled(true);
        } else {
            textError.setVisibility(View.VISIBLE);
            btnInstall.setEnabled(false);
        }
    }

    private boolean isValidVersionName(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) return false;
        String regex = "^[a-zA-Z0-9._]+$";
        return Pattern.compile(regex).matcher(name).matches();
    }

    private boolean ensureBaseDirectoryExists() {
        File baseDir = LauncherStorage.getMinecraftRoot(requireContext());
        if (!baseDir.exists()) {
            return baseDir.mkdirs();
        }
        return true;
    }

    private boolean isVersionExist(String name) {
        File baseDir = LauncherStorage.getMinecraftRoot(requireContext());
        if (!baseDir.exists()) return false;
        File targetDir = new File(baseDir, name);
        return targetDir.exists();
    }
}
