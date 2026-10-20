package dev.leymo.thorcraft.panel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Shows the panel as a NON-FOCUSABLE overlay window on the Thor's second display.
 * Non-focusable = touches work, but the gamepad / keyboard focus stays with Minecraft on the top screen.
 * Only while you type (chat, signs) the window briefly becomes focusable so the keyboard can appear.
 */
class PanelService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "panel"
        private const val NOTIF_ID = 47
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE = "toggle"
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var web: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var hidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { toggle(); return START_STICKY }
        }
        startInForeground()
        if (web == null) showPanel()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        removePanel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ window

    private fun targetDisplay(): Display {
        val dm = getSystemService(DisplayManager::class.java)
        // The Thor's bottom screen is the non-default display. Fall back to the main screen (phones / testing).
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPanel() {
        val display = targetDisplay()
        val ctx: Context = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windowManager = ctx.getSystemService(WindowManager::class.java)

        val view = WebView(ctx).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true // the offline waiting page polls the mod over http
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addJavascriptInterface(Bridge(), "Thor")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) main.post { loadWaiting() }
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        lp.title = "ThorCraft Panel"
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        windowManager.addView(view, lp)
        wm = windowManager
        web = view
        params = lp
        loadWaiting()
    }

    private fun removePanel() {
        val w = web ?: return
        try { wm?.removeView(w) } catch (_: Exception) {}
        w.destroy()
        web = null
    }

    private fun loadWaiting() {
        web?.loadUrl("file:///android_asset/waiting.html")
    }

    private fun toggle() {
        val w = web ?: return
        hidden = !hidden
        w.visibility = if (hidden) View.GONE else View.VISIBLE
        startInForeground()
    }

    private fun setFocusable(focusable: Boolean) {
        val w = web ?: return
        val lp = params ?: return
        lp.flags = if (focusable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm?.updateViewLayout(w, lp) } catch (_: Exception) {}
        if (focusable) {
            w.requestFocus()
            w.postDelayed({
                val imm = w.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(w, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        } else {
            val imm = w.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(w.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------ JS bridge (window.Thor)

    inner class Bridge {
        @JavascriptInterface fun setKeyboard(on: Boolean) { main.post { setFocusable(on) } }

        @JavascriptInterface fun vibrate(ms: Int) {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            else getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(ms.coerceIn(5, 600).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }

        @JavascriptInterface fun port(): Int = Prefs.port(this@PanelService)

        @JavascriptInterface fun waiting() { main.post { setFocusable(false); loadWaiting() } }

        @JavascriptInterface fun open(url: String) { main.post { web?.loadUrl(url) } }

        @JavascriptInterface fun hide() { main.post { if (!hidden) toggle() } }
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bottom-screen panel", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val toggle = PendingIntent.getService(this, 1, Intent(this, PanelService::class.java).setAction(ACTION_TOGGLE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, PanelService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)

        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_panel)
            .setContentTitle("ThorCraft panel " + if (hidden) "(hidden)" else "on the bottom screen")
            .setContentText("Tap for settings")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, if (hidden) "Show" else "Hide", toggle).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
