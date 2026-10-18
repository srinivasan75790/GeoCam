package com.example.geocam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.geocam.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageCapture: ImageCapture
    private lateinit var ioExecutor: ExecutorService

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    @Volatile private var latestLocation: Location? = null
    private var locationUpdatesActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            latestLocation = loc
            binding.locationText.text = String.format(
                Locale.US, "Lat %.6f°  Long %.6f°  ±%.0fm", loc.latitude, loc.longitude, loc.accuracy
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true
        val locationOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraOk && locationOk) {
            startCamera()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ioExecutor = Executors.newSingleThreadExecutor()

        binding.captureButton.setOnClickListener { takePhoto() }

        if (hasAllPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasAllPermissions()) startLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        if (locationUpdatesActive) {
            fusedClient.removeLocationUpdates(locationCallback)
            locationUpdatesActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    // ---------------------------------------------------------------- permissions

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- GPS

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        locationUpdatesActive = true
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (!::imageCapture.isInitialized) return
        val location = latestLocation
        if (location == null) {
            Toast.makeText(this, getString(R.string.waiting_gps), Toast.LENGTH_SHORT).show()
            return
        }
        val takenAt = Date()
        val tempFile = File(cacheDir, "capture_${takenAt.time}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        binding.captureButton.isEnabled = false
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Heavy work (decode, stamp, EXIF, save) off the main thread
                    ioExecutor.execute { processAndSave(tempFile, location, takenAt) }
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ---------------------------------------------------------------- geotag + save

    private fun processAndSave(source: File, location: Location, takenAt: Date) {
        try {
            val address = reverseGeocode(location)
            val stamped = File(cacheDir, "stamped_${takenAt.time}.jpg")

            // 1. Decode with correct rotation and draw the on-image stamp
            val bitmap = decodeUpright(source)
            val withStamp = drawStamp(bitmap, location, address, takenAt)
            stamped.outputStream().use { withStamp.compress(Bitmap.CompressFormat.JPEG, 95, it) }

            // 2. Write GPS + timestamp into EXIF (the real "geotag")
            val exif = ExifInterface(stamped.absolutePath)
            exif.setGpsInfo(location)   // latitude, longitude, altitude, speed, time
            val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(takenAt)
            exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()

            // 3. Save to the gallery: Pictures/GeoCam
            val savedName = saveToGallery(stamped, takenAt)

            source.delete()
            stamped.delete()

            runOnUiThread {
                binding.captureButton.isEnabled = true
                Toast.makeText(this, "Saved $savedName with GPS tag", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.captureButton.isEnabled = true
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decodeUpright(file: File): Bitmap {
        val raw = BitmapFactory.decodeFile(file.absolutePath)
        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return raw.copy(Bitmap.Config.ARGB_8888, true)
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun drawStamp(bmp: Bitmap, loc: Location, address: String?, takenAt: Date): Bitmap {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val textSize = w / 28f
        val pad = textSize * 0.7f

        val dateText = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a 'GMT' XXX", Locale.getDefault()).format(takenAt)
        val lines = buildList {
            if (!address.isNullOrBlank()) addAll(wrap(address, 44))
            add(String.format(Locale.US, "Lat %.6f°  Long %.6f°  ±%.0fm", loc.latitude, loc.longitude, loc.accuracy))
            add(dateText)
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }

        val lineH = textSize * 1.3f
        val boxH = lineH * lines.size + pad * 2
        canvas.drawRect(0f, bmp.height - boxH, w, bmp.height.toFloat(), bg)
        var y = bmp.height - boxH + pad + textSize
        for (line in lines) {
            canvas.drawText(line, pad, y, text)
            y += lineH
        }
        return bmp
    }

    private fun wrap(s: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (word in s.split(" ")) {
            if (cur.length + word.length + 1 > max && cur.isNotEmpty()) {
                out += cur.toString(); cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(loc: Location): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(this, Locale.getDefault())
            .getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()?.getAddressLine(0)
    } catch (e: Exception) {
        null // offline or geocoder unavailable: photo is still tagged with coordinates
    }

    private fun saveToGallery(file: File, takenAt: Date): String {
        val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(takenAt) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt.time)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create gallery entry")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return name
    }
}
