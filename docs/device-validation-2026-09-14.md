# Pixel extension validation — 2026-09-14

Device: Pixel 11 Pro Fold, API 37. Installed EVA debug build: 0f53e78
(`com.colonelpanic.eva.debug`, versionName `0.1.0-dev-debug`). This record concerns
the installed build, not the unfinished repository-import/UI changes in the worktree.

## Verified

- The application launches and Settings renders. The PackageCodec regex startup
  crash from 185c710 is fixed on the physical phone.
- ChatGPT device-code authentication completes in the desktop browser and EVA
  reports the ChatGPT Pro account as signed in. Reinstalling with `adb install -r`
  preserves the account.
- Caffeine and Messages package definitions load and appear in Installed extensions.
  Caffeine is enabled with both write grants; Messages is enabled with its compose
  grant. Those grants survive an app restart and replacement install.
- Refresh extensions completes without an EvaExtensions or AndroidRuntime error.
- A typed Caffeine enable request produced an attributed `HANDED_OFF` receipt. The
  Caffeine foreground-service notification appeared with “Caffeine activated.”
- A separate typed Caffeine disable request produced an attributed `HANDED_OFF`
  receipt. The Caffeine notification disappeared.
- A typed Messages request produced an attributed `HANDED_OFF` receipt and opened
  Google Messages with the requested recipient and body as an unsent draft. No SMS
  was sent.
- The conversation renders recovered `NOT_EXECUTED` receipts from requests interrupted
  by the earlier crash, plus the new handoff receipts, without crashing.

## Defect found and fixed

The first Caffeine request reached receipt rendering and crashed in
`ConversationItems.maxWidthFraction`. Compose's intrinsic measurement supplied an
unbounded-width sentinel; multiplying it by the bubble fraction created an invalid
1.9-billion-pixel constraint. Commit 0f53e78 preserves unbounded constraints during
intrinsic measurement and applies the fraction only to bounded layout constraints.
Focused JVM tests cover both paths, and `just check` passes.

The intent receipts correctly state that the requests were handed to the target app.
The independent notification check supplies the stronger evidence that Caffeine
actually changed state; the intent transport itself cannot return that evidence.

## Still unverified

Installed-service AIDL execution has no current provider test vehicle. HTTP package
execution awaits a compatible org-agenda-api deployment. Repository-import and
general AppFunctions work remain in progress. Package removal/update broadcasts,
Binder death, and background execution restrictions have not been exercised on a
physical device.

## Combined Extensions build

The subsequent combined installed-extensions worktree build includes the receipt
crash fix and dedicated Extensions destination. Installed with replacement, it
opens from the navigation drawer and shows Caffeine, Messages, their enabled
switches and three action grants, plus the repository controls. Existing receipts
still render. `just format` and `just check` pass. Remote repository installation
has JVM coverage but awaits a published repository for device verification.

The app-icon update was installed from the same worktree. A Pixel screenshot
confirmed Caffeine's installed app icon next to its extension row, with existing
grants still enabled. Generic Messages has no pinned app match and therefore no
app icon; matching does not guess a specific messaging app from its display name.
