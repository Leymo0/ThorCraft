package dev.leymo.thorcraft.panel

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Display
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Launching the game, and downloading the newest mod JAR / app APK from this project's GitHub Releases.
 * Everything here does network or disk work: call it off the main thread.
 */
object Updater {

    /** Minecraft launchers to try, best first. */
    val LAUNCHERS = listOf(
        "com.movtery.zalithlauncher.v2" to "Zalith Launcher 2",
        "com.movtery.zalithlauncher" to "Zalith Launcher",
        "com.tungsten.fcl" to "Fold Craft Launcher",
        "org.angelauncher" to "Amethyst",
        "net.kdt.pojavlaunch" to "PojavLauncher",
    )

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val build: Int, val assets: List<Asset>, val page: String) {
        fun jar() = assets.firstOrNull { it.name.endsWith(".jar") && !it.name.contains("sources") }
        fun apk() = assets.firstOrNull { it.name.endsWith(".apk") }
    }

    // ------------------------------------------------------------------ launcher

    fun installedLauncher(ctx: Context): Pair<String, String>? =
        LAUNCHERS.firstOrNull { (pkg, _) -> ctx.packageManager.getLaunchIntentForPackage(pkg) != null }

    /** Opens the launcher on the TOP screen (the Thor's main display). */
    fun launchGame(ctx: Context): String {
        val found = installedLauncher(ctx) ?: return "No Minecraft launcher found. Install Zalith Launcher 2 first."
        val intent = ctx.packageManager.getLaunchIntentForPackage(found.first)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        ctx.startActivity(intent, opts.toBundle())
        return "Opening ${found.second}…"
    }

    // ------------------------------------------------------------------ GitHub

    fun latest(): Release {
        val conn = open("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        val list = (0 until assets.length()).map {
            val a = assets.getJSONObject(it)
            Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
        }
        val build = tag.substringAfterLast('-').toIntOrNull() ?: 0
        return Release(tag, build, list, json.optString("html_url"))
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ThorCraft-Panel")
        if (conn.responseCode !in 200..299) error("GitHub answered ${conn.responseCode}")
        return conn
    }

    private fun download(asset: Asset, out: OutputStream, progress: (Int) -> Unit) {
        val conn = open(asset.url)
        val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else asset.size
        conn.inputStream.use { input -> copy(input, out, total, progress) }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, progress: (Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var last = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
                val p = (done * 100 / total).toInt()
                if (p != last) { last = p; progress(p) }
            }
        }
        out.flush()
    }

    // ------------------------------------------------------------------ profiles (Minecraft versions in the launcher)

    /** One launcher profile, e.g. "COBBLEVERSE 1.7.42", with its mods folder and the ThorCraft build in it. */
    data class Profile(val name: String, val mods: DocumentFile?, val dir: DocumentFile, val installedBuild: Int?)

    /**
     * Finds the "versions" folder whether you picked versions itself, .minecraft, a custom game folder,
     * or Zalith's own folder (android_data → files → .minecraft → versions).
     */
    private fun versionsDir(root: DocumentFile): DocumentFile? {
        if (root.name == "versions") return root
        val paths = listOf(
            listOf("versions"), listOf(".minecraft", "versions"), listOf("files", ".minecraft", "versions"),
            listOf("android_data", "files", ".minecraft", "versions")
        )
        for (path in paths) {
            var d: DocumentFile? = root
            for (part in path) d = d?.findFile(part)
            if (d != null && d.isDirectory) return d
        }
        return null
    }

    // ------------------------------------------------------------------ Zalith's own folder (one-time permission)

    /**
     * Zalith Launcher shares its app folder (Android/data/…, where profiles live) through Android's file picker.
     * This opens the picker already on that folder; you tap "Use this folder" → "Allow" once.
     */
    fun connectZalithIntent(ctx: Context): Intent {
        val pkg = installedLauncher(ctx)?.first?.takeIf { it.startsWith("com.movtery.zalithlauncher") }
            ?: "com.movtery.zalithlauncher.v2"
        val start = DocumentsContract.buildDocumentUri("$pkg.document.provider", "$pkg/android_data")
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, start)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }

    /** True if the saved folder permission still works. */
    fun folderConnected(ctx: Context): Boolean {
        val uri = Prefs.gameFolder(ctx) ?: return false
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return granted && (DocumentFile.fromTreeUri(ctx, uri)?.canRead() == true)
    }

    fun profiles(ctx: Context): List<Profile> {
        val tree = Prefs.gameFolder(ctx) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = mutableListOf<Profile>()
        val versions = versionsDir(root)
        versions?.listFiles()?.filter { it.isDirectory }?.forEach { v ->
            val mods = v.findFile("mods")
            out += Profile(v.name ?: "?", mods, v, installedBuild(mods))
        }
        // No version isolation: one shared mods folder
        if (out.isEmpty()) {
            val shared = root.findFile("mods") ?: root.findFile(".minecraft")?.findFile("mods")
            if (shared != null) out += Profile("Shared mods folder", shared, shared, installedBuild(shared))
        }
        return out.sortedWith(compareByDescending<Profile> { it.mods != null }.thenBy { it.name.lowercase() })
    }

    private fun installedBuild(mods: DocumentFile?): Int? {
        val jar = mods?.listFiles()?.firstOrNull { (it.name ?: "").startsWith("thorcraft-") && (it.name ?: "").endsWith(".jar") }
            ?: return null
        return Regex("""thorcraft-\d+\.\d+\.(\d+)\.jar""").find(jar.name ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ------------------------------------------------------------------ mod JAR

    /**
     * Installs the newest mod JAR into the chosen profile's mods folder (older ThorCraft jars removed).
     * Without a game folder/profile it saves to Downloads/ThorCraft instead.
     */
    fun installJar(ctx: Context, rel: Release, profile: Profile?, progress: (Int) -> Unit): String {
        val asset = rel.jar() ?: error("This release has no JAR")
        if (profile != null) {
            val mods = profile.mods ?: profile.dir.createDirectory("mods") ?: error("Couldn't create the mods folder")
            if (!mods.canWrite()) error("That folder is read-only")
            mods.listFiles().filter { (it.name ?: "").startsWith("thorcraft-") && (it.name ?: "").endsWith(".jar") }
                .forEach { it.delete() }
            val file = mods.createFile("application/java-archive", asset.name) ?: error("Couldn't create the file")
            ctx.contentResolver.openOutputStream(file.uri)!!.use { download(asset, it, progress) }
            return "Installed build ${rel.build} into “${profile.name}”. Restart Minecraft if it's running."
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, asset.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/java-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ThorCraft")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't save to Downloads")
        ctx.contentResolver.openOutputStream(uri)!!.use { download(asset, it, progress) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        return "Saved to Downloads/ThorCraft/${asset.name}.\nIn Zalith: your profile → Mods → add it (remove the old thorcraft jar)."
    }

    // ------------------------------------------------------------------ app APK

    /** Downloads the newest app and hands it to Android's installer (you confirm on screen). */
    fun updateApp(ctx: Context, rel: Release, progress: (Int) -> Unit): String {
        val asset = rel.apk() ?: error("This release has no APK")
        val file = File(ctx.cacheDir, "update.apk")
        file.outputStream().use { download(asset, it, progress) }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("ThorCraft-Panel.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> copy(input, out, file.length()) {} }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(callback.intentSender)
        }
        return "Installing build ${rel.build}… confirm on screen."
    }

    /** Asks the running game which mod build it has (null if Minecraft isn't running). */
    fun modBuildInGame(ctx: Context): String? = try {
        val conn = URL("http://127.0.0.1:${Prefs.port(ctx)}/ping").openConnection() as HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 800
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    fun openPage(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
