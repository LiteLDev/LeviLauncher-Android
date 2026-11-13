package org.levimc.launcher.ui.dialogs;

import android.content.Context;
import org.levimc.launcher.R;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class LauncherDialogBuilder extends MaterialAlertDialogBuilder {
    public LauncherDialogBuilder(Context context) { super(context, R.style.ThemeOverlay_Levi_LauncherDialog); clearInsets(); }
    public LauncherDialogBuilder(Context context, int theme) { super(context, theme); clearInsets(); }

    private void clearInsets() {
        setBackgroundInsetStart(0); setBackgroundInsetEnd(0);
        setBackgroundInsetTop(0); setBackgroundInsetBottom(0);
    }

    @Override public AlertDialog create() {
        AlertDialog dialog = super.create();
        LauncherDialogs.bind(dialog, 560);
        return dialog;
    }

    @Override public AlertDialog show() {
        AlertDialog dialog = super.show();
        LauncherDialogs.style(dialog, 560);
        return dialog;
    }
}
