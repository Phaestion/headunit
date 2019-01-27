package gb.xxy.hr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.preference.PreferenceManager
import android.util.Log

/**
 * Created by Emil on 21/05/2017.
 */

class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("HU-SERVICE", "Wifi receiver fired")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val autorun = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("start_on_boot", false)
            if (autorun) {
                val starts = Intent(context, gb.xxy.hr.new_hu_tra::class.java)
                starts.putExtra("mode", 5)
                context.startService(starts)
            }
        } else {
            val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)

            val autorun = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("wifi_autorun", false)
            val ssid = PreferenceManager.getDefaultSharedPreferences(context).getString("ssid", "")
            if (info != null)
                Log.d("HU-SERVICE", "Net state:  " + info.state.toString())
            if (info != null && autorun && info.state == NetworkInfo.State.CONNECTED) {
                Log.d("HU-SERVICE", "Pre-conditions met.  " + info.state.toString())


                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                var mySsid = wifiManager.connectionInfo.ssid
                if (mySsid.startsWith("\"") && mySsid.endsWith("\""))
                    mySsid = mySsid.substring(1, mySsid.length - 1)
                if (ssid == "" || ssid == mySsid) {
                    if (!isrunning) {
                        Log.d("HU-SERVICE", "App is not running so starting a new instance...")
                        /*
                            Intent i = new Intent(context.getPackageManager().getLaunchIntentForPackage("gb.xxy.hr"));
                            i.putExtra("wifi_autostart", "yes");
                            context.startActivity(i);
                            */
                        val starts = Intent(context, gb.xxy.hr.new_hu_tra::class.java)
                        starts.putExtra("mode", 3)
                        context.startService(starts)
                        //Add a bit of delay sometimes Wifi Connection needs 1-2 secs to be established.
                        try {
                            Thread.sleep(500)
                        } catch (E: Exception) {
                            Log.d("HU-RECEIVER", "Thread wasn't able to sleep...")
                        }

                        /*starts=new Intent(context, gb.xxy.hr.player.class);
                            starts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(starts);*/
                        isrunning = true
                        //We need to start the app
                    }
                }

            } else if (info.state == NetworkInfo.State.DISCONNECTED || info.state == NetworkInfo.State.DISCONNECTING) {
                isrunning = false
            }
        }

    }

    companion object {
        var isrunning = false
    }
}
