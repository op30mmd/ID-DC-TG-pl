# Telegram Profile Info (LSPosed Module)

Displays each user's **Data Center (DC)** and **User ID** right under their profile picture (in the profile status line) in Telegram, built to be **immune to R8/ProGuard class and method renaming**.

## How It Works

1. Hooks `org.telegram.ui.ProfileActivity.createView(Context)` (un-obfuscated `BaseFragment` override).
2. Reads `"user_id"` from `getArguments()` (`Bundle` key is never obfuscated).
3. Looks up `TLRPC$User` from `getMessagesController()` by matching `(Long) -> TLRPC$User` dynamically, then reads `user.photo.dc_id` (falling back to `photo_big.dc_id`).
4. Dynamically locates the `SimpleTextView` status field on `ProfileActivity` (via its un-obfuscated `setRightDrawableInside(boolean)` method and `Integer` theme tag) and appends ` · DC <dc> · ID <userId>` to the status text right under the profile picture.

> **Note:** In Telegram's MTProto schema, a user's DC ID is exposed on their `UserProfilePhoto` (`dc_id`). If a user has no profile photo, only their `ID` is shown.

## Build & Install

1. Open `TelegramProfileInfo` in **Android Studio** (or run `./gradlew assembleRelease`).
2. Install the APK on your rooted device with **LSPosed**.
3. Enable **Telegram Profile Info** in **LSPosed Manager** and ensure Telegram is checked in the module scope.
4. Force-stop and relaunch Telegram.
