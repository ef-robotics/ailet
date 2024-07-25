package com.example.ailet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ailet.ui.theme.AiletTheme
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import java.util.concurrent.Executors
private const val CAMERA_PERMISSION_REQUEST_CODE = 1001

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private var recordingJob: Job? = null
    private val client = OkHttpClient()
    private val TAG = "MainActivity"
    private val handler = Handler(Looper.getMainLooper())
    private val logMessages = mutableStateListOf<String>()

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage()
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        val file = saveImage(bytes)
        if (file != null) {
            uploadImage(file)
        }
    }

    private fun saveImage(bytes: ByteArray): File? {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return try {
            val imageFile = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
            FileOutputStream(imageFile).use { output ->
                output.write(bytes)
            }
            
            imageFile
        } catch (e: IOException) {
            Log.e(TAG, "Error saving image", e)
            null
        }
    }

    private fun uploadImage(imageFile: File) {
        val url = "https://dairy.intrtl.com/api/v2/photos/"
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("photo_id", "4343")
            .addFormDataPart("visit_id", "434")
            .addFormDataPart("task_id", "")
            .addFormDataPart("photo_data", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("accept", "application/json")
            .addHeader("Content-Type", "multipart/form-data")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Image upload failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload successful!")
                } else {
                    Log.d(TAG, "Upload failed: ${response.code}")
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AiletTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        CameraPreview()
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreview() {
        val context = LocalContext.current
        var startRecording by remember { mutableStateOf(false) }

        Column {
            SurfaceViewCamera()

            Button(
                onClick = {
                    if (startRecording) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                    startRecording = !startRecording
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text(if (startRecording) "Stop Recording" else "Start Recording")
            }
        }
    }

    @Composable
    fun SurfaceViewCamera() {
        val context = LocalContext.current

        AndroidView(factory = {
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            setupCamera(holder)
                        } else {
                            ActivityCompat.requestPermissions(
                                context as ComponentActivity,
                                arrayOf(Manifest.permission.CAMERA),
                                CAMERA_PERMISSION_REQUEST_CODE
                            )
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        })
    }

    private fun setupCamera(surfaceHolder: SurfaceHolder) {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
                return
            }

            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = streamConfigurationMap!!.getOutputSizes(SurfaceHolder::class.java)[0]
            surfaceHolder.setFixedSize(previewSize.width, previewSize.height)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(surfaceHolder)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error accessing camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when accessing camera", e)
        }
    }

    private fun startPreview(surfaceHolder: SurfaceHolder) {
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surfaceHolder.surface)

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener(imageAvailableListener, handler)

            val surfaces = listOf(surfaceHolder.surface, imageReader.surface)
            val outputConfigs = surfaces.map { OutputConfiguration(it) }

            val executor = Executors.newSingleThreadExecutor()

            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera")
                    }
                }
            )

            cameraDevice.createCaptureSession(sessionConfiguration)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error starting camera preview", e)
        }
    }

    private fun startRecording() {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    captureStillPicture()
                    delay(5000) // Capture an image every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error capturing image", e)
                }
            }
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
    }

    private fun captureStillPicture() {
        try {
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureSession.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error capturing still picture", e)
        }
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API\n      which brings increased type safety via an {@link ActivityResultContract} and the prebuilt\n      contracts for common intents available in\n      {@link androidx.activity.result.contract.ActivityResultContracts}, provides hooks for\n      testing, and allow receiving results in separate, testable classes independent from your\n      activity. Use\n      {@link #registerForActivityResult(ActivityResultContract, ActivityResultCallback)} passing\n      in a {@link RequestMultiplePermissions} object for the {@link ActivityResultContract} and\n      handling the result in the {@link ActivityResultCallback#onActivityResult(Object) callback}.")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                // Permission granted. Setup camera.
                setContent {
                    SurfaceViewCamera() // Ensure the camera setup happens within Compose
                }
            } else {
                Log.e(TAG, "Camera permission denied")
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun DefaultPreview() {
        AiletTheme {
            CameraPreview()
        }
    }
}
