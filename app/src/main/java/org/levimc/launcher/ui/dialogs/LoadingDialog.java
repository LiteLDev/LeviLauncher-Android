package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

public class LoadingDialog extends Dialog {
    private TextView messageView;
    private ProgressBar progressBar;
    private CharSequence pendingMessage;

    public LoadingDialog(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);
        setContentView(view);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 420);
        setCancelable(false);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.dimAmount = 0.6f;
        getWindow().setAttributes(params);

        messageView = view.findViewById(R.id.tv_message);
        progressBar = view.findViewById(R.id.progress_bar);
        
        if (pendingMessage != null) {
            messageView.setText(pendingMessage);
        }
        
        try {
            PersonalizationManager pm = new PersonalizationManager(getContext());
            int accent = pm.getAccentColor();
            if (accent != 0 && progressBar != null) {
                progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
            }
        } catch (Exception ignored) {}
    }

    public void setMessage(CharSequence message) {
        if (messageView != null) {
            messageView.setText(message);
        } else {
            pendingMessage = message;
        }
    }
}