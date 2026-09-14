# Messages package device test

Status: declarative package and JVM tests implemented; no device result yet.
The target is the phone's existing messaging app, such as Google Messages.
No installation, update, or custom code in that app is required. This is an
intent handoff test, not a send/delivery or message-reading test.

1. Install the EVA debug APK from this branch:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   Open `com.colonelpanic.eva.debug`, not the release EVA app.
2. Sign in to the configured model as usual. No package/server credentials or
   Shizuku setup are needed.
3. Open **Settings → Installed extensions → Messages**. Enable the package, then
   enable **Prepare a message draft**. The action needs a separate switch because
   it hands data to another app. This does not authorize sending an SMS.
4. Disconnect any existing model connection and reconnect in typed mode.
5. Use your own phone number in this request:
   “Use the Messages extension's Prepare a message draft action to open a draft
   to <your number> saying 'EVA Messages package test'. Do not send it.”
6. Check that the existing Messages app opens a draft addressed to that number,
   with the exact text. **Do not tap Send.** Return to EVA and inspect the receipt:
   it must attribute **Messages**, report **HANDED_OFF**, and say sending and
   delivery are not verified. A receipt without Messages attribution means the
   model chose the older native capability and did not prove the package path.
7. Discard the draft. Report the receipt and whether the intended recipient/body
   appeared correctly; redact private phone numbers if sharing screenshots.

No org-agenda package is bundled or registered. Its JSON remains a separate HTTP
interpreter example in docs. The general bundled-capability migration remains
paused until this device result.
