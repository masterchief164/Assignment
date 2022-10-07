package com.example.assignment

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log.d
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.example.assignment.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var readPermissionGranted: Boolean = false
    private var videoURIs: MutableList<VideoFile> = mutableListOf()
    private var videos: MutableLiveData<List<VideoFile>> =
        MutableLiveData<List<VideoFile>>()
    private var working: MutableLiveData<Boolean> = MutableLiveData(false)

    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        permissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                readPermissionGranted =
                    it[android.Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
            }
        updateOrRequestPermissions()
        subscribeToVideos()

        intentSenderLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == RESULT_OK) {
                    d("TAG", "onCreate: Permission Granted")
                    Toast.makeText(this, "Videos Deleted Successfully", Toast.LENGTH_SHORT).show()
                } else {
                    d("TAG", "onCreate: Permission Denied")
                    Toast.makeText(this, "Videos Deletion Unsuccessful", Toast.LENGTH_SHORT).show()
                }
            }

        binding.readButton.setOnClickListener {
            if (readPermissionGranted) {
                videoURIs.clear()
                videos.postValue(videoURIs)
                lifecycleScope.launch(IO) {
                    videoURIs = loadVideosFromExternalStorage().toMutableList()
                    videos.postValue(videoURIs)
                    d("MainActivity", "videoURIs: $videoURIs")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Videos loaded",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        binding.saveButton.setOnClickListener {
            if (readPermissionGranted) {
                lifecycleScope.launch(IO) {
                    saveToInternalStorage()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Videos saved",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        binding.format.setOnClickListener {
            lifecycleScope.launch {
                deleteVideos()
            }
        }
    }

    private suspend fun loadVideosFromExternalStorage(): List<VideoFile> {
        return withContext(IO) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                val videos = mutableListOf<VideoFile>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val size = cursor.getLong(sizeColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    videos.add(VideoFile(id, name, size, contentUri))
                }
                videos.toList()
            } ?: listOf()
        }
    }

    private fun updateOrRequestPermissions() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        readPermissionGranted = hasReadPermission

        val permissions = mutableListOf<String>()
        if (!readPermissionGranted)
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private suspend fun saveToInternalStorage(): Boolean {
        return withContext(IO) {
            try {
                working.postValue(true)
                d("MainActivity", "started copying")
                videoURIs.forEach {
                    d("MainActivity", "videoURI: $it")
                    contentResolver.openInputStream(it.contentUri).use { inputStream ->
                        openFileOutput(it.name, MODE_PRIVATE).use { outputStream ->
                            inputStream?.copyTo(outputStream)
                        }
                    }
                }
                d("MainActivity", "complete")

                working.postValue(false)
                true
            } catch (e: IOException) {
                e.printStackTrace()
                working.postValue(false)
                false
            } catch (e: Exception) {
                e.printStackTrace()
                working.postValue(false)
                false
            }
        }
    }

    private fun subscribeToVideos() {
        videos.observe(this) { videoList ->
            d("MainActivityaefwef", "videoList: $videoList")
            working.observe(this) {
                if (it) {
                    binding.saveButton.isEnabled = false
                    binding.readButton.isEnabled = false
                    binding.progressBar.isVisible = true
                } else {
                    binding.saveButton.isEnabled = true
                    binding.readButton.isEnabled = true
                    binding.progressBar.isVisible = false
                }
            }
            if (videoList.isEmpty()) {
                binding.saveButton.isEnabled = false
                binding.readButton.isEnabled = true
            } else {
                binding.saveButton.isEnabled = true
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun deleteVideos() {
        withContext(IO) {
            try {
                val intentSender =
                    MediaStore.createDeleteRequest(
                        contentResolver,
                        videos.value!!.map { it.contentUri }).intentSender
                intentSender.let { sender ->
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(sender).build()
                    )
                }

            } catch (e: SecurityException) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                val intentSender =
                    recoverableSecurityException?.userAction?.actionIntent?.intentSender
                intentSender?.let { sender ->
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(sender).build()
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}
