package com.freedomfighter.readersscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.freedomfighter.readersscanner.data.CaptureEngine
import com.freedomfighter.readersscanner.data.Ocr
import com.freedomfighter.readersscanner.engine.MlKit
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.scan.Detector
import com.freedomfighter.readersscanner.scan.Session
import com.freedomfighter.readersscanner.ui.ReaderTheme
import com.freedomfighter.readersscanner.ui.ScanScreen
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The capture: camera with the page outlined live, the language of the text at the top, as
 * many pages as wanted, then a review (crop, turn, look, order) and the folder to file it in.
 * Opened with [EXTRA_DOC] it adds pages to that document ([EXTRA_REVIEW]: or edits its pages).
 */
class ScanActivity : ComponentActivity() {
    /** EXTERNAL: Google's scanner screen is open (private build). */
    enum class Mode { CAMERA, REVIEW, EXTERNAL }

    private val app get() = application as App
    lateinit var session: Session
    var mode by mutableStateOf(Mode.CAMERA)
    var granted by mutableStateOf(false)
    private var asked = false
    /** The sheet in the live view (fractions of the upright frame), smoothed; null when none. */
    var live by mutableStateOf<FloatArray?>(null)
    var torch by mutableStateOf(false)
    var hasTorch by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** Bumped at each shot, for the flash on screen. */
    var shots by mutableIntStateOf(0)
    /** Between the touch and the shot: waiting for still hands and for the focus. */
    var steadying by mutableStateOf(false)
    private var shooting = false

    // --- still hands --------------------------------------------------------------------------
    // A blurred page is most often a moving phone: the touch on the screen itself shakes it.
    // The gyroscope says when the hands are still; the shot waits for that (at most 1.5 s).
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager }
    @Volatile private var lastMove = 0L
    private var gravity = FloatArray(3)
    private var hasMotionSensor = false
    private val motion = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val v = e.values
            val moving = if (e.sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE) {
                kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) > 0.10f   // rad/s
            } else {
                // no gyroscope: the accelerometer minus a slow estimate of gravity
                for (i in 0..2) gravity[i] = gravity[i] * 0.9f + v[i] * 0.1f
                val x = v[0] - gravity[0]; val y = v[1] - gravity[1]; val z = v[2] - gravity[2]
                kotlin.math.sqrt(x * x + y * y + z * z) > 0.35f   // m/s²
            }
            if (moving) lastMove = android.os.SystemClock.elapsedRealtime()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun listenForMotion(on: Boolean) {
        if (!on) { sensors.unregisterListener(motion); return }
        val sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) ?: sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        hasMotionSensor = sensor != null
        sensor?.let { sensors.registerListener(motion, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    }
    val folder: String? get() = intent.getStringExtra(EXTRA_FOLDER)
    val docId: String? get() = intent.getStringExtra(EXTRA_DOC)

    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private val analysisThread = Executors.newSingleThreadExecutor()
    private var missed = 0
    private var rotation = Surface.ROTATION_0
    var finder: PreviewView? = null
        private set

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && mode == Mode.CAMERA) bind()
    }

    /** Pages came from Google's scanner in this session: the review then shows the language row. */
    var external by mutableStateOf(false)
    private val googleScanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val uris = if (r.resultCode == RESULT_OK) MlKit.scannedPages(r.data) else emptyList()
        if (uris.isEmpty()) { if (session.pages.isEmpty()) discard() else toReview(); return@registerForActivityResult }
        external = true
        Thread { uris.forEach { session.addFromUri(it, scanned = true) }; runOnUiThread { toReview() } }.start()
    }

    private val useGoogleScanner get() = MlKit.available && app.prefs.settings.value.capture == CaptureEngine.MLKIT && MlKit.playServices(this)

    private fun openGoogleScanner() {
        mode = Mode.EXTERNAL
        unbind()
        MlKit.scanner(this, { sender -> googleScanner.launch(IntentSenderRequest.Builder(sender).build()) }, { err ->
            // Play services missing or refusing: our own camera instead, said once.
            failed = true
            android.widget.Toast.makeText(this, "ML Kit: $err", android.widget.Toast.LENGTH_LONG).show()
            mode = Mode.CAMERA
            if (granted) bind() else askAgain()
        })
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // The screen stays upright; the photo follows the hand, so a page held sideways comes out right.
                val r = when (deg) { in 45 until 135 -> Surface.ROTATION_270; in 135 until 225 -> Surface.ROTATION_180; in 225 until 315 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != rotation) { rotation = r; capture?.targetRotation = r }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = Session(applicationContext, docId, app.prefs.settings.value.filter) { app.prefs.settings.value.format.ratio }
        if (docId != null && intent.getBooleanExtra(EXTRA_REVIEW, false) && session.pages.isNotEmpty()) mode = Mode.REVIEW
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        finder = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        if (mode == Mode.CAMERA && useGoogleScanner) openGoogleScanner()
        else if (mode == Mode.CAMERA) { if (granted) bind() else { asked = true; permission.launch(Manifest.permission.CAMERA) } }
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) { ScanScreen(this, app) }
        }
    }

    override fun onResume() {
        super.onResume()
        orientation.enable()
        listenForMotion(true)
        if (!granted && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { granted = true; if (mode == Mode.CAMERA) bind() }
    }

    override fun onPause() { orientation.disable(); listenForMotion(false); super.onPause() }

    fun toCamera() {
        if (useGoogleScanner) { openGoogleScanner(); return }
        mode = Mode.CAMERA
        if (granted) bind() else askAgain()
    }

    fun toReview() {
        mode = Mode.REVIEW
        unbind()
    }

    private fun bind() {
        if (isFinishing) return
        val view = finder ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed || mode != Mode.CAMERA) return@addListener
            runCatching {
                val p = future.get(); provider = p
                // One shape for all three: the outline drawn on the view matches the photo taken.
                val four3 = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val preview = Preview.Builder().setResolutionSelector(four3).build().also { it.surfaceProvider = view.surfaceProvider }
                val shot = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(four3)
                    .setJpegQuality(95)
                    .setTargetRotation(rotation)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(640, 480), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisThread) { analyse(it) }
                p.unbindAll()
                val c = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, shot, analysis)
                camera = c; capture = shot
                hasTorch = c.cameraInfo.hasFlashUnit()
                if (torch) c.cameraControl.enableTorch(true)
                failed = false
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbind() {
        runCatching { provider?.unbindAll() }
        capture = null; camera = null; live = null
    }

    fun toggleTorch() {
        torch = !torch
        camera?.cameraControl?.enableTorch(torch)
    }

    /** One frame of the live view: its luminance, shrunk to ~320 px, turned upright, searched. */
    private fun analyse(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            val iw = image.width; val ih = image.height
            val step = max(1, max(iw, ih) / 320)
            val sw = iw / step; val sh = ih / step
            val g = IntArray(sw * sh)
            for (y in 0 until sh) { val row = y * step * rs; for (x in 0 until sw) g[y * sw + x] = buf.get(row + x * step * ps).toInt() and 255 }
            val rot = image.imageInfo.rotationDegrees
            val (u, uw, uh) = upright(g, sw, sh, rot)
            val found = Detector.detect(u, uw, uh)
            val q = found?.corners?.let { c -> FloatArray(8) { i -> c[i] / (if (i % 2 == 0) uw else uh) } }
            runOnUiThread { smooth(q) }
        } catch (_: Exception) {
        } finally { image.close() }
    }

    private fun upright(g: IntArray, w: Int, h: Int, rot: Int): Triple<IntArray, Int, Int> = when (rot) {
        90 -> Triple(IntArray(w * h) { i -> val x = i % h; val y = i / h; g[(h - 1 - x) * w + y] }, h, w)
        180 -> Triple(IntArray(w * h) { i -> g[w * h - 1 - i] }, w, h)
        270 -> Triple(IntArray(w * h) { i -> val x = i % h; val y = i / h; g[x * w + (w - 1 - y)] }, h, w)
        else -> Triple(g, w, h)
    }

    /** Follows the sheet without trembling; lets go after a few frames without it. */
    private fun smooth(q: FloatArray?) {
        if (mode != Mode.CAMERA) return
        val old = live
        if (q == null) { if (++missed >= 4) live = null; return }
        missed = 0
        live = if (old == null || (0 until 8).maxOf { abs(old[it] - q[it]) } > 0.08f) q else FloatArray(8) { old[it] * 0.55f + q[it] * 0.45f }
    }

    /**
     * The shutter: waits for still hands (at most 1.5 s), focuses on the page (at most 1.2 s),
     * then takes the photo. Either wait gives up quietly rather than miss the shot.
     */
    fun shoot() {
        if (capture == null || shooting) return
        shooting = true
        steadying = true
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        lifecycleScope.launch {
            val start = android.os.SystemClock.elapsedRealtime()
            lastMove = start   // the touch itself counts as a movement
            if (hasMotionSensor) {
                while (android.os.SystemClock.elapsedRealtime() - start < 1500 &&
                    android.os.SystemClock.elapsedRealtime() - lastMove < 300) kotlinx.coroutines.delay(25)
            } else kotlinx.coroutines.delay(300)
            focus()
            steadying = false
            take()
        }
    }

    /** Focus and exposure on the page (the middle of its outline, or of the view). */
    private suspend fun focus() {
        val cam = camera ?: return
        val view = finder ?: return
        val q = live
        val x = if (q != null) (q[0] + q[2] + q[4] + q[6]) / 4f else 0.5f
        val y = if (q != null) (q[1] + q[3] + q[5] + q[7]) / 4f else 0.5f
        runCatching {
            val point = view.meteringPointFactory.createPoint(x * view.width, y * view.height)
            val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()
            val future = cam.cameraControl.startFocusAndMetering(action)
            kotlinx.coroutines.withTimeoutOrNull(1200) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    future.addListener({ if (cont.isActive) cont.resumeWith(Result.success(Unit)) }, ContextCompat.getMainExecutor(this@ScanActivity))
                }
            }
        }
    }

    private fun take() {
        val shot = capture ?: run { shooting = false; return }
        val raw = session.rawFile()
        // The live outline is only a fallback, and only when the photo is taken upright like the view.
        val hint = if (rotation == Surface.ROTATION_0) live?.copyOf() else null
        shots++
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) { shooting = false; session.addPhoto(raw, hint) }
            override fun onError(e: ImageCaptureException) { shooting = false; raw.delete(); failed = true }
        })
    }

    /** Takes the page again: the next photo goes where this one was. */
    fun retake(d: com.freedomfighter.readersscanner.scan.Draft) {
        session.retakeAt = session.pages.indexOf(d).takeIf { it >= 0 }
        session.remove(d)
        toCamera()
    }

    fun importPhotos() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun importUris(uris: List<Uri>) {
        Thread { uris.forEach { session.addFromUri(it) }; runOnUiThread { toReview() } }.start()
    }

    /** Refused once already: Android no longer asks, so the app's page in the settings is the way. */
    fun askAgain() {
        if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) { asked = true; permission.launch(Manifest.permission.CAMERA) }
        else runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    /** Files the pages, starts reading their text, and shows where they went. */
    fun file(folder: String?, name: String?) {
        val doc = session.commit(folder, name, app.prefs.settings.value.ocrLanguage)
        Ocr.enqueue(doc.id)
        app.sync(1000)
        app.reveal.value = (doc.folder ?: "") to doc.id
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    fun discard() {
        session.discard()
        if (isTaskRoot && docId == null) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mode == Mode.CAMERA && granted && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA)) {
            if (event?.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        analysisThread.shutdown()
        if (isFinishing) session.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_DOC = "doc"
        const val EXTRA_REVIEW = "review"

        fun start(context: Context, folder: String? = null, doc: String? = null, review: Boolean = false) {
            context.startActivity(Intent(context, ScanActivity::class.java).apply {
                folder?.let { putExtra(EXTRA_FOLDER, it) }
                doc?.let { putExtra(EXTRA_DOC, it) }
                putExtra(EXTRA_REVIEW, review)
            })
        }
    }
}

