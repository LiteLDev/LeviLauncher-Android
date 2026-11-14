package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Appearance mode, matching LeviLauncher for Windows: light, dark, a manual dark-mode schedule,
 * sunrise/sunset, or follow system. The stored integers of the original three modes are kept.
 */
public class ThemeManager {
    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;
    public static final int MODE_SCHEDULE = 3;
    public static final int MODE_SUN = 4;

    public static final String DEFAULT_SCHEDULE_START = "20:00";
    public static final String DEFAULT_SCHEDULE_END = "07:00";

    private static final String THEME_PREFS = "theme_prefs";
    private static final String THEME_MODE_KEY = "theme_mode";
    private static final String SCHEDULE_START_KEY = "schedule_start";
    private static final String SCHEDULE_END_KEY = "schedule_end";
    /** Same assumption as the Windows launcher: mid latitude, longitude from the time zone. */
    private static final double APPROX_LATITUDE = 35.0;

    private static int sThemeChangeGeneration = 0;
    private final SharedPreferences prefs;
    private final Activity activity;

    public ThemeManager(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
    }

    public void applyTheme() {
        updateNightMode();
    }

    public void setThemeMode(int mode) {
        int previous = prefs.getInt(THEME_MODE_KEY, MODE_DARK);
        prefs.edit().putInt(THEME_MODE_KEY, mode).apply();
        updateNightMode();
        if (previous != mode) {
            sThemeChangeGeneration++;
        }
        if (activity != null && previous != mode) {
            activity.recreate();
        }
    }

    /** Sets the dark-mode window used by {@link #MODE_SCHEDULE} ("HH:mm"). */
    public void setSchedule(String start, String end) {
        prefs.edit().putString(SCHEDULE_START_KEY, normalizeTime(start, DEFAULT_SCHEDULE_START))
                .putString(SCHEDULE_END_KEY, normalizeTime(end, DEFAULT_SCHEDULE_END)).apply();
        if (getCurrentMode() == MODE_SCHEDULE) refreshScheduledMode();
    }

    public String getScheduleStart() {
        return normalizeTime(prefs.getString(SCHEDULE_START_KEY, DEFAULT_SCHEDULE_START), DEFAULT_SCHEDULE_START);
    }

    public String getScheduleEnd() {
        return normalizeTime(prefs.getString(SCHEDULE_END_KEY, DEFAULT_SCHEDULE_END), DEFAULT_SCHEDULE_END);
    }

    private void updateNightMode() {
        int mode = getCurrentMode();
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case MODE_SCHEDULE:
            case MODE_SUN:
                AppCompatDelegate.setDefaultNightMode(isScheduledDark()
                        ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /**
     * For the time-based modes, re-applies the night mode when the clock crossed a boundary.
     * Returns true when the night mode changed; the caller recreates the activity.
     */
    public boolean refreshScheduledMode() {
        int mode = getCurrentMode();
        if (mode != MODE_SCHEDULE && mode != MODE_SUN) return false;
        int wanted = isScheduledDark() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() == wanted) return false;
        AppCompatDelegate.setDefaultNightMode(wanted);
        sThemeChangeGeneration++;
        return true;
    }

    private boolean isScheduledDark() {
        int now = minutesOfDay(Calendar.getInstance());
        if (getCurrentMode() == MODE_SUN) {
            String[] sun = getSunTimes();
            return !inRange(now, parseMinutes(sun[0]), parseMinutes(sun[1]));
        }
        return inRange(now, parseMinutes(getScheduleStart()), parseMinutes(getScheduleEnd()));
    }

    /** True when {@code now} is in [start, end), wrapping past midnight. */
    private static boolean inRange(int now, int start, int end) {
        if (start == end) return false;
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    public int getCurrentMode() {
        int mode = prefs.getInt(THEME_MODE_KEY, MODE_DARK);
        if (mode < MODE_FOLLOW_SYSTEM || mode > MODE_SUN) {
            mode = MODE_DARK;
            prefs.edit().putInt(THEME_MODE_KEY, mode).apply();
        }
        return mode;
    }

    /** Quick toggle in the navigation, shown only for the manual light and dark modes (as on Windows). */
    public boolean canQuickToggle() {
        int mode = getCurrentMode();
        return mode == MODE_LIGHT || mode == MODE_DARK;
    }

    public static int getThemeChangeGeneration() {
        return sThemeChangeGeneration;
    }

    public static int getSavedMode(Context context) {
        return context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
                .getInt(THEME_MODE_KEY, MODE_DARK);
    }

    /** Today's local sunrise and sunset as "HH:mm", computed like the Windows launcher. */
    public static String[] getSunTimes() {
        Calendar now = Calendar.getInstance();
        TimeZone tz = now.getTimeZone();
        Calendar jan = Calendar.getInstance(tz);
        jan.set(now.get(Calendar.YEAR), Calendar.JANUARY, 1, 12, 0, 0);
        Calendar jul = Calendar.getInstance(tz);
        jul.set(now.get(Calendar.YEAR), Calendar.JULY, 1, 12, 0, 0);
        int standardOffsetMs = Math.min(tz.getOffset(jan.getTimeInMillis()), tz.getOffset(jul.getTimeInMillis()));
        double lon = standardOffsetMs / 3600000.0 * 15.0;

        double n = now.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (n - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double rad = Math.PI / 180;
        double cosH = Math.cos(90.833 * rad) / (Math.cos(APPROX_LATITUDE * rad) * Math.cos(decl))
                - Math.tan(APPROX_LATITUDE * rad) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) return new String[]{"06:00", "18:00"};
        double h = Math.acos(cosH) / rad;
        double riseUtc = 720 - 4 * (lon + h) - eqTime;
        double setUtc = 720 - 4 * (lon - h) - eqTime;
        int offsetMin = tz.getOffset(now.getTimeInMillis()) / 60000;
        return new String[]{formatMinutes((int) Math.round(riseUtc) + offsetMin),
                formatMinutes((int) Math.round(setUtc) + offsetMin)};
    }

    private static int minutesOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static String formatMinutes(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    public static int parseMinutes(String time) {
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String normalizeTime(String time, String fallback) {
        int m = time == null ? -1 : parseMinutes(time);
        return m < 0 ? fallback : formatMinutes(m);
    }
}
