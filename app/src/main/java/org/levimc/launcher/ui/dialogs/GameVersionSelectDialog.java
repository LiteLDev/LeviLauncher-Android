package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.gameversionselect.BigGroup;
import org.levimc.launcher.ui.dialogs.gameversionselect.UltimateVersionAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.util.List;

public class GameVersionSelectDialog extends Dialog {
    public interface OnVersionSelectListener {
        void onVersionSelected(GameVersion version);
    }

    private OnVersionSelectListener listener;
    private List<BigGroup> bigGroups;

    public GameVersionSelectDialog(@NonNull Context ctx, List<BigGroup> bigGroups) {
        super(ctx);
        this.bigGroups = bigGroups;
    }

    public void setOnVersionSelectListener(OnVersionSelectListener l) {
        this.listener = l;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_game_version_select);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 520);
        RecyclerView recyclerView = findViewById(R.id.recycler_versions);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        UltimateVersionAdapter adapter = new UltimateVersionAdapter(getContext(), bigGroups);
        adapter.setOnVersionSelectListener(v -> {
            if (listener != null) listener.onVersionSelected(v);
            dismiss();
        });

        adapter.setOnVersionLongClickListener(this::showRenameDialog);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f;
            
            float density = getContext().getResources().getDisplayMetrics().density;
            int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            int maxWidth = (int) (420 * density);
            params.width = Math.min((int) (screenWidth * 0.95), maxWidth);
            window.setAttributes(params);
        }
        recyclerView.setAdapter(adapter);

        // 对话框入场弹簧动画与列表交错入场
        View root = findViewById(android.R.id.content);
        if (root != null) {
            float dy = getContext().getResources().getDisplayMetrics().density * 12f;
            root.setAlpha(0f);
            root.setTranslationY(dy);
            DynamicAnim.springAlphaTo(root, 1f).start();
            DynamicAnim.springTranslationYTo(root, 0f).start();
        }
        recyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(recyclerView));
    }

    private void showRenameDialog(GameVersion version) {
        View customView = getLayoutInflater().inflate(R.layout.dialog_rename_entry, null);
        android.widget.EditText editName = customView.findViewById(R.id.edit_version_name);
        View errorText = customView.findViewById(R.id.text_version_error);

        String currentName = extractDisplayName(version);
        editName.setText(currentName);
        if (currentName != null) editName.setSelection(currentName.length());

        CustomAlertDialog renameDialog = new CustomAlertDialog(getContext())
                .setTitleText(getContext().getString(R.string.rename_version_title))
                .setCustomView(customView)
                .setUseBorderedBackground(true)
                .setBlurBackground(true)
                .setPositiveButton(getContext().getString(R.string.rename), v -> {
                    String newName = editName.getText().toString().trim();
                    if (isValidName(newName)) {
                        performRename(version, newName);
                    }
                })
                .setNegativeButton(getContext().getString(R.string.cancel), null);
        
        renameDialog.show();

        editName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean valid = isValidName(s.toString().trim());
                errorText.setVisibility(valid ? View.GONE : View.VISIBLE);
                renameDialog.getPositiveButton().setEnabled(valid);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private String extractDisplayName(GameVersion version) {
        if (version == null) return "";
        String displayName = version.displayName;
        if (displayName == null) return version.directoryName;
        int lastParenIndex = displayName.lastIndexOf(" (");
        if (lastParenIndex > 0) return displayName.substring(0, lastParenIndex);
        return version.directoryName;
    }

    private boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) return false;
        return java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]+$").matcher(name).matches();
    }

    private void performRename(GameVersion version, String newName) {
        VersionManager.get(getContext()).renameVersion(version, newName, new VersionManager.OnRenameVersionCallback() {
            @Override
            public void onRenameCompleted(boolean success) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (success) {
                        Toast.makeText(getContext(), getContext().getString(R.string.rename_success), Toast.LENGTH_SHORT).show();
                        dismiss();
                    }
                });
            }

            @Override
            public void onRenameFailed(Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(getContext(), getContext().getString(R.string.rename_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}