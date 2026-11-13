package org.levimc.launcher.ui.dialogs;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

public class LauncherDialogLayout extends LinearLayout {
    public LauncherDialogLayout(Context context) { super(context); }
    public LauncherDialogLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public LauncherDialogLayout(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        Rect visible = new Rect();
        getWindowVisibleDisplayFrame(visible);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int available = visible.height() > 0 ? Math.min(visible.height(), screenHeight) : screenHeight;
        int limit = Math.max(Math.round(96 * getResources().getDisplayMetrics().density),
                available - Math.round(24 * getResources().getDisplayMetrics().density));
        if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
    }
}
