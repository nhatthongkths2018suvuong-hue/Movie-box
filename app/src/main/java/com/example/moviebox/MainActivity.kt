package com.example.moviebox

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class Entry(val file: File?, val label: String, val kind: Int)

class MainActivity : AppCompatActivity() {

    companion object {
        val EXT = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "mts",
            "mpg", "mpeg", "webm", "3gp", "flv", "wmv", "vob"
        )
        const val OFF = 0
        const val ONE = 1
        const val FOLDER = 2
        const val ALL = 3
        const val KIND_UP = 0
        const val KIND_DIR = 1
        const val KIND_FILE = 2
        val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
        const val OSD_MS = 6000L
        const val SEEK_MS = 10000L
    }

    private val aspectNames = arrayOf("Tỉ lệ gốc", "Full cả 2 lề", "Kéo giãn lề ngang", "Kéo giãn lề dọc")

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: View
    private lateinit var divider: View
    private lateinit var videoHost: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var placeholder: View
    private lateinit var controls: LinearLayout
    private lateinit var titleV: TextView
    private lateinit var artistV: TextView
    private lateinit var timeV: TextView
    private lateinit var repeatLabel: TextView
    private lateinit var aspectLabel: TextView
    private lateinit var pathV: TextView
    private lateinit var hud: TextView
    private lateinit var subV: TextView
    private var subText = ""
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var rv: RecyclerView
    private lateinit var player: ExoPlayer

    private val adapter = Adapter()
    private val ui = Handler(Looper.getMainLooper())
    private var ready = false
    private var current: File? = null
    private var currentDir: File? = null
    private var queue: List<File> = emptyList()
    private var queueDir: File? = null
    private var browseDir: File? = null
    private var browseTracks: List<File> = emptyList()
    private var repeatMode = OFF
    private var aspectMode = 0
    private var loadToken = 0
    private var fullscreen = false
    private var osdVisible = false
    private var ffmpegOn = false
    private var vw = 0f
    private var vh = 0f
    private val allCache = ConcurrentHashMap<String, List<File>>()
    private val scanning = ConcurrentHashMap<String, Boolean>()

    private val hideHudR = Runnable { hud.visibility = View.GONE }
    private val hideOsdR = Runnable { hideOsd() }

    private val ticker = object : Runnable {
        override fun run() {
            if (ready) {
                try {
                    seek.progress = player.currentPosition.toInt()
                    updateTime()
                } catch (e: Exception) {
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("moviebox", MODE_PRIVATE)
        root = findViewById(R.id.root)
        leftPanel = findViewById(R.id.leftPanel)
        rightPanel = findViewById(R.id.rightPanel)
        divider = findViewById(R.id.divider)
        videoHost = findViewById(R.id.videoHost)
        surface = findViewById(R.id.surface)
        placeholder = findViewById(R.id.placeholder)
        controls = findViewById(R.id.controls)
        titleV = findViewById(R.id.title)
        artistV = findViewById(R.id.artist)
        timeV = findViewById(R.id.time)
        repeatLabel = findViewById(R.id.repeatLabel)
        aspectLabel = findViewById(R.id.aspectLabel)
        pathV = findViewById(R.id.path)
        hud = findViewById(R.id.hud)
        subV = findViewById(R.id.sub)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        btnRepeat = findViewById(R.id.btnRepeat)
        rv = findViewById(R.id.rv)

        repeatMode = prefs.getInt("repeat", OFF)
        aspectMode = prefs.getInt("aspect", 0)

        setupPlayer()

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        videoHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        videoHost.setOnClickListener { playFullscreen() }

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(1) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { prev() }
        btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 4
            prefs.edit().putInt("repeat", repeatMode).apply()
            updateRepeatUi()
            if (fullscreen) scheduleOsdHide()
            // Chỉ quét toàn bộ ổ lưu trữ khi người dùng chọn chế độ này
            if (repeatMode == ALL) (current ?: currentDir)?.let { ensureScan(it) }
        }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener {
            aspectMode = (aspectMode + 1) % aspectNames.size
            prefs.edit().putInt("aspect", aspectMode).apply()
            updateAspectUi()
            applyAspect()
            if (fullscreen) scheduleOsdHide()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && ready) {
                    player.seekTo(p.toLong())
                    updateTime()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        updateRepeatUi()
        updateAspectUi()
        ui.post(ticker)

        checkLastCrash()

        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startBrowsing()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
        }
    }

    private fun setupPlayer() = buildPlayer(false)

    // Mặc định dùng bộ giải mã của máy. Bộ FFmpeg chỉ được bật khi video có âm thanh mà máy không giải mã được.
    private fun buildPlayer(withFfmpeg: Boolean) {
        val rf = DefaultRenderersFactory(this)
            .setExtensionRendererMode(
                if (withFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).build()
        ffmpegOn = withFfmpeg
        player.setVideoSurfaceView(surface)
        player.addListener(playerListener)
    }

    private fun reloadWithFfmpeg() {
        val f = current ?: return
        if (ffmpegOn || prefs.getBoolean("ffmpeg_blocked", false)) return
        val pos = player.currentPosition
        getSharedPreferences("crash", MODE_PRIVATE).edit().putBoolean("ffmpeg", true).commit()
        player.release()
        ready = false
        buildPlayer(true)
        loadMedia(f)
        player.seekTo(pos)
        player.play()
        updatePlayIcon()
        showHud("Đang dùng bộ giải mã âm thanh bổ sung", 2500L)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                ready = true
                val d = player.duration
                if (d > 0) seek.max = d.toInt()
                seek.keyProgressIncrement = SEEK_MS.toInt()
                updateTime()
            } else if (playbackState == Player.STATE_ENDED) {
                onComplete()
            }
            updatePlayIcon()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
                if (!ffmpegOn && !prefs.getBoolean("ffmpeg_blocked", false)) ui.post { reloadWithFfmpeg() }
                else showHud("Máy không giải mã được âm thanh của video này", 3000L)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            subText = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            updateSubtitle()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            vw = videoSize.width * videoSize.pixelWidthHeightRatio
            vh = videoSize.height.toFloat()
            applyAspect()
        }

        override fun onPlayerError(error: PlaybackException) {
            ready = false
            toast("Không phát được: " + (current?.name ?: ""))
            updatePlayIcon()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startBrowsing()
        else toast("Cần cấp quyền đọc bộ nhớ để duyệt video")
    }

    override fun onStart() {
        super.onStart()
        if (fullscreen && current != null) showOsd()
    }

    override fun onStop() {
        // Phim không phát nền: thoát ra ngoài thì tạm dừng
        player.pause()
        updatePlayIcon()
        getSharedPreferences("crash", MODE_PRIVATE).edit().remove("playing").remove("ffmpeg").apply()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    // ---------- Ghi lại lỗi để báo cáo ----------

    private fun summarize(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            sb.append(if (depth == 0) "" else "Nguyên nhân: ").append(t.javaClass.name).append(": ").append(t.message).append('\n')
            t.stackTrace.take(6).forEach { sb.append("  tại ").append(it).append('\n') }
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    private fun installCrashHandler() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("crash", MODE_PRIVATE).edit().putString(
                    "last",
                    "Máy: " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + ", " +
                        Build.SUPPORTED_ABIS.joinToString(",") + "\nLuồng: " + t.name + "\n" + summarize(e)
                ).commit()
            } catch (x: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }

    private fun checkLastCrash() {
        val cp = getSharedPreferences("crash", MODE_PRIVATE)
        val last = cp.getString("last", null)
        val playing = cp.getString("playing", null)
        if (last == null && playing == null) return
        val ffCrash = cp.getBoolean("ffmpeg", false)
        cp.edit().clear().commit()
        if (ffCrash) prefs.edit().putBoolean("ffmpeg_blocked", true).commit()
        val blocked = prefs.getBoolean("ffmpeg_blocked", false)
        val msg = StringBuilder()
        if (playing != null) msg.append("Đang mở video: ").append(playing).append("\n\n")
        msg.append(last ?: "Không có chi tiết lỗi (nhiều khả năng lỗi nằm ở bộ giải mã).")
        if (ffCrash) msg.append("\n\nBộ giải mã âm thanh bổ sung gây lỗi trên máy này nên đã được TẮT. Các video khác vẫn phát bình thường; video có âm thanh lạ có thể không có tiếng.")
        val dlg = AlertDialog.Builder(this)
            .setTitle("Lần trước app bị đóng đột ngột")
            .setMessage(msg.toString())
            .setPositiveButton("Đóng", null)
        if (blocked) {
            dlg.setNeutralButton("Thử bật lại bộ giải mã bổ sung") { _, _ ->
                prefs.edit().putBoolean("ffmpeg_blocked", false).commit()
                toast("Đã bật lại")
            }
        }
        dlg.show()
    }

    // ---------- Phím điều khiển ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (fullscreen && !osdVisible) {
            val code = e.keyCode
            val mine = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            if (!mine) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) togglePlay()
                    KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-SEEK_MS)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(SEEK_MS)
                    else -> showOsd()
                }
            }
            return true
        }
        if (fullscreen && osdVisible && e.action == KeyEvent.ACTION_DOWN) scheduleOsdHide()
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (fullscreen) {
                    exitFullscreen()
                    return true
                }
                return super.onKeyDown(code, e)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); updatePlayIcon() }
            KeyEvent.KEYCODE_MEDIA_NEXT -> step(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(SEEK_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-SEEK_MS)
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    // ---------- Toàn màn hình ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        osdVisible = false
        rightPanel.visibility = View.GONE
        divider.visibility = View.GONE
        leftPanel.setPadding(0, 0, 0, 0)
        leftPanel.removeView(controls)
        controls.setBackgroundColor(0xCC000000.toInt())
        controls.setPadding(dp(48), dp(8), dp(48), dp(12))
        controls.visibility = View.GONE
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        videoHost.isClickable = false
        videoHost.isFocusable = false
        currentFocus?.clearFocus()
        updateSubtitle()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        // Video ở khung nhỏ thì tạm dừng; bấm play sẽ tiếp tục ở toàn màn hình
        player.pause()
        updatePlayIcon()
        hideOsd()
        fullscreen = false
        updateSubtitle()
        root.removeView(controls)
        controls.setBackgroundColor(Color.TRANSPARENT)
        controls.setPadding(0, 0, 0, 0)
        controls.visibility = View.VISIBLE
        leftPanel.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftPanel.setPadding(dp(24), dp(24), dp(24), dp(24))
        rightPanel.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        videoHost.isClickable = true
        videoHost.isFocusable = true
        var idx = adapter.items.indexOfFirst { it.file != null && it.file == current }
        if (idx < 0) idx = if (adapter.items.size > 1 && currentDir != null) 1 else 0
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
        rv.postDelayed({ rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }, 150)
    }

    private fun showOsd() {
        if (!fullscreen) return
        osdVisible = true
        controls.visibility = View.VISIBLE
        btnPlay.requestFocus()
        scheduleOsdHide()
    }

    private fun hideOsd() {
        osdVisible = false
        ui.removeCallbacks(hideOsdR)
        controls.visibility = View.GONE
    }

    private fun scheduleOsdHide() {
        ui.removeCallbacks(hideOsdR)
        ui.postDelayed(hideOsdR, OSD_MS)
    }

    private fun showHud(text: String, ms: Long = 1500L) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHudR)
        ui.postDelayed(hideHudR, ms)
    }

    // ---------- Tỉ lệ hiển thị ----------

    private fun applyAspect() {
        val w = videoHost.width
        val h = videoHost.height
        if (w <= 0 || h <= 0) return
        var tw = w
        var th = h
        if (vw > 0f && vh > 0f) {
            val s = min(w / vw, h / vh)
            val w0 = (vw * s).toInt()
            val h0 = (vh * s).toInt()
            when (aspectMode) {
                0 -> { tw = w0; th = h0 }        // giữ tỉ lệ gốc
                1 -> { tw = w; th = h }          // full cả 2 lề
                2 -> { tw = w; th = h0 }         // kéo giãn lề ngang
                else -> { tw = w0; th = h }      // kéo giãn lề dọc
            }
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    private fun updateAspectUi() {
        aspectLabel.text = "Tỉ lệ hiển thị: " + aspectNames[aspectMode]
    }

    // ---------- Duyệt thư mục (chỉ đọc thư mục đang mở, chạy nền) ----------

    private fun internalRoot(): File = Environment.getExternalStorageDirectory()

    private fun roots(): List<Pair<File, String>> {
        val res = ArrayList<Pair<File, String>>()
        val internal = internalRoot()
        res.add(internal to "Bộ nhớ trong")
        val internalCanon = try { internal.canonicalPath } catch (e: Exception) { internal.path }
        File("/storage").listFiles()?.forEach {
            val canon = try { it.canonicalPath } catch (e: Exception) { it.path }
            if (it.isDirectory && it.name != "emulated" && it.name != "self" &&
                canon != internalCanon && it.listFiles() != null
            ) res.add(it to "USB / thẻ nhớ (${it.name})")
        }
        return res
    }

    private fun startBrowsing() {
        val last = prefs.getString("last_dir", null)?.let { File(it) }
        val movies = File(internalRoot(), "Movies")
        val start = when {
            last != null && last.isDirectory && last.canRead() -> last
            movies.isDirectory -> movies
            else -> internalRoot()
        }
        loadDir(start)
    }

    private fun tracksIn(dir: File?): List<File> {
        val fs = dir?.listFiles() ?: return emptyList()
        return fs.filter { it.isFile && it.extension.lowercase() in EXT }.sortedBy { it.name.lowercase() }
    }

    private fun loadDir(dir: File?, focusFile: File? = null) {
        currentDir = dir
        val token = ++loadToken
        pathV.text = if (dir == null) "Chọn ổ lưu trữ" else dir.absolutePath
        if (dir != null) prefs.edit().putString("last_dir", dir.absolutePath).apply()
        Thread {
            val items = ArrayList<Entry>()
            var tr: List<File> = emptyList()
            if (dir == null) {
                roots().forEach { items.add(Entry(it.first, it.second, KIND_DIR)) }
            } else {
                items.add(Entry(null, ".. (lên thư mục cha)", KIND_UP))
                val all = dir.listFiles()?.filter { !it.name.startsWith(".") && it.canRead() } ?: emptyList()
                all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                    .forEach { items.add(Entry(it, it.name, KIND_DIR)) }
                tr = tracksIn(dir)
                tr.forEach { items.add(Entry(it, it.name, KIND_FILE)) }
            }
            runOnUiThread {
                if (token == loadToken) {
                    browseDir = dir
                    browseTracks = tr
                    adapter.items = items
                    adapter.notifyDataSetChanged()
                    var idx = if (focusFile != null) items.indexOfFirst { it.file == focusFile } else -1
                    if (idx < 0) idx = if (items.size > 1 && dir != null) 1 else 0
                    (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
                    if (!fullscreen) {
                        rv.postDelayed({ rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }, 80)
                    }
                }
            }
        }.start()
    }

    private fun goUp() {
        val d = currentDir ?: return
        val p = d.parentFile
        if (roots().any { it.first == d } || p == null || !p.canRead()) loadDir(null, d) else loadDir(p, d)
    }

    private fun onEntry(e: Entry) {
        when (e.kind) {
            KIND_UP -> goUp()
            KIND_DIR -> loadDir(e.file)
            else -> e.file?.let { f ->
                if (f == current) playFullscreen() else {
                    queueDir = null
                    play(f)
                }
            }
        }
    }

    // ---------- Phát video ----------

    private fun playFullscreen() {
        if (current == null) return
        enterFullscreen()
        when (player.playbackState) {
            Player.STATE_ENDED -> player.seekTo(0)
            Player.STATE_IDLE -> player.prepare()
        }
        player.play()
        updatePlayIcon()
    }

    private fun updateSubtitle() {
        if (fullscreen && subText.isNotBlank()) {
            subV.text = subText
            subV.visibility = View.VISIBLE
        } else subV.visibility = View.GONE
    }

    // Phụ đề cùng thư mục, trùng tên video (hoặc dạng "ten.vi.srt")
    private fun findSubtitle(v: File): File? {
        val base = v.nameWithoutExtension.lowercase()
        val cands = v.parentFile?.listFiles()?.filter { it.isFile && it.extension.lowercase() in SUB_EXT } ?: return null
        val exact = cands.filter { it.nameWithoutExtension.lowercase() == base }
        val pool = if (exact.isNotEmpty()) exact else cands.filter { it.nameWithoutExtension.lowercase().startsWith("$base.") }
        return pool.sortedWith(compareBy<File>({ !it.name.lowercase().contains(".vi") }, { it.name.lowercase() })).firstOrNull()
    }

    // Đổi phụ đề sang UTF-8 nếu cần (file cũ hay dùng bảng mã Windows-1258)
    private fun utf8Copy(f: File): File {
        try {
            val b = f.readBytes()
            val text: String
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
                text = String(b, 3, b.size - 3, Charsets.UTF_8)
            } else if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
                text = String(b, 2, b.size - 2, Charsets.UTF_16LE)
            } else if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
                text = String(b, 2, b.size - 2, Charsets.UTF_16BE)
            } else {
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    dec.decode(ByteBuffer.wrap(b))
                    return f
                } catch (e: Exception) {
                }
                text = String(b, charset("windows-1258"))
            }
            val out = File(cacheDir, "sub_" + Math.abs(f.absolutePath.hashCode()) + "." + f.extension)
            out.writeText(text, Charsets.UTF_8)
            return out
        } catch (e: Exception) {
            return f
        }
    }

    private fun loadMedia(f: File) {
        subText = ""
        updateSubtitle()
        val item = MediaItem.Builder().setUri(Uri.fromFile(f))
        val sub = findSubtitle(f)
        if (sub != null) {
            val mime = when (sub.extension.lowercase()) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_TTML
            }
            item.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(utf8Copy(sub)))
                    .setMimeType(mime)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()))
        }
        player.setMediaItem(item.build())
        player.prepare()
    }

    private fun play(f: File) {
        getSharedPreferences("crash", MODE_PRIVATE).edit().putString("playing", f.name).commit()
        if (!fullscreen) enterFullscreen()
        val old = current
        current = f
        if (repeatMode == ALL) ensureScan(f)
        ready = false
        seek.progress = 0
        vw = 0f
        vh = 0f
        placeholder.visibility = View.GONE
        titleV.text = f.nameWithoutExtension
        artistV.text = f.parentFile?.name ?: ""
        if (ffmpegOn) {
            // Quay về bộ phát thường; chỉ bật lại bộ giải mã bổ sung nếu video này thật sự cần
            player.release()
            buildPlayer(false)
            getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").commit()
        }
        loadMedia(f)
        player.play()
        updatePlayIcon()
        adapter.refresh(old, f)
        if (fullscreen) {
            if (osdVisible) scheduleOsdHide() else showHud(f.nameWithoutExtension, 2500L)
        }
    }

    private fun onComplete() {
        when (repeatMode) {
            ONE -> { player.seekTo(0); player.play() }
            FOLDER, ALL -> step(1)
            else -> {
                updatePlayIcon()
                if (fullscreen) showOsd()
            }
        }
    }

    private fun folderQueue(dir: File?): List<File> {
        if (dir != null && dir == queueDir) return queue
        queue = if (dir != null && dir == browseDir) browseTracks else tracksIn(dir)
        queueDir = dir
        return queue
    }

    private fun trackList(cur: File): List<File> =
        if (repeatMode == ALL) (rootOf(cur)?.let { allCache[it.path] } ?: folderQueue(cur.parentFile))
        else folderQueue(cur.parentFile)

    private fun step(dir: Int) {
        val cur = current ?: return
        val list = trackList(cur)
        if (list.isEmpty()) return
        val i = list.indexOf(cur).let { if (it < 0) 0 else it }
        play(list[(i + dir + list.size) % list.size])
    }

    private fun prev() {
        if (current != null && ready && player.currentPosition > 3000) player.seekTo(0) else step(-1)
    }

    private fun seekBy(delta: Long) {
        if (current == null || !ready) return
        val dur = player.duration
        var t = player.currentPosition + delta
        t = if (dur > 0) t.coerceIn(0L, dur) else maxOf(0L, t)
        player.seekTo(t)
        seek.progress = t.toInt()
        showHud(fmt(t.toInt()) + (if (dur > 0) " / " + fmt(dur.toInt()) else ""))
    }

    private fun togglePlay() {
        if (current == null) {
            toast("Chọn một video ở danh sách bên phải")
            return
        }
        if (!fullscreen) {
            playFullscreen()
            return
        }
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { player.prepare(); player.play() }
            player.playWhenReady -> {
                player.pause()
                if (fullscreen) showHud("Tạm dừng")
            }
            else -> {
                player.play()
                if (fullscreen) showHud("Tiếp tục phát")
            }
        }
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val st = player.playbackState
        val playing = player.playWhenReady && st != Player.STATE_ENDED && st != Player.STATE_IDLE
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateTime() {
        if (!ready) return
        val d = player.duration
        timeV.text = fmt(player.currentPosition.toInt()) + (if (d > 0) " / " + fmt(d.toInt()) else "")
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600
        val m = s / 60 % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
    }

    private fun updateRepeatUi() {
        val (txt, color) = when (repeatMode) {
            ONE -> "Lặp lại 1 video" to 0xFF82B1FF.toInt()
            FOLDER -> "Lặp lại cả thư mục" to 0xFFFFD54F.toInt()
            ALL -> "Lặp lại tất cả các thư mục" to 0xFF69F0AE.toInt()
            else -> "Không lặp (hết video thì dừng)" to 0xFFBDBDBD.toInt()
        }
        btnRepeat.setImageResource(if (repeatMode == ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.setColorFilter(color)
        repeatLabel.text = txt
        repeatLabel.setTextColor(color)
    }

    // ---------- Quét toàn bộ ổ lưu trữ (chỉ khi chọn "Lặp lại tất cả các thư mục") ----------

    private fun rootOf(f: File): File? =
        roots().map { it.first }.firstOrNull { f.path == it.path || f.path.startsWith(it.path + "/") }

    private fun ensureScan(f: File) {
        val root = rootOf(f) ?: return
        if (allCache.containsKey(root.path)) return
        if (scanning.putIfAbsent(root.path, true) != null) return
        Thread {
            val out = ArrayList<File>()
            scan(root, 0, out)
            allCache[root.path] = out.sortedBy { it.path.lowercase() }
            scanning.remove(root.path)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun scan(d: File, depth: Int, out: MutableList<File>) {
        if (depth > 12) return
        val fs = d.listFiles() ?: return
        for (x in fs) {
            if (x.name.startsWith(".")) continue
            if (x.isDirectory) {
                if (x.name != "Android") scan(x, depth + 1, out)
            } else if (x.extension.lowercase() in EXT) out.add(x)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Adapter ----------

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val text: TextView = v.findViewById(R.id.text)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<Entry> = emptyList()

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_entry, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.text.text = e.label
            h.icon.setImageResource(if (e.kind == KIND_FILE) R.drawable.ic_movie else R.drawable.ic_folder)
            val playing = e.kind == KIND_FILE && e.file == current
            val c = when {
                playing -> 0xFFFFFFFF.toInt()
                e.kind == KIND_FILE -> 0xFFB0BEC5.toInt()
                else -> 0xFFFFD54F.toInt()
            }
            h.text.setTextColor(c)
            h.text.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            h.icon.setColorFilter(c)
            h.itemView.setOnClickListener { onEntry(e) }
        }

        fun refresh(a: File?, b: File?) {
            items.forEachIndexed { i, e ->
                if (e.file != null && (e.file == a || e.file == b)) notifyItemChanged(i)
            }
        }
    }
}
