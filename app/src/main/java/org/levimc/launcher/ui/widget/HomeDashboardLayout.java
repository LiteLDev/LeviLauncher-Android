package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public class HomeDashboardLayout extends LinearLayout {
    public HomeDashboardLayout(Context context) {
        this(context, null);
    }

    public HomeDashboardLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getChildCount() != 2 || !(getChildAt(1) instanceof ViewGroup)
                || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        View launch = getChildAt(0);
        ViewGroup cards = (ViewGroup) getChildAt(1);
        ViewGroup.LayoutParams launchParams = launch.getLayoutParams();
        int originalHeight = launchParams.height;
        if (originalHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
            int horizontalPadding = getPaddingLeft() + getPaddingRight();
            if (launchParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) launchParams;
                horizontalPadding += margins.leftMargin + margins.rightMargin;
            }
            measureFresh(launch, getChildMeasureSpec(widthMeasureSpec, horizontalPadding, launchParams.width),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            launchParams.height = launch.getMeasuredHeight();
        }
        forceLayoutTree(launch);
        forceLayoutTree(cards);
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } finally {
            launchParams.height = originalHeight;
        }

        int requiredHeight = 0;
        int rowHeight = 0;
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            ScrollView scroll = findScrollView(card);
            if (scroll == null || scroll.getChildCount() == 0) continue;
            View content = scroll.getChildAt(0);
            if (!(content instanceof ViewGroup)) continue;
            ViewGroup rows = (ViewGroup) content;
            int rowsHeight = rows.getPaddingTop() + rows.getPaddingBottom();
            int count = 0;
            for (int j = 0; j < rows.getChildCount() && count < 3; j++) {
                View row = rows.getChildAt(j);
                if (row.getVisibility() == GONE) continue;
                int height = row.getMeasuredHeight() + verticalMargins(row);
                rowsHeight += height;
                rowHeight = Math.max(rowHeight, height);
                count++;
            }
            if (count == 0) continue;
            requiredHeight = Math.max(requiredHeight, card.getMeasuredHeight() - scroll.getMeasuredHeight()
                    + scroll.getPaddingTop() + scroll.getPaddingBottom() + rowsHeight + verticalMargins(card));
        }

        int spare = Math.min(rowHeight, cards.getMeasuredHeight() - requiredHeight);
        if (requiredHeight <= 0 || spare <= 0) return;
        measureFresh(launch, MeasureSpec.makeMeasureSpec(launch.getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(launch.getMeasuredHeight() + spare, MeasureSpec.EXACTLY));
        measureFresh(cards, MeasureSpec.makeMeasureSpec(cards.getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cards.getMeasuredHeight() - spare, MeasureSpec.EXACTLY));
    }

    private static void measureFresh(View view, int widthMeasureSpec, int heightMeasureSpec) {
        forceLayoutTree(view);
        view.measure(widthMeasureSpec, heightMeasureSpec);
    }

    private static void forceLayoutTree(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                forceLayoutTree(group.getChildAt(i));
            }
        }
    }

    private static ScrollView findScrollView(View view) {
        if (view.getVisibility() == GONE) return null;
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView scroll = findScrollView(group.getChildAt(i));
                if (scroll != null) return scroll;
            }
        }
        return null;
    }

    private static int verticalMargins(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return 0;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        return margins.topMargin + margins.bottomMargin;
    }
}
