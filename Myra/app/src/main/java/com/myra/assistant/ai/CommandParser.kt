package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand

object CommandParser {

  fun parse(text: String): AppCommand? {
    val lower = text.lowercase()

    // Open app commands
    val openPatterns = listOf(
      "open (\\w+)".toRegex(),
      "(\\w+) kholo".toRegex(),
      "(\\w+) open karo".toRegex(),
      "launch (\\w+)".toRegex()
    )
    for (pattern in openPatterns) {
      val match = pattern.find(lower)
      if (match != null) {
        val appName = match.groupValues[1]
        return AppCommand("OPEN_APP", mapOf("app_name" to appName))
      }
    }

    // Close app commands
    if (lower.contains("close") || lower.contains("band karo") || lower.contains("exit")) {
      val appName = extractAppName(lower)
      return AppCommand("CLOSE_APP", mapOf("app_name" to (appName ?: "")))
    }

    // Call commands
    val callPatterns = listOf(
      "call ([\\w\\s]+)".toRegex(),
      "([\\w\\s]+) ko call karo".toRegex(),
      "phone karo ([\\w\\s]+)".toRegex(),
      "dial ([\\w\\s]+)".toRegex()
    )
    for (pattern in callPatterns) {
      val match = pattern.find(lower)
      if (match != null) {
        val name = match.groupValues[1].trim()
        return AppCommand("CALL", mapOf("name" to name, "number" to ""))
      }
    }

    // SMS commands
    val smsPatterns = listOf(
      "send sms to ([\\w\\s]+)".toRegex(),
      "sms bhejo ([\\w\\s]+) ko".toRegex(),
      "message ([\\w\\s]+)".toRegex(),
      "text ([\\w\\s]+)".toRegex()
    )
    for (pattern in smsPatterns) {
      val match = pattern.find(lower)
      if (match != null) {
        val name = match.groupValues[1].trim()
        return AppCommand("SMS", mapOf("name" to name, "message" to ""))
      }
    }

    // WhatsApp commands
    val whatsAppPatterns = listOf(
      "whatsapp ([\\w\\s]+)".toRegex(),
      "whatsapp karo ([\\w\\s]+)".toRegex(),
      "wa msg ([\\w\\s]+)".toRegex()
    )
    for (pattern in whatsAppPatterns) {
      val match = pattern.find(lower)
      if (match != null) {
        val name = match.groupValues[1].trim()
        return AppCommand("WHATSAPP_MSG", mapOf("name" to name, "message" to ""))
      }
    }

    // Prime contact commands
    if (lower.contains("close friend") || lower.contains("prime contact") || lower.contains("first contact")) {
      return if (lower.contains("call"))
        AppCommand("PRIME_CALL", mapOf("index" to "0"))
      else if (lower.contains("message") || lower.contains("sms") || lower.contains("msg"))
        AppCommand("PRIME_MSG", mapOf("index" to "0"))
      else null
    }
    if (lower.contains("my love") || lower.contains("meri jaan") || lower.contains("jaan")) {
      return if (lower.contains("call"))
        AppCommand("PRIME_CALL", mapOf("index" to "0"))
      else if (lower.contains("message") || lower.contains("sms") || lower.contains("msg"))
        AppCommand("PRIME_MSG", mapOf("index" to "0"))
      else null
    }
    if (lower.contains("second contact")) {
      return if (lower.contains("call"))
        AppCommand("PRIME_CALL", mapOf("index" to "1"))
      else if (lower.contains("message") || lower.contains("sms"))
        AppCommand("PRIME_MSG", mapOf("index" to "1"))
      else null
    }

    // Volume commands
    if (lower.contains("volume up") || lower.contains("volume badhao") || lower.contains("sound badhao"))
      return AppCommand("VOLUME_UP")
    if (lower.contains("volume down") || lower.contains("volume kam") || lower.contains("sound kam"))
      return AppCommand("VOLUME_DOWN")

    // Flashlight commands
    if (lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("torch on karo"))
      return AppCommand("FLASHLIGHT_ON")
    if (lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("torch band"))
      return AppCommand("FLASHLIGHT_OFF")

    // WiFi commands
    if (lower.contains("wifi on") || lower.contains("wi-fi on") || lower.contains("wifi on karo"))
      return AppCommand("WIFI_ON")
    if (lower.contains("wifi off") || lower.contains("wi-fi off") || lower.contains("wifi band"))
      return AppCommand("WIFI_OFF")

    // Bluetooth commands
    if (lower.contains("bluetooth on") || lower.contains("bluetooth on karo"))
      return AppCommand("BLUETOOTH_ON")
    if (lower.contains("bluetooth off") || lower.contains("bluetooth band"))
      return AppCommand("BLUETOOTH_OFF")

    return null
  }

  private fun extractAppName(text: String): String? {
    val patterns = listOf(
      "close (\\w+)".toRegex(),
      "(\\w+) band karo".toRegex(),
      "exit (\\w+)".toRegex()
    )
    for (pattern in patterns) {
      val match = pattern.find(text)
      if (match != null) return match.groupValues[1]
    }
    return null
  }
}
