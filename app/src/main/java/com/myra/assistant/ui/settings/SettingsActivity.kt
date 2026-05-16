package com.myra.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.service.AccessibilityHelperService
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

  private lateinit var apiKeyInput: EditText
  private lateinit var userNameInput: EditText
  private lateinit var modelSpinner: Spinner
  private lateinit var voiceSpinner: Spinner
  private lateinit var personalityGroup: RadioGroup
  private lateinit var primeContactsRecycler: RecyclerView
  private lateinit var addPrimeBtn: Button
  private lateinit var saveBtn: Button
  private lateinit var accessibilityStatus: TextView
  private lateinit var backBtn: ImageButton

  private lateinit var prefs: android.content.SharedPreferences
  private val primeContacts = mutableListOf<PrimeContact>()
  private lateinit var primeAdapter: PrimeContactAdapter

  data class PrimeContact(val name: String, val number: String)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)

    prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
    initViews()
    loadSettings()
    setupListeners()
  }

  private fun initViews() {
    apiKeyInput = findViewById(R.id.apiKeyInput)
    userNameInput = findViewById(R.id.userNameInput)
    modelSpinner = findViewById(R.id.modelSpinner)
    voiceSpinner = findViewById(R.id.voiceSpinner)
    personalityGroup = findViewById(R.id.personalityGroup)
    primeContactsRecycler = findViewById(R.id.primeContactsRecycler)
    addPrimeBtn = findViewById(R.id.addPrimeBtn)
    saveBtn = findViewById(R.id.saveBtn)
    accessibilityStatus = findViewById(R.id.accessibilityStatus)
    backBtn = findViewById(R.id.backBtn)

    // Model spinner
    val models = listOf(
      "Native Audio (Human Voice)",
      "Flash Live (Fast)",
      "Pro Audio Dialog"
    )
    modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    // Voice spinner
    val voices = listOf(
      "Aoede (Female)", "Charon (Male)", "Kore (Female)", "Fenrir (Male)",
      "Puck (Male)", "Leda (Female)", "Orus (Male)", "Zephyr (Female)"
    )
    voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    // Prime contacts RecyclerView
    primeAdapter = PrimeContactAdapter(primeContacts) { index ->
      primeContacts.removeAt(index)
      primeAdapter.notifyDataSetChanged()
    }
    primeContactsRecycler.layoutManager = LinearLayoutManager(this)
    primeContactsRecycler.adapter = primeAdapter
  }

  private fun loadSettings() {
    apiKeyInput.setText(prefs.getString("api_key", ""))
    userNameInput.setText(prefs.getString("user_name", "Sir"))

    val modelMap = mapOf(
      "models/gemini-2.5-flash-native-audio-preview-12-2025" to 0,
      "models/gemini-2.0-flash-live-001" to 1,
      "models/gemini-2.5-flash-preview-native-audio-dialog" to 2
    )
    val savedModel = prefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
    modelSpinner.setSelection(modelMap[savedModel] ?: 0)

    val voiceMap = mapOf(
      "Aoede" to 0, "Charon" to 1, "Kore" to 2, "Fenrir" to 3,
      "Puck" to 4, "Leda" to 5, "Orus" to 6, "Zephyr" to 7
    )
    val savedVoice = prefs.getString("gemini_voice", "Aoede")
    voiceSpinner.setSelection(voiceMap[savedVoice] ?: 0)

    val personality = prefs.getString("personality_mode", "gf")
    when (personality) {
      "professional" -> findViewById<RadioButton>(R.id.radioProfessional).isChecked = true
      "assistant" -> findViewById<RadioButton>(R.id.radioAssistant).isChecked = true
      else -> findViewById<RadioButton>(R.id.radioGf).isChecked = true
    }

    // Load prime contacts
    loadPrimeContacts()

    // Accessibility status
    updateAccessibilityStatus()
  }

  private fun loadPrimeContacts() {
    primeContacts.clear()
    val json = prefs.getString("prime_contacts_json", null)
    if (json != null) {
      try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
          val obj = array.getJSONObject(i)
          primeContacts.add(PrimeContact(obj.getString("name"), obj.getString("number")))
        }
      } catch (e: Exception) {
        // Ignore parse errors
      }
    } else {
      // Legacy migration
      val oldName = prefs.getString("prime_name", null)
      val oldNumber = prefs.getString("prime_number", null)
      if (oldName != null && oldNumber != null) {
        primeContacts.add(PrimeContact(oldName, oldNumber))
      }
    }
    primeAdapter.notifyDataSetChanged()
  }

  private fun setupListeners() {
    backBtn.setOnClickListener { finish() }

    addPrimeBtn.setOnClickListener {
      showAddPrimeContactDialog()
    }

    accessibilityStatus.setOnClickListener {
      startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    saveBtn.setOnClickListener {
      saveSettings()
    }
  }

  private fun showAddPrimeContactDialog() {
    val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
    val nameInput = dialogView.findViewById<EditText>(R.id.dialogPrimeName)
    val numberInput = dialogView.findViewById<EditText>(R.id.dialogPrimeNumber)

    AlertDialog.Builder(this)
      .setTitle("Add Prime Contact")
      .setView(dialogView)
      .setPositiveButton("Add") { _, _ ->
        val name = nameInput.text.toString().trim()
        val number = numberInput.text.toString().trim()
        if (name.isNotBlank() && number.isNotBlank()) {
          primeContacts.add(PrimeContact(name, number))
          primeAdapter.notifyDataSetChanged()
        }
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun saveSettings() {
    val modelValues = listOf(
      "models/gemini-2.5-flash-native-audio-preview-12-2025",
      "models/gemini-2.0-flash-live-001",
      "models/gemini-2.5-flash-preview-native-audio-dialog"
    )
    val voiceValues = listOf(
      "Aoede", "Charon", "Kore", "Fenrir",
      "Puck", "Leda", "Orus", "Zephyr"
    )

    val personality = when (personalityGroup.checkedRadioButtonId) {
      R.id.radioProfessional -> "professional"
      R.id.radioAssistant -> "assistant"
      else -> "gf"
    }

    // Save prime contacts as JSON
    val jsonArray = JSONArray()
    for (contact in primeContacts) {
      val obj = JSONObject()
      obj.put("name", contact.name)
      obj.put("number", contact.number)
      jsonArray.put(obj)
    }

    prefs.edit().apply {
      putString("api_key", apiKeyInput.text.toString().trim())
      putString("user_name", userNameInput.text.toString().trim())
      putString("gemini_model", modelValues[modelSpinner.selectedItemPosition])
      putString("gemini_voice", voiceValues[voiceSpinner.selectedItemPosition])
      putString("personality_mode", personality)
      putString("prime_contacts_json", jsonArray.toString())
      apply()
    }

    Toast.makeText(this, "Saved! Restart app to apply changes", Toast.LENGTH_LONG).show()
    finish()
  }

  private fun updateAccessibilityStatus() {
    val enabled = AccessibilityHelperService.isEnabled(this)
    accessibilityStatus.text = if (enabled) "Accessibility: Enabled" else "Accessibility: Disabled"
    accessibilityStatus.setTextColor(
      if (enabled) getColor(android.R.color.holo_green_dark)
      else getColor(android.R.color.holo_red_dark)
    )
  }

  override fun onResume() {
    super.onResume()
    updateAccessibilityStatus()
  }

  // ===================== PRIME CONTACT ADAPTER =====================

  class PrimeContactAdapter(
    private val contacts: List<PrimeContact>,
    private val onDelete: (Int) -> Unit
  ) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
      val view = LayoutInflater.from(parent.context)
        .inflate(R.layout.item_prime_contact, parent, false)
      return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
      holder.bind(contacts[position], position)
    }

    override fun getItemCount(): Int = contacts.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
      private val nameText: TextView = itemView.findViewById(R.id.primeNameText)
      private val numberText: TextView = itemView.findViewById(R.id.primeNumberText)
      private val deleteBtn: ImageButton = itemView.findViewById(R.id.primeDeleteBtn)

      fun bind(contact: PrimeContact, position: Int) {
        nameText.text = contact.name
        numberText.text = contact.number
        deleteBtn.setOnClickListener { onDelete(position) }
      }
    }
  }
}
