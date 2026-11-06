package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.PopupMenu;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import org.levimc.launcher.R;

import java.util.Locale;

public class LanguageManager {

    private static final String PREFS_NAME = "settings";
    private static final String LANGUAGE_KEY = "language";
    private final Activity activity;

    public LanguageManager(Activity activity) {
        this.activity = activity;
    }

    public void applySavedLanguage() {
        String tag = getSavedLanguage(activity);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    public static String getSavedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedTag = prefs.getString(LANGUAGE_KEY, Locale.getDefault().toLanguageTag());
        String tag = normalizeLanguageTag(savedTag);
        if (!tag.equals(savedTag)) {
            prefs.edit().putString(LANGUAGE_KEY, tag).apply();
        }
        return tag;
    }

    private static String normalizeLanguageTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return Locale.getDefault().toLanguageTag();
        }
        int separator = tag.indexOf('-');
        String language = separator < 0 ? tag : tag.substring(0, separator);
        if ("idn".equalsIgnoreCase(language) || "in".equalsIgnoreCase(language)) {
            return "id" + (separator < 0 ? "" : tag.substring(separator));
        }
        return tag;
    }

    public void setAppLanguage(String languageCode) {
        languageCode = normalizeLanguageTag(languageCode);
        SharedPreferences.Editor editor = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE).edit();
        editor.putString(LANGUAGE_KEY, languageCode);
        editor.apply();
        setLocale(languageCode, true);
    }

    private void setLocale(String languageCode, boolean recreateActivity) {
        Locale locale = Locale.forLanguageTag(languageCode);
        Locale.setDefault(locale);
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCode));
        if (recreateActivity) {
            activity.recreate();
        }
    }

    public void showLanguageMenu(View anchor) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenuInflater().inflate(R.menu.language_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();

            // Keep English at the top (default language).
            // Sort all other languages alphabetically by their display name.
            if (itemId == R.id.action_english) {
                setAppLanguage("en");
                return true;
            } else if (itemId == R.id.action_chinese) {
                setAppLanguage("zh-CN");
                return true;
            } else if (itemId == R.id.action_french) {
                setAppLanguage("fr");
                return true;
            } else if (itemId == R.id.action_hindi) {
                setAppLanguage("hi");
                return true;
            } else if (itemId == R.id.action_indonesian) {
                setAppLanguage("id");
                return true;
            } else if (itemId == R.id.action_japanese) {
                setAppLanguage("ja");
                return true;
            } else if (itemId == R.id.action_portuguese) {
                setAppLanguage("pt");
                return true;
            } else if (itemId == R.id.action_russian) {
                setAppLanguage("ru");
                return true;
            } else if (itemId == R.id.action_spanish) {
                setAppLanguage("es");
                return true;
            } else if (itemId == R.id.action_turkish) {
                setAppLanguage("tr");
                return true;
            } else if (itemId == R.id.action_vietnamese) {
                setAppLanguage("vi");
                return true;
            }
            return false;
        });

        popup.show();
    }

    public String getCurrentLanguage() {
        LocaleListCompat list = AppCompatDelegate.getApplicationLocales();
        if (list.isEmpty()) {
            return getSavedLanguage(activity);
        }
        return normalizeLanguageTag(list.get(0).toLanguageTag());
    }
}
