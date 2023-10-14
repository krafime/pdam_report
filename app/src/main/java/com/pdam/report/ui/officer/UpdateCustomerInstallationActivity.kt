package com.pdam.report.ui.officer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.pdam.report.MainActivity
import com.pdam.report.R
import com.pdam.report.data.CustomerData
import com.pdam.report.databinding.ActivityUpdateCustomerInstallationBinding
import com.pdam.report.utils.createCustomTempFile
import com.pdam.report.utils.navigatePage
import com.pdam.report.utils.showLoading
import com.pdam.report.utils.showToast
import java.io.File

class UpdateCustomerInstallationActivity : AppCompatActivity() {

    private val binding by lazy { ActivityUpdateCustomerInstallationBinding.inflate(layoutInflater) }
    private var imageFile: File? = null

    private val databaseReference = FirebaseDatabase.getInstance().reference

    private val firebaseKey by lazy { intent.getStringExtra(AddFirstDataActivity.EXTRA_FIREBASE_KEY) }
    private val customerData by lazy { intent.getIntExtra(AddFirstDataActivity.EXTRA_CUSTOMER_DATA, 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadDataFromFirebase(firebaseKey.toString())

        setupButtons()
    }

    private fun setupButtons() {
        binding.itemImage.setOnClickListener { startTakePhoto() }
        binding.btnSimpan.setOnClickListener { saveData() }
        binding.btnHapus.setOnClickListener {
            if (customerData == 1) {
                binding.btnHapus.setOnClickListener { clearData() }
            } else {
                binding.btnHapus.setOnClickListener { deleteData() }
            }
        }
    }

    private fun deleteData() {
        val listCustomerRef = databaseReference.child("listCustomer")
        val customerRef = firebaseKey?.let { listCustomerRef.child(it) }

        customerRef?.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    // Show a confirmation dialog for delete
                    showDeleteConfirmationDialog(customerRef)
                } else {
                    showToast(this@UpdateCustomerInstallationActivity, R.string.data_not_found)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showToast(this@UpdateCustomerInstallationActivity, "${R.string.failed_access_data}: ${error.message}".toInt())
            }
        })
    }

    private fun showDeleteConfirmationDialog(customerRef: DatabaseReference) {
        AlertDialog.Builder(this@UpdateCustomerInstallationActivity).apply {
            setTitle(R.string.delete_data)
            setMessage(R.string.delete_confirmation)
            setPositiveButton(R.string.delete) { _, _ ->
                // Confirm and proceed with deletion
                deleteCustomerData(customerRef)
            }
            setNegativeButton(R.string.cancel, null)
        }.create().show()
    }

    private fun deleteCustomerData(customerRef: DatabaseReference) {
        customerRef.removeValue()
            .addOnSuccessListener {
                showToast(this@UpdateCustomerInstallationActivity, R.string.delete_success)
                finish()
            }
            .addOnFailureListener { error ->
                showToast(this@UpdateCustomerInstallationActivity, "${R.string.delete_failed}: ${error.message}".toInt())
            }
    }

    private fun clearData() {
        // Clear all input fields
        binding.edtNomorKl.text.clear()
        binding.edtMerk.text.clear()
        binding.edtDiameter.text.clear()
        binding.edtStand.text.clear()
        binding.edtNomorMeter.text.clear()
        binding.edtNomorSegel.text.clear()
        binding.edtKeterangan.text.clear()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(this, AddFirstDataActivity::class.java)
        startActivity(intent)
        super.onBackPressed()
    }

    private fun saveData() {
        // Get data from input fields
        val currentDate = System.currentTimeMillis()
        val nomorKL = binding.edtNomorKl.text.toString()
        val merkMeter = binding.edtMerk.text.toString()
        val diameterMeter = binding.edtDiameter.text.toString()
        val standMeter = binding.edtStand.text.toString()
        val nomorMeter = binding.edtNomorMeter.text.toString()
        val nomorSegel = binding.edtNomorSegel.text.toString()
        val keterangan = binding.edtKeterangan.text.toString()

        // Validate input
        if (isInputValid(nomorKL, merkMeter, diameterMeter, standMeter, nomorMeter, nomorSegel, keterangan)) {
            showLoading(true, binding.progressBar, binding.btnSimpan, binding.btnHapus)
            uploadImagesAndSaveData(currentDate, nomorKL, merkMeter, diameterMeter, standMeter, nomorMeter, nomorSegel, keterangan)
        } else {
            showLoading(false, binding.progressBar, binding.btnSimpan, binding.btnHapus)
            showToast(this, R.string.fill_all_data)
        }
    }

    private fun isInputValid(nomorKL: String, merk: String, diameter: String, stand: String, nomorMeter: String, nomorSegel: String, keterangan: String): Boolean {
        // Check if all required input is valid
        return nomorKL.isNotEmpty() && merk.isNotEmpty() && diameter.isNotEmpty() && stand.isNotEmpty() && nomorMeter.isNotEmpty() && nomorSegel.isNotEmpty() && keterangan.isNotEmpty() && imageFile != null
    }

    private fun uploadImagesAndSaveData(currentDate: Long, nomorKL: String, merk: String, diameter: String, stand: String, nomorMeter: String, nomorSegel: String, keterangan: String) {
        val storageReference = FirebaseStorage.getInstance().reference
        val dokumentasi3Ref =
            storageReference.child("dokumentasi/${System.currentTimeMillis()}_dokumentasi3.jpg")

        // Upload image 3
        dokumentasi3Ref.putFile(Uri.fromFile(imageFile)).addOnSuccessListener {
            dokumentasi3Ref.downloadUrl.addOnSuccessListener { uri1 ->
                val dokumentasi3 = uri1.toString()

                // After successfully obtaining image URLs, save the data to Firebase
                saveCustomerData(
                    currentDate,
                    nomorKL,
                    merk,
                    diameter,
                    stand,
                    nomorMeter,
                    nomorSegel,
                    keterangan,
                    dokumentasi3
                )
            }
        }
    }

    private fun saveCustomerData(currentDate: Long, nomorKL: String, merk: String, diameter: String, stand: String, nomorMeter: String, nomorSegel: String, keterangan: String, dokumentasi3: String) {
        val customerRef = databaseReference.child("listCustomer").child(firebaseKey.toString())

        val data = mapOf(
            "updateVerifDate" to currentDate,
            "nomorKL" to nomorKL,
            "merkMeter" to merk,
            "diameterMeter" to diameter,
            "standMeter" to stand,
            "nomorMeter" to nomorMeter,
            "nomorSegel" to nomorSegel,
            "keterangan2" to keterangan,
            "dokumentasi3" to dokumentasi3,
            "data" to 2
        )

        customerRef.updateChildren(data).addOnCompleteListener { task ->
            showLoading(true, binding.progressBar, binding.btnSimpan, binding.btnHapus)
            if (task.isSuccessful) {
                showToast(this, R.string.save_success)
            } else {
                showToast(this, R.string.save_failed)
            }
            showLoading(false, binding.progressBar, binding.btnSimpan, binding.btnHapus)
            finish()
        }
    }

    private fun loadDataFromFirebase(firebaseKey: String) {
        val customerRef = databaseReference.child("listCustomer").child(firebaseKey)

        customerRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val dataCustomer = snapshot.getValue(CustomerData::class.java)
                    if (dataCustomer != null) {
                        // Jika data pelanggan ditemukan, tampilkan datanya
                        displayCustomerData(dataCustomer)
                        if (customerData != 1) {
                            displayAnotherData(dataCustomer)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Menampilkan pesan kesalahan jika mengakses data gagal
                showToast(this@UpdateCustomerInstallationActivity, "${R.string.failed_access_data}: ${error.message}".toInt())
            }
        })
    }

    private lateinit var currentPhotoPath: String
    private fun startTakePhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)

        createCustomTempFile(application).also { file ->
            val photoURI: Uri = FileProvider.getUriForFile(
                this@UpdateCustomerInstallationActivity,
                "com.pdam.report",
                file
            )
            currentPhotoPath = file.absolutePath
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            launcherIntentCamera.launch(intent)
        }
    }

    private val launcherIntentCamera = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // After successfully capturing an image, assign it to the appropriate file
            val myFile = File(currentPhotoPath)
            myFile.let { file ->
                imageFile = file
                binding.itemImage.text = System.currentTimeMillis().toString() + "_dokumentasi3.jpg"
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            navigatePage(this, AddFirstDataActivity::class.java, true)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun displayCustomerData(dataCustomer: CustomerData) {
        // Mengisi tampilan dengan data pelanggan yang ditemukan dari Firebase
        binding.edtPemasanganSambungan.apply {
            setText(dataCustomer.jenisPekerjaan)
            isEnabled = false
            isFocusable = false
        }

        binding.edtPw.apply {
            setText(dataCustomer.pw.toString())
            isEnabled = false
            isFocusable = false
        }

        binding.edtNamaPelanggan.apply {
            setText(dataCustomer.name)
            isEnabled = false
            isFocusable = false
        }

        binding.edtAlamatPelanggan.apply {
            setText(dataCustomer.address)
            isEnabled = false
            isFocusable = false
        }

        binding.edtKeterangan.apply {
            setText(dataCustomer.keterangan1)
        }
    }

    private fun displayAnotherData(dataCustomer: CustomerData) {
        binding.edtNomorKl.apply {
            setText(dataCustomer.nomorKL)
            isEnabled = false
            isFocusable = false
        }

        binding.edtMerk.apply {
            setText(dataCustomer.merkMeter)
            isEnabled = false
            isFocusable = false
        }

        binding.edtDiameter.apply {
            setText(dataCustomer.diameterMeter)
            isEnabled = false
            isFocusable = false
        }

        binding.edtStand.apply {
            setText(dataCustomer.standMeter)
            isEnabled = false
            isFocusable = false
        }

        binding.edtNomorMeter.apply {
            setText(dataCustomer.nomorMeter)
            isEnabled = false
            isFocusable = false
        }

        binding.edtNomorSegel.apply {
            setText(dataCustomer.nomorSegel)
            isEnabled = false
            isFocusable = false
        }

        binding.edtKeterangan.apply {
            setText(dataCustomer.keterangan2)
            isEnabled = false
            isFocusable = false
        }

        binding.itemImage.apply {
            text = dataCustomer.dokumentasi3
            isEnabled = false
        }

        // Mengganti teks tombol Simpan untuk melanjutkan ke halaman berikutnya
        binding.btnSimpan.apply {
            if (dataCustomer.jenisPekerjaan == "Pemasangan kembali") {
                text = getString(R.string.finish)
                layoutParams.width = 1000
                navigatePage(this@UpdateCustomerInstallationActivity, UpdateCustomerInstallationActivity::class.java, true)
            } else {
                text = getString(R.string.next)
                setOnClickListener {
                    val intent = Intent(
                        this@UpdateCustomerInstallationActivity,
                        UpdateCustomerVerificationActivity::class.java
                    )

                    intent.putExtra(
                        UpdateCustomerVerificationActivity.EXTRA_FIREBASE_KEY,
                        dataCustomer.firebaseKey
                    )
                    intent.putExtra(
                        UpdateCustomerVerificationActivity.EXTRA_CUSTOMER_DATA,
                        dataCustomer.data
                    )

                    startActivity(intent)
                }
            }
        }
    }

    companion object {
        const val EXTRA_FIREBASE_KEY = "firebase_key"
        const val EXTRA_CUSTOMER_DATA = "customer_data"
    }
}