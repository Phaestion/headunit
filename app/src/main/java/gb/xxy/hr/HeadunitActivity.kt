package gb.xxy.hr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Environment
import android.os.Environment.DIRECTORY_DOCUMENTS
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.experimental.or


class HeadunitActivity : Activity() {
    private var button1: ImageButton? = null
    private var button2: ImageButton? = null
    private var button3: ImageButton? = null
    private var button4: ImageButton? = null
    private var button5: ImageButton? = null
    private var button6: ImageButton? = null
    private var m_usb_device: UsbDevice? = null
    private var m_usb_mgr: UsbManager? = null
    private var m_usb_receiver: usb_receiver? = null
    private var m_usb_dev_conn: UsbDeviceConnection? = null
    private var m_usb_iface: UsbInterface? = null
    private var m_usb_ep_in: UsbEndpoint? = null
    private var m_usb_ep_out: UsbEndpoint? = null
    private var m_ep_in_addr = -1
    private var m_ep_out_addr = -1
    private var m_usb_connected: Boolean = false

    private var doubleBackToExitPressedOnce: Boolean = false

    private var SP: SharedPreferences? = null

    private val ibLis = View.OnClickListener { v ->
        // Tap: Tune to preset
        if (v === button1) {

            try {
                val p: Process
                p = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(p.outputStream)
                os.writeBytes("am force-stop com.google.android.projection.gearhead; am start -W -n com.google.android.projection.gearhead/.companion.SplashScreenActivity; am startservice -n com.google.android.projection.gearhead/.companion.DeveloperHeadUnitNetworkService; \n")
                os.writeBytes("exit\n")
                os.flush()
                p.waitFor()
                Thread.sleep(500)
            } catch (e: Exception) {
                mylog.e("HU-SERVICE", "Exception e: $e")
            }


            /*Intent serviceIntent = new Intent(getBaseContext(),gb.xxy.hr.new_hu_tra.class);
                        serviceIntent.putExtra("mode", 0);
                        startService(serviceIntent);*/

            val i = Intent(baseContext, SelfPlayer::class.java)
            startActivity(i)

        } else if (v === button2) {
            val i = Intent(baseContext, Player::class.java)
            startActivity(i)
            showplayer = true

        } else if (v === button3) {
            showplayer = true
            val i = Intent(baseContext, gb.xxy.hr.Wifip2plaunch::class.java)
            startActivity(i)

        } else if (v === button4) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://forum.xda-developers.com/general/paid-software/android-4-1-headunit-reloaded-android-t3432348"))
                startActivity(browserIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(baseContext, "No application can handle this request." + " Please install a webbrowser", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }

        } else if (v === button5) {
            val i = Intent(baseContext, gb.xxy.hr.PrefrenceActivity::class.java)
            startActivity(i)
        } else if (v === button6) {
            stopService(Intent(baseContext, new_hu_tra::class.java))
            val mManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val mChannel = mManager.initialize(baseContext, mainLooper, null)
            try {
                mManager.removeGroup(mChannel, object : WifiP2pManager.ActionListener {
                    override fun onFailure(reasonCode: Int) {
                        mylog.d("HU-WIFIP2P", "Remove group failed. Reason :$reasonCode")
                        finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }

                    override fun onSuccess() {
                        finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                })
            } catch (e: Exception) {
                finish()
                android.os.Process.killProcess(android.os.Process.myPid())
            }

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mylog.d("HU-ACT", "On Destory")
            unregisterReceiver(m_usb_receiver)
        } catch (e: Exception) {
            mylog.d("USB-SERVICE", "Nothing to unregister...")
        }

    }

    override fun onBackPressed() {
        mylog.d("HU-SERVICE", "Back arrow button was pressed main activity...")

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            stopService(Intent(baseContext, new_hu_tra::class.java))
            showplayer = false
            showselfplayer = false
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        this.doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()

        Handler().postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onResume() {
        super.onResume()
        mylog.d("HU-SERVICE", "OnResume...$showplayer")

        if (showplayer) {
            val i = Intent(baseContext, Player::class.java)
            startActivity(i)
        } else if (showselfplayer) {
            val i = Intent(baseContext, SelfPlayer::class.java)
            startActivity(i)
        } else {
            val starts = Intent(this, new_hu_tra::class.java)
            starts.putExtra("mode", 5)
            startService(starts)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myact = this
        setContentView(R.layout.layout)
        m_usb_mgr = this.getSystemService(Context.USB_SERVICE) as UsbManager

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        addListenerOnButton()
        val intent = intent
        mylog.d("HU-APP", "OnCreate$intent")
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            mylog.d("USB-SERVICE", "Started on USB ATTACHED ACTION INTENT")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            usb_attach_handler(device)
        }
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction("gb.xxy.hr.ACTION_USB_DEVICE_PERMISSION")
        filter.addAction("gb.xxy.hr.show_connection_error")
        m_usb_receiver = usb_receiver()
        registerReceiver(m_usb_receiver, filter)

        SP = PreferenceManager.getDefaultSharedPreferences(this)
        if (SP?.getBoolean("self_autorun", false) == true) {
            try {
                val p: Process
                p = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(p.outputStream)
                os.writeBytes("am force-stop com.google.android.projection.gearhead; am start -W -n com.google.android.projection.gearhead/.companion.SplashScreenActivity; am startservice -n com.google.android.projection.gearhead/.companion.DeveloperHeadUnitNetworkService; \n")
                os.writeBytes("exit\n")
                os.flush()
                p.waitFor()
                Thread.sleep(500)
            } catch (e: Exception) {
                mylog.e("HU-SERVICE", "Exception e: $e")
            }

            val i = Intent(baseContext, SelfPlayer::class.java)
            startActivity(i)
        }

        if (SP?.getBoolean("enabledebug", false) == true) {
            Log.d("HU", "Debugging is enabled")
            debugging = true
            if (ContextCompat.checkSelfPermission(this@HeadunitActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this@HeadunitActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.d("HU", "No permission, should request permission...")
                val builder = AlertDialog.Builder(this@HeadunitActivity)
                builder.setTitle(resources.getString(R.string.stor_perm_tit))
                builder.setMessage(resources.getString(R.string.stor_perm_desc))
                builder.setPositiveButton("OK") { dialog, id ->
                    dialog.dismiss()
                    ActivityCompat.requestPermissions(this@HeadunitActivity,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 0)
                }
                builder.setNegativeButton(getString(R.string.ignore)) { dialog, id -> dialog.dismiss() }
                builder.show()

            } else {
                Log.d("HU", "Have permission, creating file...")
                val filename = "hur.log"
                val file = File(Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS), filename)

                try {
                    file.createNewFile()
                    outputStream = FileOutputStream(file)
                } catch (E: Exception) {

                }

            }
        }

    }

    fun addListenerOnButton() {


        button1 = findViewById<View>(R.id.imageButton1) as ImageButton
        button2 = findViewById<View>(R.id.imageButton2) as ImageButton
        button3 = findViewById<View>(R.id.imageButton3) as ImageButton
        button4 = findViewById<View>(R.id.imageButton4) as ImageButton
        button5 = findViewById<View>(R.id.imageButton5) as ImageButton
        button6 = findViewById<View>(R.id.imageButton6) as ImageButton
        button1?.setOnClickListener(ibLis)
        button2?.setOnClickListener(ibLis)
        button3?.setOnClickListener(ibLis)
        button4?.setOnClickListener(ibLis)
        button5?.setOnClickListener(ibLis)
        button6?.setOnClickListener(ibLis)

    }

    //USB Related Stuff
    private inner class usb_receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null) {
                val action = intent.action
                mylog.d("USB-SERVICE", "We are here" + action)
                if (action == UsbManager.ACTION_USB_DEVICE_DETACHED) {    // If detach...
                    usb_detach_handler(device)                                  // Handle detached device
                } else if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {// If attach...
                    usb_attach_handler(device)                            // Handle New attached device
                } else if (action == "gb.xxy.hr.ACTION_USB_DEVICE_PERMISSION") {                 // If Our App specific Intent for permission request...
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        usb_attach_handler(device)                         // Handle same as attached device except NOT NEW so don't add to USB device list
                    }
                }
            } else {
                val action = intent.action
                if (action == "gb.xxy.hr.show_connection_error") {
                    Toast.makeText(baseContext, resources.getString(R.string.err_noserv), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun usb_attach_handler(device: UsbDevice): Boolean {     // Handle attached device. Called only by:  transport_start() on autolaunch or device find, and...

        if (!m_usb_connected)
            usb_connect(device)

        if (m_usb_connected) {
            mylog.d("USB-SERVICE", "Connected so start JNI")
            val starts = Intent(this, new_hu_tra::class.java)
            starts.putExtra("mode", 2)
            showplayer = true
            startService(starts)

            val i = Intent(baseContext, Player::class.java)
            i.putExtra("USB", true)
            i.putExtra("ep_in", m_ep_in_addr)
            i.putExtra("ep_out", m_ep_out_addr)
            new_hu_tra.usbconn = m_usb_dev_conn
            new_hu_tra.m_usb_ep_in = m_usb_ep_in
            new_hu_tra.m_usb_ep_out = m_usb_ep_out

            //m_usb_dev_conn.releaseInterface (m_usb_iface);
            startActivity(i)
            m_usb_device = device
        }

        return true
    }

    private fun usb_detach_handler(device: UsbDevice) {                  // Handle detached device.  Called only by usb_receiver() if device detached while app is running (only ?)
        val dev_vend_id = device.vendorId                            // mVendorId=2996               HTC
        val dev_prod_id = device.productId                           // mProductId=1562              OneM8


        // If in accessory mode...
        if (dev_vend_id == USB_VID_GOO && (dev_prod_id == USB_PID_ACC || dev_prod_id == USB_PID_ACC_ADB)) {                                         //If it is our connected device, disconnet, otherwise ignore!
            stopService(Intent(baseContext, new_hu_tra::class.java))
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        } else
            mylog.d("HU-MAIN", "Not our USB DEVICE...")
    }


    private fun usb_connect(device: UsbDevice) {

        m_usb_dev_conn = null
        mylog.d("USB-SERVICE", "Device connect")

        if (m_usb_mgr?.hasPermission(device) == false) {                               // If we DON'T have permission to access the USB device...

            if (SP?.getBoolean("oldusb", false) == true) {
                val intent = Intent("gb.xxy.hr.ACTION_USB_DEVICE_PERMISSION")                 // Our App specific Intent for permission request
                intent.setPackage(this.packageName)
                val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_ONE_SHOT)
                m_usb_mgr?.requestPermission(device, pendingIntent)              // Request permission. BCR called later if we get it.
            } else {
                Handler().postDelayed({ usb_attach_handler(device) }, 3000)
            }

            return                                                            // Done for now. Wait for permission
        }
        var ret = usb_open(device)                                        // Open USB device & claim interface
        if (ret < 0) {                                                      // If error...
            usb_disconnect()                                                // Ensure state is disconnected
            return                                                            // Done
        }

        val dev_vend_id = device.vendorId                            // mVendorId=2996               HTC
        val dev_prod_id = device.productId                           // mProductId=1562              OneM8


        // If in accessory mode...
        if (dev_vend_id == USB_VID_GOO && (dev_prod_id == USB_PID_ACC || dev_prod_id == USB_PID_ACC_ADB)) {
            ret = acc_mode_endpoints_set()                                  // Set Accessory mode Endpoints
            if (ret < 0) {                                                    // If error...
                usb_disconnect()                                              // Ensure state is disconnected
            } else {
                m_usb_connected = true

            }
            return                                                            // Done
        }
        // Else if not in accessory mode...
        m_usb_dev_conn?.let { acc_mode_switch(it) }                                   // Do accessory negotiation and attempt to switch to accessory mode
        //usb_disconnect ();                                                  // Ensure state is disconnected
        // Done, wait for accessory mode
    }

    private fun usb_disconnect() {
        mylog.d("USB-SERVICE", "usb_disconnect")// m_usb_device: " + m_usb_device);
        m_usb_connected = false

        usb_close()
    }

    private fun usb_close() {                                           // Release interface and close USB device connection. Called only by usb_disconnect()
        mylog.d("USB-SERVICE", "usb_close")
        m_usb_ep_in = null                                               // Input  EP
        m_usb_ep_out = null                                               // Output EP
        m_ep_in_addr = -1                                                 // Input  endpoint Value
        m_ep_out_addr = -1                                                 // Output endpoint Value

        if (m_usb_dev_conn != null) {
            var bret = false
            if (m_usb_iface != null) {
                bret = m_usb_dev_conn?.releaseInterface(m_usb_iface) ?: false
            }
            if (bret) {
                mylog.d("USB-SERVICE", "OK releaseInterface()")
            } else {
                mylog.e("USB-SERVICE", "Error releaseInterface()")
            }

            m_usb_dev_conn?.close()                                        //
        }
        m_usb_dev_conn = null
        m_usb_iface = null
    }


    private fun usb_open(device: UsbDevice): Int {                             // Open USB device connection & claim interface. Called only by usb_connect()
        try {
            if (m_usb_dev_conn == null)
                m_usb_dev_conn = m_usb_mgr?.openDevice(device)                 // Open device for connection
        } catch (e: Throwable) {
            mylog.e("USB-SERVICE", "Throwable: $e")                                  // java.lang.IllegalArgumentException: device /dev/bus/usb/001/019 does not exist or is restricted
        }

        if (m_usb_dev_conn == null) {
            Log.w("USB-SERVICE", "Could not obtain m_usb_dev_conn for device: $device")
            return -1                                                      // Done error
        }
        mylog.d("USB-SERVICE", "Device m_usb_dev_conn: " + m_usb_dev_conn)

        try {
            val iface_cnt = device.interfaceCount
            if (iface_cnt <= 0) {
                mylog.e("USB-SERVICE", "iface_cnt: $iface_cnt")
                return -1                                                    // Done error
            }
            mylog.d("USB-SERVICE", "iface_cnt: $iface_cnt")
            m_usb_iface = device.getInterface(0)                            // java.lang.ArrayIndexOutOfBoundsException: length=0; index=0

            if (m_usb_dev_conn?.claimInterface(m_usb_iface, true) == false) {        // Claim interface, if error...   true = take from kernel
                mylog.e("USB-SERVICE", "Error claiming interface")
                return -1
            }
            mylog.d("USB-SERVICE", "Success claiming interface")
        } catch (e: Throwable) {
            mylog.e("USB-SERVICE", "Throwable: $e")           // Nexus 7 2013:    Throwable: java.lang.ArrayIndexOutOfBoundsException: length=0; index=0
            return -1                                                      // Done error
        }

        return 0                                                         // Done success
    }

    private fun acc_mode_endpoints_set(): Int {                               // Set Accessory mode Endpoints. Called only by usb_connect()
        mylog.d("USB-SERVICE", "In acc so get EPs...")
        m_usb_ep_in = null                                               // Setup bulk endpoints.
        m_usb_ep_out = null
        m_ep_in_addr = -1     // 129
        m_ep_out_addr = -1     // 2

        for (i in 0 until (m_usb_iface?.endpointCount ?: 0)) {        // For all USB endpoints...
            val ep = m_usb_iface?.getEndpoint(i)
            if (ep?.direction == UsbConstants.USB_DIR_IN) {              // If IN
                if (m_usb_ep_in == null) {                                      // If Bulk In not set yet...
                    m_ep_in_addr = ep.address
                    mylog.d("USB-SERVICE", String.format("Bulk IN m_ep_in_addr: %d  %d", m_ep_in_addr, i))
                    m_usb_ep_in = ep                                             // Set Bulk In
                }
            } else {                                                            // Else if OUT...
                if (m_usb_ep_out == null) {                                     // If Bulk Out not set yet...
                    m_ep_out_addr = ep?.address ?: -1
                    mylog.d("USB-SERVICE", String.format("Bulk OUT m_ep_out_addr: %d  %d", m_ep_out_addr, i))
                    m_usb_ep_out = ep                                            // Set Bulk Out
                }
            }
        }
        if (m_usb_ep_in == null || m_usb_ep_out == null) {
            mylog.e("USB-SERVICE", "Unable to find bulk endpoints")
            return -1                                                      // Done error
        }

        mylog.d("USB-SERVICE", "Connected have EPs")
        return 0                                                         // Done success
    }

    private fun acc_mode_switch(conn: UsbDeviceConnection) {             // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        mylog.d("USB-SERVICE", "Attempt acc")

        var len: Int
        val buffer = ByteArray(2)
        len = conn.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, 10000)
        if (len != 2) {
            mylog.e("USB-SERVICE", "Error controlTransfer len: $len")
            return
        }
        val acc_ver = buffer[1].toInt().shl(8).toByte() or buffer[0]                      // Get OAP / ACC protocol version
        mylog.d("USB-SERVICE", "Success controlTransfer len: $len  acc_ver: $acc_ver")
        if (acc_ver < 1) {                                                  // If error or version too low...
            mylog.e("USB-SERVICE", "No support acc")
            return
        }
        mylog.d("USB-SERVICE", "acc_ver: $acc_ver")

        // Send all accessory identification strings
        usb_acc_string_send(conn, 0, "Android")            // Manufacturer
        usb_acc_string_send(conn, 1, "Android Auto")            // Model
        usb_acc_string_send(conn, 2, "Android Auto")            // desc
        usb_acc_string_send(conn, 3, "2.0.1")            // ver
        usb_acc_string_send(conn, 4, "https://forum.xda-developers.com/general/paid-software/android-4-1-headunit-reloaded-android-t3432348")            // uri
        usb_acc_string_send(conn, 5, "HU-AAAAAA001")            // serial


        mylog.d("USB-SERVICE", "Sending acc start")           // Send accessory start request. Device should re-enumerate as an accessory.
        len = conn.controlTransfer(UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, null, 0, 10000)
        if (len != 0) {
            mylog.e("USB-SERVICE", "Error acc start")
        } else {
            mylog.d("USB-SERVICE", "OK acc start. Wait to re-enumerate...")
        }
    }

    // Send one accessory identification string.    Called only by acc_mode_switch()
    private fun usb_acc_string_send(conn: UsbDeviceConnection, index: Int, string: String) {
        val buffer = (string + "\u0000").toByteArray()
        val len = conn.controlTransfer(UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, buffer, buffer.size, 10000)
        if (len != buffer.size) {
            mylog.e("USB-SERVICE", "Error controlTransfer len: $len  index: $index  string: \"$string\"")
        } else {
            mylog.d("USB-SERVICE", "Success controlTransfer len: $len  index: $index  string: \"$string\"")
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {

        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
            val filename = "hur.log"
            val file = File(Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS), filename)

            try {
                file.createNewFile()
                outputStream = FileOutputStream(file)
            } catch (E: Exception) {

            }

        }

    }

    companion object {

        private var myact: HeadunitActivity? = null
        private val USB_PID_ACC = 0x2D00      // Accessory                  100
        private val USB_PID_ACC_ADB = 0x2D01      // Accessory + ADB            110
        private val USB_VID_GOO = 0x18D1
        private val ACC_REQ_GET_PROTOCOL = 51
        private val ACC_REQ_SEND_STRING = 52
        private val ACC_REQ_START = 53
        var showplayer = false
        var showselfplayer = false
        var hasPermission = false
        var debugging = false
        var outputStream: OutputStream? = null

        fun closemyapp() {
            if (myact != null)
                myact?.finishAffinity()
        }
    }

}