#!/usr/bin/env bash
# Read-only WhatsApp checks on a phone: nothing is opened, dialed or read beyond counts and
# system state. Run it holding /tmp/eva-phone.lock (the justfile recipe does).
set -euo pipefail
serial=${1:?usage: phone_checks.sh SERIAL}
a() { adb -s "$serial" "$@"; }
echo "call state (0 = idle):"; a shell dumpsys telephony.registry | grep mCallState | sort | uniq -c
echo "audio mode owner:"; a shell dumpsys audio | grep -m1 mAudioModeOwner
echo "WhatsApp:"; a shell dumpsys package com.whatsapp | grep -m1 versionName
echo "voice-call row handler:"
a shell cmd package resolve-activity --brief -a android.intent.action.VIEW \
  -t vnd.android.cursor.item/vnd.com.whatsapp.voip.call -d content://com.android.contacts/data/1 com.whatsapp | tail -1
echo "WhatsApp Telecom account:"
a shell dumpsys telecom | grep -m1 -E "PhoneAccount: ComponentInfo\{com.whatsapp/" | sed -E 's/^ *//; s/GroupId: [^ ]+/GroupId: ***/'
echo "live WhatsApp calls in Telecom:"; a shell dumpsys telecom | grep -cE "Call TC@.*com\.whatsapp" || true
echo "WhatsApp voice-call contact rows (count only):"
a shell "content query --uri content://com.android.contacts/data --projection _id --where \"mimetype='vnd.android.cursor.item/vnd.com.whatsapp.voip.call'\"" | grep -c '^Row' || true
for app in com.colonelpanic.eva.debug com.colonelpanic.eva; do
  if a shell pm path "$app" >/dev/null 2>&1; then
    echo "$app: $(a shell dumpsys package "$app" | grep -m1 versionName | xargs)"
    a shell dumpsys package "$app" | grep -E "android.permission.(READ_CONTACTS|CALL_PHONE): granted" | sed 's/^ */  /'
    echo "  $(a shell appops get "$app" MANAGE_ONGOING_CALLS)"
  fi
done
