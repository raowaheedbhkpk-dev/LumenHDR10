package com.lumen.hdr

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F1218")
    private val panel = Color.parseColor("#171B23")
    private val fg = Color.parseColor("#E8EBF1")
    private val muted = Color.parseColor("#9099A9")
    private val accent = Color.parseColor("#F2A531")
    private val good = Color.parseColor("#5CC48A")
    private val bad = Color.parseColor("#FF7A5C")

    private lateinit var status: TextView
    private lateinit var support: TextView
    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var convertBtn: Button
    private lateinit var openBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var sizeGroup: RadioGroup

    private val converter by lazy { HdrConverter(this) }
    private var source: Uri? = null
    private var videoInfo: VideoInfo? = null
    private var result: Uri? = null
    private var running = false
    private var encoderOk = false

    // slider-backed settings
    private var peak = 1000f
    private var strength = 0.8f
    private var shadows = 0.3f
    private var saturation = 1.15f
    private var sharpness = 0.25f
    private var exposure = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        checkEncoder()
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun label(s: String) = text(s.uppercase(Locale.ROOT), 11f, muted).apply {
        letterSpacing = 0.12f; typeface = Typeface.MONOSPACE
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(14).toFloat() }
        setPadding(dp(16), dp(8), dp(16), dp(16))
        children.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun button(s: String, primary: Boolean) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) Color.parseColor("#1B1204") else fg)
        background = GradientDrawable().apply {
            setColor(if (primary) accent else panel); cornerRadius = dp(12).toFloat()
            if (!primary) setStroke(dp(1), Color.parseColor("#2A303C"))
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) }
    }

    private fun slider(name: String, lo: Float, hi: Float, value: Float, fmt: (Float) -> String, set: (Float) -> Unit): View {
        val steps = 100
        val title = text(name, 15f)
        val valueText = text(fmt(value), 13f, muted).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
        val row = LinearLayout(this).apply {
            addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(valueText)
            setPadding(0, dp(10), 0, 0)
        }
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((value - lo) / (hi - lo) * steps).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / steps
                    set(v); valueText.text = fmt(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(row); addView(bar) }
    }

    private fun pct(v: Float) = "${(v * 100).toInt()}%"

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(32))
        }
        root.addView(text("Lumen HDR10", 30f, fg, true))
        root.addView(text("Turns normal video into real HDR10: 10-bit HEVC, BT.2020 color, PQ brightness up to your chosen peak.", 14f, muted).apply { setPadding(0, dp(4), 0, 0) })
        support = text("Checking HDR10 encoder…", 13f, muted).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(support)

        preview = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(16) }
        }
        root.addView(preview)
        info = text("", 13f, muted).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        root.addView(info)

        val pick = button("Choose video", false).apply { setOnClickListener { pickVideo() } }
        root.addView(pick)

        root.addView(card(
            label("Brightness"),
            slider("Peak brightness", 400f, 1600f, peak, { "${(it / 50).toInt() * 50} nits" }) { peak = (it / 50).toInt() * 50f },
            slider("HDR strength", 0f, 1f, strength, ::pct) { strength = it },
            slider("Exposure", -1f, 1f, exposure, { String.format(Locale.US, "%+.1f EV", it) }) { exposure = it },
            slider("Shadows", 0f, 1f, shadows, ::pct) { shadows = it },
            label("Color & detail"),
            slider("Saturation", 0.8f, 1.5f, saturation, ::pct) { saturation = it },
            slider("Sharpness", 0f, 1f, sharpness, ::pct) { sharpness = it },
        ))

        sizeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf("Original size" to 1, "Upscale to 1440p" to 2, "Upscale to 4K" to 3).forEach { (t, id) ->
                addView(RadioButton(this@MainActivity).apply {
                    this.id = id; text = t; setTextColor(fg); textSize = 15f
                    buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                })
            }
            check(1)
        }
        root.addView(card(label("Output size"), sizeGroup))

        convertBtn = button("Convert to HDR10", true).apply { isEnabled = false; alpha = 0.5f; setOnClickListener { onConvert() } }
        root.addView(convertBtn)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(14) }
        }
        root.addView(progress)
        status = text("Choose a video to start.", 14f, muted).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(status)
        openBtn = button("Open in Photos", false).apply { visibility = View.GONE; setOnClickListener { openResult() } }
        root.addView(openBtn)

        root.addView(text("Tip: watch the result in Google Photos with brightness up. HDR10 shows its full range on HDR screens like your Pixel's; on other screens it is shown toned down.", 12f, muted).apply { setPadding(0, dp(20), 0, 0) })

        return ScrollView(this).apply {
            setBackgroundColor(bg); isFillViewport = true
            addView(root, ViewGroup.LayoutParams(-1, -2))
        }
    }

    // ---------- logic ----------
    private fun checkEncoder() {
        thread {
            val enc = HdrConverter.findHdr10Encoder()
            runOnUiThread {
                if (enc == null) {
                    encoderOk = false
                    support.text = "✕ No HDR10 encoder on this phone. Conversion won't work."
                    support.setTextColor(bad)
                } else {
                    encoderOk = true
                    val editing = HdrConverter.supportsHdrEditing(enc)
                    support.text = "✓ HDR10 encoder ready (${enc.name})" + if (editing) "" else " · HDR editing not reported"
                    support.setTextColor(good)
                }
                refreshButton()
            }
        }
    }

    private fun refreshButton() {
        val ok = encoderOk && videoInfo != null && videoInfo?.isHdr == false && !running
        convertBtn.isEnabled = ok || running
        convertBtn.alpha = if (ok || running) 1f else 0.5f
        convertBtn.text = if (running) "Cancel" else "Convert to HDR10"
    }

    @Suppress("DEPRECATION")
    private fun pickVideo() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "video/*" }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            }, REQ_PICK)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadVideo(uri)
    }

    private fun loadVideo(uri: Uri) {
        source = uri; result = null; openBtn.visibility = View.GONE; progress.progress = 0
        status.text = "Reading video…"; status.setTextColor(muted)
        thread {
            try {
                val vi = converter.probe(uri)
                val thumb = MediaMetadataRetriever().run {
                    try { setDataSource(this@MainActivity, uri); getFrameAtTime(0) } finally { release() }
                }
                runOnUiThread {
                    videoInfo = vi
                    preview.setImageBitmap(thumb); preview.visibility = View.VISIBLE
                    val secs = vi.durationUs / 1_000_000
                    info.text = "${vi.width}×${vi.height} · ${"%.0f".format(vi.fps)} fps · ${secs / 60}:${"%02d".format(secs % 60)}" +
                        (if (!vi.hasAudio) " · no audio" else if (!vi.audioCopyable) " · audio not AAC, will be dropped" else "")
                    info.visibility = View.VISIBLE
                    if (vi.isHdr) {
                        status.text = "This video is already HDR. Choose a normal (SDR) video."
                        status.setTextColor(bad)
                    } else {
                        status.text = "Ready. Conversion takes about as long as the video, or longer for 4K."
                    }
                    refreshButton()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't read that video: ${e.message}"; status.setTextColor(bad); videoInfo = null; refreshButton() }
            }
        }
    }

    private fun onConvert() {
        if (running) { converter.cancelled = true; status.text = "Cancelling…"; return }
        val uri = source ?: return
        val vi = videoInfo ?: return
        val longSide = when (sizeGroup.checkedRadioButtonId) { 2 -> 2560; 3 -> 3840; else -> null }
        val (w, h) = converter.outputSize(vi, longSide)
        val settings = HdrSettings(peak, strength, shadows, saturation, sharpness, exposure)
        val out = File(cacheDir, "hdr10-out.mp4")

        running = true; converter.cancelled = false
        openBtn.visibility = View.GONE; progress.progress = 0
        status.text = "Converting to HDR10 at ${w}×$h…"; status.setTextColor(muted)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshButton()

        thread(name = "hdr10-convert") {
            var message: String
            var ok = false
            try {
                converter.convert(uri, vi, w, h, settings, out) { p ->
                    runOnUiThread { progress.progress = (p * 1000).toInt() }
                }
                result = saveToGallery(out)
                message = "Saved to Movies/Lumen HDR as HDR10 (${"%.1f".format(out.length() / 1048576f)} MB)."
                ok = true
            } catch (e: CancelledException) {
                message = "Conversion cancelled."
            } catch (e: Exception) {
                message = "Conversion failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                out.delete()
            }
            runOnUiThread {
                running = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = message
                status.setTextColor(if (ok) good else if (message.contains("cancel")) muted else bad)
                if (ok) { progress.progress = 1000; openBtn.visibility = View.VISIBLE } else progress.progress = 0
                refreshButton()
            }
        }
    }

    private fun saveToGallery(file: File): Uri {
        val name = "HDR10_" + System.currentTimeMillis() + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Lumen HDR")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file in your gallery")
        contentResolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun openResult() {
        val uri = result ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            status.text = "No app found to play the video. Open it from Google Photos."
        }
    }

    companion object { private const val REQ_PICK = 7 }
}
