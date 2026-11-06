package org.levimc.launcher.core.minecraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import com.mojang.minecraftpe.MainActivity
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.core.mods.ModManager
import org.levimc.launcher.core.mods.inbuilt.nativemod.PojavControlsMod
import org.levimc.launcher.core.mods.inbuilt.overlay.InbuiltOverlayManager
import org.levimc.launcher.preloader.PreloaderInput
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.util.LauncherBackgroundController
import org.levimc.pojavcontrols.PojavControls
import org.levimc.pojavcontrols.PojavControlsHost
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MinecraftActivity : MainActivity(), PojavControlsHost {

    companion object {
        private const val PRELOADER_DOCUMENT_REQUEST = 0x4C50
    }

    private lateinit var gameManager: GamePackageManager
    private lateinit var trace: LaunchTrace
    private var overlayManager: InbuiltOverlayManager? = null
    private var normalExitPrepared = false
    private var normalExitRestartScheduled = false
    private var gameRuntimeStarted = false
    private var preloaderTextInput: PreloaderTextInput? = null
    private var previousInputFocus: View? = null
    private var inbuiltKeyDispatchDepth = 0
    @Volatile
    private var storageDirectoryCache: ConcurrentHashMap<String, File>? = null

    private class PreloaderTextInput(context: Context) : AppCompatEditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val target = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(target, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (!text.isNullOrEmpty() && PreloaderInput.onTextInput(text)) {
                        super.commitText(text, newCursorPosition)
                        return true
                    }
                    return super.commitText(text, newCursorPosition)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun deleteSurroundingTextInCodePoints(
                    beforeLength: Int,
                    afterLength: Int
                ): Boolean {
                    if (beforeLength > 0 && dispatchBackspace()) {
                        return true
                    }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }

                private fun dispatchBackspace(): Boolean {
                    val downConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        true
                    )
                    val upConsumed = PreloaderInput.onKeyEvent(
                        KeyEvent.KEYCODE_DEL,
                        0,
                        false
                    )
                    return downConsumed || upConsumed
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherBackgroundController.suspendForMinecraft()
        LauncherBackgroundController.awaitShutdown()
        trace = LaunchTrace.ensure(intent)
        trace.mark("MinecraftActivity onCreate entered")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(resolveLaunchBackgroundColor()))

        if (savedInstanceState != null) {
            trace.mark("MinecraftActivity finishing restored instance")
            gameRuntimeStarted = true
            super.onCreate(null)
            finish()
            return
        }

        try {
            val preparedRuntime = MinecraftLaunchSession.getPreparedRuntime()
                ?: MinecraftRuntimePreparer.prepare(applicationContext, intent)
            gameManager = preparedRuntime.gameManager
            configureMinecraftFirebase()
            trace.mark("Prepared runtime consumed")
        } catch (throwable: Throwable) {
            trace.error("MinecraftActivity prepare failed", formatLaunchFailure(throwable))
            returnToLauncherAfterLaunchFailure()
            return
        }
        trace.mark("Native mod enable started")
        ModManager.enableLoadedMods()
        trace.mark("Native mod enable finished")
        trace.mark("Mojang MainActivity super.onCreate starting")
        try {
            gameRuntimeStarted = true
            super.onCreate(savedInstanceState)
        } catch (throwable: Throwable) {
            trace.error("Mojang MainActivity super.onCreate failed", formatLaunchFailure(throwable))
            returnToLauncherAfterLaunchFailure()
            return
        }
        trace.mark("Mojang MainActivity super.onCreate finished")

        val launchVertically = intent.getBooleanExtra("LAUNCH_VERTICALLY", false)
        if (launchVertically) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        initializePreloaderTextInput()
        PreloaderInput.setActivity(this)
        MinecraftActivityState.onCreated(this)
        trace.milestone("Minecraft activity ready")
    }


    private fun configureMinecraftFirebase() {
        val appId = gameManager.getGameStringResource("google_app_id")
            ?: "1:486187589451:android:b2331110821fe2304bd2ce"
        val apiKey = gameManager.getGameStringResource("google_api_key")
            ?: gameManager.getGameStringResource("google_crash_reporting_api_key")
            ?: ""
        val projectId = gameManager.getGameStringResource("project_id")
            ?: "minecraft-bedrock-57580"
        val senderId = gameManager.getGameStringResource("gcm_defaultSenderId")
            ?: "486187589451"
        intent.putExtra("MINECRAFT_FIREBASE_APP_ID", appId)
        intent.putExtra("MINECRAFT_FIREBASE_API_KEY", apiKey)
        intent.putExtra("MINECRAFT_FIREBASE_PROJECT_ID", projectId)
        intent.putExtra("MINECRAFT_FIREBASE_SENDER_ID", senderId)
    }

    private fun returnToLauncherAfterLaunchFailure() {
        gameRuntimeStarted = false
        MinecraftLaunchSession.clear()
        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this)
        finish()
    }

    private fun formatLaunchFailure(throwable: Throwable): String {
        return throwable.message ?: throwable.javaClass.simpleName
    }

    private fun resolveLaunchBackgroundColor(): Int {
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            typedValue.data
        } else {
            Color.BLACK
        }
    }

    private fun startInbuiltModServices() {
        overlayManager = InbuiltOverlayManager(this)
        overlayManager?.showEnabledOverlays()
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }

    override fun onNewIntent(intent: Intent) {
        setIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PRELOADER_DOCUMENT_REQUEST) {
            handlePreloaderDocumentResult(resultCode, data)
            return
        }
        if (org.levimc.launcher.core.mods.inbuilt.overlay.MoreButtonsEditor.onActivityResult(requestCode, resultCode, data)) return
        if (PojavControls.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun requestOpenModMenu() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    overlayManager?.showModMenu()
                }
            }
        }
    }

    fun requestCloseModMenu() {
        runOnUiThread {
            overlayManager?.hideModMenu()
        }
    }

    fun openPreloaderDocumentPicker(mimeType: String) {
        runOnUiThread {
            try {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType.ifBlank { "*/*" }
                }
                startActivityForResult(picker, PRELOADER_DOCUMENT_REQUEST)
            } catch (throwable: Throwable) {
                PreloaderInput.onDocumentResult(false, "", "", throwable.message ?: "Unable to open document picker")
            }
        }
    }

    private fun handlePreloaderDocumentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            PreloaderInput.onDocumentResult(false, "", "", "Import cancelled")
            return
        }
        val uri = data.data ?: return
        var temporaryFile: File? = null
        try {
            var displayName = "selected_file"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) displayName = cursor.getString(index) ?: displayName
                }
            }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "selected_file" }
            val directory = File(cacheDir, "preloader_documents").also { it.mkdirs() }
            val importedFile = File(directory, "${System.nanoTime()}_$safeName")
            temporaryFile = importedFile
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(importedFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to read selected document")
            PreloaderInput.onDocumentResult(true, importedFile.absolutePath, displayName, "")
        } catch (throwable: Throwable) {
            PreloaderInput.onDocumentResult(false, "", "", throwable.message ?: "Unable to import selected document")
        } finally {
            temporaryFile?.delete()
        }
    }

    override fun onBackPressed() {
        if (org.levimc.launcher.core.mods.inbuilt.overlay.MoreButtonsEditor.closeEditor()) return
        if (PojavControls.closeEditor()) return
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) {
            normalExitPrepared = false
            normalExitRestartScheduled = false
        }
        MinecraftActivityState.onResumed(this)
        org.levimc.launcher.core.mods.inbuilt.overlay.MoreButtonsEditor.onResume()

        if (overlayManager == null) {
            startInbuiltModServices()
        }
    }

    private fun isMouseSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    private fun getMouseButton(event: KeyEvent): Int {
        if (!isMouseSource(event.source)) {
            return 0
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> MotionEvent.BUTTON_PRIMARY
            KeyEvent.KEYCODE_BUTTON_2 -> MotionEvent.BUTTON_SECONDARY
            KeyEvent.KEYCODE_BUTTON_3 -> MotionEvent.BUTTON_TERTIARY
            KeyEvent.KEYCODE_BUTTON_4 -> MotionEvent.BUTTON_BACK
            KeyEvent.KEYCODE_BUTTON_5 -> MotionEvent.BUTTON_FORWARD
            else -> 0
        }
    }

    private fun shouldConsumeMouseMotion(event: MotionEvent): Boolean {
        return isMouseSource(event.source) &&
            PreloaderInput.onMouseMotion(
                event.actionMasked,
                event.actionButton,
                event.buttonState
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val textInputActive = isTextWidgetActive() || preloaderTextInput?.hasFocus() == true
        if (textInputActive) {
            overlayManager?.releaseKeybinds()
        }
        if (inbuiltKeyDispatchDepth == 0 && !textInputActive &&
            overlayManager?.handleModMenuShortcut(event) == true) {
            return true
        }
        val inbuiltKeyReleased = inbuiltKeyDispatchDepth == 0 &&
            event.action == KeyEvent.ACTION_UP && overlayManager?.handleKeyEvent(event) == true
        if (isTextWidgetActive() &&
            (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            return super.dispatchKeyEvent(event)
        }

        val mouseButton = getMouseButton(event)
        if (mouseButton != 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            PreloaderInput.onMouse(mouseButton, event.action == KeyEvent.ACTION_DOWN)) {
            return true
        }

        val unicodeChar = event.unicodeChar
        if (event.action == KeyEvent.ACTION_UP) {
            if (org.levimc.launcher.preloader.PreloaderInput.onKeyEvent(event.keyCode, unicodeChar, false)) {
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (org.levimc.launcher.preloader.PreloaderInput.onKeyEvent(event.keyCode, unicodeChar, true)) {
                return true
            }
        }

        if (inbuiltKeyReleased) return true
        if (inbuiltKeyDispatchDepth == 0 && !textInputActive) {
            if (overlayManager?.handleKeyEvent(event) == true) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun dispatchInbuiltKeyEvent(event: KeyEvent): Boolean {
        inbuiltKeyDispatchDepth++
        return try {
            dispatchKeyEvent(event)
        } finally {
            inbuiltKeyDispatchDepth--
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        if (org.levimc.launcher.core.mods.inbuilt.overlay.VirtualCursorMod.isActive()) {
            org.levimc.launcher.core.mods.inbuilt.overlay.VirtualCursorMod.processTouchEvent(event, this)
            return true
        }

        if (PojavControls.ownsTouchInput()) {
            return super.dispatchTouchEvent(event)
        }

        val action = event.actionMasked
        val consumed = if (action == MotionEvent.ACTION_MOVE ||
            action == MotionEvent.ACTION_CANCEL) {
            var anyConsumed = false
            for (index in 0 until event.pointerCount) {
                if (PreloaderInput.onTouch(action, event.getPointerId(index),
                        event.getX(index), event.getY(index))) {
                    anyConsumed = true
                }
            }
            anyConsumed
        } else {
            val index = event.actionIndex
            PreloaderInput.onTouch(action, event.getPointerId(index),
                event.getX(index), event.getY(index))
        }
        if (consumed) {
            return true
        }

        overlayManager?.handleTouchEvent(event)

        return super.dispatchTouchEvent(event)
    }

    fun dispatchGenericMotionEventToGame(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    fun dispatchTouchEventToGame(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (shouldConsumeMouseMotion(event)) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            overlayManager?.handleMouseEvent(event)
        }

        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vScroll != 0f) {
                overlayManager?.let { manager ->
                    if (manager.handleScrollEvent(vScroll)) {
                        return true
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun pojavSendKey(bedrockKeyCode: Int, down: Boolean) {
        PojavControlsMod.nativeSendKey(bedrockKeyCode, down)
    }

    override fun pojavSendMouseButton(androidButton: Int, down: Boolean) {
        PojavControlsMod.nativeSendMouseButton(androidButton, down)
    }

    override fun pojavSendScroll(vertical: Float) {
        PojavControlsMod.nativeSendScroll(vertical)
    }

    override fun pojavSendLookDelta(deltaX: Float, deltaY: Float) {
        PojavControlsMod.nativeSendLookDelta(deltaX, deltaY)
    }

    override fun pojavSendPointer(x: Float, y: Float) {
        PojavControlsMod.nativeSendPointer(x, y)
    }

    override fun pojavSendTouch(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    override fun pojavShowKeyboard() {
        showSoftKeyboard()
    }

    override fun pojavIsMenuOpen(): Boolean {
        if (PreloaderInput.isShowingMenu() || PreloaderInput.isPauseMenuOpen()) return true
        return !PreloaderInput.shouldForceGlobalModMenu() && !PreloaderInput.isHudScreenOpen()
    }

    override fun onPause() {
        overlayManager?.releaseModMenuShortcut()
        overlayManager?.releaseKeybinds()
        val shouldRestartAfterNormalExit = shouldRestartAfterNormalExit()
        if (shouldRestartAfterNormalExit) {
            PreloaderInput.cancelDocumentRequest("Minecraft closed")
            ModManager.disableAndUnloadLoadedMods()
            prepareNormalExitCleanup()
            scheduleNormalExitProcessRestart()
        }
        MinecraftActivityState.onPaused(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            overlayManager?.releaseModMenuShortcut()
            overlayManager?.releaseKeybinds()
        }
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        PreloaderInput.cancelDocumentRequest("Minecraft closed")
        ModManager.disableAndUnloadLoadedMods()
        val shouldPrepareNormalExit = shouldRestartAfterNormalExit()
        if (shouldPrepareNormalExit) {
            prepareNormalExitCleanup()
        }

        preloaderTextInput = null
        previousInputFocus = null
        PreloaderInput.clearActivity()
        MinecraftActivityState.onDestroyed(this)
        MinecraftLaunchSession.clear()
        stopInbuiltModServices()

        try {
            super.onDestroy()
        } finally {
            if (shouldPrepareNormalExit) {
                scheduleNormalExitProcessRestart()
            }
        }
    }

    private fun shouldRestartAfterNormalExit(): Boolean {
        return gameRuntimeStarted && isFinishing && !CrashReporter.isHandlingCrash()
    }

    private fun prepareNormalExitCleanup() {
        if (normalExitPrepared) return
        normalExitPrepared = true
    }

    private fun scheduleNormalExitProcessRestart() {
        if (normalExitRestartScheduled) return
        normalExitRestartScheduled = true

        MinecraftProcessRestarter.restartLauncherAfterMinecraftExit(this)
    }

    override fun getAssets(): AssetManager {
        return if (::gameManager.isInitialized) {
            gameManager.getAssets()
        } else {
            super.getAssets()
        }
    }

    override fun getFilesDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR) { super.getFilesDir() }
    }

    override fun tick() {
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR) { super.getDataDir() }
    }

    override fun getExternalFilesDir(type: String?): File? {
        val baseDir = resolveStorageDir(
            MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR
        ) { super.getExternalFilesDir(null) }
        return if (type.isNullOrEmpty()) {
            baseDir
        } else {
            File(baseDir, type).also { if (!it.isDirectory) it.mkdirs() }
        }
    }

    override fun getInternalStoragePath(): String {
        return getFilesDir().absolutePath
    }

    override fun getExternalStoragePath(): String {
        return (getExternalFilesDir(null) ?: getFilesDir()).absolutePath
    }

    private fun resolveStorageDir(extraName: String, fallback: () -> File?): File {
        val path = intent?.getStringExtra(extraName)
        if (path.isNullOrEmpty()) {
            return (fallback() ?: super.getFilesDir()).also { if (!it.isDirectory) it.mkdirs() }
        }
        var cache = storageDirectoryCache
        if (cache == null) {
            cache = synchronized(this) {
                storageDirectoryCache ?: ConcurrentHashMap<String, File>().also { storageDirectoryCache = it }
            }
        }
        return cache.getOrPut(extraName + '\u0000' + path) {
            File(path).also { if (!it.isDirectory) it.mkdirs() }
        }
    }

    override fun getDatabasePath(name: String): File {
        val dbDir = File(getDataDir(), "databases")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        return File(dbDir, name)
    }

    override fun getCacheDir(): File {
        return resolveStorageDir(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR) { super.getCacheDir() }
    }

    private fun initializePreloaderTextInput() {
        val input = PreloaderTextInput(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isEmojiCompatEnabled = false
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            alpha = 0f
            visibility = View.GONE
        }
        findViewById<ViewGroup>(android.R.id.content).addView(
            input,
            ViewGroup.LayoutParams(1, 1)
        )
        preloaderTextInput = input
    }

    fun showSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            previousInputFocus = currentFocus?.takeUnless { it === input }
            input.visibility = View.VISIBLE
            input.setText("")
            input.requestFocus()
            input.setSelection(0)

            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.restartInput(input)
            if (!inputMethodManager.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            ) {
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        }
    }

    fun hideSoftKeyboard() {
        runOnUiThread {
            val input = preloaderTextInput ?: return@runOnUiThread
            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            input.visibility = View.GONE

            previousInputFocus
                ?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
                ?.requestFocus()
            previousInputFocus = null
        }
    }
}
