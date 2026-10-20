package dev.leymo.thorcraft.panel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup screen: grant overlay permission, pick the port, start/stop the bottom-screen panel. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var portField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "ThorCraft Panel"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF7A2F"))
        })
        root.addView(TextView(this).apply {
            text = "Puts the ThorCraft Minecraft panel on the AYN Thor's bottom screen. " +
                "Touching it never takes controller focus away from the game on the top screen."
            setTextColor(Color.parseColor("#B0B6C2"))
            setPadding(0, pad / 2, 0, pad)
        })

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        root.addView(status)

        portField = EditText(this).apply {
            hint = "Port (default 47123)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.port(this@MainActivity).toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(portField)

        root.addView(button("1. Allow drawing over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Start bottom-screen panel", primary = true) {
            savePort()
            if (!Settings.canDrawOverlays(this)) {
                status.text = "⚠ Overlay permission needed first (button 1)."
                return@button
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, PanelService::class.java))
            refresh()
        })
        root.addView(button("Stop panel") {
            stopService(Intent(this, PanelService::class.java))
            refresh()
        })

        root.addView(TextView(this).apply {
            text = "\nHow to use\n" +
                "• Start the panel once; it waits on the bottom screen until Minecraft (with the ThorCraft mod) is running.\n" +
                "• Launch Minecraft on the top screen as usual.\n" +
                "• The panel follows the game: chests, inventories, server menus, battles and chat show up below.\n" +
                "• Pull down the notification to hide or stop the panel."
            setTextColor(Color.parseColor("#B0B6C2"))
        })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.BLACK) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun savePort() {
        val p = portField.text.toString().toIntOrNull() ?: 47123
        Prefs.setPort(this, p)
    }

    private fun refresh() {
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays.joinToString { "#${it.displayId} ${it.name} ${it.mode.physicalWidth}×${it.mode.physicalHeight}" }
        val overlay = if (Settings.canDrawOverlays(this)) "✓ allowed" else "✗ not allowed"
        val running = if (PanelService.running) "running" else "stopped"
        status.text = "Overlay permission: $overlay\nPanel: $running\nDisplays: $displays\n"
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setBackgroundColor(Color.parseColor(if (primary) "#FF7A2F" else "#1F222A"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (10 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }
}

object Prefs {
    private const val FILE = "thorcraft"
    fun port(a: android.content.Context): Int = a.getSharedPreferences(FILE, 0).getInt("port", 47123)
    fun setPort(a: android.content.Context, p: Int) = a.getSharedPreferences(FILE, 0).edit().putInt("port", p).apply()
}
