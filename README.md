# AutoReply

Android app that replies to message notifications automatically using rules you define.
Supports WhatsApp, WhatsApp Business, Messenger, Facebook Lite, Telegram and IMO, including several copies of the same app (clones / dual apps): every copy that posts a notification is listed in the app and can be switched on or off separately. The APK is built by GitHub Actions and published on the Releases page.
Installed copies check this page and show an update button when a newer release exists.

The app has notification access and, for update checks only, internet access. The only network calls are to
`github.com` (which release is the latest) and the APK download from this repository's Releases.
