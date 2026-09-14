# Org-agenda Pixel proving test

Status: Android wiring and JVM tests implemented. No device result yet. Stop after
this test before migrating bundled intent capabilities.

## Install and configure

1. Deploy the confirmed org-agenda-api server changes first: search q/limit/total
   and strict completion returning HTTP 409 on reference mismatch. EVA cannot
   detect an old server that silently ignores strict; do not test writes against one.
2. Install `app/build/outputs/apk/debug/app-debug.apk` from this branch:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   The application ID is `com.colonelpanic.eva.debug`; open that debug app rather
   than the separately installed release.
3. Sign in to ChatGPT or configure the model credential as usual.
4. Open **Settings → Package servers and wait budgets → Org agenda**.
   Enter the HTTPS API origin (for example `https://your-agenda-host`, no path),
   basic-auth username, and password. Tap **Save server and credentials**.
   This configures the package's `org-agenda` credential reference. Credentials
   are encrypted on the phone and never read back into the form. The saved
   approved origin remains visible.
5. Under **Installed extensions → Org agenda**, enable the package. This enables
   agenda, search_todos, and custom_view. Enable **Capture todo** and
   **Complete todo** separately. Leave **Create through mova** off unless testing
   the optional handoff.
6. Leave waits at defaults for the first run: the package declares 30 seconds,
   overriding the mode defaults. If needed, save an **Org agenda wait override**
   of 45 or 60 seconds. Nothing can exceed 60 seconds.
7. Disconnect any existing model connection and reconnect in **typed mode**.
   Newly enabled actions appear at connection open. Shizuku and mova extension
   code are not required.

## Four separate typed requests

Choose an existing disposable TODO with a distinctive title that is safe to
complete; use its actual title in request 2.

1. “Use Org agenda to show today's agenda, including overdue items.”
2. “Use Org agenda to search todos for '<disposable TODO title>', limit 5.
   Show the exact title and returned id, or file and pos, for each match.”
3. “Use Org agenda to capture a todo titled 'EVA Pixel capture test 2026-09-14'
   using the default template.”
4. “Use Org agenda to complete '<disposable TODO title>' from the earlier search.
   Use its returned id, or exact file, pos and title. Do not search again or
   complete by title alone.”

Request 4 uses a reference from request 2, not the newly captured task. If the
reference is ambiguous or missing, stop and inspect it rather than guessing.
The single-action-per-request rule prevents search→complete in one request.

## Expected evidence

- Agenda/search: COMPLETED, compact item lines carrying identifiers, explicit
  truncation if total exceeds returned rows.
- Capture: COMPLETED only with the declared `status: created` response.
- Complete: COMPLETED only with `status: completed`; stale reference produces
  NOT_EXECUTED from HTTP 409 and changes nothing.
- Every receipt attributes Org agenda and records the selected wait and all layers.
- Timeout or lost write reply: UNKNOWN. Inspect the server/app before retrying;
  EVA does not automatically retry.
- Optional mova handoff uses `mova://create?title=...`; mova must be signed in.
  EVA reports only HANDED_OFF, not verified creation.

Report the four receipt statuses/messages, whether the intended records changed,
and any timeout or settings issue. Exclude credentials and authorization headers.
Capture leaves a disposable test todo; clean it up deliberately after verification.
