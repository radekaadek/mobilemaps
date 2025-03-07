package pl.pw.graterenowa

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
                Toast.makeText(
                    this,
                    "Permission granted",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }

    private var permissionsGranted = false
    private var bluetoothEnabled = false
    private var locationEnabled = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart")
        findViewById<Button>(R.id.map_button).setOnClickListener {
            Log.d("MainActivity", "CLICK!!")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
    }

    private fun showExplanation(title: String, explanation: String) {
        // show an explanation to the user that this app requires bluetooth
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        alertDialog.show()
    }

    private fun checkBluetoothStatus() : Boolean {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            showExplanation("Bluetooth not found on the device", "This app needs Bluetooth in order to scan beacons.")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // check again an if not enabled, show explanation
            if (!bluetoothAdapter.isEnabled) {
                showExplanation("Bluetooth must be enabled", "This app needs Bluetooth to be enabled in order to scan beacons.")
                return false
            }
        }
        return true
    }

    private fun checkBluetoothON() : Boolean {
        val status = checkBluetoothStatus()
        bluetoothEnabled = status
        return status
    }

    private fun runApp() {
        checkPermissions()
        if (!permissionsGranted) {
            showExplanation("Permissions not granted", "This app needs the Location and Bluetooth permissions in order to scan beacons.")
            return
        }

        if (!checkBluetoothON()) {
            return
        }

        startBeaconScanning()
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        runApp()
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d("MainActivity", "onRestart")
    }

    private fun checkPermissions(): Boolean {
        permissionsGranted = (checkLocationPermission() && checkBluetoothPermission())
        return permissionsGranted
    }

    private fun checkLocationPermission(): Boolean {
        return checkPermission(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Location permission is required for scanning beacons"
        )
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermission(
                Manifest.permission.BLUETOOTH_SCAN,
                "Bluetooth scanning permission is required for scanning beacons"
            ) && checkPermission(
                Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth connecting permission is required for scanning beacons"
            )
        } else {
            TODO("VERSION.SDK_INT < S")
            return false
        }
    }

    private fun checkPermission(permission: String, rationale: String): Boolean {
        when {
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is granted. Continue the action or workflow in your
                // app.
                return true
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                permission
            ) -> {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
                Toast.makeText(
                    this,
                    rationale,
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(permission)
                return ActivityCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_DENIED
            }
        }
    }


    private fun startBeaconScanning() {
        // TODO beacon scanning
        Log.d("MainActivity", "Permissions granted, scanning beacons...")
    }
}
