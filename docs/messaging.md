# Messaging

EVA exposes one search/read/send tool family with two execution paths:

- SMS/MMS: native Android conversation lookup, history and sending.
- Other apps: recent messaging notifications and their explicit text-reply
  actions. No target-app changes, extensions, Shizuku, or app-specific package
  allowlist are required.

Ivan reported a verified real SMS send on 2026-09-14. That verifies direct
sending; it is distinct from the Messages JSON extension's previously verified
unsent draft. Group MMS and the new notification reply path do not inherit that
verification. Notification replies have focused JVM/Robolectric tests but have
not yet been verified against WhatsApp, Telegram, or another real messaging app.

## Using it

Open **Settings → Messaging**:

1. Enable **Read messaging notifications**. This explicitly permits EVA to use
   message excerpts with the configured model; media notification access alone
   does not opt into message collection.
2. Grant Android notification access using **Change**.
3. Receive a messaging notification, leave it active, and select **Refresh
   messaging apps** (returning to EVA also refreshes).
4. Enable **Allow replies** for the app you intend to use.
5. In a typed conversation, ask “What recent WhatsApp conversations can you see?”
   or “Show recent conversations from my messaging apps.”
6. Identify the intended conversation, then ask for a reply with exact text.
   Check the destination app to verify the result.

The user must authorize the particular real message sent during a device test.
Automated tests use fake reply callbacks and construct Android intents without
sending messages.

## Shared tool contract

Existing capability IDs remain stable:

| Tool | SMS/MMS | Notification-backed app |
| --- | --- | --- |
| eva.android.messages.conversations | Omit service or use sms; optional participant query | service is notifications for discovery, exact package name, or unique visible app label; query matches conversation title |
| eva.android.messages.history | Use the returned integer conversationId | Use the returned opaque conversationRef; result is only a notification excerpt |
| eva.android.messages.send | Explicit recipient number(s) or conversationId, plus message | conversationRef and message; optional service must match |

App search results include service package name, conversation title,
conversationRef, and replyAvailable. If labels are ambiguous, use the package
name. EVA never substitutes SMS when an app was explicitly requested. Device
contacts remain useful for SMS, but a phone number is not an app reply target.

The controller allows native reads before one send in a request. Every send
still uses the dispatcher, journal, schema validation, and correlated receipt.
Messaging content is quoted and attributed as external data, including resumed
thread history. It does not become a model instruction.

## What this path can establish

SMS keeps its existing sent-callback result handling. App notification replies
return **HANDED_OFF**, not delivered or read: Android accepted the app's reply
action, but does not expose a reliable cross-app server-delivery receipt.
Expired/cancelled targets or missing permission return **NOT_EXECUTED**.
Uncertain submission returns **UNKNOWN** and is not automatically retried.

Only the current Android user's non-summary messaging notifications are
considered. Android must expose exactly one eligible freeform reply action owned
by the notification's package/UID; modern actions must declare reply semantics
and use a mutable PendingIntent. Unsupported actions remain readable but cannot
be replied to.

References live only in memory, expire after 15 minutes, and are invalidated by
replacement, removal, refresh, notification-listener disconnect, or process restart. A reference is
consumed before submission, even if the result becomes unknown. The service
rechecks the current notification, package identity, notification access,
unlocked device, and reply grant before invoking its PendingIntent.

Reply grants persist by app UID, package, signing certificate set, and first
installation time. Reinstalls or changed signers do not inherit permission.
Grant changes are serialized against durable dispatcher admission and rechecked
at submission. Disabling message access clears captured notifications; it does
not erase previously requested conversation receipts or undo sent messages.

## Boundaries and next steps

This is not a full WhatsApp/Telegram client: it cannot start arbitrary new app
chats, retrieve complete history, list silent/archived conversations, recover
dismissed notifications, or send attachments. Locked-device notification reads
and replies are refused. Notification visibility and action support vary by app.
“No match” means no match among available notifications, not that the chat does
not exist.

The shared interface is deliberately independent of extension files. Future
service-account adapters can provide complete history/new-chat sends where an
official API supports the user's account. They should preserve explicit service
selection, account-scoped targets, authority checks, and honest receipt statuses,
rather than replacing the common user-facing tools.
