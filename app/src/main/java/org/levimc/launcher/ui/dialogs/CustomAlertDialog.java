package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.UiKit;

public class CustomAlertDialog extends Dialog {
    private String mTitle, mMessage, mPositiveText, mNegativeText, mNeutralText;
    private View.OnClickListener mPositiveListener, mNegativeListener, mNeutralListener;
    private Button mPositiveButton;
    private String[] mItems;
    private DialogInterface.OnClickListener mItemClickListener;
    private View mCustomView;
    private boolean mBlurBackground = true;
    private int mTitleColor;
    private int mIconResource;
    private boolean mDismissing;
    private Runnable mDismissAnimationEndListener;

    public CustomAlertDialog(Context context) { super(context); }
    public CustomAlertDialog setTitleText(String title) { mTitle = title; return this; }
    public CustomAlertDialog setMessage(String message) { mMessage = message; return this; }
    public CustomAlertDialog setPositiveButton(String text, View.OnClickListener listener) { mPositiveText = text; mPositiveListener = listener; return this; }
    public CustomAlertDialog setNegativeButton(String text, View.OnClickListener listener) { mNegativeText = text; mNegativeListener = listener; return this; }
    public CustomAlertDialog setNeutralButton(String text, View.OnClickListener listener) { mNeutralText = text; mNeutralListener = listener; return this; }
    public CustomAlertDialog setItems(String[] items, DialogInterface.OnClickListener listener) { mItems = items; mItemClickListener = listener; return this; }
    public CustomAlertDialog setCustomView(View view) { mCustomView = view; return this; }
    public CustomAlertDialog setBlurBackground(boolean blur) { mBlurBackground = blur; return this; }
    public CustomAlertDialog setTitleColor(int color) { mTitleColor = color; return this; }
    public CustomAlertDialog setIconResource(int icon) { mIconResource = icon; return this; }
    public CustomAlertDialog setUseBorderedBackground(boolean bordered) { return this; }
    public CustomAlertDialog setOnDismissAnimationEndListener(Runnable listener) { mDismissAnimationEndListener = listener; return this; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.alert_dialog_custom);
        setCanceledOnTouchOutside(false);
        TextView title = findViewById(R.id.tv_title);
        title.setText(mTitle == null ? "" : mTitle);
        findViewById(R.id.dialog_header).setVisibility(mTitle == null || mTitle.isEmpty() ? View.GONE : View.VISIBLE);
        ImageView icon = findViewById(R.id.dialog_header_icon);
        if (mIconResource != 0) {
            icon.setImageResource(mIconResource);
            icon.setImageTintList(ColorStateList.valueOf(mTitleColor != 0 ? mTitleColor : UiKit.accentText(getContext())));
            icon.setBackground(LauncherDialogs.shape(getContext(), LauncherDialogs.raised(getContext()), 10, true));
            icon.setVisibility(View.VISIBLE);
        }
        TextView message = findViewById(R.id.tv_message);
        message.setText(mMessage == null ? "" : mMessage);
        message.setVisibility(mMessage == null || mMessage.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout custom = findViewById(R.id.custom_view_container);
        RecyclerView items = findViewById(R.id.items_recycler_view);
        if (mCustomView != null) {
            if (mCustomView.getParent() instanceof ViewGroup) ((ViewGroup) mCustomView.getParent()).removeView(mCustomView);
            custom.addView(mCustomView);
            custom.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams space = (LinearLayout.LayoutParams) custom.getLayoutParams();
            space.topMargin = message.getVisibility() == View.VISIBLE ? dp(12) : 0;
            custom.setLayoutParams(space);
        } else if (mItems != null && mItems.length > 0) {
            findViewById(R.id.message_scroll_view).setVisibility(View.GONE);
            items.setVisibility(View.VISIBLE);
            items.setItemAnimator(null);
            items.setLayoutManager(new LinearLayoutManager(getContext()));
            items.setAdapter(new ItemsAdapter(mItems, index -> {
                if (mItemClickListener != null) mItemClickListener.onClick(this, index);
                dismiss();
            }));
        }
        mPositiveButton = findViewById(R.id.btn_positive);
        Button negative = findViewById(R.id.btn_negative);
        Button neutral = findViewById(R.id.btn_neutral);
        bindButton(negative, mNegativeText, mNegativeListener, false);
        bindButton(neutral, mNeutralText, mNeutralListener, false);
        bindButton(mPositiveButton, mPositiveText, mPositiveListener, true);
        LinearLayout footer = findViewById(R.id.btn_container);
        int count = (mNegativeText != null ? 1 : 0) + (mNeutralText != null ? 1 : 0) + (mPositiveText != null ? 1 : 0);
        footer.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        findViewById(R.id.dialog_action_divider).setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        findViewById(R.id.dialog_action_divider).setBackgroundColor(LauncherDialogs.border(getContext()));
        int width = Math.min(dp(520), getContext().getResources().getDisplayMetrics().widthPixels - dp(32));
        int available = width - dp(32);
        float required = dp(8 * Math.max(0, count - 1));
        for (Button button : new Button[]{negative, neutral, mPositiveButton}) if (button.getVisibility() == View.VISIBLE) required += Math.max(dp(88), button.getPaint().measureText(button.getText().toString()) + button.getPaddingLeft() + button.getPaddingRight());
        boolean distribute = required > available || count == 3;
        boolean first = true;
        for (Button button : new Button[]{negative, neutral, mPositiveButton}) {
            if (button.getVisibility() != View.VISIBLE) continue;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(distribute ? 0 : -2, -2, distribute ? 1 : 0);
            params.setMarginStart(first ? 0 : dp(8));
            button.setLayoutParams(params);
            if (!distribute) button.setMinWidth(dp(88));
            first = false;
        }
        LauncherDialogs.bind(this, 520, mBlurBackground);
        if (getWindow() != null) {
            getWindow().setLayout(width, -2);
            getWindow().setWindowAnimations(0);
        }
    }

    private void bindButton(Button button, String label, View.OnClickListener listener, boolean primary) {
        button.setVisibility(label == null ? View.GONE : View.VISIBLE);
        if (label == null) return;
        button.setText(label);
        LauncherDialogs.button(button, primary);
        button.setOnClickListener(view -> { if (listener != null) listener.onClick(view); dismiss(); });
    }

    @Override public void show() {
        mDismissing = false;
        super.show();
        LauncherDialogs.style(this, 520);
        TextView title = findViewById(R.id.tv_title);
        if (mTitleColor != 0) title.setTextColor(mTitleColor);
        View root = findViewById(R.id.dialog_shell);
        root.animate().withEndAction(null).cancel();
        root.setAlpha(0f); root.setTranslationY(dp(6));
        root.animate().alpha(1f).translationY(0).setDuration(150L).start();
    }

    @Override public void dismiss() {
        if (!isShowing() || mDismissing) return;
        mDismissing = true;
        View root = findViewById(R.id.dialog_shell);
        if (root == null || !root.isAttachedToWindow()) { finishDismiss(true); return; }
        root.animate().alpha(0f).translationY(dp(4)).setDuration(120L).withEndAction(() -> finishDismiss(true)).start();
    }

    private void finishDismiss(boolean notify) {
        View root = findViewById(R.id.dialog_shell);
        if (root != null) root.animate().withEndAction(null).cancel();
        try { super.dismiss(); }
        catch (RuntimeException ignored) {}
        if (notify) {
            Runnable callback = mDismissAnimationEndListener;
            mDismissAnimationEndListener = null;
            if (callback != null) callback.run();
        }
    }

    public void dismissImmediately() { mDismissing = true; finishDismiss(false); }
    public Button getPositiveButton() { return mPositiveButton; }
    private int dp(float value) { return LauncherDialogs.dp(getContext(), value); }

    private static final class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.Holder> {
        interface Listener { void selected(int index); }
        final String[] items;
        final Listener listener;
        ItemsAdapter(String[] items, Listener listener) { this.items = items; this.listener = listener; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context context = parent.getContext();
            TextView row = new TextView(context);
            row.setTextColor(LauncherDialogs.text(context)); row.setTextSize(13.5f);
            row.setMinHeight(LauncherDialogs.dp(context, 48));
            row.setPadding(LauncherDialogs.dp(context, 12), LauncherDialogs.dp(context, 12), LauncherDialogs.dp(context, 12), LauncherDialogs.dp(context, 12));
            row.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22808080),
                    LauncherDialogs.shape(context, LauncherDialogs.raised(context), 10, false), LauncherDialogs.shape(context, Color.WHITE, 10, false)));
            RecyclerView.LayoutParams layout = new RecyclerView.LayoutParams(-1, -2);
            layout.bottomMargin = LauncherDialogs.dp(context, 6); row.setLayoutParams(layout);
            return new Holder(row);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int index) { holder.text.setText(items[index]); holder.text.setOnClickListener(view -> listener.selected(index)); }
        @Override public int getItemCount() { return items.length; }
        static final class Holder extends RecyclerView.ViewHolder { final TextView text; Holder(TextView view) { super(view); text = view; } }
    }
}
