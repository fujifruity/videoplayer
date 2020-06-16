package com.gmail.fujifruity.videoplayerapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.gmail.fujifruity.videoplayer.VideoPlayer
import com.gmail.fujifruity.videoplayer.logd

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    lateinit var videoPlayer: VideoPlayer
    private lateinit var surfaceView: SurfaceView

    private val requestCodePermissions = 1
    private val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun allPermissionsGranted() = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (allPermissionsGranted()) {
            surfaceView.holder.addCallback(this)
        } else {
            Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)

        if (allPermissionsGranted()) {
            surfaceView.holder.addCallback(this)
        } else {
            ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
        }

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        logd(TAG) { "surface created." }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        logd(TAG) { "surface changed." }
        videoPlayer =
            VideoPlayer(this, holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        logd(TAG) { "surface destroyed." }
        videoPlayer.close()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }

}

