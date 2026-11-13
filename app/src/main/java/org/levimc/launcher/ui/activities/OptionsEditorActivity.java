package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.OptionsEditor;
import org.levimc.launcher.core.content.OptionsEditor.OptionProperty;
import org.levimc.launcher.databinding.ActivityOptionsEditorBinding;
import org.levimc.launcher.ui.adapter.OptionsPropertiesAdapter;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.widget.LauncherPopup;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OptionsEditorActivity extends BaseActivity {
    public static final String EXTRA_OPTIONS_PATH = "options_path";
    public static final String EXTRA_STORAGE_TYPE = "storage_type";
    private static final String[] CATEGORIES = {"Graphics", "Audio", "Controls", "Interface", "Game", "Developer", "Multiplayer", "VR", "Other"};
    private ActivityOptionsEditorBinding binding;
    private OptionsEditor optionsEditor;
    private OptionsPropertiesAdapter adapter;
    private ExecutorService executor;
    private List<OptionProperty> allProperties = new ArrayList<>();
    private final Map<String, String> savedValues = new LinkedHashMap<>();
    private boolean hasUnsavedChanges;
    private boolean loading = true;
    private boolean saving;
    private int category = -1;
    private TextView status;
    private MaterialButton categoryButton;
    private Bundle restoredEdits;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityOptionsEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        executor = Executors.newSingleThreadExecutor();
        String path = getIntent().getStringExtra(EXTRA_OPTIONS_PATH);
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, R.string.options_file_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (state != null) {
            restoredEdits = state.getBundle("option_edits");
            category = state.getInt("option_category", -1);
            if (category < -1 || category >= CATEGORIES.length) category = -1;
        }
        optionsEditor = new OptionsEditor(new File(path));
        status = findViewById(R.id.options_editor_status);
        categoryButton = findViewById(R.id.options_category_button);
        binding.saveButton.setEnabled(false);
        binding.saveButton.setOnClickListener(v -> saveChanges(false));
        adapter = new OptionsPropertiesAdapter();
        adapter.setOnOptionChangedListener((property, value) -> {
            optionsEditor.setValue(property.getKey(), value);
            property.setValue(value);
            updateDirtyState();
        });
        binding.optionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.optionsRecyclerView.setAdapter(adapter);
        binding.optionsRecyclerView.setItemAnimator(null);
        categoryButton.setOnClickListener(v -> showCategories());
        categoryButton.setText(category < 0 ? getString(R.string.options_category_all)
                : OptionsPropertiesAdapter.categoryLabel(this, CATEGORIES[category]));
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { if (!loading) filterProperties(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        if (state != null) binding.searchEditText.setText(state.getString("option_query", ""));
        binding.loadingProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                optionsEditor.load();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    binding.loadingProgress.setVisibility(View.GONE);
                    savedValues.putAll(optionsEditor.getOptions());
                    allProperties = optionsEditor.getOptionProperties();
                    if (restoredEdits != null) for (OptionProperty property : allProperties) {
                        if (!restoredEdits.containsKey(property.getKey())) continue;
                        String value = restoredEdits.getString(property.getKey(), property.getValue());
                        property.setValue(value);
                        optionsEditor.setValue(property.getKey(), value);
                    }
                    restoredEdits = null;
                    updateDirtyState();
                    filterProperties();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    loading = false;
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.emptyText.setVisibility(View.VISIBLE);
                    binding.emptyText.setText(getString(R.string.error_loading_options) + ": " + error.getMessage());
                });
            }
        });
    }

    private void showCategories() {
        LauncherPopup popup = new LauncherPopup(categoryButton).section(getString(R.string.category))
                .choice(0, getString(R.string.options_category_all), category < 0);
        for (int i = 0; i < CATEGORIES.length; i++) {
            popup.choice(i + 1, OptionsPropertiesAdapter.categoryLabel(this, CATEGORIES[i]), category == i);
        }
        popup.onSelect(id -> {
            category = id - 1;
            categoryButton.setText(category < 0 ? getString(R.string.options_category_all)
                    : OptionsPropertiesAdapter.categoryLabel(this, CATEGORIES[category]));
            filterProperties();
        }).show();
    }

    private void filterProperties() {
        String query = binding.searchEditText.getText().toString().trim().toLowerCase(Locale.ROOT);
        Map<String, List<OptionProperty>> grouped = new LinkedHashMap<>();
        int visible = 0;
        for (OptionProperty property : allProperties) {
            if (category >= 0 && !CATEGORIES[category].equals(property.getCategory())) continue;
            String text = property.getDisplayName() + " " + property.getKey() + " " + property.getValue() + " "
                    + OptionsPropertiesAdapter.categoryLabel(this, property.getCategory());
            if (!query.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(query)) continue;
            grouped.computeIfAbsent(property.getCategory(), key -> new ArrayList<>()).add(property);
            visible++;
        }
        adapter.setProperties(grouped);
        binding.optionsRecyclerView.setVisibility(visible == 0 ? View.GONE : View.VISIBLE);
        binding.emptyText.setVisibility(visible == 0 ? View.VISIBLE : View.GONE);
        binding.emptyText.setText(allProperties.isEmpty() ? R.string.no_options_found : R.string.no_matching_options);
        status.setText(getString(R.string.options_editor_results, visible, allProperties.size()));
    }

    private void updateDirtyState() {
        hasUnsavedChanges = false;
        for (OptionProperty property : allProperties) if (!Objects.equals(savedValues.get(property.getKey()), property.getValue())) {
            hasUnsavedChanges = true;
            break;
        }
        binding.saveButton.setEnabled(hasUnsavedChanges && !saving);
        ((TextView) findViewById(R.id.options_editor_info)).setText(saving ? R.string.options_editor_saving
                : hasUnsavedChanges ? R.string.unsaved_changes : R.string.options_editor_subtitle);
    }

    private void saveChanges(boolean closeAfterSave) {
        if (saving || loading) return;
        if (!hasUnsavedChanges) { if (closeAfterSave) finish(); return; }
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        Map<String, String> snapshot = optionsEditor.getOptions();
        saving = true;
        updateDirtyState();
        executor.execute(() -> {
            try {
                optionsEditor.save(snapshot);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    saving = false;
                    savedValues.clear();
                    savedValues.putAll(snapshot);
                    updateDirtyState();
                    Toast.makeText(this, R.string.options_saved, Toast.LENGTH_SHORT).show();
                    if (closeAfterSave && !hasUnsavedChanges) finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    saving = false;
                    updateDirtyState();
                    Toast.makeText(this, getString(R.string.save_failed) + ": " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override public void onBackPressed() {
        if (saving) { Toast.makeText(this, R.string.options_editor_saving, Toast.LENGTH_SHORT).show(); return; }
        if (!hasUnsavedChanges) { super.onBackPressed(); return; }
        new CustomAlertDialog(this).setTitleText(getString(R.string.unsaved_changes))
                .setMessage(getString(R.string.discard_changes_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.discard), v -> finish())
                .setPositiveButton(getString(R.string.options_editor_save_close), v -> saveChanges(true)).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        Bundle edits = new Bundle();
        for (OptionProperty property : allProperties) if (!Objects.equals(savedValues.get(property.getKey()), property.getValue())) edits.putString(property.getKey(), property.getValue());
        state.putBundle("option_edits", edits);
        state.putInt("option_category", category);
        state.putString("option_query", binding.searchEditText.getText().toString());
    }

    @Override protected void onDestroy() {
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }
}
