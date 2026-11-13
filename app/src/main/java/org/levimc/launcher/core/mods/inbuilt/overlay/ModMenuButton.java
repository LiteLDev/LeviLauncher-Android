package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
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
import android.widget.ImageButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.ExternalModBridge;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.preloader.PreloaderInput;

public class ModMenuButton {

    private final Activity activity;
    private View buttonView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;
    private Runnable pendingShow;
    
    private float initialX, initialY, initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private long touchDownTime = 0;
    private static final long TAP_TIMEOUT = 200;
    private static final float DRAG_THRESHOLD = 10f;
    
    public ModMenuButton(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
    }
    
    public void show(int startX, int startY) {
        if (isShowing || pendingShow != null) return;
        pendingShow = () -> {
            pendingShow = null;
            showInternal(startX, startY);
        };
        handler.postDelayed(pendingShow, 500);
    }
    
    private void showInternal(int startX, int startY) {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        
        try {
            buttonView = LayoutInflater.from(ModMenuTheme.wrap(activity)).inflate(R.layout.overlay_mod_menu_button, null);
            ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);
            
            float density = activity.getResources().getDisplayMetrics().density;
            int buttonSize = Math.round(new ModMenuPreferences(activity).buttonSize() * density);
            
            wmParams = new WindowManager.LayoutParams(
                buttonSize,
                buttonSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT
            );
            wmParams.gravity = Gravity.TOP | Gravity.START;
            wmParams.x = startX;
            wmParams.y = startY;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            
            btn.setOnTouchListener(this::handleTouch);
            buttonView.setVisibility(View.GONE);
            windowManager.addView(buttonView, wmParams);
            isShowing = true;
            applyStyle();
            applyRuntimeVisibility();
        } catch (Exception e) {
            showFallback(startX, startY);
        }
    }
    
    private void showFallback(int startX, int startY) {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        
        buttonView = LayoutInflater.from(ModMenuTheme.wrap(activity)).inflate(R.layout.overlay_mod_menu_button, null);
        ImageButton btn = buttonView.findViewById(R.id.mod_menu_fab);
        
        float density = activity.getResources().getDisplayMetrics().density;
        int buttonSize = Math.round(new ModMenuPreferences(activity).buttonSize() * density);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = startX;
        params.topMargin = startY;
        
        btn.setOnTouchListener(this::handleTouchFallback);
        buttonView.setVisibility(View.GONE);
        rootView.addView(buttonView, params);
        isShowing = true;
        wmParams = null;
        applyStyle();
        applyRuntimeVisibility();
    }

    private void applyOpacity() {
        if (buttonView != null) {
            int opacity = InbuiltModManager.getInstance(activity).getModMenuButtonOpacity();
            buttonView.setAlpha(opacity / 100f);
        }
    }

    private void applyButtonOpacity() {
        applyOpacity();
    }

    private void applyRuntimeVisibility() {
        if (buttonView == null) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        boolean visible = !ExternalModBridge.isFloatingModMenuButtonHidden() &&
                (!manager.isPauseMenuOnly() ||
                        (PreloaderInput.isPauseMenuOpen() && PreloaderInput.isShowingMenu()));
        buttonView.setVisibility(visible ? View.VISIBLE : View.GONE);
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.refreshRuntimeVisibility();
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = wmParams.x;
                initialY = wmParams.y;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging && windowManager != null && buttonView != null) {
                    OverlayBounds.Position point = OverlayBounds.clampPosition(activity, buttonView,
                            (int) (initialX + dx), (int) (initialY + dy));
                    wmParams.x = point.x;
                    wmParams.y = point.y;
                    windowManager.updateViewLayout(buttonView, wmParams);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }
    
    private boolean handleTouchFallback(View v, MotionEvent event) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) buttonView.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = params.leftMargin;
                initialY = params.topMargin;
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                isDragging = false;
                touchDownTime = SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - initialTouchX;
                float dy = event.getRawY() - initialTouchY;
                if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                    isDragging = true;
                }
                if (isDragging) {
                    OverlayBounds.Position point = OverlayBounds.clampPosition(activity, buttonView,
                            (int) (initialX + dx), (int) (initialY + dy));
                    params.leftMargin = point.x;
                    params.topMargin = point.y;
                    buttonView.setLayoutParams(params);
                }
                return true;
            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchDownTime;
                if (!isDragging && elapsed < TAP_TIMEOUT) {
                    handler.post(this::onButtonClick);
                }
                isDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }
    
    private void onButtonClick() {
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.toggleModMenuFromButton();
        }
    }
    

    
    private void applyConfigurationChanges(String modId) {
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.applyConfigurationChanges(modId);
        }
    }
    
    public void hide() {
        if (pendingShow != null) {
            handler.removeCallbacks(pendingShow);
            pendingShow = null;
        }
        if (!isShowing || buttonView == null) return;
        handler.post(() -> {
            try {
                if (wmParams != null && windowManager != null) {
                    windowManager.removeView(buttonView);
                } else {
                    ViewGroup rootView = activity.findViewById(android.R.id.content);
                    if (rootView != null) {
                        rootView.removeView(buttonView);
                    }
                }
            } catch (Exception ignored) {}
            buttonView = null;
            isShowing = false;
        });
    }
    
    public void setVisibility(int visibility) {
        if (buttonView != null) {
            buttonView.setVisibility(visibility);
        }
    }
    
    public boolean isShowing() {
        return isShowing;
    }

    public void refreshOpacity() {
        applyStyle();
    }

    private void applyStyle() {
        if (buttonView == null) return;
        ModMenuPreferences prefs = new ModMenuPreferences(activity);
        ModMenuTheme theme = new ModMenuTheme(activity);
        int size = theme.dp(prefs.buttonSize());
        ImageButton button = buttonView.findViewById(R.id.mod_menu_fab);
        button.setBackgroundTintList(null);
        button.setImageResource(R.drawable.ic_leaf_logo);
        button.setImageTintList(null);
        button.clearColorFilter();
        button.setBackground(theme.touch(prefs.buttonShape() == 2 ? android.graphics.Color.TRANSPARENT : android.graphics.Color.WHITE,
                prefs.buttonShape() == 1 ? prefs.buttonSize() / 2f : 10, false));
        button.setPadding(size / 7, size / 7, size / 7, size / 7);
        button.setContentDescription(activity.getString(R.string.mod_menu_modules));
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setOutlineProvider(null);
        buttonView.setBackground(null);
        buttonView.setElevation(0f);
        buttonView.setTranslationZ(0f);
        buttonView.setOutlineProvider(null);
        button.setStateListAnimator(null);
        if (wmParams != null) {
            if (wmParams.width != size || wmParams.height != size) {
                wmParams.width = size;
                wmParams.height = size;
                try { windowManager.updateViewLayout(buttonView, wmParams); } catch (RuntimeException ignored) {}
            }
        } else {
            ViewGroup.LayoutParams params = buttonView.getLayoutParams();
            if (params.width != size || params.height != size) {
                params.width = size;
                params.height = size;
                buttonView.setLayoutParams(params);
            }
        }
        applyOpacity();
        buttonView.post(() -> {
            if (buttonView == null) return;
            if (wmParams != null) {
                OverlayBounds.Position point = OverlayBounds.clampPosition(activity, buttonView, wmParams.x, wmParams.y);
                if (wmParams.x != point.x || wmParams.y != point.y) {
                    wmParams.x = point.x;
                    wmParams.y = point.y;
                    try { windowManager.updateViewLayout(buttonView, wmParams); } catch (RuntimeException ignored) {}
                }
            } else if (buttonView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) buttonView.getLayoutParams();
                OverlayBounds.Position point = OverlayBounds.clampPosition(activity, buttonView, params.leftMargin, params.topMargin);
                if (params.leftMargin != point.x || params.topMargin != point.y) {
                    params.leftMargin = point.x;
                    params.topMargin = point.y;
                    buttonView.setLayoutParams(params);
                }
            }
        });
    }
}
