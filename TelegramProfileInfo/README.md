# Telegram Profile Info (LSPosed Module)

Displays **Data Center (DC)** and **ID** right under profile pictures (in the profile status/member-count line) for both **users** and **supergroups/groups/channels**, built to be **immune to R8/ProGuard class and method renaming**.

## How It Works

1. Hooks `org.telegram.ui.ProfileActivity.createView(Context)` (un-obfuscated `BaseFragment` override).
2. Reads `"user_id"` or `"chat_id"` from `getArguments()`.
3. Dynamically resolves `TLRPC$User` or `TLRPC$Chat` from `getMessagesController()`, extracts `photo.dc_id` (falling back to `photo_big.dc_id`), and formats supergroup/channel IDs as `-100<chat_id>`.
4. Walks the `SimpleTextView` class hierarchy to hook inherited text setters, skips the title/name view (`first_name` or `title`), and appends ` · DC <dc> · ID <id>` to the status line.

> **Note:** In Telegram's MTProto schema, DC ID is exposed on `UserProfilePhoto.dc_id` / `ChatPhoto.dc_id`. When no profile photo is set, only the `ID` is displayed.

## Build & Install

1. Open `TelegramProfileInfo` in **Android Studio** (or build with Gradle / GitHub Actions).
2. Install the APK on your rooted device with **LSPosed**.
3. Enable **Telegram Profile Info** in **LSPosed Manager** and ensure Telegram is checked in the module scope.
4. Force-stop and relaunch Telegram.
