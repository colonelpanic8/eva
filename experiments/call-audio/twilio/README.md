# Twilio fake hotline

A repeatable far-end test for `../call_agent.py`. Everything serving the call is
on Twilio: one US local number, a protected Serverless Function, two Assets,
and a Sync service. No application server, browser login, or Twilio CLI is needed.
Deployment uses the REST/Serverless APIs with Python's standard library.

## Run

The owner manages the account and its `twilio.com` pass entry. The first line
is the website password and is never used. Later lines include these API keys:

```text
account-sid: <AC followed by 32 hex characters>
auth-token: <32 hex characters>
```

An awk filter discards the password line and all unrelated fields before the
credential parser receives any output. Duplicate/malformed API keys fail without
displaying their contents. `TWILIO_PASS_ENTRY` overrides the entry name; a direct
`hotline.py --pass-entry NAME ...` invocation also works. Do not run `pass ls`
on an entry: it can print its contents. The scripts pass only the extracted API
credentials in a child process's environment; the API worker removes
them from its environment before launching other processes. They never go in
arguments, files, or output. Twilio supplies its own runtime credentials through
the Serverless Service's `IncludeCredentials` setting.

From this worktree's root:

```sh
cd /home/imalison/projects/eva/.worktrees/phone-call-agent
direnv exec . experiments/call-audio/twilio/setup
direnv exec . experiments/call-audio/twilio/e2e
direnv exec . experiments/call-audio/twilio/verify
# Read-only account type, owned-number inventory, and inbound minutes:
direnv exec . python3 experiments/call-audio/twilio/hotline.py status
```

`setup` first looks for `TWILIO_HOTLINE_NUMBER` when set, then a friendly name
containing `eva-fake-hotline` or the owner's existing `EVA Testing` name. Use
`setup --reuse-number PN...` or an E.164 number to explicitly reuse a test number.
When provisioning is authorized, `setup --provision` can provision exactly one
US local voice number if the account owns none. Without that flag it never
buys a number. Existing unrelated numbers or an unmatched explicit reuse request
stop setup. It reuses the named Function, Assets, environment, and Sync service, and
deploys new code/asset versions. A local lock prevents overlapping setups, and
the number list is checked again immediately before purchase. Mutation requests
are never automatically retried after a network failure. Rerunning setup
rediscovers resources, including a purchase whose response was lost. Output is
the number and non-secret resource SIDs. It does not upgrade the account.
Some newer trials expose only shared onboarding numbers, which do not appear
in the owned-number API and cannot be configured through its voice webhook.
Error [21404](https://www.twilio.com/docs/api/errors/21404) means provisioning
requires an account upgrade by the owner. Serverless/Sync HTTP 401 with code
[20003](https://www.twilio.com/docs/api/errors/20003) indicates those resources
are not accessible with the current account credentials. These failures must
be resolved before the test can run; the scripts never log in or upgrade.

`e2e` makes **one** short call, using the specified wireless Pixel by default.
It connects only that device's mDNS service if necessary, reads all
`mCallState` entries, and waits up to five minutes for all of them to become zero.
An unreadable state or a phone that remains busy prevents dialing.
It invokes the existing prototype with a goal to select **billing**, say **Ivan Malison**
and **4417**, remain quiet on hold, ask for the hours, and say no to further help.
The default voice path completes the menu by speech. `e2e --ivr keypad` tests
keypad selection and its current limitation: the prototype synthesizes in-band
DTMF audio, while [Twilio Gather accepts RFC 2833/4733 signaling and does not support in-band DTMF](https://help.twilio.com/articles/223180068-Troubleshooting-issues-with-Gather).
An injected tone is not proof that a keypress registered. If a trial announcement requests a keypress, the agent
is instructed to handle that separately before the menu. No other phone UI is
operated by these scripts; the existing prototype owns dialing and normal hangup.

The default test-call budget is 210 seconds (`--max-seconds` changes it).
On a timeout or local prototype failure, cleanup ends only the newly identified
Twilio inbound test call through Twilio's API, rather than pressing keys on a
potentially unrelated phone call. A dedicated adb bridge port avoids other
sessions' forwards. `CALL_AGENT_PYTHON` can select a Python with numpy/websockets;
otherwise the known Nix environment is used, or built with `nix build --no-link`.
The prototype reads its existing desktop ChatGPT tokens without these scripts
reading or displaying them.

`agent_runner.py` imports the existing prototype and changes only phase/keypad
tool completion: it returns the tool result without requesting another model
response. The next far-end utterance resumes the conversation. This avoids
speech after a phase report and lets keypad tones arrive before speech can win
a combined DTMF/speech Gather. Phase reporting is limited to hold; active menu
and representative turns require a spoken answer. The call goal also suppresses
action narration.

## Scenario and evidence

`scenario.json` owns prompts, transitions, Gather input/timeouts, and hold duration.
The default call goes through:

1. “Thanks for calling Test Company,” then billing/support by DTMF or speech.
2. Exact readback (“You pressed …” or “I heard: …”), followed by name/account
   verification and another exact readback.
3. Two generated 12-second music segments with one “Your call is important to
   us” interjection, totaling approximately 26–28 seconds on hold.
4. A test representative asks how to help, repeats the received speech, and
   gives the company's fixed hours: weekdays, 9 AM–5 PM Pacific.
5. “Is there anything else?” A no/goodbye or input timeout ends politely.

The representative is deterministic; the hours response follows any nonempty
representative request. This is a hotline fixture, not an open-ended chatbot.
The `gather`, `hold`, and `end` nodes can be rearranged or extended for other
scenarios; scenario validation happens before deployment.

The initial Function response answers with a short connecting message and redirects to
the answered handler. Only then does it start a dual-channel, both-direction
recording, before the hotline greeting. The realtime recording API rejects
calls that are still ringing. This records the connected Twilio call, excluding
the initial connecting message and any preceding trial
announcement. It checks for an existing recording before starting another on
a webhook retry. A recording API rejection is saved, read aloud, and does not
disable Gather. Recording-completion callbacks are saved too.

Every Gather's `Digits`, `SpeechResult`, and `Confidence` is written immediately
to a **Sync Map named `call-<CallSid>`** before returning the next TwiML response.
The prefix is required because Sync rejects names that match the SID pattern.
Keys contain stage/turn and a content hash: identical retries deduplicate;
different results remain visible. Missing values stay null, including timeouts.
Sync data has no configured expiry. Maps are unordered, so verification sorts
by turn and receipt time. [Twilio Sync Map documentation](https://www.twilio.com/docs/sync/api/map-resource)
describes persistence and ordering.

Function logs supply near-real-time fallback receipts. Twilio retains only the
last 3 KB per invocation, so oversized speech is explicitly marked as deferred
in logs while its complete result still goes to Sync. Sync errors are announced
on the call, with logs and Call Events retained as fallbacks. [Function log limits](https://www.twilio.com/docs/serverless/api/resource/logs)
and [Call Events](https://www.twilio.com/docs/voice/api/call-event-resource) document
the platform constraints: Events are available about 15 minutes after hangup;
logs and webhook request/response data are retained for 30 days. Export evidence
locally rather than relying on these fallback retention windows.

`verify [CA...]` defaults to the latest inbound call to the configured number.
It prints ordered Gather results with their evidence source, call status,
duration, reported call price, recording URLs, and any failure or missing data.
It downloads available WAV recordings and writes `report.json` and
`gathers.jsonl` under the already-ignored
`experiments/call-audio/build/twilio-<CallSid>/`. Dual-channel recordings are also
split into `*-caller.wav` and `*-hotline.wav`. For this inbound REST recording,
channel 1 is received caller audio and channel 2 is sent hotline audio; a mono
recording is retained without claiming channel isolation. See
[Twilio recording API and media retrieval](https://www.twilio.com/docs/voice/api/recording).
There is no extra paid recording-transcription job; Gather provides the received
speech text and the caller-channel WAV permits independent listening.

`e2e` prints AGENT utterances against their closest received speech by text
similarity, plus requested DTMF vs received digits and exact name/account text
checks. This comparison is explicitly **not** timestamp alignment or acoustic
word accuracy: intended AGENT text alone cannot prove delivery. E2E returns
nonzero for missing/mismatched menu or verification evidence or missing
representative/final speech. `verify` returns nonzero if no Gather is available
or a receipt is still deferred. Missing recordings remain visible even if
Gather evidence proves speech delivery.

Useful follow-up commands:

```sh
# Revisit one call, optionally comparing its intended speech:
direnv exec . experiments/call-audio/twilio/verify CA... \
  --transcript experiments/call-audio/build/call-twilio-YYYYMMDD-HHMMSS/transcript.jsonl

# Only if Sync/logs failed, wait for Twilio's delayed request records:
direnv exec . experiments/call-audio/twilio/verify CA... --require-events --wait 960

# Deploy a different scenario using the same number:
direnv exec . experiments/call-audio/twilio/setup --scenario path/to/scenario.json
```

## EVA's Twilio call route

EVA's `twilio` call route places calls from inside EVA with the Twilio Voice Android SDK
(see the architecture's Twilio provider section). It needs three account resources, each
found again by the name `eva-voice-sdk`:

- a standard **API key**, from which EVA mints its access tokens on the phone; Twilio shows
  its secret once, and `voice-sdk` pipes it straight into the pass entry
  `twilio.com/eva-voice-sdk` (line one; never printed, written to a file or an argument);
- a **Serverless service** with a protected Function `/dial` ([`dial.js`](dial.js)) in an
  environment named `voice`, with `CALLER_ID` and `CLIENT_IDENTITY` variables: it answers only
  the SDK identity `client:eva`, dials an E.164 `To` with `answerOnBridge` (EVA's call connects
  only when the other party answers) and a 60 s ring, and rejects EVA's call as busy when the
  line was busy;
- a **TwiML app** whose voice URL is that Function.

```sh
direnv exec . experiments/call-audio/twilio/voice-sdk           # read-only plan
direnv exec . experiments/call-audio/twilio/voice-sdk --apply   # only once the owner approves
```

The caller ID defaults to the hotline number; `--caller-id` takes another number on the
account or a verified caller ID. An API key named `eva-voice-sdk` whose secret is not in the
pass entry stops `--apply` (Twilio cannot show it again); delete it in the console first. If
pass cannot store a newly created secret, that key is deleted again. Rerunning updates the
Function, variables and app, and rewrites the pass entry's fields with the same secret. The
entry then holds what EVA's **Settings > Phone call agent > Twilio calls** asks for, which
[the emulator test bed](../emulator/README.md) types in.

A route call to the hotline is two billed legs: the SDK leg and the dialed call, plus the
hotline's inbound leg, Gathers and recording. `verify` ignores the SDK's `client:` parent
call and reports the hotline leg.

## Trials and costs

Twilio has multiple trial generations; account-specific behavior must be tested.
Its [current trial Voice documentation](https://www.twilio.com/docs/usage/trials/try-out-voice)
requires verified caller/recipient numbers and documents a 10-hop chain limit,
10-minute call limit, and blocked recording TwiML. The default scenario uses
four Gather callbacks and no hold redirect, staying below that hop limit.
The owner must verify the Pixel's number themselves if it was not the sign-up
number. No verification code or account setup is automated here.
Twilio's [Voice test guide](https://static1.twilio.com/docs/flex/admin-guide/setup/voice/test)
also documents a trial announcement requesting a keypress. The agent handles
it only when heard. Trial recording restrictions may prevent the REST recording
too; the actual API response, recording list, and Gather receipts are the evidence.
The scripts do not forward calls or buy another number to evade trial limits.

Published [US Voice pricing](https://www.twilio.com/en-us/voice/pricing/us) lists
$1.15/month for one US local number, $0.0085/minute inbound calling, and
$0.0025/minute recording. Default speech Gather is $0.02/use on paid accounts;
the normal scenario has four Gathers. The owner authorized normal test-line
usage fees, including Gather, recording, transcription, and Sync, at a reasonable
scale: one number and approximately five test calls, with normal per-use fees
approved. Account upgrades remain the owner's responsibility.
Recording storage has a [10,000-minute free allowance](https://help.twilio.com/articles/223132527-How-much-does-it-cost-to-record-a-call-);
the account's other usage also counts. [Functions and Assets](https://www.twilio.com/en-us/serverless/pricing)
have 10,000 free invocations/requests monthly. Sync's small receipt usage is
authorized; this setup adds no paid transcription, outbound call, transfer,
subscription, or second number. `Call.price` does not include every feature
charge, so consult account usage for actual billing. Keep this test to roughly
a handful of calls; the scripts do not loop or automatically retry calls.

## Local checks

```sh
experiments/call-audio/twilio/check
```

The check script obtains missing lint tools through a temporary Nix shell and
installs the pinned Twilio helper into an ignored local cache for XML tests.
It checks syntax/formatting, shell wrappers, scenario transitions, recording
failure handling, Sync receipts, credential parsing, purchase idempotence,
idle detection, evidence merging, and recording-channel extraction without
credentials, a device, or Twilio API requests. These checks do not establish
device uplink delivery or successful live deployment.

### Cancellation role-play regression

`scenario-roleplay.json` asks for a ZIP code the brief does not disclose, accepts a
fee-free cancellation, and requests a read-back of confirmation 2234. Deploy with
`setup --scenario experiments/call-audio/twilio/scenario-roleplay.json --reuse-number +15592493930`.
Give the call agent the goal of cancelling the fictional Iron Temple Fitness membership
for Ivan Malison, member 4417, with no fee or retention offer; permit only that name and
member number and explain that it does not have the ZIP code. Review the received speech
for an audible answer to the ZIP question, no internal bookkeeping narration, a read-back,
and a brief closing. The recording and receipts are evidence; a completed call alone is
not a pass. Restore the usual hotline with `setup` afterwards.
