package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.play.GameDownloads;
import org.levimc.launcher.core.play.GameRelease;
import org.levimc.launcher.core.play.PlayClient;
import org.levimc.launcher.core.play.PlayException;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.ApkImportManager;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.StorageAccess;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the Minecraft builds the signed-in Google account can download from Google Play and
 * downloads and installs them as instances, mirroring LeviLauncher for Windows' download page.
 */
public class GameDownloadActivity extends BaseActivity implements GameDownloads.Listener {
    private static final int TYPE_ALL = 0;
    private static final int TYPE_RELEASE = 1;
    private static final int TYPE_PREVIEW = 2;
    private static final int STATUS_ALL = 0;
    private static final int STATUS_DOWNLOADED = 1;
    private static final int STATUS_NOT_DOWNLOADED = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<GameRelease> allReleases = new ArrayList<>();
    private final List<GameRelease> visible = new ArrayList<>();
    private final Set<String> installedVersionNames = new HashSet<>();

    private GameDownloads downloads;
    private ApkImportManager apkImportManager;
    private ActivityResultLauncher<Intent> googleLoginLauncher;
    private ActivityResultLauncher<Intent> apkImportLauncher;

    private TextView subtitle;
    private TextView status;
    private MaterialButton accountButton;
    private ImageButton refreshButton;
    private EditText search;
    private View previewRow;
    private SwitchMaterial previewSwitch;
    private RecyclerView recycler;
    private ProgressBar progress;
    private View empty;
    private TextView emptyText;
    private MaterialButton emptyAction;
    private ReleaseAdapter adapter;

    private int accent;
    private int typeFilter = TYPE_ALL;
    private int statusFilter = STATUS_ALL;
    private long latestVersionCode;
    private boolean refreshing;
    private boolean updatingPreviewSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_download);
        downloads = GameDownloads.get(this);

        apkImportManager = new ApkImportManager(this, null);
        apkImportManager.setOnImportCompleteListener(this::refreshInstalled);
        apkImportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> apkImportManager.handleActivityResult(result.getResultCode(), result.getData()));
        googleLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    updateAccountUi();
                    if (PlayClient.isSignedIn(this)) refreshFromPlay();
                });

        subtitle = findViewById(R.id.game_download_subtitle);
        status = findViewById(R.id.game_download_status);
        accountButton = findViewById(R.id.game_download_account);
        refreshButton = findViewById(R.id.game_download_refresh);
        search = findViewById(R.id.game_download_search);
        previewRow = findViewById(R.id.game_download_preview_row);
        previewSwitch = findViewById(R.id.game_download_preview_switch);
        recycler = findViewById(R.id.game_download_recycler);
        progress = findViewById(R.id.game_download_progress);
        empty = findViewById(R.id.game_download_empty);
        emptyText = findViewById(R.id.game_download_empty_text);
        emptyAction = findViewById(R.id.game_download_empty_action);

        accent = new PersonalizationManager(this).getAccentColor();
        tintFilled(accountButton);
        tintFilled(emptyAction);
        tintOutlined(findViewById(R.id.game_download_by_code));
        tintOutlined(findViewById(R.id.game_download_local_install));

        adapter = new ReleaseAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        accountButton.setOnClickListener(v -> onAccountClicked());
        emptyAction.setOnClickListener(v -> onAccountClicked());
        refreshButton.setOnClickListener(v -> {
            loadLocal();
            if (PlayClient.isSignedIn(this)) refreshFromPlay();
        });
        findViewById(R.id.game_download_local_install).setOnClickListener(
                v -> apkImportLauncher.launch(StorageAccess.downloadsPicker(false)));
        findViewById(R.id.game_download_by_code).setOnClickListener(v -> showVersionCodeDialog());
        previewSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingPreviewSwitch) setPreviewChannel(checked);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { applyFilters(); }
        });
        setupSpinner(findViewById(R.id.game_download_type), new String[]{
                getString(R.string.game_download_type_all),
                getString(R.string.game_download_type_release),
                getString(R.string.game_download_type_preview)}, position -> {
            typeFilter = position;
            applyFilters();
        });
        setupSpinner(findViewById(R.id.game_download_status_filter), new String[]{
                getString(R.string.game_download_status_all),
                getString(R.string.game_download_status_downloaded),
                getString(R.string.game_download_status_not_downloaded)}, position -> {
            statusFilter = position;
            applyFilters();
        });

        updateAccountUi();
        loadLocal();
        if (PlayClient.isSignedIn(this)) refreshFromPlay();
    }

    @Override
    protected void onStart() {
        super.onStart();
        downloads.addListener(this);
    }

    @Override
    protected void onStop() {
        downloads.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setActiveNavTab(R.id.nav_tab_download);
        refreshInstalled();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    /** Filled buttons follow the launcher's accent color, as on the other pages. */
    private void tintFilled(MaterialButton button) {
        if (accent == 0 || button == null) return;
        button.setBackgroundTintList(ColorStateList.valueOf(accent));
        button.setTextColor(Color.WHITE);
    }

    private void tintOutlined(MaterialButton button) {
        if (accent == 0 || button == null) return;
        button.setStrokeColor(ColorStateList.valueOf(accent));
        button.setTextColor(accent);
    }

    private interface OnSelected {
        void onSelected(int position);
    }

    private void setupSpinner(Spinner spinner, String[] labels, OnSelected listener) {
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                listener.onSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ---- Account ----

    private void updateAccountUi() {
        String email = PlayClient.signedInEmail(this);
        if (email == null) {
            subtitle.setText(R.string.game_download_subtitle_signed_out);
            accountButton.setText(R.string.game_download_google_sign_in);
            previewRow.setVisibility(View.GONE);
        } else {
            subtitle.setText(getString(R.string.game_download_subtitle_signed_in, email));
            accountButton.setText(R.string.game_download_google_account);
        }
        updateEmptyState();
    }

    private void onAccountClicked() {
        String email = PlayClient.signedInEmail(this);
        if (email == null) {
            googleLoginLauncher.launch(new Intent(this, GoogleLoginActivity.class));
            return;
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_google_account))
                .setMessage(getString(R.string.game_download_sign_out_message, email))
                .setPositiveButton(getString(R.string.game_download_sign_out), v -> {
                    PlayClient.signOut(this);
                    latestVersionCode = 0;
                    setStatus(null);
                    updateAccountUi();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private boolean requireSignIn() {
        if (PlayClient.isSignedIn(this)) return true;
        Toast.makeText(this, R.string.game_download_sign_in_required, Toast.LENGTH_SHORT).show();
        googleLoginLauncher.launch(new Intent(this, GoogleLoginActivity.class));
        return false;
    }

    // ---- Data ----

    private void loadLocal() {
        executor.execute(() -> {
            List<GameRelease> releases = downloads.knownReleases();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                allReleases.clear();
                allReleases.addAll(releases);
                applyFilters();
            });
        });
    }

    private void refreshFromPlay() {
        if (refreshing) return;
        refreshing = true;
        setStatus(getString(R.string.game_download_checking_play));
        refreshButton.setEnabled(false);
        if (allReleases.isEmpty()) progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            PlayClient.AppDetails details = null;
            PlayException error = null;
            try {
                details = new PlayClient(this).details(GameDownloads.MINECRAFT_PACKAGE);
                downloads.setOfferType(details.offerType);
                if (details.versionCode > 0) {
                    downloads.remember(new GameRelease(details.versionCode, details.versionName,
                            details.downloadSize, System.currentTimeMillis()));
                }
            } catch (PlayException e) {
                error = e;
            }
            List<GameRelease> releases = downloads.knownReleases();
            PlayClient.AppDetails finalDetails = details;
            PlayException finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                refreshing = false;
                refreshButton.setEnabled(true);
                progress.setVisibility(View.GONE);
                allReleases.clear();
                allReleases.addAll(releases);
                if (finalDetails != null) {
                    latestVersionCode = finalDetails.versionCode;
                    setStatus(null);
                    updatePreviewSwitch(finalDetails);
                } else {
                    setStatus(describe(finalError));
                    if (finalError.kind == PlayException.Kind.UNAUTHORIZED) updateAccountUi();
                }
                applyFilters();
            });
        });
    }

    private void updatePreviewSwitch(PlayClient.AppDetails details) {
        boolean show = details.testingProgramAvailable || details.testingProgramSubscribed;
        previewRow.setVisibility(show ? View.VISIBLE : View.GONE);
        updatingPreviewSwitch = true;
        previewSwitch.setChecked(details.testingProgramSubscribed);
        updatingPreviewSwitch = false;
    }

    private void setPreviewChannel(boolean join) {
        previewSwitch.setEnabled(false);
        executor.execute(() -> {
            String message;
            boolean ok;
            try {
                ok = new PlayClient(this).setTestingProgram(GameDownloads.MINECRAFT_PACKAGE, join);
                message = getString(ok
                        ? (join ? R.string.game_download_preview_joined : R.string.game_download_preview_left)
                        : R.string.game_download_preview_failed);
            } catch (PlayException e) {
                ok = false;
                message = describe(e);
            }
            boolean success = ok;
            String finalMessage = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                previewSwitch.setEnabled(true);
                if (!success) {
                    updatingPreviewSwitch = true;
                    previewSwitch.setChecked(!join);
                    updatingPreviewSwitch = false;
                }
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void refreshInstalled() {
        installedVersionNames.clear();
        VersionManager manager = VersionManager.get(this);
        List<GameVersion> versions = new ArrayList<>();
        if (manager.getInstalledVersions() != null) versions.addAll(manager.getInstalledVersions());
        if (manager.getCustomVersions() != null) versions.addAll(manager.getCustomVersions());
        for (GameVersion version : versions) {
            if (version != null && version.versionCode != null) installedVersionNames.add(version.versionCode);
        }
        adapter.notifyDataSetChanged();
    }

    private void applyFilters() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (GameRelease release : allReleases) {
            if (typeFilter == TYPE_RELEASE && release.isPreview()) continue;
            if (typeFilter == TYPE_PREVIEW && !release.isPreview()) continue;
            boolean isDownloaded = downloads.downloaded(release.versionCode) != null;
            if (statusFilter == STATUS_DOWNLOADED && !isDownloaded) continue;
            if (statusFilter == STATUS_NOT_DOWNLOADED && isDownloaded) continue;
            if (!query.isEmpty()
                    && !release.displayName().toLowerCase(Locale.ROOT).contains(query)
                    && !String.valueOf(release.versionCode).contains(query)) {
                continue;
            }
            visible.add(release);
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (emptyText == null) return;
        boolean isEmpty = visible.isEmpty() && !(refreshing && allReleases.isEmpty());
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (!isEmpty) return;
        boolean signedIn = PlayClient.isSignedIn(this);
        if (!signedIn) {
            emptyText.setText(R.string.game_download_empty_signed_out);
            emptyAction.setVisibility(View.VISIBLE);
        } else {
            emptyText.setText(allReleases.isEmpty()
                    ? R.string.game_download_empty_signed_in
                    : R.string.game_download_empty_filtered);
            emptyAction.setVisibility(View.GONE);
        }
    }

    private void setStatus(String text) {
        status.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        status.setText(text);
    }

    private String describe(PlayException error) {
        if (error == null) return null;
        switch (error.kind) {
            case UNAUTHORIZED:
                return getString(R.string.game_download_error_unauthorized);
            case NOT_PURCHASED:
                return getString(R.string.game_download_error_not_purchased);
            case NOT_AVAILABLE:
                return getString(R.string.game_download_error_not_available);
            case RATE_LIMITED:
                return getString(R.string.game_download_error_rate_limited);
            case NETWORK:
                return getString(R.string.game_download_error_network, String.valueOf(error.getMessage()));
            default:
                return getString(R.string.game_download_error_generic, String.valueOf(error.getMessage()));
        }
    }

    // ---- Actions ----

    private void showVersionCodeDialog() {
        if (!requireSignIn()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.game_download_by_code_hint);
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.on_surface));
        input.setHintTextColor(getColor(R.color.text_secondary));
        FrameLayout container = new FrameLayout(this);
        int padding = (int) (4 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);
        container.addView(input);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_by_code))
                .setMessage(getString(R.string.game_download_by_code_message))
                .setCustomView(container)
                .setPositiveButton(getString(R.string.game_download_download), v -> {
                    long code;
                    try {
                        code = Long.parseLong(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.game_download_by_code_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (code <= 0) {
                        Toast.makeText(this, R.string.game_download_by_code_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startDownload(code, null);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void startDownload(long versionCode, String versionName) {
        if (!requireSignIn()) return;
        downloads.start(versionCode, versionName);
        boolean listed = false;
        for (GameRelease release : allReleases) {
            if (release.versionCode == versionCode) {
                listed = true;
                break;
            }
        }
        if (!listed) {
            allReleases.add(0, new GameRelease(versionCode, versionName, 0, 0));
            applyFilters();
        }
    }

    private void install(GameRelease release) {
        List<java.io.File> files = downloads.downloadedFiles(release.versionCode);
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.game_download_missing_files, Toast.LENGTH_SHORT).show();
            loadLocal();
            return;
        }
        GameDownloads.Downloaded downloaded = downloads.downloaded(release.versionCode);
        String name = downloaded != null && !TextUtils.isEmpty(downloaded.versionName)
                ? downloaded.versionName : release.displayName();
        apkImportManager.installDownloadedFiles(files, name);
    }

    private void confirmDelete(GameRelease release) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_delete_title))
                .setMessage(getString(R.string.game_download_delete_message, release.displayName()))
                .setPositiveButton(getString(R.string.delete), v -> executor.execute(() -> {
                    boolean deleted = downloads.delete(release.versionCode);
                    List<GameRelease> releases = downloads.knownReleases();
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!deleted) {
                            Toast.makeText(this, R.string.game_download_delete_failed, Toast.LENGTH_SHORT).show();
                        }
                        allReleases.clear();
                        allReleases.addAll(releases);
                        applyFilters();
                    });
                }))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onTaskChanged(GameDownloads.Task task) {
        if (task.state == GameDownloads.State.DONE) {
            loadLocal();
            return;
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).versionCode == task.versionCode) {
                adapter.notifyItemChanged(i, task);
                return;
            }
        }
    }

    // ---- List ----

    private final class ReleaseAdapter extends RecyclerView.Adapter<ReleaseHolder> {
        @NonNull
        @Override
        public ReleaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ReleaseHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_game_download, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ReleaseHolder holder, int position) {
            holder.bind(visible.get(position));
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }

    private final class ReleaseHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView type;
        final TextView latest;
        final TextView state;
        final TextView meta;
        final MaterialButton delete;
        final MaterialButton secondary;
        final MaterialButton action;
        final View progressRow;
        final ProgressBar progressBar;
        final TextView progressText;
        final TextView error;

        ReleaseHolder(View view) {
            super(view);
            name = view.findViewById(R.id.game_release_name);
            type = view.findViewById(R.id.game_release_type);
            latest = view.findViewById(R.id.game_release_latest);
            state = view.findViewById(R.id.game_release_state);
            meta = view.findViewById(R.id.game_release_meta);
            delete = view.findViewById(R.id.game_release_delete);
            secondary = view.findViewById(R.id.game_release_secondary);
            action = view.findViewById(R.id.game_release_action);
            progressRow = view.findViewById(R.id.game_release_progress_row);
            progressBar = view.findViewById(R.id.game_release_progress);
            progressText = view.findViewById(R.id.game_release_progress_text);
            error = view.findViewById(R.id.game_release_error);
            tintFilled(action);
            tintOutlined(secondary);
        }

        void bind(GameRelease release) {
            GameDownloads.Task task = downloads.task(release.versionCode);
            GameDownloads.Downloaded downloaded = downloads.downloaded(release.versionCode);
            String versionName = downloaded != null && !TextUtils.isEmpty(downloaded.versionName)
                    ? downloaded.versionName
                    : (task != null && !TextUtils.isEmpty(task.versionName) ? task.versionName : release.versionName);
            GameRelease shown = new GameRelease(release.versionCode, versionName, release.size, release.seenAt);

            name.setText(shown.displayName());
            boolean knownName = !TextUtils.isEmpty(versionName);
            type.setVisibility(knownName ? View.VISIBLE : View.GONE);
            type.setText(shown.isPreview() ? R.string.game_download_type_preview : R.string.game_download_type_release);
            latest.setVisibility(release.versionCode == latestVersionCode ? View.VISIBLE : View.GONE);

            boolean installed = knownName && installedVersionNames.contains(versionName);
            if (installed) {
                state.setVisibility(View.VISIBLE);
                state.setText(R.string.game_download_state_installed);
            } else if (downloaded != null) {
                state.setVisibility(View.VISIBLE);
                state.setText(R.string.game_download_state_downloaded);
            } else {
                state.setVisibility(View.GONE);
            }

            long size = downloaded != null && downloaded.size > 0 ? downloaded.size : release.size;
            String metaText = getString(R.string.game_download_version_code, release.versionCode);
            if (size > 0) metaText += " · " + Formatter.formatShortFileSize(GameDownloadActivity.this, size);
            meta.setText(metaText);

            boolean active = task != null && task.isActive();
            boolean failed = task != null && task.state == GameDownloads.State.FAILED;
            progressRow.setVisibility(active ? View.VISIBLE : View.GONE);
            error.setVisibility(failed ? View.VISIBLE : View.GONE);
            delete.setVisibility(downloaded != null && !active ? View.VISIBLE : View.GONE);
            secondary.setVisibility(View.GONE);

            if (active) {
                bindProgress(task);
                action.setText(R.string.cancel);
                action.setOnClickListener(v -> downloads.cancel(release.versionCode));
            } else if (downloaded != null) {
                action.setText(R.string.install);
                action.setOnClickListener(v -> install(shown));
                secondary.setVisibility(View.VISIBLE);
                secondary.setText(R.string.game_download_redownload);
                secondary.setOnClickListener(v -> startDownload(release.versionCode, versionName));
            } else {
                action.setText(failed ? R.string.game_download_retry : R.string.game_download_download);
                action.setOnClickListener(v -> startDownload(release.versionCode, versionName));
            }
            if (failed) {
                error.setText(task.errorKind != null
                        ? describe(new PlayException(task.errorKind, task.error))
                        : String.valueOf(task.error));
            }
            delete.setOnClickListener(v -> confirmDelete(release));
        }

        private void bindProgress(GameDownloads.Task task) {
            switch (task.state) {
                case QUEUED:
                    progressBar.setIndeterminate(true);
                    progressText.setText(R.string.game_download_queued);
                    break;
                case RESOLVING:
                    progressBar.setIndeterminate(true);
                    progressText.setText(R.string.game_download_resolving);
                    break;
                case VERIFYING:
                    progressBar.setIndeterminate(true);
                    progressText.setText(R.string.game_download_verifying);
                    break;
                default:
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(task.percent());
                    progressText.setText(getString(R.string.game_download_progress,
                            task.percent(),
                            Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesDone),
                            Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesTotal),
                            Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesPerSecond)));
                    break;
            }
        }
    }
}
