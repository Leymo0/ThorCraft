package dev.leymo.thorcraft.panel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile

/**
 * The ThorCraft hub — everything before you open the game:
 *   Play (open Zalith), install/update the mod into a profile, update this app, and the bottom-screen panel.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_FOLDER = 41
        private const val GREEN = "#5B8731"
        private const val ORANGE = "#FF7A2F"
        private const val CARD = "#16181D"
        private const val MUTED = "#9AA1AE"
    }

    private val dp get() = resources.displayMetrics.density
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playInfo: TextView
    private lateinit var modInfo: TextView
    private lateinit var folderInfo: TextView
    private lateinit var profileList: LinearLayout
    private lateinit var installBtn: Button
    private lateinit var panelInfo: TextView
    private lateinit var appInfo: TextView
    private lateinit var appBtn: Button
    private lateinit var portField: EditText
    private lateinit var setupList: LinearLayout
    private lateinit var setupTitle: TextView

    @Volatile private var latest: Updater.Release? = null
    @Volatile private var profiles: List<Updater.Profile> = emptyList()
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (14 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(text("ThorCraft", 28f, ORANGE, bold = true))
        root.addView(text("Minecraft & Cobbleverse on both screens · app build ${BuildConfig.BUILD_NUMBER}", 13f, MUTED))
        status = text("", 14f, "#FFFFFF").also { it.setPadding(0, (8 * dp).toInt(), 0, 0) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        root.addView(progress)

        // ---------------- Setup (one-time)
        root.addView(card("Setup").apply {
            setupTitle = text("", 14f, "#FFFFFF")
            addView(setupTitle)
            setupList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(setupList)
        })

        // ---------------- Play
        root.addView(card("Play").apply {
            playInfo = text("", 14f, MUTED)
            addView(playInfo)
            addView(button("▶  Play — open Zalith Launcher", GREEN, big = true) { play() })
        })

        // ---------------- Mod
        root.addView(card("Mod (JAR)").apply {
            modInfo = text("Checking for the latest build…", 14f, "#FFFFFF")
            addView(modInfo)
            folderInfo = text("", 13f, MUTED)
            addView(folderInfo)
            profileList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(profileList)
            installBtn = button("Install mod", GREEN) { installMod() }
            addView(installBtn)
            addView(button("Use a different game folder", "#1F222A") { pickFolder(zalith = false) })
        })

        // ---------------- Bottom-screen panel
        root.addView(card("Bottom-screen panel").apply {
            panelInfo = text("", 14f, MUTED)
            addView(panelInfo)
            addView(button("Allow drawing over other apps", "#1F222A") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(button("Start panel", ORANGE) { startPanel(); refreshLocal() })
            addView(button("Stop panel", "#1F222A") { stopService(Intent(this@MainActivity, PanelService::class.java)); refreshLocal() })
            portField = EditText(this@MainActivity).apply {
                hint = "Port (default 47123)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Prefs.port(this@MainActivity).toString())
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            addView(portField)
        })

        // ---------------- App
        root.addView(card("This app").apply {
            appInfo = text("", 14f, MUTED)
            addView(appInfo)
            appBtn = button("Update app", ORANGE) { updateApp() }
            addView(appBtn)
            addView(button("Open releases page", "#1F222A") {
                Updater.openPage(this@MainActivity, "https://github.com/${BuildConfig.REPO}/releases")
            })
        })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.BLACK) })
    }

    override fun onResume() {
        super.onResume()
        refreshLocal()
        refreshRemote()
    }

    // ------------------------------------------------------------------ actions

    private fun play() {
        savePort()
        if (Settings.canDrawOverlays(this)) startPanel()
        say(Updater.launchGame(this))
    }

    private fun startPanel() {
        savePort()
        if (!Settings.canDrawOverlays(this)) {
            say("⚠ Allow drawing over other apps first.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, PanelService::class.java))
    }

    /** zalith = open the picker on Zalith's own folder (profiles live there); otherwise a normal folder picker. */
    private fun pickFolder(zalith: Boolean = true) {
        val i = if (zalith) Updater.connectZalithIntent(this)
        else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        if (zalith) say("Tap “Use this folder”, then “Allow”.")
        try {
            startActivityForResult(i, REQ_FOLDER)
        } catch (e: Exception) {
            say("⚠ Couldn't open the folder picker: ${e.message}")
        }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Prefs.setGameFolder(this, uri)
        Prefs.setProfile(this, null)
        refreshRemote()
    }

    private fun selectedProfile(): Updater.Profile? {
        val name = Prefs.profile(this)
        return profiles.firstOrNull { it.name == name } ?: profiles.firstOrNull()
    }

    private fun installMod() {
        val rel = latest ?: return say("Still checking GitHub… try again in a moment.")
        val profile = selectedProfile()
        work("Downloading build ${rel.build}…") { p -> Updater.installJar(this, rel, profile, p) }
    }

    private fun updateApp() {
        val rel = latest ?: return say("Still checking GitHub… try again in a moment.")
        if (!packageManager.canRequestPackageInstalls()) {
            say("Allow ThorCraft Panel to install updates, then tap Update again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        work("Downloading app build ${rel.build}…") { p -> Updater.updateApp(this, rel, p) }
    }

    /** Runs a download in the background with a progress bar. */
    private fun work(start: String, job: ((Int) -> Unit) -> String) {
        if (busy) return
        busy = true
        say(start)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        Thread {
            val msg = try {
                job { p -> runOnUiThread { progress.progress = p } }
            } catch (e: Exception) {
                "⚠ ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                say(msg)
                refreshRemote()
            }
        }.start()
    }

    // ------------------------------------------------------------------ refresh

    private fun refreshLocal() {
        renderSetup()
        val launcher = Updater.installedLauncher(this)
        playInfo.text = if (launcher != null) "Launcher: ${launcher.second} ✓" else "No Minecraft launcher found — install Zalith Launcher 2."
        val overlay = if (Settings.canDrawOverlays(this)) "✓ allowed" else "✗ not allowed yet"
        panelInfo.text = "Overlay permission: $overlay · Panel: ${if (PanelService.running) "running" else "stopped"}"
    }

    private fun refreshRemote() {
        Thread {
            val rel = try { Updater.latest() } catch (e: Exception) { null }
            val profs = try { Updater.profiles(this) } catch (e: Exception) { emptyList() }
            val inGame = Updater.modBuildInGame(this)
            latest = rel ?: latest
            profiles = profs
            runOnUiThread { render(rel, inGame) }
        }.start()
    }

    private fun render(rel: Updater.Release?, inGame: String?) {
        val r = rel ?: latest
        modInfo.text = buildString {
            append(if (r != null) "Latest mod: build ${r.build}" else "Couldn't reach GitHub (offline?)")
            if (inGame != null) append("\nRunning in game: ${inGame.removePrefix("thorcraft ")}")
        }

        val folder = Prefs.gameFolder(this)?.let { DocumentFile.fromTreeUri(this, it)?.name }
        folderInfo.text = if (folder != null) "Game folder: $folder" else "Not connected to Zalith yet (Setup step 3) — the JAR will go to Downloads/ThorCraft."

        profileList.removeAllViews()
        val chosen = selectedProfile()
        for (p in profiles) {
            val state = when {
                p.installedBuild == null -> "not installed"
                r != null && p.installedBuild < r.build -> "build ${p.installedBuild} → update available"
                else -> "build ${p.installedBuild} ✓"
            }
            val selected = p.name == chosen?.name
            profileList.addView(button((if (selected) "● " else "○ ") + "${p.name}\n$state", if (selected) "#2A3A1E" else "#1F222A") {
                Prefs.setProfile(this, p.name)
                render(latest, inGame)
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 14f })
        }
        if (folder != null && profiles.isEmpty()) {
            profileList.addView(text("No profiles found in that folder. Pick the folder that contains “versions” (usually .minecraft).", 13f, "#FFCC33"))
        }

        installBtn.text = when {
            r == null -> "Install mod"
            chosen == null -> "⬇ Save build ${r.build} to Downloads"
            chosen.installedBuild == r.build -> "Reinstall build ${r.build} into “${chosen.name}”"
            chosen.installedBuild != null -> "⬆ Update “${chosen.name}” to build ${r.build}"
            else -> "⬇ Install build ${r.build} into “${chosen.name}”"
        }

        val mine = BuildConfig.BUILD_NUMBER
        appInfo.text = if (r == null) "This app: build $mine"
        else if (r.build > mine) "This app: build $mine · New: build ${r.build}"
        else "This app: build $mine ✓ up to date"
        appBtn.visibility = if (r != null && r.build > mine) View.VISIBLE else View.GONE
        refreshLocal()
    }

    private fun renderSetup() {
        setupList.removeAllViews()
        val launcher = Updater.installedLauncher(this)
        val overlay = Settings.canDrawOverlays(this)
        val folder = Updater.folderConnected(this)
        val updates = packageManager.canRequestPackageInstalls()
        val steps = listOf(
            Triple(launcher != null, "1. Minecraft launcher",
                if (launcher != null) "${launcher.second} found" else "Install Zalith Launcher 2 first"),
            Triple(overlay, "2. Bottom-screen panel", if (overlay) "Allowed" else "Let ThorCraft draw on the bottom screen"),
            Triple(folder, "3. Connect to Zalith", if (folder) "Connected — mods install straight into your profiles"
                else "One tap so ThorCraft can install the mod into your profiles"),
            Triple(updates, "4. App updates", if (updates) "Allowed" else "Let ThorCraft update itself"),
        )
        val done = steps.count { it.first }
        setupTitle.text = if (done == steps.size) "✓ All set" else "$done of ${steps.size} done"
        steps.forEachIndexed { i, (ok, title, sub) ->
            if (ok) {
                setupList.addView(text("✓  $title — $sub", 14f, "#3DDC84"))
            } else {
                setupList.addView(button("$title\n$sub", "#2A1A10") {
                    when (i) {
                        0 -> Updater.openPage(this, "https://github.com/ZalithLauncher/ZalithLauncher2/releases")
                        1 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        2 -> pickFolder(zalith = true)
                        3 -> startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 14f })
            }
        }
        setupList.addView(text("Then in game: bottom screen → More → Controller setup (press A B X Y L R once).", 12f, MUTED))
    }

    // ------------------------------------------------------------------ ui helpers

    private fun savePort() {
        Prefs.setPort(this, portField.text.toString().toIntOrNull() ?: 47123)
    }

    private fun say(msg: String) {
        status.text = msg
    }

    private fun text(s: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(CARD)); cornerRadius = 14 * dp; setStroke((1 * dp).toInt(), Color.parseColor("#2A2E38"))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (12 * dp).toInt() }
        addView(text(title.uppercase(), 12f, MUTED, bold = true))
    }

    private fun button(label: String, color: String, big: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = if (big) 19f else 15f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = 10 * dp }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * dp).toInt()
        layoutParams = lp
        minHeight = ((if (big) 64 else 48) * dp).toInt()
        setOnClickListener { onClick() }
    }
}

object Prefs {
    private const val FILE = "thorcraft"
    private fun sp(a: android.content.Context) = a.getSharedPreferences(FILE, 0)
    fun port(a: android.content.Context): Int = sp(a).getInt("port", 47123)
    fun setPort(a: android.content.Context, p: Int) = sp(a).edit().putInt("port", p).apply()
    fun gameFolder(a: android.content.Context): android.net.Uri? = sp(a).getString("gameFolder", null)?.let(android.net.Uri::parse)
    fun setGameFolder(a: android.content.Context, u: android.net.Uri?) = sp(a).edit().putString("gameFolder", u?.toString()).apply()
    fun profile(a: android.content.Context): String? = sp(a).getString("profile", null)
    fun setProfile(a: android.content.Context, name: String?) = sp(a).edit().putString("profile", name).apply()
}
