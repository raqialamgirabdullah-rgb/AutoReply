# AutoReply

Android app that replies to message notifications automatically using rules you define.
Supports WhatsApp, WhatsApp Business, Messenger, Telegram and IMO, including several copies of the same app (clones / dual apps): every copy that posts a notification is listed in the app and can be switched on or off separately. The APK is built by GitHub Actions and published on the Releases page.
Installed copies check this page and show an update button when a newer release exists.

The app has notification access and internet access. By default the only network calls are to
`github.com` (which release is the latest) and the APK download from this repository's Releases.

Optional AI chat: if you switch on "AI স্বয়ংক্রিয় চ্যাট" and enter your own API key and business information, messages that match no
rule are answered by the AI provider you chose (Claude / Anthropic, or any OpenAI-compatible API). Only the message text (no name or number) is sent
to that provider, and the key is stored on the phone only.

## ইনস্টল করার নিয়ম (Install guide)

এই অ্যাপ Google Play থেকে নয়, সরাসরি APK ফাইল থেকে ইনস্টল হয়। তাই ফোন কয়েকটা সতর্কবার্তা দেখাতে পারে। এগুলো ফোনের নিরাপত্তা-ব্যবস্থা, সাধারণ ব্যাপার।

1. **APK নামান:** [Releases](../../releases/latest) পাতা থেকে `AutoReply.apk` ডাউনলোড করুন (বা যিনি পাঠিয়েছেন তার ফাইল)।
2. **ফাইলে চাপ দিন।** "এই সোর্স থেকে ইনস্টল করা যাবে না" লিখে এলে **Settings** চাপুন, **এই সোর্স থেকে অনুমতি দিন (Allow from this source)** চালু করে ফিরে আসুন।
3. **Play Protect-এর সতর্কবার্তা** এলে **বিস্তারিত (More details) → তবুও ইনস্টল করুন (Install anyway)** চাপুন। "অ্যাপ স্ক্যান করুন (Scan app)" অপশন থাকলে সেটাও ব্যবহার করা যায়।
4. **অ্যাপ খুলে** নোটিফিকেশনের অনুমতি দিন এবং **নোটিফিকেশন অ্যাক্সেস** চালু করুন। সুইচ ধূসর থাকলে: Settings → Apps → Auto Reply → ওপরে ডানে ⋮ → **Allow restricted settings**।
5. **ব্যাকগ্রাউন্ডে চালু রাখতে:** অ্যাপের ☰ মেনু থেকে ব্যাটারি ও Auto-start সেটিং ঠিক করুন (ফোনভেদে নাম আলাদা)।

আপডেট এলে অ্যাপ নিজেই জানাবে; আপডেট একই কী দিয়ে সাইন করা তাই আবার সতর্কবার্তা আসার কথা নয়।
