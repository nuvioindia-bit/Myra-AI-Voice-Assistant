package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

  companion object {
    const val TAG = "AccessibilityHelper"
    var instance: AccessibilityHelperService? = null

    fun isEnabled(context: Context): Boolean {
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: return false
      val serviceName = "${context.packageName}/${AccessibilityHelperService::class.java.canonicalName}"
      return enabledServices.contains(serviceName)
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d(TAG, "Accessibility service connected")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
  override fun onInterrupt() {}

  override fun onDestroy() {
    super.onDestroy()
    instance = null
  }

  fun closeCurrentApp() {
    performGlobalAction(GLOBAL_ACTION_HOME)
  }

  fun goBack() {
    performGlobalAction(GLOBAL_ACTION_BACK)
  }

  fun clickOnText(text: String) {
    val root = rootInActiveWindow ?: return
    val node = findNodeByText(root, text)
    if (node != null) {
      val clickable = if (node.isClickable) node else findClickableParent(node)
      clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    root.recycle()
  }

  fun typeText(text: String) {
    val root = rootInActiveWindow ?: return
    val editText = findEditText(root)
    if (editText != null) {
      val args = android.os.Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
      }
      editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      editText.recycle()
    }
    root.recycle()
  }

  fun scrollDown() {
    val root = rootInActiveWindow ?: return
    val scrollable = findScrollableNode(root)
    scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    root.recycle()
  }

  fun scrollUp() {
    val root = rootInActiveWindow ?: return
    val scrollable = findScrollableNode(root)
    scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    root.recycle()
  }

  private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
    if (root.text?.toString()?.contains(text, ignoreCase = true) == true) {
      return root
    }
    for (i in 0 until root.childCount) {
      val child = root.getChild(i) ?: continue
      val found = findNodeByText(child, text)
      if (found != null) return found
    }
    return null
  }

  private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      if (current.isClickable) return current
      current = current.parent
    }
    return null
  }

  private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (root.className?.toString()?.contains("EditText") == true) {
      return root
    }
    for (i in 0 until root.childCount) {
      val child = root.getChild(i) ?: continue
      val found = findEditText(child)
      if (found != null) return found
    }
    return null
  }

  private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
      val child = root.getChild(i) ?: continue
      val found = findScrollableNode(child)
      if (found != null) return found
    }
    return null
  }
}
