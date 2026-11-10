package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Source;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;

import java.util.Locale;

public final class InstallByIdDialog {
    private InstallByIdDialog() {
    }

    public static void show(Activity a, GameVersion target, @Nullable PackageActions.Done done) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        View body = LayoutInflater.from(a).inflate(R.layout.dialog_pkg_install_by_id, null, false);
        EditText input = body.findViewById(R.id.pkg_install_id_input);
        TextView status = body.findViewById(R.id.pkg_install_id_status);
        InstallByIdDialog.Request[] current = {InstallByIdDialog.Request.parse("")};

        CustomAlertDialog dialog = new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.pkg_install_id_title))
                .setCustomView(body)
                .setPositiveButton(a.getString(R.string.pkg_action_install), v -> install(a, target, current[0], done))
                .setNeutralButton(a.getString(R.string.pkg_install_id_open_page), v -> {
                    if (current[0].hasPackagePage()) {
                        a.startActivity(PackageActivity.intent(a, current[0].identifier, target));
                    }
                })
                .setNegativeButton(a.getString(R.string.dialog_negative_cancel), null);
        dialog.show();

        Button positive = dialog.getPositiveButton();
        View neutral = dialog.findViewById(R.id.btn_neutral);
        Runnable render = () -> {
            InstallByIdDialog.Request r = current[0];
            boolean ready = r.kind != InstallByIdDialog.Request.Kind.INVALID;
            if (positive != null) {
                positive.setEnabled(ready);
                positive.setAlpha(ready ? 1f : 0.5f);
            }
            if (neutral != null) {
                neutral.setEnabled(r.hasPackagePage());
                neutral.setAlpha(r.hasPackagePage() ? 1f : 0.5f);
            }
            String text;
            int color;
            if (ready) {
                text = a.getString(R.string.pkg_install_id_ready, r.label(), PackagesActivity.Instances.displayName(target));
                color = a.getColor(R.color.text_secondary);
            } else if (input.getText().toString().trim().isEmpty()) {
                text = "";
                color = a.getColor(R.color.text_secondary);
            } else if (PackageService.ERR_PACKAGE_VERSION_REQUIRED.equals(r.code)) {
                text = a.getString(R.string.pkg_install_id_version_hint);
                color = a.getColor(R.color.pkg_log_warning);
            } else {
                text = a.getString(R.string.pkg_install_id_invalid) + (r.detail.isEmpty() ? "" : "\n" + r.detail);
                color = a.getColor(R.color.error);
            }
            status.setText(text);
            status.setTextColor(color);
            status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
        input.addTextChangedListener(SimpleTextWatcher.after(s -> {
            current[0] = InstallByIdDialog.Request.parse(s.toString());
            render.run();
        }));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && current[0].kind != InstallByIdDialog.Request.Kind.INVALID) {
                dialog.dismiss();
                install(a, target, current[0], done);
                return true;
            }
            return false;
        });
        render.run();
    }

    private static void install(Activity a, GameVersion target, InstallByIdDialog.Request r,
                                @Nullable PackageActions.Done done) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        switch (r.kind) {
            case GITHUB:
                PackageActions.installIdentifier(a, target, r.identifier, r.version, done);
                break;
            case SOURCE:
                String source = r.source;
                PackageActions.installSource(a, target, source, () -> source, done);
                break;
            default:
                PackageActions.toastError(a, r.code, r.detail);
                break;
        }
    }

    public final static class Request {
        public enum Kind {
            GITHUB,
            SOURCE,
            INVALID
        }

        public final Kind kind;
        public final String identifier;
        public final String version;
        public final String source;
        public final String code;
        public final String detail;

        private Request(Kind kind, String identifier, String version, String source, String code,
                                  String detail) {
            this.kind = kind;
            this.identifier = identifier;
            this.version = version;
            this.source = source;
            this.code = code;
            this.detail = detail;
        }

        private static Request invalid(String code, String identifier, String detail) {
            return new Request(Kind.INVALID, identifier, "", "", code, detail);
        }

        public boolean hasPackagePage() {
            return !identifier.isEmpty();
        }

        public String label() {
            return kind == Kind.GITHUB ? identifier + "@" + version : source;
        }

        public static Request parse(String text) {
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) {
                return invalid(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, "", "");
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("https://")) {
                String gitHub = gitHubRepository(t);
                if (gitHub == null) {
                    String reason = Source.check(t);
                    if (reason != null) {
                        return invalid(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, "", reason);
                    }
                    return new Request(Kind.SOURCE, "", "", t, "", "");
                }
                t = gitHub;
            } else if (lower.contains("://") || lower.startsWith("file:") || t.startsWith("/") || t.startsWith(".")
                    || t.startsWith("\\") || t.startsWith("~")) {
                // http://, other schemes and local paths.
                return invalid(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, "", "");
            }

            String base = t;
            String version = "";
            int at = t.indexOf('@');
            if (at >= 0) {
                base = t.substring(0, at).trim();
                version = t.substring(at + 1).trim();
            }
            if (PackageService.Request.parse(base) != null) {
                if (version.isEmpty()) {
                    return invalid(PackageService.ERR_PACKAGE_VERSION_REQUIRED, base, "");
                }
                if (version.indexOf('@') >= 0 || version.indexOf(' ') >= 0) {
                    return invalid(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, base, "");
                }
                return new Request(Kind.GITHUB, base, version, "", "", "");
            }
            String reason = Source.check(t);
            if (reason != null) {
                return invalid(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, "", reason);
            }
            if (t.indexOf('@') < 0) {
                return invalid(PackageService.ERR_PACKAGE_VERSION_REQUIRED, "", "");
            }
            return new Request(Kind.SOURCE, "", "", t, "", "");
        }

        static String gitHubRepository(String url) {
            String rest = url.substring("https://".length());
            String tail = "";
            int cut = indexOfAny(rest, '#', '@');
            if (cut >= 0) {
                tail = rest.substring(cut);
                rest = rest.substring(0, cut);
            }
            while (rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            if (rest.toLowerCase(Locale.ROOT).endsWith(".git")) {
                rest = rest.substring(0, rest.length() - 4);
            }
            String[] parts = rest.split("/", -1);
            if (parts.length != 3 || !parts[0].equalsIgnoreCase("github.com") && !parts[0].equalsIgnoreCase("www.github.com")) {
                return null;
            }
            String repo = parts[1] + "/" + parts[2];
            if (PackageService.Request.parse(repo) == null) {
                return null;
            }
            return repo + tail;
        }

        private static int indexOfAny(String s, char a, char b) {
            int i = s.indexOf(a);
            int j = s.indexOf(b);
            if (i < 0) {
                return j;
            }
            return j < 0 ? i : Math.min(i, j);
        }

        @Override
        public String toString() {
            return kind + "{" + identifier + "@" + version + " " + source + " " + code + "}";
        }
    }
}
