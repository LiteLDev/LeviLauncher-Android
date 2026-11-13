package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class CpsDisplayOverlay {
    private static final int UPDATE_INTERVAL = 100;
    private static final double CPS_WINDOW = 1.0;
    private static final float DRAG_THRESHOLD = 10f;

    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Double> clickTimes = new ArrayList<>();

    private View overlayView;
    private TextView statsText;
    private WindowManager.LayoutParams wmParams;
    private boolean isShowing = false;
    private boolean runtimeActive = true;
    private boolean updatesRunning;
    private int lastDisplayValue = Integer.MIN_VALUE;
    private Runnable pendingShowRunnable;

    private float initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private boolean isLocked = false;
    private long touchDownTime = 0;

    private int lastButtonState = 0;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!updatesRunning || !canUpdate()) { updatesRunning = false; return; }
            updateDisplay();
            handler.postDelayed(this, UPDATE_INTERVAL);
        }
    };

    public CpsDisplayOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public void registerClick() {
        synchronized (clickTimes) {
            double now = getTime();
            clickTimes.removeIf(time -> now - time > CPS_WINDOW);
            clickTimes.add(now);
        }
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (!isShowing) return false;
        
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (buttonState == 0) {
                registerClick();
                return true;
            }
        }
        
        return false;
    }

    public boolean handleMouseEvent(MotionEvent event) {
        if (!isShowing) return false;
        
        int action = event.getActionMasked();
        
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            int buttonState = event.getButtonState();
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0 && 
                (lastButtonState & MotionEvent.BUTTON_PRIMARY) == 0) {
                registerClick();
            }
            if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0 && 
                (lastButtonState & MotionEvent.BUTTON_SECONDARY) == 0) {
                registerClick();
            }
            lastButtonState = buttonState;
            return true;
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            lastButtonState = event.getButtonState();
            return true;
        }
        
        if (action == MotionEvent.ACTION_DOWN && event.getButtonState() != 0) {
            int buttonState = event.getButtonState();
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0 && 
                (lastButtonState & MotionEvent.BUTTON_PRIMARY) == 0) {
                registerClick();
                lastButtonState = buttonState;
                return true;
            }
        }
        
        return false;
    }

    private double getTime() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    private int getCps() {
        synchronized (clickTimes) {
            double now = getTime();
            Iterator<Double> it = clickTimes.iterator();
            while (it.hasNext()) {
                if (now - it.next() > CPS_WINDOW) {
                    it.remove();
                }
            }
            return clickTimes.size();
        }
    }

    public void show(int startX, int startY) {
        if (isShowing) return;
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
        }
        pendingShowRunnable = () -> showInternal(startX, startY);
        handler.postDelayed(pendingShowRunnable, 500);
    }

    private void showInternal(int startX, int startY) {
        pendingShowRunnable = null;
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;

        try {
            overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_stats_display, null);
            statsText = overlayView.findViewById(R.id.stats_text);
            statsText.setText(activity.getString(R.string.mod_overlay_cps_value, 0));

            wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.x = startX;
            wmParams.y = startY;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();

            overlayView.setOnTouchListener(this::handleTouch);
            overlayView.setVisibility(View.GONE);
            windowManager.addView(overlayView, wmParams);
            isShowing = true;

            syncUpdates();
            applyOpacity();
            updateLockState();
            applySize();
            updatePosition(wmParams.x, wmParams.y);
            refreshRuntimeVisibility();
        } catch (Exception e) {
            showFallback(startX, startY);
        }
    }

    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        overlayView = LayoutInflater.from(activity).inflate(R.layout.overlay_stats_display, null);
        statsText = overlayView.findViewById(R.id.stats_text);
        statsText.setText(activity.getString(R.string.mod_overlay_cps_value, 0));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, startX, startY);
        params.leftMargin = position.x;
        params.topMargin = position.y;

        overlayView.setOnTouchListener(this::handleTouchFallback);
        overlayView.setVisibility(View.GONE);
        rootView.addView(overlayView, params);
        isShowing = true;
        wmParams = null;

        syncUpdates();
        applyOpacity();
        updateLockState();
        applySize();
        refreshRuntimeVisibility();
    }

    private void refreshRuntimeVisibility() {
        InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
        if (manager != null) {
            manager.refreshRuntimeVisibility();
        }
    }

    private void updateDisplay() {
        if (statsText == null) return;
        int value = getCps();
        if (lastDisplayValue == value) return;
        lastDisplayValue = value;
        statsText.setText(activity.getString(R.string.mod_overlay_cps_value, value));
        statsText.post(() -> { if (canUpdate()) clampCurrentPosition(false); });
    }

    private void applyOpacity() {
        if (overlayView != null) {
            int opacity = InbuiltModManager.getInstance(activity).getOverlayOpacity(ModIds.CPS_DISPLAY);
            if (overlayView.getBackground() != null) {
                overlayView.getBackground().mutate().setAlpha((int) (opacity * 2.55f));
            }
        }
    }

    private void applySize() {
        if (statsText != null) {
            int sizeDp = InbuiltModManager.getInstance(activity).getOverlayButtonSize(ModIds.CPS_DISPLAY);
            float scale = sizeDp / 56f;
            statsText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12 * scale);
            
            float density = activity.getResources().getDisplayMetrics().density;
            int hPad = (int) (8 * scale * density);
            int vPad = (int) (4 * scale * density);
            statsText.setPadding(hPad, vPad, hPad, vPad);
            
            if (wmParams != null && windowManager != null && overlayView != null && isShowing) {
                try {
                    clampCurrentPosition(true);
                } catch (Exception ignored) {}
            }
        }
    }

    private void updateLockState() {
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(ModIds.CPS_DISPLAY);
    }

    private boolean isHudEditorMode = false;
    
    public void setHudEditorMode(boolean active) {
        this.isHudEditorMode = active;
        if (overlayView != null) {
            overlayView.setBackgroundColor(active ? 0x44FFFFFF : android.graphics.Color.TRANSPARENT);
        }
    }

    public void updatePosition(int x, int y) {
        if (wmParams != null && windowManager != null && overlayView != null && isShowing) {
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
            wmParams.x = position.x;
            wmParams.y = position.y;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView != null && isShowing) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            if (params != null) {
                OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
                params.leftMargin = position.x;
                params.topMargin = position.y;
                overlayView.setLayoutParams(params);
            }
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.selectHudEditorDisplay(ModIds.CPS_DISPLAY);
                    }
                }
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                v.getParent().requestDisallowInterceptTouchEvent(isHudEditorMode);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    if (isHudEditorMode) {
                        isDragging = true;
                    }
                }
                if (isDragging && isHudEditorMode && windowManager != null && overlayView != null) {
                    OverlayBounds.Position position = OverlayBounds.clampPosition(
                            activity, overlayView, (int) (initialX + dx), (int) (initialY + dy));
                    wmParams.x = position.x;
                    wmParams.y = position.y;
                    windowManager.updateViewLayout(overlayView, wmParams);
                }
                return isHudEditorMode || !isDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging && isHudEditorMode) {
                    savePosition(wmParams.x, wmParams.y);
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }

    private void savePosition(int x, int y) {
        InbuiltModManager.getInstance(activity).setOverlayPosition(ModIds.CPS_DISPLAY, x, y);
    }

    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.selectHudEditorDisplay(ModIds.CPS_DISPLAY);
                    }
                }
                initialX = params.leftMargin;
                initialY = params.topMargin;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                v.getParent().requestDisallowInterceptTouchEvent(isHudEditorMode);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    if (isHudEditorMode) {
                        isDragging = true;
                    }
                }
                if (isDragging && isHudEditorMode) {
                    OverlayBounds.Position position = OverlayBounds.clampPosition(
                            activity, overlayView, (int) (initialX + dx), (int) (initialY + dy));
                    params.leftMargin = position.x;
                    params.topMargin = position.y;
                    overlayView.setLayoutParams(params);
                }
                return isHudEditorMode || !isDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging && isHudEditorMode) {
                    savePosition(params.leftMargin, params.topMargin);
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }

    public void hide() {
        updatesRunning = false;
        handler.removeCallbacks(updateRunnable);
        lastDisplayValue = Integer.MIN_VALUE;
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
            pendingShowRunnable = null;
        }
        if (!isShowing) return;
        isShowing = false;
        handler.removeCallbacks(updateRunnable);
        try {
            if (wmParams != null && windowManager != null) {
                windowManager.removeView(overlayView);
            } else {
                ViewGroup rootView = activity.findViewById(android.R.id.content);
                if (rootView != null) {
                    rootView.removeView(overlayView);
                }
            }
        } catch (Exception ignored) {}
        overlayView = null;
        statsText = null;
        synchronized (clickTimes) {
            clickTimes.clear();
        }
    }

    public void applyConfigurationChanges() {
        if (!isShowing || overlayView == null) return;
        applyOpacity();
        updateLockState();
        applySize();
    }

    public void setVisibility(int visibility) {
        if (overlayView != null && overlayView.getVisibility() != visibility) overlayView.setVisibility(visibility);
        syncUpdates();
    }

    public void setRuntimeActive(boolean active) {
        runtimeActive = active;
        syncUpdates();
    }

    private boolean canUpdate() {
        return isShowing && runtimeActive && overlayView != null && overlayView.getVisibility() == View.VISIBLE;
    }

    private void syncUpdates() {
        if (!canUpdate()) {
            updatesRunning = false;
            handler.removeCallbacks(updateRunnable);
        } else if (!updatesRunning) {
            updatesRunning = true;
            handler.post(updateRunnable);
        }
    }

    private void clampCurrentPosition(boolean save) {
        if (overlayView == null || !isShowing) return;
        if (wmParams != null && windowManager != null) {
            OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, wmParams.x, wmParams.y);
            if (position.x != wmParams.x || position.y != wmParams.y) {
                wmParams.x = position.x;
                wmParams.y = position.y;
                windowManager.updateViewLayout(overlayView, wmParams);
                if (save) {
                    savePosition(position.x, position.y);
                }
            }
        } else {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            if (params == null) return;
            OverlayBounds.Position position = OverlayBounds.clampPosition(
                    activity, overlayView, params.leftMargin, params.topMargin);
            if (position.x != params.leftMargin || position.y != params.topMargin) {
                params.leftMargin = position.x;
                params.topMargin = position.y;
                overlayView.setLayoutParams(params);
                if (save) {
                    savePosition(position.x, position.y);
                }
            }
        }
    }
}
