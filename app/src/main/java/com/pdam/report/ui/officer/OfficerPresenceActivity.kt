package com.pdam.report.ui.officer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.pdam.report.MainActivity
import com.pdam.report.R
import com.pdam.report.data.PresenceData
import com.pdam.report.data.UserData
import com.pdam.report.databinding.ActivityOfficerPresenceBinding
import com.pdam.report.utils.PermissionHelper
import com.pdam.report.utils.createCustomTempFile
import com.pdam.report.utils.getNetworkTime
import com.pdam.report.utils.navigatePage
import com.pdam.report.utils.reduceFileImageInBackground
import com.pdam.report.utils.showBlockingLayer
import com.pdam.report.utils.showLoading
import com.pdam.report.utils.showToast
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

@Suppress("DEPRECATION")
class OfficerPresenceActivity : AppCompatActivity() {

    // Variabel untuk menyimpan file gambar yang diambil dari kamera
    private var getFile: File? = null

    // Variabel untuk menyimpan referensi Firebase Storage dan Database
    private val storageReference = FirebaseStorage.getInstance().reference
    private val databaseReference = FirebaseDatabase.getInstance().reference

    // Variabel untuk menyimpan referensi FusedLocationProviderClient
    private val fuse: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(
            this
        )
    }

    // Variabel untuk menyimpan lokasi perangkat
    private var latLng: LatLng? = null
    private lateinit var locationRequest: LocationRequest

    // Variabel untuk menyimpan referensi FirebaseAuth
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val currentUser = auth.currentUser

    private var isToastShown = false
    private var isUploading = false // State variable to track if the upload is in progress

    // Variabel untuk menyimpan referensi binding
    private val binding: ActivityOfficerPresenceBinding by lazy {
        ActivityOfficerPresenceBinding.inflate(
            layoutInflater
        )
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            navigatePage(this@OfficerPresenceActivity, MainActivity::class.java)
            finish()
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setBackgroundDrawable(resources.getDrawable(R.color.tropical_blue))

        // Membuat permintaan lokasi
        locationRequest = createLocationRequest()

        checkPermissions()
        setupButtons()
        checkLocationSettings()
    }

    @Suppress("DEPRECATION")
    private fun createLocationRequest(): LocationRequest {
        return LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }

    // Mengatur fungsi tombol pada tampilan
    private fun setupButtons() {
        // Aksi saat tombol kamera ditekan
        binding.cameraButton.setOnClickListener {
            if (PermissionHelper.hasCameraPermission(this@OfficerPresenceActivity)) {
                startTakePhoto()
            } else {
                PermissionHelper.requestCameraPermission(this@OfficerPresenceActivity)
            }
        }

        // Membuat tombol unggah nonaktif awalnya
        binding.uploadButton.isEnabled = false

        // Aksi saat tombol unggah ditekan
        binding.uploadButton.setOnClickListener { uploadImage() }
    }

    // Navigasi kembali ke halaman utama (MainActivity)
    private fun navigateToMainActivity() {
        if (PermissionHelper.hasLocationPermission(this@OfficerPresenceActivity)) {
            fuse.removeLocationUpdates(locationCallback)
        }
        navigatePage(this, MainActivity::class.java, true)
        finish()
    }

    // Menangani tindakan saat tombol kembali di ActionBar ditekan
    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Metode untuk menangani hasil dari permintaan pengaturan lokasi
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) {
                getLocation()
            } else if (resultCode == RESULT_CANCELED) {
                showToast(this, R.string.enable_location)
            }
        }
    }

    // Metode untuk memulai pengambilan foto
    private lateinit var currentPhotoPath: String
    private fun startTakePhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)

        // Membuat file temporer untuk menyimpan foto
        createCustomTempFile(application).also { file ->
            val photoURI: Uri = FileProvider.getUriForFile(
                this@OfficerPresenceActivity,
                "com.pdam.report",
                file
            )
            currentPhotoPath = file.absolutePath
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)

            // Memulai kamera untuk mengambil foto
            launcherIntentCamera.launch(intent)
        }
    }

    // Metode untuk menangani hasil dari pemotretan
    private val launcherIntentCamera = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val myFile = File(currentPhotoPath)
            myFile.let { file ->
                getFile = file
                binding.previewImageView.setImageBitmap(BitmapFactory.decodeFile(file.path))
            }
        }
    }

    // Fungsi untuk mengunggah gambar
    private fun uploadImage() {
        if (isUploading) {
            // Don't initiate another upload if one is already in progress
            return
        }

        if (getFile != null) {
            val uid = currentUser?.uid

            if (uid != null) {
                val userRef = databaseReference.child("users").child(uid)
                userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val userData = snapshot.getValue(UserData::class.java)
                            if (userData != null) {
                                val username = userData.username
                                showBlockingLayer(window, true)
                                showLoading(
                                    true,
                                    binding.progressBar,
                                    binding.cameraButton,
                                    binding.uploadButton
                                )

                                lifecycleScope.launch {
                                    try {
                                        isUploading =
                                            true // Set the state variable to indicate that an upload is in progress

                                        showToast(
                                            this@OfficerPresenceActivity,
                                            R.string.compressing_image
                                        )
                                        getFile = getFile?.reduceFileImageInBackground()
                                    } catch (e: Exception) {
                                        showToast(
                                            this@OfficerPresenceActivity,
                                            R.string.compressing_image_failed
                                        )
                                    } finally {
                                        isUploading =
                                            false // Reset the state variable after the upload attempt, whether it succeeds or fails
                                    }

                                    if (!isUploading) {
                                        // Menentukan referensi untuk penyimpanan gambar
                                        val currentTime = getNetworkTime()
                                        val photoRef =
                                            storageReference.child("presence/${currentTime}_${userData.username}.jpg")
                                        // Mengunggah gambar ke Firebase Storage
                                        photoRef.putFile(Uri.fromFile(getFile))
                                            .addOnSuccessListener { uploadTask ->
                                                uploadTask.storage.downloadUrl.addOnSuccessListener { downloadUri ->
                                                    showLoading(
                                                        false,
                                                        binding.progressBar,
                                                        binding.cameraButton,
                                                        binding.uploadButton
                                                    )

                                                    // Membuat objek data presensi
                                                    val data = PresenceData(
                                                        currentTime,
                                                        username,
                                                        latLng?.latitude ?: 0.0,
                                                        latLng?.longitude ?: 0.0,
                                                        downloadUri.toString(),
                                                    )

                                                    // Mengunggah data presensi ke database
                                                    databaseReference.child("listPresence")
                                                        .child(uid).push().setValue(data)
                                                        .addOnCompleteListener { task ->
                                                            if (task.isSuccessful) {
                                                                databaseReference.child("users")
                                                                    .child(uid)
                                                                    .child("lastPresence")
                                                                    .setValue(currentTime)
                                                                showToast(
                                                                    this@OfficerPresenceActivity,
                                                                    R.string.upload_success
                                                                )
                                                                navigateToMainActivity()
                                                            } else {
                                                                showToast(
                                                                    this@OfficerPresenceActivity,
                                                                    R.string.upload_failed
                                                                )
                                                            }
                                                        }
                                                }.addOnFailureListener {
                                                    showLoading(
                                                        false,
                                                        binding.progressBar,
                                                        binding.cameraButton,
                                                        binding.uploadButton
                                                    )
                                                    showToast(
                                                        this@OfficerPresenceActivity,
                                                        it.message.toString().toInt()
                                                    )
                                                }
                                            }

                                    }
                                }
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        showLoading(
                            false,
                            binding.progressBar,
                            binding.cameraButton,
                            binding.uploadButton
                        )
                        showToast(this@OfficerPresenceActivity, "Error: ${error.message}".toInt())
                    }
                })
                showBlockingLayer(window, false)
            } else {
                showToast(this@OfficerPresenceActivity, R.string.invalid_auth)
            }
        } else {
            showToast(this@OfficerPresenceActivity, R.string.select_image)
        }
    }

    // Memeriksa izin yang diperlukan
    private fun checkPermissions() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this@OfficerPresenceActivity,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    // Memeriksa pengaturan lokasi perangkat
    @Suppress("DEPRECATION")
    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            getLocation()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this, REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    sendEx.printStackTrace()
                }
            }
        }
    }

    // Memulai permintaan lokasi
    private fun getLocation() {
        if (latLng == null && !isToastShown) {
            showToast(this@OfficerPresenceActivity, R.string.initialize_location)
        }
        if (PermissionHelper.hasLocationPermission(this)) {
            try {
                fuse.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            } catch (e: SecurityException) {
                showToast(this@OfficerPresenceActivity, R.string.permission_denied)
            }
        } else {
            PermissionHelper.requestLocationPermission(this@OfficerPresenceActivity)
        }
    }

    // Menangani pembaruan lokasi perangkat untuk android 12+
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                latLng = LatLng(location.latitude, location.longitude)
                if (latLng != null && !isToastShown) {
                    showToast(this@OfficerPresenceActivity, R.string.location_found)
                    binding.uploadButton.isEnabled = true
                    isToastShown = true
                } else if (latLng == null) {
                    showToast(this@OfficerPresenceActivity, R.string.location_not_found)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Hapus pembaruan lokasi
        fuse.removeLocationUpdates(locationCallback)

        // Hapus file gambar
        getFile?.delete()

        // Hapus file gambar yang diambil dari penyimpanan internal
        val file = File(currentPhotoPath)
        file.delete()

        // stop coroutine
        lifecycleScope.coroutineContext.cancel()
    }

    // Menangani hasil permintaan izin
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS -> {
                if (allPermissionsGranted()) {
                    getLocation()
                } else {
                    showToast(this@OfficerPresenceActivity, R.string.must_allow_permission)
                }
            }
        }
    }

    // Memeriksa apakah semua izin yang diperlukan telah diberikan
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CHECK_SETTINGS = 123
    }
}