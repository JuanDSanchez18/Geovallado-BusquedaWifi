package com.example.geovalladoscanner

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private lateinit var wifiManager: WifiManager
private lateinit var wakeLock: PowerManager.WakeLock

private lateinit var locationManager: LocationManager

private var isGeofence = false
private var isRepetitiveScan = false
private var numberOfSSID = 0

private const val defaultRepetitiveDelay = 31 * 1000
private const val changeRepetitiveDelay = 2 * 60 * 1000
private var repetitiveDelay = 0
private var isChangeRepetitivedelay = false

private val apSSIDlist = listOf("Esp32_Calle45", "Esp32_Marly")
private var capApList: MutableList<String> = mutableListOf()
private var repetitiveAplist : MutableList<Int> = mutableListOf()
private var nearestAp: String = ""
private var outSSID = 0

class ScannerWifiService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                Actions.ENTER.name -> {
                    isGeofence = true
                    repetitiveDelay = defaultRepetitiveDelay
                    if (!isRepetitiveScan) {
                        sendForegroundNotification()
                        geofenceEnter()
                    } else if (isChangeRepetitivedelay) {
                        repetitiveScanWifi()
                    }
                    isChangeRepetitivedelay = false
                }
                Actions.DWELL.name -> {
                    if (!isChangeRepetitivedelay) {
                        repetitiveDelay = changeRepetitiveDelay
                        isChangeRepetitivedelay = true
                    }
                }
                Actions.EXIT.name -> {
                    if (isGeofence) {
                        isGeofence = false
                    }
                }
                Actions.ENDSERVICE.name -> {
                    stopService()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
        return null
    }

    // Scanner Wifi code

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                Toast.makeText(context, "Success Scan", Toast.LENGTH_SHORT).show()
                scanSuccess()
            }
        }
    }

    private fun sendForegroundNotification(){
        val notificationId = 420
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = notificationForeground(this)
            startForeground(notificationId, notification)
        }
    }

    @SuppressLint("ShortAlarm")
    private fun geofenceEnter() {
        initializeWLocks()
        initializeScanWifi()
        repetitiveScanWifi()
    }

    private fun initializeWLocks() {
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag").apply {
                    acquire(10*60*1000L /*10 minutes*/)
                }
            }
    }

    private fun startWLock() {
        wakeLock.acquire(10*60*1000L /*10 minutes*/)
    }

    private fun stopWLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun initializeScanWifi() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }

    private fun repetitiveScanWifi() {
        isRepetitiveScan = true
        GlobalScope.launch(Dispatchers.IO) {
            while (isRepetitiveScan) {
                launch(Dispatchers.IO) {
                    if (!wakeLock.isHeld)
                        startWLock()
                    startWifiScan()
                }
                delay(repetitiveDelay.toLong())
            }
        }
    }

    private fun startWifiScan () {
        var ready = true
        val myContext: Context = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
            ready = false
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(myContext, "Required GPS", Toast.LENGTH_LONG).show()
            }
            stopService()
        }
        if (ready) {
            val success = wifiManager.startScan()
            if (!success) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(myContext, "Something wrong", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scanSuccess() {
        val results = wifiManager.scanResults

        var isSSID = false
        var numSSID = 0
        var inirssi = -200
        var apnearest = ""

        for (result in results) {
            val apssid = result.SSID
            if (apssid in apSSIDlist) {
                if (apssid in capApList) {
                    val index = capApList.indexOf(apssid)
                    val countSSID = repetitiveAplist[index] + 1
                    repetitiveAplist[index] = countSSID
                    if (countSSID >= 15 && !isChangeRepetitivedelay) { // ~= 465 s = 7,75 min
                        repetitiveDelay = changeRepetitiveDelay
                        isChangeRepetitivedelay = true
                    }
                }else {
                    capApList.add(apssid)
                    repetitiveAplist.add(0)
                }
                val rssi = result.level
                if (rssi > inirssi) {
                    inirssi = rssi
                    apnearest = apssid
                }
                numSSID += 1
            }
        }

        if (numSSID  >  0) {
            isSSID = true
            if (outSSID > 0) {
                outSSID = 0
                repetitiveDelay = 31 * 1000
                isChangeRepetitivedelay = false
            }
            if (numSSID != numberOfSSID)
                numberOfSSID = numSSID

            if (nearestAp != apnearest)
                nearestAp = apnearest

        }

        if (!isSSID and !isGeofence) {
            outSSID++
            if (outSSID == 1)
                repetitiveDelay = 20 * 1000

            if (outSSID == 3)
                stopService()
        }
    }

    private fun stopService() {
        isRepetitiveScan = false
        stopWLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        }else stopSelf()
    }

}

/*
References
https://robertohuertas.com/2019/06/29/android_foreground_services/

*/

//probar sin wlock