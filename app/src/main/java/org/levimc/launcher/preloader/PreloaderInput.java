package org.levimc.launcher.preloader;

import android.view.MotionEvent;

import java.io.File;

public class PreloaderInput {
    private static final int[] MOUSE_BUTTONS = {
            MotionEvent.BUTTON_PRIMARY,
            MotionEvent.BUTTON_SECONDARY,
            MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_BACK,
            MotionEvent.BUTTON_FORWARD,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            MotionEvent.BUTTON_STYLUS_SECONDARY
    };

    private static int mouseButtonsDown;
    private static int blockedMouseButtons;
    private static int lastConsumedMouseTransitions;
    private static boolean blockedMousePointerStream;

    public static native boolean nativeOnTouch(int action, int pointerId, float x, float y);
    public static native boolean nativeOnKeyEvent(int keyCode, int unicodeChar, boolean isKeyDown);
    public static native boolean nativeOnTextInput(String text);
    public static native boolean nativeOnMouse(int button, boolean isDown);
    public static native void nativeOnDocumentResult(boolean success, String path, String displayName, String error);
    public static native void nativeSetActivity(Object activity);
    public static native void nativeClearActivity();
    public static native boolean nativeIsPauseMenuOpen();
    public static native boolean nativeIsHudScreenOpen();
    public static native boolean nativeIsShowingMenu();
    public static native boolean nativeShouldForceGlobalModMenu();
    public static native void nativeConfigureSignatureRules(String rulesPath, String minecraftVersion);
    private static native void nativePrepareRuntime();

    public static void prepareRuntime() {
        try {
            nativePrepareRuntime();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static void configureSignatureRules(File rulesFile, String minecraftVersion) {
        try {
            nativeConfigureSignatureRules(
                    rulesFile == null ? "" : rulesFile.getAbsolutePath(),
                    minecraftVersion == null ? "" : minecraftVersion
            );
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public static boolean isPauseMenuOpen() {
        try {
            return nativeIsPauseMenuOpen();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean isHudScreenOpen() {
        try {
            return nativeIsHudScreenOpen();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean isShowingMenu() {
        try {
            return nativeIsShowingMenu();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean shouldForceGlobalModMenu() {
        try {
            return nativeShouldForceGlobalModMenu();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean onTouch(int action, int pointerId, float x, float y) {
        try {
            return nativeOnTouch(action, pointerId, x, y);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean onKeyEvent(int keyCode, int unicodeChar, boolean isKeyDown) {
        try {
            return nativeOnKeyEvent(keyCode, unicodeChar, isKeyDown);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean onTextInput(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        try {
            return nativeOnTextInput(text.toString());
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static void onDocumentResult(boolean success, String path, String displayName, String error) {
        try {
            nativeOnDocumentResult(
                    success,
                    path == null ? "" : path,
                    displayName == null ? "" : displayName,
                    error == null ? "" : error
            );
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public static void cancelDocumentRequest(String reason) {
        onDocumentResult(false, "", "", reason == null ? "Import cancelled" : reason);
    }

    private static boolean dispatchMouseTransition(int button, boolean isDown) {
        boolean alreadyDown = (mouseButtonsDown & button) != 0;
        if (alreadyDown == isDown) {
            return (lastConsumedMouseTransitions & button) != 0;
        }

        boolean consumed;
        try {
            consumed = nativeOnMouse(button, isDown);
        } catch (UnsatisfiedLinkError e) {
            consumed = false;
        }

        if (isDown) {
            mouseButtonsDown |= button;
            if (consumed) {
                blockedMouseButtons |= button;
                lastConsumedMouseTransitions |= button;
            } else {
                blockedMouseButtons &= ~button;
                lastConsumedMouseTransitions &= ~button;
            }
            return consumed;
        }

        boolean effectiveConsumed = consumed || (blockedMouseButtons & button) != 0;
        mouseButtonsDown &= ~button;
        blockedMouseButtons &= ~button;
        if (effectiveConsumed) {
            lastConsumedMouseTransitions |= button;
        } else {
            lastConsumedMouseTransitions &= ~button;
        }
        return effectiveConsumed;
    }

    public static synchronized boolean onMouse(int button, boolean isDown) {
        if (button == 0) {
            return false;
        }
        return dispatchMouseTransition(button, isDown);
    }

    public static synchronized boolean onMouseMotion(int action, int actionButton, int buttonState) {
        int normalizedButtonState = buttonState;
        if (action == MotionEvent.ACTION_DOWN && normalizedButtonState == 0 && actionButton == 0) {
            normalizedButtonState = MotionEvent.BUTTON_PRIMARY;
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            normalizedButtonState |= actionButton;
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            normalizedButtonState &= ~actionButton;
        } else if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_POINTER_UP ||
                action == MotionEvent.ACTION_CANCEL) {
            normalizedButtonState = 0;
        }

        int pressedButtons = normalizedButtonState & ~mouseButtonsDown;
        int releasedButtons = mouseButtonsDown & ~normalizedButtonState;
        int blockedBeforeTransitions = blockedMouseButtons;
        boolean transitionConsumed = false;

        for (int button : MOUSE_BUTTONS) {
            if ((pressedButtons & button) != 0) {
                transitionConsumed |= dispatchMouseTransition(button, true);
            }
        }
        for (int button : MOUSE_BUTTONS) {
            if ((releasedButtons & button) != 0) {
                transitionConsumed |= dispatchMouseTransition(button, false);
            }
        }

        boolean blockedButtonActive = (normalizedButtonState & blockedMouseButtons) != 0;
        boolean duplicateTransitionConsumed = actionButton != 0 &&
                (lastConsumedMouseTransitions & actionButton) != 0;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (transitionConsumed || blockedButtonActive || duplicateTransitionConsumed) {
                    blockedMousePointerStream = true;
                }
                return blockedMousePointerStream;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                return blockedMousePointerStream || blockedButtonActive;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_CANCEL:
                boolean consumeRelease = transitionConsumed ||
                        blockedMousePointerStream ||
                        blockedBeforeTransitions != 0 ||
                        duplicateTransitionConsumed;
                if (mouseButtonsDown == 0 || action == MotionEvent.ACTION_CANCEL) {
                    blockedMousePointerStream = false;
                }
                return consumeRelease;
            default:
                return blockedMousePointerStream || blockedButtonActive;
        }
    }

    public static synchronized void resetMouseState() {
        mouseButtonsDown = 0;
        blockedMouseButtons = 0;
        lastConsumedMouseTransitions = 0;
        blockedMousePointerStream = false;
    }

    public static void setActivity(Object activity) {
        resetMouseState();
        try {
            nativeSetActivity(activity);
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public static void clearActivity() {
        resetMouseState();
        try {
            nativeClearActivity();
        } catch (UnsatisfiedLinkError e) {
        }
    }
}
