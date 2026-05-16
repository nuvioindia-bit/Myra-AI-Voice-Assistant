package com.myra.assistant.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.model.AppCommand
import com.myra.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    const val TAG = "MainViewModel"
    val APP_PACKAGE_MAP = mapOf(
      "youtube" to "com.google.android.youtube",
      "whatsapp" to "com.whatsapp",
      "instagram" to "com.instagram.android",
      "facebook" to "com.facebook.katana",
      "chrome" to "com.android.chrome",
      "gmail" to "com.google.android.gm",
      "maps" to "com.google.android.apps.maps",
      "spotify" to "com.spotify.music",
      "netflix" to "com.netflix.mediaclient",
      "twitter" to "com.twitter.android",
      "x" to "com.twitter.android",
      "telegram" to "org.telegram.messenger",
      "snapchat" to "com.snapchat.android",
      "settings" to "com.android.settings",
      "calculator" to "com.google.android.calculator",
      "calendar" to "com.google.android.calendar",
      "clock" to "com.google.android.deskclock",
      "phone" to "com.android.dialer",
      "contacts" to "com.android.contacts",
      "play store" to "com.android.vending",
      "amazon" to "in.amazon.mShop.android.shopping",
      "flipkart" to "com.flipkart.android",
      "paytm" to "net.one97.paytm",
      "phonepe" to "com.phonepe.app",
      "gpay" to "com.google.android.apps.nbu.paisa.user",
      "zoom" to "us.zoom.videomeetings",
      "meet" to "com.google.android.apps.meetings",
      "teams" to "com.microsoft.teams",
      "tiktok" to "com.zhiliaoapp.musically",
      "discord" to "com.discord",
      "linkedin" to "com.linkedin.android"
    )
  }

  private val _commandResult = MutableLiveData<String?>()
  val commandResult: LiveData<String?> = _commandResult

  private val context = application.applicationContext
  private val contentResolver: ContentResolver = context.contentResolver

  fun executeCommand(command: AppCommand) {
    viewModelScope.launch(Dispatchers.IO) {
      val result = when (command.type) {
        "OPEN_APP" -> openApp(command.params["app_name"] ?: "")
        "CLOSE_APP" -> closeApp()
        "CALL" -> makeCall(command.params["name"] ?: "", command.params["number"] ?: "")
        "SMS" -> sendSms(command.params["name"] ?: "", command.params["message"] ?: "")
        "WHATSAPP_MSG" -> sendWhatsApp(command.params["name"] ?: "", command.params["message"] ?: "")
        "PRIME_CALL" -> {
          val index = (command.params["index"] ?: "0").toIntOrNull() ?: 0
          makePrimeCall(index)
        }
        "PRIME_MSG" -> {
          val index = (command.params["index"] ?: "0").toIntOrNull() ?: 0
          sendPrimeSms(index)
        }
        "VOLUME_UP" -> volumeUp()
        "VOLUME_DOWN" -> volumeDown()
        "FLASHLIGHT_ON" -> "Flashlight toggle requires camera permission"
        "FLASHLIGHT_OFF" -> "Flashlight toggle requires camera permission"
        "WIFI_ON" -> toggleWifi(true)
        "WIFI_OFF" -> toggleWifi(false)
        "BLUETOOTH_ON" -> toggleBluetooth(true)
        "BLUETOOTH_OFF" -> toggleBluetooth(false)
        else -> "Unknown command: ${command.type}"
      }
      withContext(Dispatchers.Main) {
        _commandResult.value = result
      }
    }
  }

  private fun openApp(appName: String): String {
    val key = appName.lowercase().trim()
    val packageName = APP_PACKAGE_MAP[key]
    return if (packageName != null) {
      val intent = context.packageManager.getLaunchIntentForPackage(packageName)
      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Opened $appName"
      } else {
        tryFallbackScan(key)
      }
    } else {
      tryFallbackScan(key)
    }
  }

  private fun tryFallbackScan(appName: String): String {
    val packages = context.packageManager.getInstalledApplications(0)
    val match = packages.find {
      context.packageManager.getApplicationLabel(it).toString().lowercase().contains(appName)
    }
    return if (match != null) {
      val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
      intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
      "Opened ${context.packageManager.getApplicationLabel(match)}"
    } else {
      "App '$appName' not found"
    }
  }

  private fun closeApp(): String {
    if (!AccessibilityHelperService.isEnabled(context)) {
      return "Accessibility service is disabled. Please enable it in Settings."
    }
    AccessibilityHelperService.instance?.closeCurrentApp()
    return "Closed current app"
  }

  private fun makeCall(name: String, number: String): String {
    val resolvedNumber = if (number.isNotBlank()) number else resolveContactNumber(name)
    return if (resolvedNumber != null) {
      val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      "Calling $name"
    } else {
      "Contact '$name' not found"
    }
  }

  private fun sendSms(name: String, message: String): String {
    val number = resolveContactNumber(name)
    return if (number != null) {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$number")).apply {
        putExtra("sms_body", message)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      "Opening SMS to $name"
    } else {
      "Contact '$name' not found"
    }
  }

  private fun sendWhatsApp(name: String, message: String): String {
    val number = resolveContactNumber(name)
    return if (number != null) {
      val cleanNumber = number.replace("[^0-9]".toRegex(), "")
      val url = "https://wa.me/$cleanNumber?text=${Uri.encode(message)}"
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      "Opening WhatsApp to $name"
    } else {
      "Contact '$name' not found"
    }
  }

  private fun makePrimeCall(index: Int): String {
    val prefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
    val json = prefs.getString("prime_contacts_json", null)
    return if (json != null) {
      try {
        val array = org.json.JSONArray(json)
        if (index < array.length()) {
          val contact = array.getJSONObject(index)
          val name = contact.getString("name")
          val number = contact.getString("number")
          makeCall(name, number)
        } else {
          "Prime contact not found at index $index"
        }
      } catch (e: Exception) {
        "Error reading prime contacts"
      }
    } else {
      // Legacy fallback
      val name = prefs.getString("prime_name", null)
      val number = prefs.getString("prime_number", null)
      if (name != null && number != null) {
        makeCall(name, number)
      } else {
        "No prime contacts configured"
      }
    }
  }

  private fun sendPrimeSms(index: Int): String {
    val prefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
    val json = prefs.getString("prime_contacts_json", null)
    return if (json != null) {
      try {
        val array = org.json.JSONArray(json)
        if (index < array.length()) {
          val contact = array.getJSONObject(index)
          val name = contact.getString("name")
          val number = contact.getString("number")
          sendSms(name, "")
        } else {
          "Prime contact not found at index $index"
        }
      } catch (e: Exception) {
        "Error reading prime contacts"
      }
    } else {
      "No prime contacts configured"
    }
  }

  private fun volumeUp(): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    return "Volume increased"
  }

  private fun volumeDown(): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    return "Volume decreased"
  }

  private fun toggleWifi(enable: Boolean): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    wifiManager.isWifiEnabled = enable
    return if (enable) "WiFi turned ON" else "WiFi turned OFF"
  }

  private fun toggleBluetooth(enable: Boolean): String {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    return if (adapter != null) {
      if (enable) {
        adapter.enable()
        "Bluetooth turned ON"
      } else {
        adapter.disable()
        "Bluetooth turned OFF"
      }
    } else {
      "Bluetooth not available"
    }
  }

  fun acceptCall() {
    try {
      val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
      telecomManager.acceptRingingCall()
      _commandResult.value = "Call accepted"
    } catch (e: Exception) {
      Log.e(TAG, "Error accepting call", e)
      _commandResult.value = "Failed to accept call"
    }
  }

  fun rejectCall() {
    try {
      val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
      telecomManager.endCall()
      _commandResult.value = "Call rejected"
    } catch (e: Exception) {
      Log.e(TAG, "Error rejecting call", e)
      _commandResult.value = "Failed to reject call"
    }
  }

  private fun resolveContactNumber(name: String): String? {
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.NUMBER,
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
    )
    val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("%$name%")
    var cursor: Cursor? = null
    try {
      cursor = contentResolver.query(uri, projection, selection, args, null)
      cursor?.use {
        if (it.moveToFirst()) {
          return it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error resolving contact", e)
    }
    return null
  }
}
