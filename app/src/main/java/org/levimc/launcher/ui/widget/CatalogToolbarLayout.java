package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public final class CatalogToolbarLayout extends ViewGroup {
    private boolean wrapped;
    private int rowHeight;
    private final int gap;

    public CatalogToolbarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        gap = Math.round(8 * getResources().getDisplayMetrics().density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int controls = 0;
        int count = 0;
        rowHeight = 0;
        for (int i = 1; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            controls += child.getMeasuredWidth() + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            count++;
        }
        wrapped = available - controls < Math.round(160 * getResources().getDisplayMetrics().density);
        if (wrapped && count > 0 && controls - gap > available) {
            int limit = Math.max(0, (available - gap * (count - 1)) / count);
            controls = 0;
            for (int i = 1; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                controls += child.getMeasuredWidth() + gap;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
        }
        View search = getChildAt(0);
        int searchWidth = wrapped ? available : Math.max(0, available - controls);
        int minimum = Math.round(44 * getResources().getDisplayMetrics().density);
        search.measure(MeasureSpec.makeMeasureSpec(searchWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(minimum, rowHeight), MeasureSpec.EXACTLY));
        rowHeight = Math.max(rowHeight, search.getMeasuredHeight());
        int height = rowHeight + (wrapped && count > 0 ? rowHeight + gap : 0);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int cursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
        View search = getChildAt(0);
        place(search, cursor, getPaddingTop(), rtl);
        cursor += (rtl ? -1 : 1) * (search.getMeasuredWidth() + gap);
        if (wrapped) cursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
        int y = getPaddingTop() + (wrapped ? rowHeight + gap : 0);
        for (int i = 1; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            place(child, cursor, y + (rowHeight - child.getMeasuredHeight()) / 2, rtl);
            cursor += (rtl ? -1 : 1) * (child.getMeasuredWidth() + gap);
        }
    }

    private void place(View child, int x, int y, boolean rtl) {
        int left = rtl ? x - child.getMeasuredWidth() : x;
        child.layout(left, y, left + child.getMeasuredWidth(), y + child.getMeasuredHeight());
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    @Override public LayoutParams generateLayoutParams(AttributeSet attrs) { return new LayoutParams(getContext(), attrs); }
}
