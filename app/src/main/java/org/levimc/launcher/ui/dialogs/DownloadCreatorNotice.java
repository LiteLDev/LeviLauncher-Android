package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.UiKit;

public final class DownloadCreatorNotice extends Dialog {
    private static final String PREFERENCES = "download_creator_notice";
    private static final String HIDE_NOTICE = "hide_notice";
    private static final String SOURCE_URL = "https://github.com/LiteLDev/LeviLauncher-Android/blob/HEAD/app/src/main/java/org/levimc/launcher/core/play";
    private final Activity activity;
    private final boolean dark;
    private final int textColor;
    private final int secondaryColor;
    private final int accentColor;
    private LinearLayout card;
    private CheckBox rememberChoice;
    private Button acknowledge;
    private ScrollView contentScroll;
    private DialogBackdrop backdropEffect;
    private boolean backdropApplied;

    private DownloadCreatorNotice(Activity activity) {
        super(activity);
        this.activity = activity;
        dark = UiKit.isDark(activity);
        textColor = dark ? 0xFFF4F4F5 : 0xFF18181B;
        secondaryColor = dark ? 0xFFBAC2CA : 0xFF52525B;
        accentColor = dark ? 0xFF5EEAD4 : 0xFF087F6B;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static DownloadCreatorNotice showIfNeeded(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || preferences(activity).getBoolean(HIDE_NOTICE, false)) return null;
        DownloadCreatorNotice notice = new DownloadCreatorNotice(activity);
        try {
            notice.show();
            return notice;
        } catch (WindowManager.BadTokenException error) {
            notice.dismiss();
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle(R.string.creator_notice_title);
        setCanceledOnTouchOutside(false);
        int width = Math.min(dp(620), availableBounds().width() - dp(32));
        card = new LinearLayout(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = Math.max(dp(96), availableBounds().height() - dp(24));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dark ? new int[]{0xFF25221C, 0xFF17191D, 0xFF17191D} : new int[]{0xFFFFFCF2, Color.WHITE, Color.WHITE});
        surface.setCornerRadius(dp(18));
        surface.setStroke(dp(1), dark ? 0xFF464236 : 0xFFE5DEC8);
        card.setBackground(surface);

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_creator_notice);
        icon.setImageTintList(ColorStateList.valueOf(dark ? 0xFFFBBF24 : 0xFFB45309));
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setBackground(shape(dark ? 0xFF3B2D13 : 0xFFFFEBC0, 12));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(getContext());
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView badge = label(R.string.creator_notice_label, 10, true);
        badge.setTextColor(dark ? 0xFFFBBF24 : 0xFF995700);
        badge.setLetterSpacing(0.08f);
        titles.addView(badge);
        TextView title = label(R.string.creator_notice_title, 18, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(3);
        titles.addView(title, titleParams);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1);
        titlesParams.setMarginStart(dp(12));
        header.addView(titles, titlesParams);
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        contentScroll = new ScrollView(getContext());
        contentScroll.setFillViewport(false);
        contentScroll.setClipToPadding(false);
        contentScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        contentScroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(14), 0, dp(14));
        LinearLayout message = new LinearLayout(getContext());
        message.setOrientation(LinearLayout.VERTICAL);
        TextView headline = label(R.string.creator_notice_headline, 15, true);
        message.addView(headline);
        TextView risk = label(R.string.creator_notice_risk, 12.5f, false);
        risk.setTextColor(secondaryColor);
        risk.setLineSpacing(dp(2), 1);
        LinearLayout.LayoutParams riskParams = new LinearLayout.LayoutParams(-1, -2);
        riskParams.topMargin = dp(8);
        message.addView(risk, riskParams);
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));

        View informationDivider = new View(getContext());
        informationDivider.setBackgroundColor(dark ? 0xFF363B3C : 0xFFE5E7E3);
        LinearLayout.LayoutParams informationDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        informationDividerParams.topMargin = dp(12);
        informationDividerParams.bottomMargin = dp(10);
        content.addView(informationDivider, informationDividerParams);
        TextView ownership = label(R.string.creator_notice_ownership, 12.5f, true);
        content.addView(ownership, new LinearLayout.LayoutParams(-1, -2));
        TextView sourceText = label(R.string.creator_notice_source_text, 12, false);
        sourceText.setTextColor(secondaryColor);
        sourceText.setLineSpacing(dp(1), 1);
        LinearLayout.LayoutParams sourceTextParams = new LinearLayout.LayoutParams(-1, -2);
        sourceTextParams.topMargin = dp(6);
        content.addView(sourceText, sourceTextParams);
        Button sourceLink = new Button(getContext());
        sourceLink.setText(R.string.creator_notice_source_link);
        sourceLink.setTextSize(12);
        sourceLink.setTextColor(accentColor);
        sourceLink.setAllCaps(false);
        sourceLink.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        sourceLink.setPadding(0, 0, 0, 0);
        sourceLink.setMinHeight(dp(40));
        sourceLink.setMinimumHeight(dp(40));
        android.graphics.drawable.Drawable codeIcon = getContext().getDrawable(R.drawable.ic_ui_code).mutate();
        codeIcon.setTint(accentColor);
        codeIcon.setBounds(0, 0, dp(14), dp(14));
        sourceLink.setCompoundDrawablesRelative(codeIcon, null, null, null);
        sourceLink.setCompoundDrawablePadding(dp(6));
        sourceLink.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22808080), null, shape(Color.WHITE, 6)));
        sourceLink.setOnClickListener(v -> openSource());
        content.addView(sourceLink, new LinearLayout.LayoutParams(-2, -2));
        contentScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        card.addView(contentScroll, new LinearLayout.LayoutParams(-1, -2, 1));

        View divider = new View(getContext());
        divider.setBackgroundColor(dark ? 0xFF363B3C : 0xFFE5E7E3);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout footer = new LinearLayout(getContext());
        boolean stacked = width < dp(340) || getContext().getResources().getConfiguration().fontScale > 1.35f;
        footer.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(8), 0, 0);
        rememberChoice = new CheckBox(getContext());
        rememberChoice.setText(R.string.creator_notice_remember);
        rememberChoice.setTextColor(secondaryColor);
        rememberChoice.setTextSize(12);
        rememberChoice.setMinHeight(dp(48));
        rememberChoice.setPadding(0, dp(4), dp(8), dp(4));
        rememberChoice.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accentColor, secondaryColor}));
        footer.addView(rememberChoice, new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1));
        acknowledge = new Button(getContext());
        acknowledge.setText(R.string.creator_notice_acknowledge);
        acknowledge.setAllCaps(false);
        acknowledge.setTextColor(Color.WHITE);
        acknowledge.setTextSize(13);
        acknowledge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        acknowledge.setMinWidth(0);
        acknowledge.setMinimumWidth(0);
        acknowledge.setMinHeight(dp(48));
        acknowledge.setMinimumHeight(dp(48));
        acknowledge.setPadding(dp(16), dp(10), dp(16), dp(10));
        acknowledge.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(0xFF087F6B, 10), shape(Color.WHITE, 10)));
        acknowledge.setOnClickListener(v -> {
            if (rememberChoice.isChecked()) preferences(activity).edit().putBoolean(HIDE_NOTICE, true).apply();
            dismiss();
        });
        LinearLayout.LayoutParams acknowledgeParams = new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
        if (stacked) acknowledgeParams.topMargin = dp(4);
        else acknowledgeParams.setMarginStart(dp(8));
        footer.addView(acknowledge, acknowledgeParams);
        card.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(card);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0.55f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setWindowAnimations(0);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(Math.min(dp(620), Math.max(dp(1), availableBounds().width() - dp(32))), -2);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
            WindowManager.LayoutParams position = window.getAttributes();
            position.gravity = Gravity.CENTER;
            position.x = 0;
            position.y = 0;
            position.horizontalMargin = 0;
            position.verticalMargin = 0;
            if (Build.VERSION.SDK_INT >= 30) position.setFitInsetsTypes(0);
            window.setAttributes(position);
            window.getDecorView().setPadding(0, 0, 0, 0);
        }
        if (backdropEffect == null) backdropEffect = DialogBackdrop.acquire(activity);
        backdropApplied = true;
    }

    private void clearBackdrop() {
        if (backdropEffect != null) backdropEffect.release();
        backdropEffect = null;
        backdropApplied = false;
    }

    @Override public void dismiss() { clearBackdrop(); super.dismiss(); }
    @Override protected void onStop() { clearBackdrop(); super.onStop(); }

    private void openSource() {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))); }
        catch (ActivityNotFoundException error) { Toast.makeText(activity, R.string.game_download_browser_unavailable, Toast.LENGTH_SHORT).show(); }
    }

    private TextView label(int text, float size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Rect availableBounds() {
        View decor = activity.getWindow().getDecorView();
        Rect bounds = new Rect();
        decor.getWindowVisibleDisplayFrame(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) bounds.set(0, 0,
                getContext().getResources().getDisplayMetrics().widthPixels, getContext().getResources().getDisplayMetrics().heightPixels);
        return bounds;
    }

    private int dp(float value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }

}
