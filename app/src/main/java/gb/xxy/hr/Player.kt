package gb.xxy.hr

import android.app.Activity
import android.content.*
import android.content.res.Configuration
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.*
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import gb.xxy.hr.new_hu_tra.LocalBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer


/**
 * Created by Emil on 26/12/2016.
 */

class Player : Activity(), SurfaceHolder.Callback {

    private var mSurfaceView: SurfaceView? = null
    private var m_virt_vid_wid = 800.0
    private var m_virt_vid_hei = 480.0
    private val m_pwr_mgr: PowerManager? = null
    private val m_wakelock: PowerManager.WakeLock? = null
    internal var m_codec: MediaCodec? = null
    private var m_codec_buf_info: MediaCodec.BufferInfo? = null
    private var h264_wait = 0
    private var m_codec_input_bufs: Array<ByteBuffer>? = null

    private var last_touch: Long = 0

    private var connection_ok: Boolean = false
    @Volatile
    var codec_ready = false
    private var mHolder: SurfaceHolder? = null
    private var mService: new_hu_tra? = null
    private var this_player: Player? = null
    @Volatile
    internal var mBound = false
    private var usb_mode: Boolean = false
    private var wifi_direct = ""
    private var ep_in: Int = 0
    private var ep_out: Int = 0
    private var doubleBackToExitPressedOnce: Boolean = false
    private var last_possition: Int = 0

    private var m_message_receiver: message_receiver? = null

    private var swdec: Boolean = false
    private val lastframe: Long = 0


    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private val mConnection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName,
                                        service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            mylog.d("HU-SERVICE", "Service connected")
            val binder = service as LocalBinder
            mService = binder.service
            mService!!.update_mplayer(this_player)
            mBound = true
            mylog.d("HU-SERVICE", "mBound is:$mBound")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false

        }
    }


    val handler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            mylog.d("HU-SERVICE", "Handler message is..." + msg.arg2)
            if (msg.arg1 == 1)
                Toast.makeText(baseContext, resources.getString(R.string.err_noserv), Toast.LENGTH_LONG).show()
            else if (msg.arg1 == 2)
                Toast.makeText(baseContext, resources.getString(R.string.err_nowifi), Toast.LENGTH_LONG).show()
            else if (msg.arg1 == 3)
                Toast.makeText(baseContext, resources.getString(R.string.err_conterm), Toast.LENGTH_LONG).show()
            else if (msg.arg1 == 4)
                Toast.makeText(baseContext, resources.getString(R.string.err_usberr), Toast.LENGTH_LONG).show()

            if (msg.arg2 == 0) {
                mService!!.m_stopping = true
                mService!!.aap_running = false
                this_player!!.finish()
                HeadunitActivity.showplayer = false
            } else {
                mService!!.m_stopping = true
                mService!!.aap_running = false
                mService!!.stopSelf()
                this_player!!.finish()
                try {
                    HeadunitActivity.closemyapp()
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }

                android.os.Process.killProcess(android.os.Process.myPid())
            }
            /*mService.stopSelf();
                finishAffinity();
                //this_player.finish();
                //android.os.Process.killProcess(android.os.Process.myPid());
                mService.stopSelf();
                this_player.finish();*/
        }
    }


    //LifeCycle Function
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mylog.d("HU-SERVICE", "Prevent onDestroy being called due to orientation and other changes....")
    }

    override fun onBackPressed() {
        mylog.d("HU-SERVICE", "Back arrow button was pressed...")
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            HeadunitActivity.showplayer = false
            connection_ok = false
            mService!!.byebye_send()
            mService!!.m_stopping = true

            codec_ready = false
            try {
                unregisterReceiver(mService!!.wifi_receiver)
            } catch (e: Exception) {

            }

            try {
                mService!!.mySensorManager.unregisterListener(mService!!.LightSensorListener)
            } catch (e: Exception) {

            }

            try {
                mService!!.locationManager.removeUpdates(mService!!.listener)
            } catch (e: Exception) {

            }

            if (mService!!.mode == 3) {
                mService!!.stopSelf()
                finish()
                android.os.Process.killProcess(android.os.Process.myPid())
            }

            return
        }

        this.doubleBackToExitPressedOnce = true
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show()

        Handler().postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
    }

    override fun onResume() {
        super.onResume()
        mylog.d("HU-SERVICE", "Player on Resume")
    }

    override fun onRestart() {
        super.onRestart()
        mylog.d("HU-SERVICE", "Player on Restart")
    }

    override fun onStop() {
        super.onStop()
        mylog.d("HU-SERVICE", "Player on Stop")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(m_message_receiver)
        mylog.d("HU-SERVICE", "Player on Destory")
        WifiReceiver.isrunning = false
    }

    override fun onPause() {
        super.onPause()

        mylog.d("HU-SERVICE", "Player on Pause")
        try {
            mService!!.update_mplayer(null)
            unbindService(mConnection)
        } catch (E: Exception) {

        }

        //finish();
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mylog.d("HU-SERVICE", "Player on Create")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player)
        this_player = this
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        //Check the required Video size
        val SP = PreferenceManager.getDefaultSharedPreferences(this)
        val hires = SP.getBoolean("hires", false)
        swdec = SP.getBoolean("h264", false)

        if (hires) {
            m_virt_vid_wid = 1280.0
            m_virt_vid_hei = 720.0
        }
        usb_mode = intent.hasExtra("USB")
        if (usb_mode) {
            ep_in = intent.getIntExtra("ep_in", 0)
            ep_out = intent.getIntExtra("ep_out", 0)

        }
        if (intent.hasExtra("wifi_direct"))
            wifi_direct = intent.getStringExtra("wifi_direct_ip")

        mSurfaceView = findViewById<View>(R.id.surfaceView) as SurfaceView
        mSurfaceView!!.visibility = View.VISIBLE


        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("stretch_full", true)) {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            var width = displayMetrics.widthPixels
            val original_height = displayMetrics.heightPixels
            mylog.d("HU-SERVICE", "Surface size is: $width")
            var aspect_ratio = 1.66.toFloat()
            if (m_virt_vid_wid != 800.0)
                aspect_ratio = 1.77.toFloat()
            var height = (width / aspect_ratio).toInt()
            mylog.d("HU-SERVICE", "Original height: " + original_height + "needed height:" + height)
            if (height > original_height)
            // we have some weired screen ratio, need to letter box it other way around...
            {
                height = original_height
                width = (height * aspect_ratio).toInt()
            }
            mylog.d("HU-SERVICE", "Height: " + height + "width:" + width)
            mSurfaceView!!.layoutParams = FrameLayout.LayoutParams(width, height, 17)
        }
        mSurfaceView!!.holder.addCallback(this)
        mSurfaceView!!.setOnTouchListener { v, event ->
            touch_send(event)
            true
        }
        val intent = Intent(this, new_hu_tra::class.java)
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ADJUST_WITH_ACTIVITY)


        val thread = object : Thread() {
            override fun run() {
                mylog.d("HU-SERVICE", "Player thread started....")
                while (!mBound || !codec_ready) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                }

                mylog.d("HU-SERVICE", "Bond to service....")
                if (!mService!!.aap_running) {
                    if (!usb_mode && wifi_direct.isEmpty()) {
                        val cm = baseContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val activeNetwork = cm.activeNetworkInfo
                        val isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting

                        if (!isConnected) {
                            mylog.d("HU-SERVICE", "Player considers itself not connected....")
                            val msg = handler.obtainMessage()
                            msg.arg1 = 2
                            msg.arg2 = 0
                            handler.sendMessage(msg)
                            return
                        }

                        val wifii = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val d = wifii.dhcpInfo
                        mylog.d("HU-Service", "GateWay is: " + intToIp(d.gateway) + " IP setting is:" + PreferenceManager.getDefaultSharedPreferences(baseContext).getString("ip", ""))
                        if (PreferenceManager.getDefaultSharedPreferences(baseContext).getString("ip", "")!!.length == 0)
                            mService!!.jni_aap_start(intToIp(d.gateway), 0, 0)
                        else
                            mService!!.jni_aap_start(PreferenceManager.getDefaultSharedPreferences(baseContext).getString("ip", ""), 0, 0)

                    } else if (!usb_mode && !wifi_direct.isEmpty())
                        mService!!.jni_aap_start(wifi_direct, 0, 0)
                    else {

                        mService!!.jni_aap_start("", ep_out, ep_in)
                    }


                } else {

                    val data = byteArrayOf(0x03, 0x80.toByte(), 0x08, 0x08, 0x01, 0x10, 0x01)
                    val tosend = ByteArray(11)
                    val nextchunk = ByteBuffer.allocate(4).putInt(7).array()
                    System.arraycopy(nextchunk, 0, tosend, 0, 4)
                    System.arraycopy(data, 0, tosend, 4, 7)
                    //mylog.d("HU-SERVICE","video start");
                    mService!!.send_que.write(tosend, 0, tosend.size)


                }

            }
        }

        thread.start()


        WifiReceiver.isrunning = true
        val filter = IntentFilter()
        filter.addAction("gb.xxy.hr.sendmessage")
        m_message_receiver = message_receiver()
        registerReceiver(m_message_receiver, filter)

    }

    //SurfaceView
    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(sLock) {
            if (m_codec != null) {
                mylog.d("HU-SERVICE", "Codec is running")
                return
            }
        }
        mHolder = holder
        codec_init()

        GlobalScope.launch(Dispatchers.IO) {
            delay(10000)

            GlobalScope.launch(Dispatchers.Main) {
                Log.d("Steyn", "Broadcasting playpause")
                sendBroadcast(Intent("gb.xxy.hr.playpause"))
            }
        }
    }


    override fun surfaceChanged(holder: SurfaceHolder, format_2: Int, width: Int, height: Int) {


    }


    override fun surfaceDestroyed(holder: SurfaceHolder) {

        val data = byteArrayOf(0x03, 0x80.toByte(), 0x08, 0x08, 0x02, 0x10, 0x01)
        val tosend = ByteArray(11)
        val nextchunk = ByteBuffer.allocate(4).putInt(7).array()
        System.arraycopy(nextchunk, 0, tosend, 0, 4)
        System.arraycopy(data, 0, tosend, 4, 7)
        //mylog.d("HU-SERVICE","Sendq surface destroy");
        mService!!.send_que.write(tosend, 0, tosend.size)


        codec_ready = false
        if (m_codec != null)
            try {
                m_codec!!.flush()
                m_codec!!.stop()
                m_codec = null
            } catch (E: Exception) {
                throw E
            }

        try {
            holder.removeCallback(this)
            holder.surface.release()
        } catch (E: Exception) {
            throw E
        }

    }


    //Here we start with our own functions
    fun codec_init() {
        synchronized(sLock) {
            mylog.d("HU-SERVICE", "surfaceChanged called: ")
            val displaymetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displaymetrics)
            var height = displaymetrics.heightPixels
            val width = displaymetrics.widthPixels

            if (height > 1080) {                                              // Limit surface height to 1080 or N7 2013 won't work: width: 1920  height: 1104    Screen 1200x1920, nav = 96 pixels
                mylog.e("HU-SERVICE", "height: $height")
                height = 1080
            }


            mylog.d("HU-SERVICE", "Setting up media Player")


            try {
                if (!swdec)
                    m_codec = MediaCodec.createDecoderByType("video/avc")       // Create video codec: ITU-T H.264 / ISO/IEC MPEG-4 Part 10, Advanced Video Coding (MPEG-4 AVC)
                else {
                    m_codec = MediaCodec.createByCodecName("OMX.google.h264.decoder")
                    h264_wait = 10000
                    mylog.d("HU-VIDEO", "Using Software decoding.... might be slow...")
                }
                m_codec_buf_info = MediaCodec.BufferInfo()                         // Create Buffer Info
                val format = MediaFormat.createVideoFormat("video/avc", width, height)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 655360)
                m_codec!!.configure(format, mHolder!!.surface, null, 0)               // Configure codec for H.264 with given width and height, no crypto and no flag (ie decode)
                m_codec!!.start()                                             // Start codec
                codec_ready = true
                //Thread videothread = new Thread(videoplayback, "Playback_audio 0");
                //videothread.start();

            } catch (t: Throwable) {
                mylog.e("HU-SERVICE", "Throwable: $t")
            }

        }
    }


    fun sys_ui_hide() {
        val m_ll_main = findViewById<View>(R.id.ll_main)
        if (m_ll_main != null)
            m_ll_main.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar

                    or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar

                    or View.SYSTEM_UI_FLAG_IMMERSIVE)//_STICKY);
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        mylog.d("HU-SERVICE", "Focus changed!")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            if (hasFocus) {
                sys_ui_hide()

            }
        }
    }

    private fun touch_send(event: MotionEvent) {


        val x = (event.getX(0) / (mSurfaceView!!.width / m_virt_vid_wid)).toInt()
        val y = (event.getY(0) / (mSurfaceView!!.height / m_virt_vid_hei)).toInt()

        if (x < 0 || y < 0 || x >= 65535 || y >= 65535) {   // Infinity if vid_wid_get() or vid_hei_get() return 0
            mylog.e("HU-SERVICE", "Invalid x: $x  y: $y")
            return
        }

        var aa_action: Byte = 0
        val me_action = event.actionMasked
        when (me_action) {
            MotionEvent.ACTION_DOWN -> {
                mylog.d("HU-SERVICE", "event: $event (ACTION_DOWN)    x: $x  y: $y")
                aa_action = 0
            }
            MotionEvent.ACTION_MOVE -> {
                mylog.d("HU-SERVICE", "event: $event (ACTION_MOVE)    x: $x  y: $y")
                aa_action = 2
            }
            MotionEvent.ACTION_CANCEL -> {
                mylog.d("HU-SERVICE", "event: $event (ACTION_CANCEL)  x: $x  y: $y")
                aa_action = 1
            }
            MotionEvent.ACTION_UP -> {
                mylog.d("HU-SERVICE", "event: $event (ACTION_UP)      x: $x  y: $y")
                aa_action = 1
            }
            else -> {
                mylog.e("HU-SERVICE", "event: $event (Unknown: $me_action)  x: $x  y: $y")
                return
            }
        }
        if (mBound) {
            mService!!.touch_send(aa_action, x, y, event.eventTime * 1000000L)
            last_touch = event.eventTime
            //We need to keep track of the last touch event for SelfMode, otherwise it will generate onUserLeaveHint when dialing!
        }

    }


    private fun codec_input_provide(content: ByteBuffer): Boolean {            // Called only by media_decode() with new NAL unit in Byte Buffer

        try {
            val index = m_codec!!.dequeueInputBuffer(3000000)           // Get input buffer with 3 second timeout
            if (index < 0) {
                mylog.e("HU-VIDEO", "No input buffer available...")
                /*if (h264_wait>0)
                {*/
                m_codec!!.flush()
                m_codec_input_bufs = null
                m_codec_buf_info = null
                m_codec_buf_info = MediaCodec.BufferInfo()
                val data = byteArrayOf(0x03, 0x80.toByte(), 0x08, 0x08, 0x02, 0x10, 0x01)
                val tosend = ByteArray(11)
                val nextchunk = ByteBuffer.allocate(4).putInt(7).array()
                System.arraycopy(nextchunk, 0, tosend, 0, 4)
                System.arraycopy(data, 0, tosend, 4, 7)
                //mylog.d("HU-SERVICE","Sendq surface destroy");
                mService!!.send_que.write(tosend, 0, tosend.size)
                tosend[8] = 1
                Thread.sleep(200)
                mService!!.send_que.write(tosend, 0, tosend.size)
                return false                                                 // Done with "No buffer" error
                /* }*/
            }

            if (m_codec_input_bufs == null) {
                m_codec_input_bufs = m_codec!!.inputBuffers                // Set m_codec_input_bufs if needed
            }

            val buffer = m_codec_input_bufs!![index]
            val capacity = buffer.capacity()
            buffer.clear()


            if (content.limit() <= capacity) {                           // If we can just put() the content...
                buffer.put(content)                                           // Put the content
            } else {                                                            // Else... (Should not happen ?)
                val limit = content.limit()
                content.limit(content.position() + capacity)                 // Temporarily set constrained limit
                buffer.put(content)
                content.limit(limit)                                          // Restore original limit
            }
            buffer.flip()                                                   // Flip buffer for reading
            m_codec!!.queueInputBuffer(index, 0, buffer.limit(), 0, 0)       // Queue input buffer for decoding w/ offset=0, size=limit, no microsecond timestamp and no flags (not end of stream)
            return true                                                    // Processed
        } catch (t: Throwable) {
            mylog.e("HU-SERVICE", "Throwable: $t")
        }

        return false                                                     // Error: exception
    }

    private fun codec_output_consume() {                                // Called only by media_decode() after codec_input_provide()
        //mylog.d("HU-SERVICE","");
        var index = -777
        while (true) {                                                          // Until no more buffers...

            m_codec_buf_info?.let {
                index = m_codec?.dequeueOutputBuffer(it, h264_wait.toLong()) ?: -777
            }
            // Dequeue an output buffer but do not wait
            if (index >= 0) {
                m_codec?.releaseOutputBuffer(index, true /*render*/)           // Return the buffer to the codec

                //mService.lastframe=System.currentTimeMillis();
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)
            // See this 1st shortly after start. API >= 21: Ignore as getOutputBuffers() deprecated
                mylog.d("HU-SERVICE", "INFO_OUTPUT_BUFFERS_CHANGED: ")
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            // See this 2nd shortly after start. Output format changed for subsequent data. See getOutputFormat()
            {
                mylog.d("HU-SERVICE", "INFO_OUTPUT_FORMAT_CHANGED:")

            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // mylog.e("HU-VIDEO","Timed out....");
                break

            } else
                break
        }
        if (index != MediaCodec.INFO_TRY_AGAIN_LATER)
            mylog.e("HU-SERVICE", "index: $index")
    }


    fun media_decode(buffer: ByteArray, size: Int) {                       // Decode audio or H264 video content. Called only by video_test() & hu_tra.aa_cmd_send()


        // synchronized (sLock) {
        val content = ByteBuffer.wrap(buffer, 0, size)

        while (content.hasRemaining()) {                                 // While there is remaining content...

            if (!codec_input_provide(content)) {                          // Process buffer; if no available buffers...
                mylog.e("HU-SERVICE", "Dropping content because there are no available buffers.")
                content.clear()
            }
            if (content.hasRemaining())
            // Never happens now
                mylog.e("HU-SERVICE", "content.hasRemaining ()")

            codec_output_consume()                                        // Send result to video codec
        }
        // }

    }


    fun intToIp(addr: Int): String {
        var addr = addr

        val first = (addr and 0xFF).toString()

        val second = addr.ushr(8) and 0xFF
        addr = addr.ushr(8)

        val third = addr.ushr(8) and 0xFF
        addr = addr.ushr(8)

        val fourth = addr.ushr(8) and 0xFF

        return "$first.$second.$third.$fourth"
    }


    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {

        val ts = android.os.SystemClock.elapsedRealtime() * 1000000L
        val SP = PreferenceManager.getDefaultSharedPreferences(this)
        var hires = SP.getBoolean("hires", false)
        if (hires && SP.getBoolean("h264", false))
            hires = false
        if (SP.getBoolean("non_st_k", false)) {
            val playb = Integer.parseInt(SP.getString("cust_key_play", "0")!!)
            val nextb = Integer.parseInt(SP.getString("cust_key_next", "0")!!)
            val prevb = Integer.parseInt(SP.getString("cust_key_prev", "0")!!)
            val micb = Integer.parseInt(SP.getString("cust_key_mic", "0")!!)
            val phoneb = Integer.parseInt(SP.getString("cust_key_phone", "0")!!)


            if (keyCode == nextb) {
                mService!!.key_send(0x57, 1, ts)
                mService!!.key_send(0x57, 0, ts + 200)

                return true
            } else if (keyCode == prevb) {
                mService!!.key_send(0x58, 1, ts)
                mService!!.key_send(0x58, 0, ts + 200)

                return true
            } else if (keyCode == playb) {
                mService!!.key_send(0x55, 1, ts)
                mService!!.key_send(0x55, 0, ts + 200)
                return true
            } else if (keyCode == micb) {
                mService!!.key_send(0x54, 1, ts)
                mService!!.key_send(0x54, 0, ts + 200)
                return true
            } else if (keyCode == phoneb) {
                mService!!.key_send(0x05, 1, ts)
                mService!!.key_send(0x05, 0, ts + 200)
                return true
            }


        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                mService!!.key_send(0x18, 1, ts)
                mService!!.key_send(0x18, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                mService!!.key_send(0x19, 1, ts)
                mService!!.key_send(0x19, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                mService!!.key_send(0x57, 1, ts)
                mService!!.key_send(0x57, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                mService!!.key_send(0x58, 1, ts)
                mService!!.key_send(0x58, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                mService!!.key_send(0x55, 1, ts)
                mService!!.key_send(0x55, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                mService!!.key_send(0x55, 1, ts)
                mService!!.key_send(0x55, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                mService!!.key_send(0x55, 1, ts)
                mService!!.key_send(0x55, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                mService!!.key_send(0x56, 1, ts)
                mService!!.key_send(0x56, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                mService!!.key_send(0x59, 1, ts)
                mService!!.key_send(0x59, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_CALL -> {
                mService!!.key_send(0x05, 1, ts)
                mService!!.key_send(0x05, 0, ts + 200)

                last_possition = 2
                return true
            }
            KeyEvent.KEYCODE_F // SAME AS CALL CONVENIENCE BUTTON
            -> {
                mService!!.key_send(0x05, 1, ts)
                mService!!.key_send(0x05, 0, ts + 200)

                last_possition = 2
                return true
            }
            KeyEvent.KEYCODE_M //mic
            -> {
                mService!!.key_send(0x54, 1, ts)
                mService!!.key_send(0x54, 0, ts + 200)

                return true
            }
            KeyEvent.KEYCODE_H //home screen (middle)
            -> {
                mService!!.key_send(0x03, 1, ts)
                mService!!.key_send(0x03, 0, ts + 200)

                last_possition = 3
                return true
            }
            KeyEvent.KEYCODE_D //home screen (middle){0x80, 0x03, 0x52, 2, 8, cmd_buf[1]};
            -> {
                mService!!.night_toggle(0)
                return true
            }
            KeyEvent.KEYCODE_T //home screen (middle){0x80, 0x03, 0x52, 2, 8, cmd_buf[1]};
            -> {
                if (!mService!!.isnightset) {
                    mService!!.night_toggle(1)
                    mService!!.isnightset = true
                } else {
                    mService!!.night_toggle(0)
                    mService!!.isnightset = false
                }
                return true
            }
            KeyEvent.KEYCODE_N //home screen (middle){0x80, 0x03, 0x52, 2, 8, cmd_buf[1]};
            -> {
                mService!!.night_toggle(1)
                return true
            }
            /*
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mService.key_send(21,1,ts);
                mService.key_send(21,0,ts+200);
                return true;
          case KeyEvent.KEYCODE_DPAD_RIGHT:
                mService.key_send(22,1,ts);
                mService.key_send(22,0,ts+200);
                return true;
          case KeyEvent.KEYCODE_DPAD_UP:
                mService.key_send(19,1,ts);
                mService.key_send(19,0,ts+200);
                return true;
          case KeyEvent.KEYCODE_DPAD_DOWN:
                mService.key_send(20,1,ts);
                mService.key_send(20,0,ts+200);
                return true;
          case KeyEvent.KEYCODE_SOFT_LEFT:
                mService.key_send(1,1,ts);
                mService.key_send(1,0,ts+200);
                return true;
            case KeyEvent.KEYCODE_SOFT_RIGHT:
                mService.key_send(2,1,ts);
                mService.key_send(2,0,ts+200);
                return true;
*/

            KeyEvent.KEYCODE_1 -> {
                last_possition = 1
                if (hires) {
                    mService!!.touch_send(0.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                } else {
                    mService!!.touch_send(0.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                }
                return true
            }
            KeyEvent.KEYCODE_4 -> {
                last_possition = 4
                if (hires) {
                    mService!!.touch_send(0.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                } else {
                    mService!!.touch_send(0.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (last_possition == 0)
                    last_possition = 4
                if (last_possition > 0) {
                    last_possition--
                    if (hires) {
                        mService!!.touch_send(0.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L)
                        mService!!.touch_send(1.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                    } else {
                        mService!!.touch_send(0.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L)
                        mService!!.touch_send(1.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (last_possition == 4)
                    last_possition = 0
                if (last_possition < 4) {
                    last_possition++
                    if (hires) {
                        mService!!.touch_send(0.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L)
                        mService!!.touch_send(1.toByte(), (last_possition - 1) * 300 + 50, 700, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                    } else {
                        mService!!.touch_send(0.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L)
                        mService!!.touch_send(1.toByte(), (last_possition - 1) * 200 + 10, 460, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                    }
                }
                if (hires) {
                    mService!!.touch_send(0.toByte(), 50, 200, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 200, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                } else {
                    mService!!.touch_send(0.toByte(), 50, 120, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 120, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (hires) {
                    mService!!.touch_send(0.toByte(), 50, 200, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 200, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                } else {
                    mService!!.touch_send(0.toByte(), 50, 120, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 120, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (hires) {
                    mService!!.touch_send(0.toByte(), 50, 550, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 550, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                } else {
                    mService!!.touch_send(0.toByte(), 50, 370, android.os.SystemClock.elapsedRealtime() * 1000000L)
                    mService!!.touch_send(1.toByte(), 50, 370, android.os.SystemClock.elapsedRealtime() * 1000000L + 100)
                }
                return true
            }


            else -> return super.onKeyUp(keyCode, event)
        }

    }

    private inner class message_receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val message = intent.getStringExtra("message")
            mylog.d("HU-RECEIVER", "Message: $message")
            val mynot = GenericMessage.GenericMessageNotification.newBuilder()
            mynot.messageId = "01"
            mynot.messageBoddy = "Test notification"


            val my_len = message.length

            val data = ByteArray(my_len / 2)

            var mi = 0
            while (mi < my_len) {
                data[mi / 2] = ((Character.digit(message[mi], 16) shl 4) + Character.digit(message[mi + 1], 16)).toByte()
                mi += 2
            }
            mService!!.aa_cmd_send(data.size, data, 0, null, "")


        }
    }

    companion object {
        private val sLock = Any()
    }


}