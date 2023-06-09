package kr.ac.kumoh.s20190610.first

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.Image
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kr.ac.kumoh.s20190610.first.databinding.ActivityCameraBinding
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private val PICK_IMAGE_REQUEST = 1000

    private lateinit var binding: ActivityCameraBinding
    val apis = APIs.create()

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null

    private var savedUri: Uri? = null

    private lateinit var cameraAnimationListener: Animation.AnimationListener

    private val TAG = "Camera_Test"

    private lateinit var previewLayout: LinearLayout
    private lateinit var checkLayout: LinearLayout
    private lateinit var shutterButton: ImageButton
    private lateinit var previewView: PreviewView
    private lateinit var imageCheckView: ImageView
    private lateinit var cancelButton: Button
    private lateinit var confirmButton: Button
    private lateinit var shotCancelButton: AppCompatButton
    private lateinit var galleryButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        changeColor(R.color.black)

        findView()
        permissionCheck()
        setListener()
        setCameraAnimationListener()

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun permissionCheck() {

        var permissionList =
            listOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)

        if (!PermissionUtil.checkPermission(this, permissionList)) {
            PermissionUtil.requestPermission(this, permissionList)
        } else {
            openCamera()
        }
    }

    private fun findView() {
        previewView = binding.previewView
        previewLayout = binding.LayoutPreview
        checkLayout =  binding.LayoutCheckview
        shutterButton = binding.btnShutter
        imageCheckView = binding.imageView
        cancelButton = binding.btnCancel
        confirmButton = binding.btnConfirm
        shotCancelButton = binding.btnCancelShot
        galleryButton = binding.btnGallery
    }

    private fun setListener() {
        shutterButton.setOnClickListener {
            savePhoto()
        }

        cancelButton.setOnClickListener {
            hideCaptureImage()
        }

        shotCancelButton.setOnClickListener {
            onBackPressed()
        }

        galleryButton.setOnClickListener {
            openGallery()
        }

        confirmButton.setOnClickListener {
            if (savedUri != null) {
                val data = uriToBase64String(savedUri!!)
                postDataToServer(data)
            }
            else {
                hideCaptureImage()
                Toast.makeText(this, "오류가 발생하였습니다. 다시 촬영해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            savedUri = data.data

            previewLayout.visibility = View.GONE
            showCaptureImage()
        }
    }


    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "승인")
            openCamera()
        } else {
            Log.d(TAG, "승인 거부")
            onBackPressed()
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun openCamera() {
        Log.d(TAG, "openCamera")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                Log.d(TAG, "바인딩 성공")

            } catch (e: Exception) {
                Log.d(TAG, "바인딩 실패 $e")
            }
        }, ContextCompat.getMainExecutor(this))

    }

    private fun savePhoto() {
        imageCapture = imageCapture ?: return

        val animation =
            AnimationUtils.loadAnimation(this@CameraActivity, R.anim.camera_shutter)
        animation.setAnimationListener(cameraAnimationListener)
        shutterButton.animation = animation
        shutterButton.visibility = View.VISIBLE
        shutterButton.startAnimation(animation)

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yymmdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".png"
        )
        val outputOption = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOption,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    savedUri = Uri.fromFile(photoFile)
                    Log.d(TAG, "savedUri : $savedUri")

                    previewLayout.visibility = View.GONE
                    showCaptureImage()


                    Log.d(TAG, "imageCapture")
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    onBackPressed()
                }

            })

    }

    private fun setCameraAnimationListener() {
        cameraAnimationListener = object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {
            }

            override fun onAnimationEnd(animation: Animation?) {

            }

            override fun onAnimationRepeat(animation: Animation?) {
            }
        }
    }

    private fun showCaptureImage(): Boolean {
        if (previewLayout.visibility == View.GONE) {
            checkLayout.visibility = View.VISIBLE
            imageCheckView.setImageURI(savedUri)
            return false
        }
        return true
    }

    private fun hideCaptureImage() {
        imageCheckView.setImageURI(null)
        checkLayout.visibility = View.GONE
        previewLayout.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (showCaptureImage()) {
            finish()
        } else {
            hideCaptureImage()
        }
    }

    private fun uriToBase64String(imageUri: Uri): String {
        val contentResolver: ContentResolver = this.contentResolver
        val inputStream: InputStream = contentResolver.openInputStream(imageUri) ?: return ""

        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            bytes.write(buffer, 0, bytesRead)
        }
        val imageBytes = bytes.toByteArray()
        var base64Image = ""
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                base64Image = Base64.getEncoder().encodeToString(imageBytes)
            }
            else {
                base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        inputStream.close()
        bytes.close()



        return base64Image
    }

    private fun postDataToServer(postData: String) {
        val requestBody = RequestBody.create(MediaType.parse("text/plain"), postData)
        apis.uploadImage(requestBody).enqueue(object : retrofit2.Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: retrofit2.Response<ResponseBody>) {
                if (response.isSuccessful) {
                    val responseBody = response.body()
                    if (responseBody != null) {
                        val resultData = responseBody.string()
                        if (resultData == "[]") {
                            Toast.makeText(this@CameraActivity, "영수증이 잘 보이도록 다시 촬영해주세요.", Toast.LENGTH_SHORT).show()
                            hideCaptureImage()
                        }
                        else {
                            Log.d(TAG, resultData)
                            val intent = Intent()
                            intent.putExtra("RECEIPT_DATA", resultData)
                            setResult(Activity.RESULT_OK, intent)
                            finish()
                        }
                    }
                } else {
                    Toast.makeText(this@CameraActivity, "통신 오류가 발생하였습니다. 다시 촬영해주세요.", Toast.LENGTH_SHORT).show()
                    hideCaptureImage()
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Toast.makeText(this@CameraActivity, "통신 오류가 발생하였습니다. 다시 촬영해주세요.", Toast.LENGTH_SHORT).show()
                hideCaptureImage()
            }
        })
    }

    private fun changeColor(colorResId: Int) {
        val window: Window = getWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = resources.getColor(colorResId, null)
            window.navigationBarColor = resources.getColor(colorResId, null)
        }
    }

    object PermissionUtil {
        fun checkPermission(context: Context, permissionList: List<String>): Boolean {
            for (i: Int in permissionList.indices) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        permissionList[i]
                    ) == PackageManager.PERMISSION_DENIED
                ) {
                    return false
                }
            }
            return true
        }

        fun requestPermission(activity: Activity, permissionList: List<String>) {
            ActivityCompat.requestPermissions(activity, permissionList.toTypedArray(), 10)
        }
    }
}