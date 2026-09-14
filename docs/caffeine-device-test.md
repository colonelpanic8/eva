# Caffeine declarative package device test

Status: the first Pixel attempt (185c710) crashed before UI during regex initialization.
The portable-regex/startup-isolation fix is JVM-tested; reinstallation and physical
action verification are pending. Caffeine needs
no modification, server, credentials, Shizuku or EVA-specific service.

1. Install `app/build/outputs/apk/debug/app-debug.apk` (package
   `com.colonelpanic.eva.debug`) on the Pixel with Caffeine installed. Open
   Caffeine first and complete any permissions/setup that it requests.
2. In EVA debug settings, under Installed extensions, enable Caffeine, then
   grant **Enable keep-awake** and **Disable keep-awake** separately. Both are
   writes and start without grants. No server credential entry is needed.
3. Close any current connection and reconnect in typed mode, keeping EVA visible.
4. Ask: **Use Caffeine to keep the screen awake.** Expect a Caffeine enable
   receipt with status HANDED_OFF. Check Caffeine's indicator and leave the
   screen idle beyond the usual screen timeout to verify the effect.
5. Return to EVA if needed. In a separate request ask: **Use Caffeine to stop
   keeping the screen awake.** Expect a disable HANDED_OFF receipt. Check its
   indicator and verify normal screen timeout resumes.

The intent asks Caffeine to perform the action directly, not open an editable
draft. Android launch success does not give EVA state evidence. Caffeine's
published integration exposes no state query, so EVA must not claim it verified
the screen state. Missing/disabled activity: NOT_EXECUTED with install/enable
instructions. A lost foreground surface or Android denial also refuses execution.
Enabling the extension alone must not authorize either write action.
