package com.asterleen.provodach

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils

import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager


class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }
}


class SettingsActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {

        when (key) {
            "use_hls" -> {
                val hlsStateIntent = Intent(ACTION_UPDATE_HLS);
                hlsStateIntent.putExtra("use_hls", sharedPreferences.getBoolean(key, false))
                sendBroadcast(hlsStateIntent)
            }
            "user_name" -> {
                val userNameIntent = Intent(ACTION_UPDATE_NAME);
                userNameIntent.putExtra("user_name", sharedPreferences.getString(key, ""))
                sendBroadcast(userNameIntent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    companion object {
        const val ACTION_UPDATE_HLS = "com.asterleen.provodach.prefs.UPDATE_HLS"
        const val ACTION_UPDATE_NAME = "com.asterleen.provodach.prefs.UPDATE_NAME"
    }

}