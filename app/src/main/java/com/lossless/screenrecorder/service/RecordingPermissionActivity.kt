package com.lossless.screenrecorder.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class RecordingPermissionActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenRecordService.startRecordingWithProjection(
                context = this,
                resultCode = result.resultCode,
                resultData = result.data!!
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestMediaProjectionCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (neededPermissions.isNotEmpty()) {
            runtimePermissionsLauncher.launch(neededPermissions.toTypedArray())
        } else {
            requestMediaProjectionCapture()
        }
    }

    private fun requestMediaProjectionCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, RecordingPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
