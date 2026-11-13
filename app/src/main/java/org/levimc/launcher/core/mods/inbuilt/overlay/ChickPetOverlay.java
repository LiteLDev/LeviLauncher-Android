package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

import java.util.Random;

public class ChickPetOverlay {
    private static final int FRAME_DELAY = 200;
    private static final int BLINK_DELAY = 2500;
    private static final int STATE_CHANGE_MIN = 4000;
    private static final int STATE_CHANGE_MAX = 10000;

    private enum State { IDLE, WALKING, SLEEPING, RUNNING }

    private final Activity activity;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private ImageView chickView;
    private WindowManager.LayoutParams wmParams;
    private boolean isShowing = false;
    private boolean runtimeActive = true;
    private boolean animationsRunning;
    private int displayedFrame;
    private boolean isHiding = false;
    private Runnable pendingShowRunnable;

    private State currentState = State.IDLE;
    private int posX, posY;
    private int dirX = 1, dirY = 0;
    private int screenWidth, screenHeight;
    private int chickSize;

    private int animFrame = 0;
    private boolean facingRight = true;
    private long lastBlinkTime = 0;
    private boolean isBlinking = false;

    private int[] idleFrames;
    private int[] walkSideFrames;
    private int[] walkFrontFrames;
    private int[] walkBackFrames;
    private int[] sleepFrames;
    private int[] runFrames;


    private final Runnable animationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!animationsRunning || !canAnimate()) { animationsRunning = false; return; }
            updateAnimation();
            handler.postDelayed(this, FRAME_DELAY);
        }
    };

    private final Runnable stateChangeRunnable = () -> {
        if (!animationsRunning || !canAnimate()) return;
        if (currentState != State.RUNNING) {
            changeToRandomState();
        }
        scheduleNextStateChange();
    };

    public ChickPetOverlay(Activity activity) {
        this.activity = activity;
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
        initFrames();
    }

    private void initFrames() {
        idleFrames = new int[] { R.drawable.chick_idle_1, R.drawable.chick_idle_2 };
        walkSideFrames = new int[] { R.drawable.chick_walk_1, R.drawable.chick_walk_2 };
        walkFrontFrames = new int[] { R.drawable.chick_walk_front_1, R.drawable.chick_walk_front_2 };
        walkBackFrames = new int[] { R.drawable.chick_walk_back_1, R.drawable.chick_walk_back_2 };
        sleepFrames = new int[] { R.drawable.chick_sleep_1, R.drawable.chick_sleep_2 };
        runFrames = new int[] { R.drawable.chick_run_1, R.drawable.chick_run_2 };
    }

    public void show() {
        if (isShowing || isHiding || activity.isFinishing() || activity.isDestroyed()) return;
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
        }
        pendingShowRunnable = this::showInternal;
        handler.postDelayed(pendingShowRunnable, 800);
    }

    private void showInternal() {
        pendingShowRunnable = null;
        if (isShowing || isHiding || activity.isFinishing() || activity.isDestroyed()) return;

        float density = activity.getResources().getDisplayMetrics().density;
        int sizeDp = InbuiltModManager.getInstance(activity).getOverlayButtonSize(ModIds.CHICK_PET);
        chickSize = (int) (sizeDp * density);
        screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        screenHeight = activity.getResources().getDisplayMetrics().heightPixels;

        posX = random.nextInt(Math.max(1, screenWidth - chickSize));
        posY = screenHeight - chickSize - (int)(100 * density);

        chickView = new ImageView(activity);
        chickView.setImageResource(idleFrames[0]);
        chickView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        wmParams = new WindowManager.LayoutParams(
            chickSize, chickSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        wmParams.gravity = Gravity.TOP | Gravity.START;
        wmParams.x = posX;
        wmParams.y = posY;
        wmParams.token = activity.getWindow().getDecorView().getWindowToken();

        chickView.setOnTouchListener(this::handleTouch);

        try {
            chickView.setVisibility(View.GONE);
            windowManager.addView(chickView, wmParams);
            isShowing = true;
            InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
            if (manager != null) {
                manager.refreshRuntimeVisibility();
            }
            syncAnimations();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setVisibility(int visibility) {
        if (chickView != null && chickView.getVisibility() != visibility) chickView.setVisibility(visibility);
        syncAnimations();
    }

    public void setRuntimeActive(boolean active) {
        runtimeActive = active;
        syncAnimations();
    }

    private boolean canAnimate() {
        return isShowing && runtimeActive && chickView != null && chickView.getVisibility() == View.VISIBLE;
    }

    private void syncAnimations() {
        if (!canAnimate()) {
            animationsRunning = false;
            handler.removeCallbacks(animationRunnable);
            handler.removeCallbacks(stateChangeRunnable);
            handler.removeCallbacks(runEndRunnable);
        } else if (!animationsRunning) {
            animationsRunning = true;
            handler.post(animationRunnable);
            scheduleNextStateChange();
            if (currentState == State.RUNNING) handler.postDelayed(runEndRunnable, 1500);
        }
    }

    private final Runnable runEndRunnable = () -> {
        if (canAnimate() && currentState == State.RUNNING) changeToRandomState();
    };

    private boolean handleTouch(View v, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startRunning();
            return true;
        }
        return false;
    }

    private void startRunning() {
        currentState = State.RUNNING;
        float density = activity.getResources().getDisplayMetrics().density;
        int speed = (int) (6 * density);
        dirX = random.nextBoolean() ? speed : -speed;
        dirY = (random.nextInt(3) - 1) * (int)(2 * density);
        facingRight = dirX > 0;
        handler.removeCallbacks(runEndRunnable);
        handler.postDelayed(runEndRunnable, 1500 + random.nextInt(1500));
    }

    private void changeToRandomState() {
        int r = random.nextInt(100);
        if (r < 30) {
            currentState = State.IDLE;
            dirX = 0;
            dirY = 0;
        } else if (r < 75) {
            currentState = State.WALKING;
            float density = activity.getResources().getDisplayMetrics().density;
            int speed = (int) (2 * density);
            dirX = (random.nextInt(3) - 1) * speed;
            dirY = (random.nextInt(3) - 1) * speed;
            if (dirX == 0 && dirY == 0) {
                dirX = random.nextBoolean() ? speed : -speed;
            }
            if (dirX != 0) facingRight = dirX > 0;
        } else {
            currentState = State.SLEEPING;
            dirX = 0;
            dirY = 0;
        }
        animFrame = 0;
    }

    private void scheduleNextStateChange() {
        int delay = STATE_CHANGE_MIN + random.nextInt(STATE_CHANGE_MAX - STATE_CHANGE_MIN);
        handler.postDelayed(stateChangeRunnable, delay);
    }


    private void updateAnimation() {
        if (chickView == null || !isShowing) return;

        int[] frames;
        boolean useFlip = false;
        
        switch (currentState) {
            case WALKING:
                moveChick(dirX, dirY);
                if (dirY != 0) {
                    if (dirY < 0) {
                        frames = walkBackFrames;
                    } else {
                        frames = walkFrontFrames;
                    }
                } else {
                    frames = walkSideFrames;
                    useFlip = true;
                }
                animFrame = (animFrame + 1) % frames.length;
                break;
            case SLEEPING:
                frames = sleepFrames;
                animFrame = (animFrame + 1) % frames.length;
                break;
            case RUNNING:
                frames = runFrames;
                moveChick(dirX, dirY);
                animFrame = (animFrame + 1) % frames.length;
                useFlip = true;
                break;
            default:
                frames = idleFrames;
                long now = System.currentTimeMillis();
                if (isBlinking) {
                    animFrame = 1;
                    if (now - lastBlinkTime > 200) {
                        isBlinking = false;
                        animFrame = 0;
                    }
                } else if (now - lastBlinkTime > BLINK_DELAY + random.nextInt(2000)) {
                    isBlinking = true;
                    lastBlinkTime = now;
                    animFrame = 1;
                } else {
                    animFrame = 0;
                }
                break;
        }

        if (displayedFrame != frames[animFrame]) {
            displayedFrame = frames[animFrame];
            chickView.setImageResource(displayedFrame);
        }
        chickView.setScaleX(useFlip && !facingRight ? -1f : 1f);
    }

    private void moveChick(int dx, int dy) {
        posX += dx;
        posY += dy;

        if (posX < 0) {
            posX = 0;
            dirX = Math.abs(dirX);
            if (dirX != 0) facingRight = true;
        } else if (posX > screenWidth - chickSize) {
            posX = screenWidth - chickSize;
            dirX = -Math.abs(dirX);
            if (dirX != 0) facingRight = false;
        }

        int topMargin = (int) (50 * activity.getResources().getDisplayMetrics().density);
        int bottomMargin = (int) (80 * activity.getResources().getDisplayMetrics().density);
        if (posY < topMargin) {
            posY = topMargin;
            dirY = Math.abs(dirY);
        } else if (posY > screenHeight - chickSize - bottomMargin) {
            posY = screenHeight - chickSize - bottomMargin;
            dirY = -Math.abs(dirY);
        }

        if (wmParams != null && chickView != null && (wmParams.x != posX || wmParams.y != posY)) {
            wmParams.x = posX;
            wmParams.y = posY;
            try {
                windowManager.updateViewLayout(chickView, wmParams);
            } catch (Exception ignored) {}
        }
    }

    public void hide() {
        animationsRunning = false;
        displayedFrame = 0;
        handler.removeCallbacks(animationRunnable);
        handler.removeCallbacks(stateChangeRunnable);
        handler.removeCallbacks(runEndRunnable);
        if (pendingShowRunnable != null) {
            handler.removeCallbacks(pendingShowRunnable);
            pendingShowRunnable = null;
        }
        if (!isShowing) {
            isShowing = false;
            return;
        }
        isHiding = true;
        isShowing = false;
        handler.removeCallbacks(animationRunnable);
        handler.removeCallbacks(stateChangeRunnable);
        try {
            if (chickView != null && windowManager != null) {
                windowManager.removeView(chickView);
            }
        } catch (Exception ignored) {}
        chickView = null;
        isHiding = false;
    }
}
