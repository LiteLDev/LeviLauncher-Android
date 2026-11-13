package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.levimc.launcher.core.mods.inbuilt.ExternalModBridge;
import org.levimc.launcher.core.mods.inbuilt.ExternalModBridge.DrawCommand;

public class HudOverlay extends View {
    private boolean isShowing = false;
    private final Paint paint = new Paint();
    private DrawCommand[] cachedDrawCommands = new DrawCommand[0];
    private long cachedDrawRevision = Long.MIN_VALUE;
    private boolean frameCallbackPosted = false;
    private boolean runtimeActive = true;
    private boolean isHudEditorMode = false;
    private Runnable pendingShow;
    private long snapshotSequence;
    private final android.graphics.Path trianglePath = new android.graphics.Path();
    private final android.graphics.RectF imageBounds = new android.graphics.RectF();
    private final java.util.Map<String, android.graphics.Typeface> typefaceCache = new java.util.HashMap<>();

    private final java.util.Map<String, android.graphics.Bitmap> imageCache = new java.util.HashMap<>();
    private final java.util.Set<String> pendingFonts = new java.util.HashSet<>();
    private final java.util.Set<String> pendingImages = new java.util.HashSet<>();
    private final java.util.Set<String> missingFonts = new java.util.HashSet<>();
    private final java.util.Set<String> missingImages = new java.util.HashSet<>();
    private int assetGeneration;
    private final java.util.concurrent.ThreadPoolExecutor assetWorker = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "ModHudAssets");
                thread.setDaemon(true);
                return thread;
            });

    private android.graphics.Typeface getFont(String fontId) {
        if (fontId == null || fontId.isEmpty()) return null;
        if (typefaceCache.containsKey(fontId)) return typefaceCache.get(fontId);
        if (!pendingFonts.add(fontId)) return null;
        int generation = assetGeneration;
        assetWorker.execute(() -> {
            android.graphics.Typeface loaded = null;
            java.io.File temporary = null;
            try {
                byte[] bytes = ExternalModBridge.nativeGetRegisteredFontBytes(fontId);
                if (bytes != null && bytes.length > 0) {
                    temporary = java.io.File.createTempFile("hud-font-", ".ttf", getContext().getCacheDir());
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) { output.write(bytes); }
                    loaded = android.graphics.Typeface.createFromFile(temporary);
                }
            } catch (java.io.IOException | RuntimeException | LinkageError ignored) {
            } finally {
                if (temporary != null) temporary.delete();
            }
            android.graphics.Typeface result = loaded;
            handler.post(() -> {
                if (generation != assetGeneration) return;
                pendingFonts.remove(fontId);
                typefaceCache.put(fontId, result);
                if (result == null) { missingFonts.add(fontId); scheduleAssetRetry(); }
                else if (canRender()) invalidate();
            });
        });
        return null;
    }

    private android.graphics.Bitmap getImage(String imageId) {
        if (imageId == null || imageId.isEmpty()) return null;
        if (imageCache.containsKey(imageId)) return imageCache.get(imageId);
        if (!pendingImages.add(imageId)) return null;
        int generation = assetGeneration;
        assetWorker.execute(() -> {
            android.graphics.Bitmap loaded = null;
            try {
                Object[] image = ExternalModBridge.nativeGetRegisteredImage(imageId);
                if (image != null && image.length >= 2 && image[0] instanceof byte[] && image[1] instanceof int[]) {
                    byte[] bytes = (byte[]) image[0];
                    int[] size = (int[]) image[1];
                    if (size.length >= 2 && size[0] > 0 && size[1] > 0 && (long) size[0] * size[1] * 4L == bytes.length) {
                        loaded = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888);
                        loaded.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes));
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {}
            android.graphics.Bitmap result = loaded;
            handler.post(() -> {
                if (generation != assetGeneration) return;
                pendingImages.remove(imageId);
                imageCache.put(imageId, result);
                if (result == null) { missingImages.add(imageId); scheduleAssetRetry(); }
                else if (canRender()) invalidate();
            });
        });
        return null;
    }

    public HudOverlay(Activity activity) {
        super(activity);
        paint.setAntiAlias(true);
        setWillNotDraw(false);
        setElevation(100f);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idlePoll = this::postFrameCallback;
    private final Runnable assetRetry = this::retryMissingAssets;

    private void scheduleAssetRetry() {
        if (canRender() && (!missingFonts.isEmpty() || !missingImages.isEmpty()) && !handler.hasCallbacks(assetRetry)) {
            handler.postDelayed(assetRetry, 1000L);
        }
    }

    private void retryMissingAssets() {
        if (!canRender()) return;
        java.util.List<String> fonts = new java.util.ArrayList<>(missingFonts);
        java.util.List<String> images = new java.util.ArrayList<>(missingImages);
        missingFonts.clear();
        missingImages.clear();
        for (String id : fonts) { typefaceCache.remove(id); getFont(id); }
        for (String id : images) { imageCache.remove(id); getImage(id); }
    }
    private final android.view.Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackPosted = false;
        if (!canRender()) return;
        long revision = ExternalModBridge.getDrawCommandsRevision();
        if (revision < 0L || revision != cachedDrawRevision) {
            DrawCommand[] next = ExternalModBridge.getHudDrawCommands(cachedDrawCommands);
            cachedDrawRevision = revision;
            snapshotSequence++;
            boolean changed = next != cachedDrawCommands && (next.length != 0 || cachedDrawCommands.length != 0);
            cachedDrawCommands = next;
            if (changed || isHudEditorMode) invalidate();
        }
        if (cachedDrawCommands.length == 0 && !isHudEditorMode) handler.postDelayed(idlePoll, 100L);
        else postFrameCallback();
    };

    private boolean canRender() {
        return isShowing && runtimeActive && isAttachedToWindow()
                && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
    }

    private void postFrameCallback() {
        if (frameCallbackPosted || !canRender()) return;
        handler.removeCallbacks(idlePoll);
        scheduleAssetRetry();
        frameCallbackPosted = true;
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopFrames() {
        handler.removeCallbacks(idlePoll);
        handler.removeCallbacks(assetRetry);
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameCallbackPosted = false;
    }

    public void setRuntimeActive(boolean active) {
        runtimeActive = active;
        if (canRender()) postFrameCallback(); else stopFrames();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (handler == null) return;
        if (canRender()) postFrameCallback(); else stopFrames();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (handler == null) return;
        if (canRender()) postFrameCallback(); else stopFrames();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrames();
        super.onDetachedFromWindow();
    }

    public void show() {
        if (isShowing || pendingShow != null) return;
        pendingShow = () -> {
            pendingShow = null;
            if (isShowing || ((Activity) getContext()).isFinishing() || ((Activity) getContext()).isDestroyed()) return;
            ViewGroup root = ((Activity) getContext()).findViewById(android.R.id.content);
            if (root == null) return;
            setVisibility(GONE);
            root.addView(this, new FrameLayout.LayoutParams(-1, -1));
            isShowing = true;
            cachedDrawRevision = Long.MIN_VALUE;
            InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
            if (manager != null) manager.refreshRuntimeVisibility();
            postFrameCallback();
        };
        handler.postDelayed(pendingShow, 100L);
    }

    public void hide() {
        if (pendingShow != null) { handler.removeCallbacks(pendingShow); pendingShow = null; }
        isShowing = false;
        stopFrames();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        cachedDrawCommands = new DrawCommand[0];
        cachedDrawRevision = Long.MIN_VALUE;
        assetGeneration++;
        assetWorker.getQueue().clear();
        pendingImages.clear();
        pendingFonts.clear();
        missingImages.clear();
        missingFonts.clear();
        imageCache.clear();
        typefaceCache.clear();
    }


    private String draggingModule = null;
    private ExternalModBridge.HudEditorElement draggingHudElement = null;
    private float dragOffsetX = 0f;
    private float dragOffsetY = 0f;
    private float draggingWidth = 1f;
    private float draggingHeight = 1f;
    private float snapGuideX = Float.NaN;
    private float snapGuideY = Float.NaN;

    private final java.util.Map<String, Boolean> hiddenInHudCache = new java.util.HashMap<>();
    private boolean hiddenInHudCacheLoaded = false;
    private ExternalModBridge.HudEditorElement[] cachedHudElements = new ExternalModBridge.HudEditorElement[0];
    private long cachedHudElementsRevision = Long.MIN_VALUE;

    private void loadHiddenInHudCache() {
        if (hiddenInHudCacheLoaded) return;
        hiddenInHudCache.clear();
        String bulkJson = ExternalModBridge.getExternalModsInfo();
        if (bulkJson != null) {
            try {
                org.json.JSONArray array = new org.json.JSONArray(bulkJson);
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject obj = array.getJSONObject(i);
                    hiddenInHudCache.put(obj.optString("module_id", ""),
                        obj.optBoolean("hide_in_hud_editor", false));
                }
                hiddenInHudCacheLoaded = true;
                return;
            } catch (Exception ignored) {
                hiddenInHudCache.clear();
            }
        }
        int extCount = ExternalModBridge.getExternalModCount();
        for (int i = 0; i < extCount; i++) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(ExternalModBridge.getExternalModInfo(i));
                hiddenInHudCache.put(obj.optString("module_id", ""),
                    obj.optBoolean("hide_in_hud_editor", false));
            } catch (Exception ignored) {
            }
        }
        hiddenInHudCacheLoaded = true;
    }

    private boolean isHiddenInHudEditor(String moduleId) {
        if (moduleId == null) return false;
        loadHiddenInHudCache();
        return hiddenInHudCache.getOrDefault(moduleId, false);
    }

    public void setHudEditorMode(boolean active) {
        isHudEditorMode = active;
        hiddenInHudCache.clear();
        hiddenInHudCacheLoaded = false;
        cachedHudElements = new ExternalModBridge.HudEditorElement[0];
        cachedHudElementsRevision = Long.MIN_VALUE;
        clearDragging();
        invalidate();
        stopFrames();
        postFrameCallback();
    }

    public boolean isHudEditorMode() {
        return isHudEditorMode;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ExternalModBridge.setHudSurfaceSize(w, h);
    }

    private DrawCommand[] currentDrawCommands() {
        return cachedDrawCommands;
    }

    private ExternalModBridge.HudEditorElement[] currentHudEditorElements() {
        if (snapshotSequence != cachedHudElementsRevision) {
            cachedHudElements = ExternalModBridge.getHudEditorElements();
            cachedHudElementsRevision = snapshotSequence;
        }
        return cachedHudElements;
    }

    private boolean hasExplicitHudElements(String moduleId) {
        if (moduleId == null) return false;
        for (ExternalModBridge.HudEditorElement element : currentHudEditorElements()) {
            if (element != null && moduleId.equals(element.moduleId)) return true;
        }
        return false;
    }

    private ModuleBounds getHudElementBounds(ExternalModBridge.HudEditorElement element) {
        if (element == null) return null;
        float left = resolveX(element.x);
        float top = resolveY(element.y);
        float right = left + Math.max(1f, element.width);
        float bottom = top + Math.max(1f, element.height);
        return new ModuleBounds(left, top, right, bottom);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (!isHudEditorMode) return false;

        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                float touchX = event.getX();
                float touchY = event.getY();
                ExternalModBridge.HudEditorElement[] elements = currentHudEditorElements();
                for (int i = elements.length - 1; i >= 0; i--) {
                    ExternalModBridge.HudEditorElement element = elements[i];
                    if (element == null || element.moduleId == null || isHiddenInHudEditor(element.moduleId)) continue;
                    ModuleBounds bounds = getHudElementBounds(element);
                    if (bounds != null && bounds.contains(touchX, touchY)) {
                        draggingModule = element.moduleId;
                        draggingHudElement = element;
                        draggingWidth = Math.max(1f, bounds.width());
                        draggingHeight = Math.max(1f, bounds.height());
                        dragOffsetX = touchX - bounds.left;
                        dragOffsetY = touchY - bounds.top;
                        return true;
                    }
                }

                DrawCommand[] cmds = currentDrawCommands();
                if (cmds != null) {
                    for (int i = cmds.length - 1; i >= 0; i--) {
                        DrawCommand cmd = cmds[i];
                        if (cmd.moduleId == null || isHiddenInHudEditor(cmd.moduleId)
                                || hasExplicitHudElements(cmd.moduleId) || !isDraggableCommand(cmd)) continue;
                        ModuleBounds hitBounds = getCommandBounds(cmd);
                        if (hitBounds != null && hitBounds.contains(touchX, touchY)) {
                            draggingModule = cmd.moduleId;
                            ModuleBounds moduleBounds = getModuleBounds(cmds, draggingModule);
                            if (moduleBounds == null) {
                                clearDragging();
                                return true;
                            }
                            draggingWidth = Math.max(1f, moduleBounds.width());
                            draggingHeight = Math.max(1f, moduleBounds.height());
                            dragOffsetX = touchX - moduleBounds.left;
                            dragOffsetY = touchY - moduleBounds.top;
                            return true;
                        }
                    }
                }
                break;
            }
            case android.view.MotionEvent.ACTION_MOVE:
                if (draggingModule != null) {
                    float newX = clamp(event.getX() - dragOffsetX, 0f, Math.max(0f, getWidth() - draggingWidth));
                    float newY = clamp(event.getY() - dragOffsetY, 0f, Math.max(0f, getHeight() - draggingHeight));
                    if (draggingHudElement != null) {
                        SnapPosition snapped = snapHudElement(draggingHudElement, newX, newY);
                        newX = clamp(snapped.x, 0f, Math.max(0f, getWidth() - draggingWidth));
                        newY = clamp(snapped.y, 0f, Math.max(0f, getHeight() - draggingHeight));
                        draggingHudElement.x = newX;
                        draggingHudElement.y = newY;
                        ExternalModBridge.setExternalModConfig(draggingModule, draggingHudElement.positionKeyX, String.valueOf(newX));
                        ExternalModBridge.setExternalModConfig(draggingModule, draggingHudElement.positionKeyY, String.valueOf(newY));
                        invalidate();
                    } else {
                        snapGuideX = Float.NaN;
                        snapGuideY = Float.NaN;
                        ExternalModBridge.setExternalModConfig(draggingModule, "hudPosX", String.valueOf(Math.round(newX)));
                        ExternalModBridge.setExternalModConfig(draggingModule, "hudPosY", String.valueOf(Math.round(newY)));
                    }
                    return true;
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                clearDragging();
                invalidate();
                return true;
        }
        return true;
    }

    private void clearDragging() {
        draggingModule = null;
        draggingHudElement = null;
        dragOffsetX = 0f;
        dragOffsetY = 0f;
        draggingWidth = 1f;
        draggingHeight = 1f;
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;
    }

    private boolean isDraggableCommand(DrawCommand cmd) {
        return cmd.type != DrawCommand.TYPE_LINE
                && cmd.type != DrawCommand.TYPE_CIRCLE_FILLED
                && cmd.type != DrawCommand.TYPE_TRIANGLE_FILLED;
    }

    private ModuleBounds getModuleBounds(DrawCommand[] cmds, String moduleId) {
        ModuleBounds bounds = null;
        for (DrawCommand cmd : cmds) {
            if (!moduleId.equals(cmd.moduleId) || !isDraggableCommand(cmd)) continue;
            ModuleBounds commandBounds = getCommandBounds(cmd);
            if (commandBounds == null) continue;
            if (bounds == null) bounds = commandBounds;
            else bounds.include(commandBounds);
        }
        return bounds;
    }

    private SnapPosition snapHudElement(ExternalModBridge.HudEditorElement moving, float x, float y) {
        float bestX = x;
        float bestY = y;
        float threshold = Math.max(0f, moving.snapThreshold);
        float bestXDistance = threshold + 0.001f;
        float bestYDistance = threshold + 0.001f;
        snapGuideX = Float.NaN;
        snapGuideY = Float.NaN;

        if ((moving.snapFlags & ExternalModBridge.HudEditorElement.SNAP_GRID) != 0 && moving.gridSize > 0f) {
            bestX = Math.round(bestX / moving.gridSize) * moving.gridSize;
            bestY = Math.round(bestY / moving.gridSize) * moving.gridSize;
        }

        if ((moving.snapFlags & ExternalModBridge.HudEditorElement.SNAP_SCREEN_CENTER) != 0) {
            float centerX = (getWidth() - draggingWidth) * 0.5f;
            float centerY = (getHeight() - draggingHeight) * 0.5f;
            float dx = Math.abs(centerX - bestX);
            float dy = Math.abs(centerY - bestY);
            if (dx < bestXDistance) {
                bestXDistance = dx;
                bestX = centerX;
                snapGuideX = getWidth() * 0.5f;
            }
            if (dy < bestYDistance) {
                bestYDistance = dy;
                bestY = centerY;
                snapGuideY = getHeight() * 0.5f;
            }
        }

        if ((moving.snapFlags & ExternalModBridge.HudEditorElement.SNAP_ELEMENTS) == 0) {
            return new SnapPosition(bestX, bestY);
        }

        for (ExternalModBridge.HudEditorElement target : currentHudEditorElements()) {
            if (target == null || target == moving || target.moduleId == null || target.elementId == null) continue;
            if (moving.moduleId != null && moving.moduleId.equals(target.moduleId)
                    && moving.elementId != null && moving.elementId.equals(target.elementId)) continue;
            if (isHiddenInHudEditor(target.moduleId)) continue;
            if (moving.snapGroup != null && !moving.snapGroup.isEmpty()
                    && !moving.snapGroup.equals(target.snapGroup)) continue;
            ModuleBounds targetBounds = getHudElementBounds(target);
            if (targetBounds == null) continue;
            float gap = Math.max(moving.gridGap, target.gridGap);

            float[] xCandidates = {
                    targetBounds.left,
                    targetBounds.right - draggingWidth,
                    targetBounds.centerX() - draggingWidth * 0.5f,
                    targetBounds.right + gap,
                    targetBounds.left - gap - draggingWidth
            };
            for (float candidate : xCandidates) {
                float distance = Math.abs(candidate - bestX);
                if (distance < bestXDistance) {
                    bestXDistance = distance;
                    bestX = candidate;
                    snapGuideX = candidate + draggingWidth * 0.5f;
                }
            }

            float[] yCandidates = {
                    targetBounds.top,
                    targetBounds.bottom - draggingHeight,
                    targetBounds.centerY() - draggingHeight * 0.5f,
                    targetBounds.bottom + gap,
                    targetBounds.top - gap - draggingHeight
            };
            for (float candidate : yCandidates) {
                float distance = Math.abs(candidate - bestY);
                if (distance < bestYDistance) {
                    bestYDistance = distance;
                    bestY = candidate;
                    snapGuideY = candidate + draggingHeight * 0.5f;
                }
            }
        }

        if (moving.snapGroup == null || moving.snapGroup.isEmpty()) {
            DrawCommand[] cmds = currentDrawCommands();
            java.util.HashSet<String> visited = new java.util.HashSet<>();
            for (DrawCommand cmd : cmds) {
                if (cmd == null || cmd.moduleId == null || cmd.moduleId.equals(moving.moduleId)
                        || hasExplicitHudElements(cmd.moduleId) || isHiddenInHudEditor(cmd.moduleId)
                        || !visited.add(cmd.moduleId)) continue;
                ModuleBounds targetBounds = getModuleBounds(cmds, cmd.moduleId);
                if (targetBounds == null) continue;
                float gap = Math.max(0f, moving.gridGap);
                float[] xCandidates = {targetBounds.left, targetBounds.right - draggingWidth,
                        targetBounds.centerX() - draggingWidth * 0.5f,
                        targetBounds.right + gap, targetBounds.left - gap - draggingWidth};
                for (float candidate : xCandidates) {
                    float distance = Math.abs(candidate - bestX);
                    if (distance < bestXDistance) {
                        bestXDistance = distance;
                        bestX = candidate;
                        snapGuideX = candidate + draggingWidth * 0.5f;
                    }
                }
                float[] yCandidates = {targetBounds.top, targetBounds.bottom - draggingHeight,
                        targetBounds.centerY() - draggingHeight * 0.5f,
                        targetBounds.bottom + gap, targetBounds.top - gap - draggingHeight};
                for (float candidate : yCandidates) {
                    float distance = Math.abs(candidate - bestY);
                    if (distance < bestYDistance) {
                        bestYDistance = distance;
                        bestY = candidate;
                        snapGuideY = candidate + draggingHeight * 0.5f;
                    }
                }
            }
        }

        return new SnapPosition(bestX, bestY);
    }

    private static final class SnapPosition {
        final float x;
        final float y;

        SnapPosition(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private ModuleBounds getCommandBounds(DrawCommand cmd) {
        float drawX = resolveX(cmd.x);
        float drawY = resolveY(cmd.y);
        float left = drawX;
        float top = drawY;
        float right;
        float bottom;

        if (cmd.type == DrawCommand.TYPE_TEXT) {
            android.graphics.Typeface tf = getFont(cmd.fontId);
            paint.setTypeface(tf != null ? tf : android.graphics.Typeface.DEFAULT);
            paint.setTextSize(cmd.size);
            String text = cmd.text;
            if (text != null && text.contains("{DISPLAY_SIZE}")) {
                text = text.replace("{DISPLAY_SIZE}", getWidth() + "x" + getHeight());
            }
            float textWidth = text != null && !text.isEmpty() ? paint.measureText(text) : 100f;
            float width = cmd.w > 0 ? cmd.w : textWidth;
            float height = cmd.h > 0 ? cmd.h : (cmd.size > 0 ? cmd.size : 30f);
            if (cmd.w == -1f) {
                left = drawX - textWidth;
            } else if (cmd.w == -2f) {
                left = drawX - textWidth / 2f;
            }
            if (cmd.w <= 0f || cmd.h <= 0f) {
                top = drawY - height;
            }
            right = left + width;
            bottom = top + height;
        } else {
            right = drawX + cmd.w;
            bottom = drawY + cmd.h;
            if (right < left) {
                float temp = left;
                left = right;
                right = temp;
            }
            if (bottom < top) {
                float temp = top;
                top = bottom;
                bottom = temp;
            }
        }

        return new ModuleBounds(left, top, right, bottom);
    }

    private float resolveX(float x) {
        if (x <= -19000f) return (getWidth() / 2f) + (x + 20000f);
        if (x <= -9000f) return getWidth() + (x + 10000f);
        return x;
    }

    private float resolveY(float y) {
        if (y <= -19000f) return (getHeight() / 2f) + (y + 20000f);
        if (y <= -9000f) return getHeight() + (y + 10000f);
        return y;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ModuleBounds {
        float left;
        float top;
        float right;
        float bottom;

        ModuleBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        void include(ModuleBounds other) {
            left = Math.min(left, other.left);
            top = Math.min(top, other.top);
            right = Math.max(right, other.right);
            bottom = Math.max(bottom, other.bottom);
        }

        boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        float centerX() {
            return (left + right) * 0.5f;
        }

        float centerY() {
            return (top + bottom) * 0.5f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isShowing) return;
        if (isHudEditorMode) {
            canvas.drawColor(0x88000000);
        }

        DrawCommand[] cmds = currentDrawCommands();
        if (cmds != null) {
            for (DrawCommand cmd : cmds) {
                if (isHudEditorMode && isHiddenInHudEditor(cmd.moduleId)) continue;
                paint.setColor(cmd.color);
                
                float drawX = cmd.x;
                if (drawX <= -19000f) drawX = (getWidth() / 2f) + (drawX + 20000f);
                else if (drawX <= -9000f) drawX = getWidth() + (drawX + 10000f);
                
                float drawY = cmd.y;
                if (drawY <= -19000f) drawY = (getHeight() / 2f) + (drawY + 20000f);
                else if (drawY <= -9000f) drawY = getHeight() + (drawY + 10000f);

                if (cmd.type == DrawCommand.TYPE_TEXT) {
                    android.graphics.Typeface tf = getFont(cmd.fontId);
                    if (tf != null) {
                        paint.setTypeface(tf);
                    } else {
                        paint.setTypeface(android.graphics.Typeface.DEFAULT);
                    }
                    paint.setTextSize(cmd.size);
                    paint.setStyle(Paint.Style.FILL);
                    
                    String txt = cmd.text;
                    if (txt != null && txt.contains("{DISPLAY_SIZE}")) {
                        txt = txt.replace("{DISPLAY_SIZE}", getWidth() + "x" + getHeight());
                    }
                    
                    int bgColor = Float.floatToRawIntBits(cmd.x3);
                    if (bgColor != 0 && txt != null && !txt.isEmpty()) {
                        float textWidth = paint.measureText(txt);
                        float paddingX = 4f;
                        float left = drawX;
                        if (cmd.w == -1f) left = drawX - textWidth;
                        else if (cmd.w == -2f) left = drawX - textWidth / 2f;

                        float top = drawY - cmd.size * 0.9f; 
                        float right = left + textWidth;
                        float bottom = drawY + (cmd.size + 4f - cmd.size * 0.9f); 
                        
                        int oldColor = paint.getColor();
                        paint.setColor(bgColor);
                        canvas.drawRect(left - paddingX, top, right + paddingX, bottom, paint);
                        paint.setColor(oldColor);
                    }

                    if (txt != null) {
                        paint.setShadowLayer(3f, 1f, 1f, 0xFF000000);
                        if (cmd.w > 0 && cmd.h > 0) {
                            paint.setTextAlign(Paint.Align.CENTER);
                            float textY = drawY + (cmd.h / 2f) - ((paint.descent() + paint.ascent()) / 2f);
                            canvas.drawText(txt, drawX + (cmd.w / 2f), textY, paint);
                        } else {
                            if (cmd.w == 0f) paint.setTextAlign(Paint.Align.LEFT);
                            else if (cmd.w == -1f) paint.setTextAlign(Paint.Align.RIGHT);
                            else if (cmd.w == -2f) paint.setTextAlign(Paint.Align.CENTER);
                            
                            canvas.drawText(txt, drawX, drawY, paint);
                        }
                        paint.clearShadowLayer();
                    }
                } else if (cmd.type == DrawCommand.TYPE_RECT) {
                    paint.setStyle(Paint.Style.STROKE);
                    if (cmd.x3 > 0) {
                        canvas.drawRoundRect(drawX, drawY, drawX + cmd.w, drawY + cmd.h, cmd.x3, cmd.x3, paint);
                    } else {
                        canvas.drawRect(drawX, drawY, drawX + cmd.w, drawY + cmd.h, paint);
                    }
                } else if (cmd.type == DrawCommand.TYPE_RECT_FILLED) {
                    paint.setStyle(Paint.Style.FILL);
                    if (cmd.x3 > 0) {
                        canvas.drawRoundRect(drawX, drawY, drawX + cmd.w, drawY + cmd.h, cmd.x3, cmd.x3, paint);
                    } else {
                        canvas.drawRect(drawX, drawY, drawX + cmd.w, drawY + cmd.h, paint);
                    }
                } else if (cmd.type == DrawCommand.TYPE_LINE) {
                    paint.setStrokeWidth(cmd.size);
                    paint.setStyle(Paint.Style.STROKE);
                    float endX = cmd.x + cmd.w;
                    if (endX <= -19000f) endX = (getWidth() / 2f) + (endX + 20000f);
                    else if (endX <= -9000f) endX = getWidth() + (endX + 10000f);
                    float endY = cmd.y + cmd.h;
                    if (endY <= -19000f) endY = (getHeight() / 2f) + (endY + 20000f);
                    else if (endY <= -9000f) endY = getHeight() + (endY + 10000f);
                    canvas.drawLine(drawX, drawY, endX, endY, paint);
                } else if (cmd.type == DrawCommand.TYPE_CIRCLE_FILLED) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(drawX, drawY, cmd.size, paint);
                } else if (cmd.type == DrawCommand.TYPE_TRIANGLE_FILLED) {
                    paint.setStyle(Paint.Style.FILL);
                    android.graphics.Path path = trianglePath;
                    path.rewind();
                    path.moveTo(drawX, drawY);
                    
                    float pt2X = cmd.w;
                    if (pt2X <= -19000f) pt2X = (getWidth() / 2f) + (pt2X + 20000f);
                    else if (pt2X <= -9000f) pt2X = getWidth() + (pt2X + 10000f);
                    float pt2Y = cmd.h;
                    if (pt2Y <= -19000f) pt2Y = (getHeight() / 2f) + (pt2Y + 20000f);
                    else if (pt2Y <= -9000f) pt2Y = getHeight() + (pt2Y + 10000f);
                    path.lineTo(pt2X, pt2Y);
                    
                    float pt3X = cmd.x3;
                    if (pt3X <= -19000f) pt3X = (getWidth() / 2f) + (pt3X + 20000f);
                    else if (pt3X <= -9000f) pt3X = getWidth() + (pt3X + 10000f);
                    float pt3Y = cmd.y3;
                    if (pt3Y <= -19000f) pt3Y = (getHeight() / 2f) + (pt3Y + 20000f);
                    else if (pt3Y <= -9000f) pt3Y = getHeight() + (pt3Y + 10000f);
                    path.lineTo(pt3X, pt3Y);
                    
                    path.close();
                    canvas.drawPath(path, paint);
                } else if (cmd.type == DrawCommand.TYPE_IMAGE) {
                    android.graphics.Bitmap bitmap = getImage(cmd.imageId);
                    if (bitmap != null) {
                        imageBounds.set(drawX, drawY, drawX + cmd.w, drawY + cmd.h);
                        canvas.drawBitmap(bitmap, null, imageBounds, paint);
                    }
                }

                if (isHudEditorMode && cmd.moduleId != null) {
                    if (hasExplicitHudElements(cmd.moduleId) || !isDraggableCommand(cmd)) {
                        continue;
                    }
                    ModuleBounds bounds = getCommandBounds(cmd);
                    if (bounds == null) {
                        continue;
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2f);
                    paint.setColor(0xFF4AE0A0);
                    canvas.drawRect(bounds.left - 2, bounds.top - 2, bounds.right + 2, bounds.bottom + 2, paint);
                }
            }
        }

        if (isHudEditorMode) {
            if (draggingHudElement != null
                    && (draggingHudElement.snapFlags & ExternalModBridge.HudEditorElement.SNAP_GRID) != 0
                    && draggingHudElement.gridSize > 0f) {
                float step = draggingHudElement.gridSize;
                while (step < 16f) step *= 2f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                paint.setColor(0x334AE0A0);
                for (float x = 0f; x <= getWidth(); x += step) canvas.drawLine(x, 0f, x, getHeight(), paint);
                for (float y = 0f; y <= getHeight(); y += step) canvas.drawLine(0f, y, getWidth(), y, paint);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(0xFF4AE0A0);
            for (ExternalModBridge.HudEditorElement element : currentHudEditorElements()) {
                if (element == null || isHiddenInHudEditor(element.moduleId)) continue;
                ModuleBounds bounds = getHudElementBounds(element);
                if (bounds != null) canvas.drawRect(bounds.left - 2f, bounds.top - 2f, bounds.right + 2f, bounds.bottom + 2f, paint);
            }

            paint.setColor(0xAAFFFFFF);
            paint.setStrokeWidth(1.5f);
            if (!Float.isNaN(snapGuideX)) canvas.drawLine(snapGuideX, 0f, snapGuideX, getHeight(), paint);
            if (!Float.isNaN(snapGuideY)) canvas.drawLine(0f, snapGuideY, getWidth(), snapGuideY, paint);
        }
    }
}
