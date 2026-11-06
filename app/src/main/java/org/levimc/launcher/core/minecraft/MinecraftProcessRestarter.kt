package org.levimc.launcher.core.minecraft

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import org.levimc.launcher.R
import org.levimc.launcher.ui.activities.BaseActivity
import org.levimc.launcher.ui.activities.MainActivity
import org.levimc.launcher.util.PersonalizationManager
import kotlin.system.exitProcess

private const val EXTRA_OLD_MAIN_PROCESS_PID = "org.levimc.launcher.extra.OLD_MAIN_PROCESS_PID"
const val EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW =
    "org.levimc.launcher.extra.CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW"
const val ACTION_MAIN_ACTIVITY_FIRST_DRAWN =
    "org.levimc.launcher.action.MAIN_ACTIVITY_FIRST_DRAWN"
private const val LEGACY_LAUNCHER_RESTART_REQUEST_CODE = 0x1E72
private const val KILL_OLD_PROCESS_DELAY_MS = 300L
private const val RELAUNCH_AFTER_KILL_DELAY_MS = 700L

object MinecraftProcessRestarter {
    fun restartLauncherAfterMinecraftExit(context: Context) {
        org.levimc.launcher.core.discord.DiscordPresence.shutdown()
        val appContext = context.applicationContext
        val oldPid = Process.myPid()
        cancelLegacyLauncherRestart(appContext)

        val intent = Intent(appContext, LauncherRestartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_OLD_MAIN_PROCESS_PID, oldPid)
        }

        try {
            appContext.startActivity(intent)
        } catch (_: Throwable) {
            Process.killProcess(oldPid)
            exitProcess(0)
        }
    }

    fun cancelLegacyLauncherRestart(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = legacyLauncherRestartPendingIntent(context) ?: return

        try {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (_: Throwable) {
        }
    }

    private fun legacyLauncherRestartPendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val flags = PendingIntent.FLAG_NO_CREATE or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        return PendingIntent.getActivity(
            context,
            LEGACY_LAUNCHER_RESTART_REQUEST_CODE,
            intent,
            flags
        )
    }
}

class LauncherRestartActivity : BaseActivity() {
    private var handled = false
    private var closeReceiverRegistered = false
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MAIN_ACTIVITY_FIRST_DRAWN) {
                hideSystemUI()
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun shouldSkipNavBar(): Boolean = true

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        registerCloseReceiver()
        setupRestartUi()
        restartLauncher(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restartLauncher(intent)
    }

    private fun restartLauncher(sourceIntent: Intent?) {
        if (handled) return
        handled = true

        val oldPid = sourceIntent?.getIntExtra(EXTRA_OLD_MAIN_PROCESS_PID, -1) ?: -1

        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.postDelayed({
            hideSystemUI()
            if (oldPid > 0 && oldPid != Process.myPid()) {
                Process.killProcess(oldPid)
                hideSystemUI()
            }

            mainHandler.postDelayed({ hideSystemUI() }, 120L)
            mainHandler.postDelayed({ hideSystemUI() }, 360L)
            mainHandler.postDelayed({
                hideSystemUI()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra(EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW, true)
                })
                overridePendingTransition(0, 0)
            }, RELAUNCH_AFTER_KILL_DELAY_MS)
        }, KILL_OLD_PROCESS_DELAY_MS)
    }

    private fun registerCloseReceiver() {
        if (closeReceiverRegistered) return
        closeReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter(ACTION_MAIN_ACTIVITY_FIRST_DRAWN),
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun setupRestartUi() {
        val accent = PersonalizationManager(this).getAccentColor()
        val baseBackground = ContextCompat.getColor(this, R.color.background)
        val onBackground = ContextCompat.getColor(this, R.color.on_background)
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = baseBackground
        window.navigationBarColor = baseBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply {
            setBackgroundColor(baseBackground)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            alpha = 0f
            translationY = dp(6).toFloat()
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val logoHolder = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(accent, if (isDarkMode()) 44 else 32))
                setStroke(dp(1), withAlpha(accent, if (isDarkMode()) 110 else 92))
            }
        }
        content.addView(
            logoHolder,
            LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                bottomMargin = dp(18)
            }
        )

        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = getString(R.string.splash_logo_desc)
        }
        logoHolder.addView(
            logo,
            FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        )

        val appName = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(accent)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(
            appName,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val title = TextView(this).apply {
            setText(R.string.launcher_restart_title)
            setTextColor(onBackground)
            textSize = 24f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            setText(R.string.launcher_restart_subtitle)
            setTextColor(secondaryText)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
        }
        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
            }
        )

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(withAlpha(accent, 42))
        }
        val progressWidth = (resources.displayMetrics.widthPixels - dp(96))
            .coerceAtMost(dp(280))
            .coerceAtLeast(dp(180))
        content.addView(
            progress,
            LinearLayout.LayoutParams(progressWidth, dp(5)).apply {
                topMargin = dp(24)
            }
        )

        val status = TextView(this).apply {
            setText(R.string.launcher_restart_status)
            setTextColor(blendColors(secondaryText, accent, 0.35f))
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        content.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(13)
            }
        )

        setContentView(root)
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun isDarkMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val boundedRatio = ratio.coerceIn(0f, 1f)
        val inverse = 1f - boundedRatio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * boundedRatio).toInt(),
            (Color.green(from) * inverse + Color.green(to) * boundedRatio).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * boundedRatio).toInt()
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onDestroy() {
        if (closeReceiverRegistered) {
            unregisterReceiver(closeReceiver)
            closeReceiverRegistered = false
        }
        super.onDestroy()
    }
}
