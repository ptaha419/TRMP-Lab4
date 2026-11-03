package com.example.lab4_var6

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.lab4_var6.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Класс для сохранения координат для геотега
data class PhotoGeoTag(val latitude: Double, val longitude: Double)

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    // переменная для захвата изображения через CameraX
    private var imageCapture: ImageCapture? = null
    // поток для операций с камерой
    private lateinit var cameraExecutor: ExecutorService

    private var curLat: Double? = null // широта
    private var curLong: Double? = null // долгота

    // клиент для получения местоположения устройства
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var photoGeoTag: PhotoGeoTag? = null // геотег

    // лаунчер для запроса разрешений
    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            var anyDenied = false

            for ((perm, isGranted) in permissions) {
                if (!isGranted) {
                    anyDenied = true
                } else {
                    // все разрешения даны
                }
            }

            if (anyDenied) {
                // проверяем, можем ли мы ещё раз запросить
                val shouldShowRationale = REQUIRED_PERMISSIONS.any { perm ->
                    shouldShowRequestPermissionRationale(perm)
                }

                if (shouldShowRationale) {
                    Toast.makeText(
                        baseContext,
                        "Пожалуйста, предоставьте разрешения на использование камеры и геолокации.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        baseContext,
                        "Разрешения отклонены. Включите их в настройках приложения.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                startCamera()
            }
        }

    // игнорирование предупреждения для fusedLocationClient.lastLocation
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (allPermissionsGranted()) {
            startCamera() // запуск камеры
        } else {
            requestPermissions() // запрос разрешений
        }

        // обработчик клика мыши
        viewBinding.imageCaptureButton.setOnClickListener {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    photoGeoTag = PhotoGeoTag(loc.latitude, loc.longitude)
                }
                takePhoto()
            }.addOnFailureListener {
                takePhoto() // без координат
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // Функция для создания фото
    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(java.util.Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,
                SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        showLoading(true)
        viewBinding.imageCaptureButton.isEnabled = false

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this), // onCaptureSuccess в главном потоке
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = image.toBitmap()
                    image.close() // закрываем ImageProxy

                    if (bitmap == null) {
                        showLoading(false)
                        Toast.makeText(baseContext, "Ошибка конвертации в Bitmap", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // фоновая обработка через корутину
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val rotated = rotateBitmap(bitmap, rotation)
                            val watermark = applyWatermark(rotated, timestamp, photoGeoTag)
                            saveBitmap(watermark, contentValues)

                            rotated.recycle()
                            if (watermark !== rotated) watermark.recycle()
                        }

                        // UI-обновление после фоновой работы
                        showLoading(false)
                        viewBinding.imageCaptureButton.isEnabled = true
                        Toast.makeText(baseContext, "Фото сохранено в CameraX-Image", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    showLoading(false)
                    viewBinding.imageCaptureButton.isEnabled = true
                    Toast.makeText(baseContext, "Не удалось сделать фото", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Функция для поворота битмапа на указанное количество градусов
    // Если поворот не требуется, то возвращает исходный битмап без изменений
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Функция для сохранения битмапа в галерею
    private fun saveBitmap(bitmap: Bitmap, contentValues: ContentValues) {
        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                Log.e(TAG, "Не удалось сохранить в MediaStore")
                runOnUiThread { Toast.makeText(baseContext, "Не удалось сохранить фото", Toast.LENGTH_SHORT).show() }
                return
            }

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            Log.d(TAG, "Фото успешно сохранено: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения в MediaStore", e)
            runOnUiThread { Toast.makeText(baseContext, "Ошибка при сохранении фото", Toast.LENGTH_SHORT).show() }
        }
    }

    // Функция для инициализации камеры
    // Привязывает Preview и ImageCapture к ЖЦ Activity
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // настройка превью (живого изображения с камеры)
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // выбираем заднюю камеру
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // отвязка use cases перед rebinding
                cameraProvider.unbindAll()

                // привязка use cases к камере
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Не удалось привязать use cases к камере", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // Функция для наложения вотермарки
    private fun applyWatermark(source: Bitmap, timestamp: String, geoTag: PhotoGeoTag?): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val geoLoc = if (geoTag != null) {
            String.format(Locale.US, "Lat: %.4f, Lon: %.4f", geoTag.latitude, geoTag.longitude)
        } else {
            "Lat: -, Lon: -"
        }

        // отступ от края
        val margin = 20f

        // измеряем ширину текстов
        val tsWidth = paint.measureText(timestamp)
        val locWidth = paint.measureText(geoLoc)

        // координаты для правого нижнего угла
        val x = result.width - margin - maxOf(tsWidth, locWidth)  // выравниваем по правому краю самого длинного текста
        val yBase = result.height - margin                        // базовая линия по нижнему краю

        // рисуем дату и время чуть выше координат
        canvas.drawText(timestamp, x, yBase - paint.textSize, paint)
        canvas.drawText(geoLoc, x, yBase, paint)

        return result
    }

    // Отладочный метод, нужен был для проверки получения координат
    @SuppressLint("MissingPermission")
    private fun getPhotoGeoLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { loc ->
                curLat = loc.latitude
                curLong = loc.longitude

                Log.d("Geo", "Lat: ${loc.latitude}, Lon: ${loc.longitude}")
                photoGeoTag = PhotoGeoTag(loc.latitude, loc.longitude)
                if (photoGeoTag != null) {
                    Log.d("Geo", "Геолокация устройства получена")
                }
            } ?: run {
                Log.w("Geo", "Данные о геолокации отсутствуют")
            }
        }.addOnFailureListener { e ->
            Log.e("Geo", "Не удалось получить данные о геолокации", e)
        }
    }

    // Функция отображения загрузки
    private fun showLoading(show: Boolean) {
        viewBinding.progressBar.visibility =
            if (show) {
                View.VISIBLE
            } else {
                View.GONE
            }
        viewBinding.imageCaptureButton.isEnabled = !show // защита от повторного нажатия
    }

    // Запрос необходимых разрешений
    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // Проверка наличия разрешений
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // Объект для настройки: тэг, имя файла, необходимые разрешения, проверка версии SDK
    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}