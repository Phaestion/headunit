package gb.xxy.hr

import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.PreferenceActivity
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.view.View

class PrefrenceActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)

        val itemList = findPreference("lightsens") as ListPreference
        val itemList2 = findPreference("luxval") as EditTextPreference
        val itemList3 = findPreference("luxsensibility") as EditTextPreference
        if (itemList.findIndexOfValue(itemList.value) != 0) {
            itemList2.isEnabled = false
            itemList3.isEnabled = false
        }
        itemList.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val `val` = newValue.toString()
            val index = itemList.findIndexOfValue(`val`)
            if (index == 0) {
                itemList2.isEnabled = true
                itemList3.isEnabled = true
            } else {
                itemList2.isEnabled = false
                itemList3.isEnabled = false
            }
            true
        }
    }

    fun exitpref(v: View) {
        this.finish()
    }
}