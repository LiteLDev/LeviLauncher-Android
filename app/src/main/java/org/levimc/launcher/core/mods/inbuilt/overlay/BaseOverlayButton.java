package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.minecraft.MinecraftActivity;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;

public abstract class BaseOverlayButton {
    protected final Activity activity;
    protected View overlayView;
    protected WindowManager windowManager;
    protected WindowManager.LayoutParams wmParams;
    private float initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private boolean isLocked = false;
    private long touchDownTime = 0;
    private static final long TAP_TIMEOUT = 200;
    private static final float DRAG_THRESHOLD = 10f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;
    private boolean isHiding = false;
    private Runnable pendingShowRunnable;
    private boolean isHudEditorSelected = false;
    
    public BaseOverlayButton(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }

    protected int getButtonSizePx() {
        int sizeDp = InbuiltModManager.getInstance(activity).getOverlayButtonSize(getModId());
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (sizeDp * density);
    }

    protected int getButtonWidthPx() {
        return (int) (getButtonSizePx() * getWidthScale());
    }

    protected int getButtonHeightPx() {
        return (int) (getButtonSizePx() * getHeightScale());
    }

    protected float getWidthScale() {
        return 1.0f;
    }

    protected float getHeightScale() {
        return 1.0f;
    }

    protected float getButtonOpacity() {
        int opacity = InbuiltModManager.getInstance(activity).getOverlayOpacity(getModId());
        return opacity / 100f;
    }

    protected void applyOpacity() {
        if (overlayView != null) {
            overlayView.setAlpha(getButtonOpacity());
        }
    }

    protected void updateLockState() {
        isLocked = InbuiltModManager.getInstance(activity).isOverlayLocked(getModId());
    }

    protected abstract String getModId();

    protected int getLayoutResource() {
        return R.layout.overlay_mod_button;
    }

    public void tick() {}

    public void pauseInput() {
        onButtonPressEnd();
    }

    protected void onOverlayGeometryChanged(int x, int y, int width, int height) {}

    private void notifyOverlayGeometryChanged() {
        if (overlayView == null) return;
        if (wmParams != null) {
            onOverlayGeometryChanged(wmParams.x, wmParams.y, wmParams.width, wmParams.height);
            return;
        }
        ViewGroup.LayoutParams params = overlayView.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
            onOverlayGeometryChanged(frameParams.leftMargin, frameParams.topMargin, frameParams.width, frameParams.height);
        }
    }

    protected long getShowDelayMillis() {
        return 500L;
    }

    public void show(int startX, int startY) {
        if (isShowing || isHiding) return;
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
        }
        pendingShowRunnable = () -> showInternal(startX, startY);
        handler.postDelayed(pendingShowRunnable, getShowDelayMillis());
    }

    private void showInternal(int startX, int startY) {
        pendingShowRunnable = null;
        if (isShowing || isHiding || activity.isFinishing() || activity.isDestroyed()) return;
        
        try {
            overlayView = LayoutInflater.from(activity).inflate(getLayoutResource(), null);
            if (overlayView instanceof ImageButton) {
                ImageButton btn = (ImageButton) overlayView;
                if (getIconResource() != 0) {
                    btn.setImageResource(getIconResource());
                }
                btn.setScaleType(ImageButton.ScaleType.FIT_CENTER);
            }
            configureOverlayView(overlayView);

            int buttonWidth = getButtonWidthPx();
            int buttonHeight = getButtonHeightPx();
            wmParams = new WindowManager.LayoutParams(
                buttonWidth,
                buttonHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            wmParams.gravity = Gravity.TOP | Gravity.START;
            OverlayBounds.Position position = OverlayBounds.clampPosition(
                activity, startX, startY, buttonWidth, buttonHeight);
            wmParams.x = position.x;
            wmParams.y = position.y;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();

            overlayView.setOnTouchListener(this::handleTouch);
            overlayView.setVisibility(View.GONE);
            windowManager.addView(overlayView, wmParams);
            isShowing = true;
            applyOpacity();
            updateLockState();
            notifyOverlayGeometryChanged();
            refreshRuntimeVisibility();
        } catch (Exception e) {
            showFallback(startX, startY);
        }
    }

    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        overlayView = LayoutInflater.from(activity).inflate(getLayoutResource(), null);
        if (overlayView instanceof ImageButton) {
            ImageButton btn = (ImageButton) overlayView;
            if (getIconResource() != 0) {
                btn.setImageResource(getIconResource());
            }
            btn.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        }
        configureOverlayView(overlayView);

        int buttonWidth = getButtonWidthPx();
        int buttonHeight = getButtonHeightPx();
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            buttonWidth,
            buttonHeight
        );
        params.gravity = Gravity.TOP | Gravity.START;
        OverlayBounds.Position position = OverlayBounds.clampPosition(
            activity, startX, startY, buttonWidth, buttonHeight);
        params.leftMargin = position.x;
        params.topMargin = position.y;

        overlayView.setOnTouchListener(this::handleTouchFallback);
        overlayView.setVisibility(View.GONE);
        rootView.addView(overlayView, params);
        isShowing = true;
        wmParams = null;
        applyOpacity();
        updateLockState();
        notifyOverlayGeometryChanged();
        refreshRuntimeVisibility();
    }

    private void refreshRuntimeVisibility() {
        InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
        if (manager != null) {
            manager.refreshRuntimeVisibility();
        }
    }

    public void hide() {
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
            pendingShowRunnable = null;
        }
        if (!isShowing || overlayView == null) {
            isShowing = false;
            return;
        }
        isHiding = true;
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
        isShowing = false;
        isHiding = false;
    }

    private boolean isHudEditorMode = false;
    
    public void setHudEditorMode(boolean active) {
        this.isHudEditorMode = active;
        if (!active) {
            isHudEditorSelected = false;
        }
        refreshHudEditorBackground();
    }

    public void setHudEditorSelected(boolean selected) {
        isHudEditorSelected = selected;
        refreshHudEditorBackground();
    }

    public String getOverlayConfigKey() {
        return getModId();
    }

    public int getCurrentButtonSizeDp() {
        return InbuiltModManager.getInstance(activity).getOverlayButtonSize(getModId());
    }

    private void refreshHudEditorBackground() {
        if (overlayView != null) {
            if (isHudEditorMode) {
                overlayView.setBackgroundColor(isHudEditorSelected ? 0x664AE0A0 : 0x44FFFFFF);
            } else {
                overlayView.setBackgroundColor(Color.TRANSPARENT);
            }
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
        notifyOverlayGeometryChanged();
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.selectHudEditorOverlay(this);
                    }
                }
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                v.getParent().requestDisallowInterceptTouchEvent(!isLocked || isHudEditorMode);
                if (!isHudEditorMode) {
                    onButtonPressStart();
                }
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
                    notifyOverlayGeometryChanged();
                }
                return isHudEditorMode || !isDragging;

            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                float totalDx = event.getRawX() - initialTouchX;
                float totalDy = event.getRawY() - initialTouchY;
                boolean wasDragging = Math.abs(totalDx) > DRAG_THRESHOLD || Math.abs(totalDy) > DRAG_THRESHOLD;

                if (!wasDragging && elapsed < TAP_TIMEOUT && !isHudEditorMode) {
                    handler.post(this::onButtonClick);
                } else if (isDragging && isHudEditorMode) {
                    savePosition(wmParams.x, wmParams.y);
                }
                if (!isHudEditorMode) {
                    onButtonPressEnd();
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                if (!isHudEditorMode) {
                    onButtonPressEnd();
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return false;
        }
        return false;
    }

    protected void savePosition(int x, int y) {
        InbuiltModManager.getInstance(activity).setOverlayPosition(getModId(), x, y);
    }

    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isHudEditorMode) {
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.selectHudEditorOverlay(this);
                    }
                }
                initialX = params.leftMargin;
                initialY = params.topMargin;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                v.getParent().requestDisallowInterceptTouchEvent(!isLocked || isHudEditorMode);
                if (!isHudEditorMode) {
                    onButtonPressStart();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    if (!isLocked || isHudEditorMode) {
                        isDragging = true;
                    }
                }
                if (isDragging && (!isLocked || isHudEditorMode)) {
                    OverlayBounds.Position position = OverlayBounds.clampPosition(
                        activity, overlayView, (int) (initialX + dx), (int) (initialY + dy));
                    params.leftMargin = position.x;
                    params.topMargin = position.y;
                    overlayView.setLayoutParams(params);
                    notifyOverlayGeometryChanged();
                }
                return isHudEditorMode || !isLocked || !isDragging;

            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                float totalDx = event.getRawX() - initialTouchX;
                float totalDy = event.getRawY() - initialTouchY;
                boolean wasDragging = Math.abs(totalDx) > DRAG_THRESHOLD || Math.abs(totalDy) > DRAG_THRESHOLD;

                if (!wasDragging && elapsed < TAP_TIMEOUT && !isHudEditorMode) {
                    handler.post(this::onButtonClick);
                } else if (isDragging && (!isLocked || isHudEditorMode)) {
                    savePosition(params.leftMargin, params.topMargin);
                }
                if (!isHudEditorMode) {
                    onButtonPressEnd();
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!isHudEditorMode) {
                    onButtonPressEnd();
                }
                isDragging = false;
                v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }

    protected void sendKey(int keyCode) {
        handler.post(() -> {
            long time = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(time, time + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            dispatchGameKey(down);
            dispatchGameKey(up);
        });
    }

    protected void sendKeyDown(int keyCode) {
        handler.post(() -> {
            long time = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            dispatchGameKey(down);
        });
    }

    protected void sendKeyUp(int keyCode) {
        handler.post(() -> {
            long time = SystemClock.uptimeMillis();
            KeyEvent up = new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
            dispatchGameKey(up);
        });
    }

    private void dispatchGameKey(KeyEvent event) {
        if (activity instanceof MinecraftActivity) {
            ((MinecraftActivity) activity).dispatchInbuiltKeyEvent(event);
        } else {
            activity.dispatchKeyEvent(event);
        }
    }

    protected abstract int getIconResource();
    protected abstract void onButtonClick();

    protected void configureOverlayView(View view) {}

    protected void onButtonPressStart() {}

    protected void onButtonPressEnd() {}

    public void applyConfigurationChanges() {
        if (!isShowing || overlayView == null) return;

        int newWidth = getButtonWidthPx();
        int newHeight = getButtonHeightPx();
        if (wmParams != null) {
            wmParams.width = newWidth;
            wmParams.height = newHeight;
            OverlayBounds.Position position = OverlayBounds.clampPosition(
                activity, wmParams.x, wmParams.y, newWidth, newHeight);
            wmParams.x = position.x;
            wmParams.y = position.y;
            try {
                windowManager.updateViewLayout(overlayView, wmParams);
            } catch (Exception ignored) {}
            savePosition(wmParams.x, wmParams.y);
        } else {
            ViewGroup.LayoutParams params = overlayView.getLayoutParams();
            if (params != null) {
                params.width = newWidth;
                params.height = newHeight;
                if (params instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
                    OverlayBounds.Position position = OverlayBounds.clampPosition(
                        activity, frameParams.leftMargin, frameParams.topMargin, newWidth, newHeight);
                    frameParams.leftMargin = position.x;
                    frameParams.topMargin = position.y;
                    savePosition(position.x, position.y);
                }
                overlayView.setLayoutParams(params);
            }
        }

        applyOpacity();

        updateLockState();
        notifyOverlayGeometryChanged();
        onButtonSizeChanged();
    }

    protected void onButtonSizeChanged() {}
}
