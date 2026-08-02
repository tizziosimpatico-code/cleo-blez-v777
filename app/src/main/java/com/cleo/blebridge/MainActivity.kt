package com.cleo.blebridge

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cleo.blebridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonTestMode.setOnClickListener {
            startActivity(Intent(this, TestModeActivity::class.java))
        }
        binding.buttonCameraMode.setOnClickListener {
            startActivity(Intent(this, CameraModeActivity::class.java))
        }
        binding.buttonUsbCameraMode.setOnClickListener {
            startActivity(Intent(this, UsbCameraModeActivity::class.java))
        }
    }
}
