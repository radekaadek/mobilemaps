package pl.pw.graterenowa.common.recievers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat.getSystemService

class ConnectivityStateReceiver(
    private val context: Context,
    private val connectionReadyCallback: () -> Unit,
    private val connectionNotReadyCallback: () -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {

    }

    private var gpsON = false
    private var bluetoothON = false
}
