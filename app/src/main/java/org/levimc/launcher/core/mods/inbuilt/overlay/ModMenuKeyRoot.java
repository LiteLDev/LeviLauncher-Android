package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

public class ModMenuKeyRoot extends FrameLayout {
    public interface ShortcutHandler {
        boolean handle(KeyEvent event);
    }

    private ShortcutHandler shortcutHandler;

    public ModMenuKeyRoot(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setShortcutHandler(ShortcutHandler shortcutHandler) {
        this.shortcutHandler = shortcutHandler;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ModMenuDialogs.dispatchKeyEvent(this, event)) return true;
        View focusedView = findFocus();
        if (!(focusedView instanceof EditText) && shortcutHandler != null && shortcutHandler.handle(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
