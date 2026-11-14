package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.auth.MsftAuthManager;
import org.levimc.launcher.ui.adapter.AccountsAdapter;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.widget.LauncherPopup;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.AccountTextUtils;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccountsActivity extends BaseActivity {
    private final AccountsAdapter adapter = new AccountsAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> loginLauncher;
    private RecyclerView recycler;
    private MaterialButton addButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);
        setActiveNavTab(0);
        recycler = findViewById(R.id.accounts_recycler_view);
        addButton = findViewById(R.id.bottom_add_button);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recycler.setAdapter(adapter);
        UiKit.applyPrimary(addButton);
        loginLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null
                    && result.getData().getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
                String name = result.getData().getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
                Toast.makeText(this, getString(R.string.ms_login_success,
                        TextUtils.isEmpty(name) ? getString(R.string.accounts_page_title) : name), Toast.LENGTH_SHORT).show();
            }
            refreshUI();
        });
        addButton.setOnClickListener(v -> openSignIn());
        adapter.setOnAccountActionListener(new AccountsAdapter.OnAccountActionListener() {
            @Override
            public void onSetActive(MsftAccountStore.MsftAccount account) {
                runAccountAction(R.string.accounts_switching, () ->
                        MsftAccountStore.selectSavedAccount(getApplicationContext(), account.id));
            }

            @Override
            public void onMore(MsftAccountStore.MsftAccount account, View anchor) {
                showAccountActions(account, anchor);
            }
        });
        refreshUI();
    }

    private void openSignIn() {
        if (!busy) loginLauncher.launch(new Intent(this, MsftLoginActivity.class));
    }

    private void showAccountActions(MsftAccountStore.MsftAccount account, View anchor) {
        LauncherPopup popup = new LauncherPopup(anchor).width(252)
                .section(AccountTextUtils.displayNameOrNotSigned(this, account))
                .action(0, getString(R.string.accounts_details), R.drawable.ic_ui_person);
        if (account.active) popup.action(1, getString(R.string.accounts_refresh_sign_in), R.drawable.ic_ui_refresh);
        popup.action(2, getString(R.string.accounts_sign_in_again), R.drawable.ic_internet)
                .divider()
                .action(3, getString(R.string.accounts_remove), R.drawable.ic_ui_delete)
                .onSelect(id -> {
                    if (id == 0) showAccountDetails(account);
                    else if (id == 1) runAccountAction(R.string.ms_login_fetch_minecraft_identity, () ->
                            MsftAuthManager.refreshAccountAndActivate(getApplicationContext(), account.id));
                    else if (id == 2) openSignIn();
                    else if (id == 3) confirmRemove(account);
                }).show();
    }

    private void showAccountDetails(MsftAccountStore.MsftAccount account) {
        String updated = account.lastUpdated > 0 ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(account.lastUpdated)) : getString(R.string.accounts_unavailable);
        new CustomAlertDialog(this)
                .setTitleText(AccountTextUtils.displayNameOrNotSigned(this, account))
                .setIconResource(R.drawable.ic_ui_person)
                .setMessage(getString(R.string.accounts_details_message,
                        TextUtils.isEmpty(account.xuid) ? getString(R.string.accounts_unavailable) : account.xuid, updated))
                .setPositiveButton(getString(android.R.string.ok), null)
                .show();
    }

    private void confirmRemove(MsftAccountStore.MsftAccount account) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.accounts_remove))
                .setIconResource(R.drawable.ic_ui_delete)
                .setMessage(getString(R.string.accounts_remove_message, AccountTextUtils.displayNameOrNotSigned(this, account)))
                .setPositiveButton(getString(R.string.accounts_remove), v ->
                        runAccountAction(R.string.accounts_removing, () -> MsftAccountStore.remove(getApplicationContext(), account.id)))
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show();
    }

    private interface AccountAction {
        void run() throws Exception;
    }

    private void runAccountAction(int status, AccountAction action) {
        if (busy) return;
        setBusy(true, status);
        executor.execute(() -> {
            Exception failure = null;
            try {
                action.run();
            } catch (Exception error) {
                failure = error;
            }
            Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false, status);
                refreshUI();
                if (result != null) {
                    new CustomAlertDialog(this)
                            .setTitleText(getString(R.string.ms_login_failed))
                            .setMessage(MsftAuthManager.describeError(this, result))
                            .setPositiveButton(getString(android.R.string.ok), null)
                            .show();
                }
            });
        });
    }

    private void setBusy(boolean value, int status) {
        busy = value;
        addButton.setEnabled(!value);
        adapter.setBusy(value);
        findViewById(R.id.accounts_operation).setVisibility(value ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.accounts_operation_text)).setText(status);
    }

    private void refreshUI() {
        if (recycler == null || isFinishing() || isDestroyed()) return;
        List<MsftAccountStore.MsftAccount> accounts = MsftAccountStore.list(this);
        adapter.updateAccounts(accounts);
        ((TextView) findViewById(R.id.accounts_count)).setText(getString(R.string.accounts_count, accounts.size()));
        findViewById(R.id.empty_state_container).setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(accounts.isEmpty() ? View.GONE : View.VISIBLE);
        if (accounts.isEmpty()) recycler.stopScroll();
        refreshNavAccountUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    @Override
    protected void onNavAccountChanged() {
        refreshUI();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (recycler != null) recycler.setAdapter(null);
        super.onDestroy();
    }
}
