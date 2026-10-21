package com.example.filtercam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var filterRow: LinearLayout
    private lateinit var thumb: ImageView
    private var lastUri: Uri? = null
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentFilter = CamFilter.NORMAL

    private val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.CAMERA] == true) startCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        filterRow = findViewById(R.id.filterRow)
        thumb = findViewById(R.id.thumb)
        thumb.setOnClickListener { sharePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()

        // COMPATIBLE = TextureView, which lets us tint the live preview with a color filter.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        buildFilterChips()
        applyPreviewFilter()

        findViewById<View>(R.id.captureButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.flipButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera()
        else permissionLauncher.launch(permissions)
    }

    private fun buildFilterChips() {
        val inflater = LayoutInflater.from(this)
        CamFilter.values().forEach { f ->
            val chip = inflater.inflate(R.layout.item_filter, filterRow, false) as TextView
            chip.text = f.label
            chip.isSelected = f == currentFilter
            chip.setOnClickListener {
                currentFilter = f
                for (i in 0 until filterRow.childCount) filterRow.getChildAt(i).isSelected = false
                chip.isSelected = true
                applyPreviewFilter()
            }
            filterRow.addView(chip)
        }
    }

    private fun applyPreviewFilter() {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(currentFilter.matrix) }
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val filter = currentFilter
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotated = rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                    val filtered = applyFilter(rotated, filter)
                    if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE) {
                        returnToCaller(filtered)
                    } else {
                        val uri = save(filtered)
                        val small = Bitmap.createScaledBitmap(
                            filtered, 160, (160f * filtered.height / filtered.width).toInt().coerceAtLeast(1), true
                        )
                        runOnUiThread {
                            lastUri = uri
                            thumb.setImageBitmap(small)
                            Toast.makeText(this@MainActivity, "Saved. Tap the thumbnail to share", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    // Used when another app launched us to take a picture (e.g. "Choose an action" sheet).
    @Suppress("DEPRECATION")
    private fun returnToCaller(bitmap: Bitmap) {
        val outUri: Uri? = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        if (outUri != null) {
            contentResolver.openOutputStream(outUri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            runOnUiThread { setResult(RESULT_OK); finish() }
        } else {
            val thumb = Bitmap.createScaledBitmap(bitmap, bitmap.width / 4, bitmap.height / 4, true)
            runOnUiThread { setResult(RESULT_OK, Intent().putExtra("data", thumb)); finish() }
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun applyFilter(src: Bitmap, filter: CamFilter): Bitmap {
        if (filter == CamFilter.NORMAL) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(filter.matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun save(bitmap: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FilterCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilterCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create MediaStore entry")
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }

    private fun sharePhoto() {
        val uri = lastUri ?: run {
            Toast.makeText(this, "Take a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
