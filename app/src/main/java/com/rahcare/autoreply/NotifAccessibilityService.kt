package com.rahcare.autoreply

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent

// দ্বিতীয় পথ: কিছু ফোন স্ক্রিন বন্ধ থাকলে অ্যাপের প্রসেস থামিয়ে রাখে, কিন্তু চালু থাকা Accessibility সার্ভিসে সাধারণত হাত দেয় না।
// শুধু চেনা মেসেজিং অ্যাপের (WhatsApp, Messenger, Facebook Lite, Telegram) নোটিফিকেশন-ঘটনা শোনে (accessibility_config.xml-এ সীমাবদ্ধ); পর্দার কিছু পড়ে না।
class NotifAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.ev(applicationContext, "Accessibility সহায়ক সংযুক্ত")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val c = applicationContext
        val pkg = e.packageName?.toString() ?: return
        @Suppress("DEPRECATION")
        val n = e.parcelableData as? Notification
        if (n == null) {
            Store.ev(c, "a11y: $pkg — নোটিফিকেশনের তথ্য পাওয়া যায়নি")
            return
        }
        val ra = n.actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } == true
        Store.ev(c, "a11y নোটিফিকেশন: $pkg reply-বাটন=$ra")
        if (!Store.enabled(c)) return
        try {
            Replier.handle(c, pkg, 0, "a11y|$pkg", System.currentTimeMillis(), n, false, false)
        } catch (x: Exception) {
            Store.ev(c, "a11y handle ব্যর্থ: ${x.javaClass.simpleName}")
        }
    }

    override fun onInterrupt() {}
}
