package com.cleo.blebridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class UsbCameraModeActivity : AppCompatActivity(R.layout.activity_usb_camera_mode) {

    private val permissionsNeeded: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return list.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { addFragmentIfNeeded() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasAllPermissions()) {
            addFragmentIfNeeded()
        } else {
            permissionLauncher.launch(permissionsNeeded)
        }
    }

    private fun hasAllPermissions(): Boolean =
        permissionsNeeded.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun addFragmentIfNeeded() {
        if (supportFragmentManager.findFragmentById(R.id.usbFragmentContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.usbFragmentContainer, UsbCameraFragment())
                .commit()
        }
    }
}
