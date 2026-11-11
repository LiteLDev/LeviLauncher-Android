package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.levimc.launcher.R;

public class CenteredNavbarLayout extends LinearLayout {
    public CenteredNavbarLayout(Context context) {
        this(context, null);
    }

    public CenteredNavbarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getChildCount() != 3) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        View start = getChildAt(0);
        View middle = getChildAt(1);
        View end = getChildAt(2);
        LayoutParams startParams = (LayoutParams) start.getLayoutParams();
        LayoutParams middleParams = (LayoutParams) middle.getLayoutParams();
        LayoutParams endParams = (LayoutParams) end.getLayoutParams();
        int heightPadding = getPaddingTop() + getPaddingBottom();
        int naturalWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        start.measure(naturalWidth, getChildMeasureSpec(heightMeasureSpec,
                heightPadding + startParams.topMargin + startParams.bottomMargin, startParams.height));
        end.measure(naturalWidth, getChildMeasureSpec(heightMeasureSpec,
                heightPadding + endParams.topMargin + endParams.bottomMargin, endParams.height));

        int startInset = getPaddingStart() + startParams.getMarginStart()
                + startParams.getMarginEnd() + middleParams.getMarginStart();
        int endInset = getPaddingEnd() + endParams.getMarginStart()
                + endParams.getMarginEnd() + middleParams.getMarginEnd();
        int sideWidth = Math.max(start.getMeasuredWidth() + startInset,
                end.getMeasuredWidth() + endInset);
        int offset = Math.min(getResources().getDimensionPixelSize(R.dimen.levi_navbar_center_offset),
                sideWidth - endInset - end.getMeasuredWidth());
        startParams.width = sideWidth - startInset + offset;
        endParams.width = sideWidth - endInset - offset;
        fitTabPadding(middle, MeasureSpec.getSize(widthMeasureSpec) - 2 * sideWidth, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void fitTabPadding(View middle, int availableWidth, int heightMeasureSpec) {
        if (!(middle instanceof HorizontalScrollView)) return;
        View child = ((HorizontalScrollView) middle).getChildAt(0);
        if (!(child instanceof LinearLayout)) return;
        LinearLayout tabs = (LinearLayout) child;
        int count = 0;
        int currentPadding = 0;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getVisibility() == GONE) continue;
            count++;
            currentPadding += tab.getPaddingStart() + tab.getPaddingEnd();
        }
        if (count == 0) return;
        tabs.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                getChildMeasureSpec(heightMeasureSpec, getPaddingTop() + getPaddingBottom(),
                        tabs.getLayoutParams().height));
        int contentWidth = tabs.getMeasuredWidth() - currentPadding;
        int preferred = getResources().getDimensionPixelSize(R.dimen.levi_navbar_item_padding);
        int minimum = contentWidth <= availableWidth ? 0
                : Math.round(getResources().getDisplayMetrics().density);
        int padding = Math.max(minimum, Math.min(preferred, (availableWidth - contentWidth) / (2 * count)));
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getPaddingStart() != padding || tab.getPaddingEnd() != padding) {
                tab.setPaddingRelative(padding, tab.getPaddingTop(), padding, tab.getPaddingBottom());
            }
        }
    }
}
