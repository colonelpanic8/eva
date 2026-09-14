# The prompt file

EVA's system prompt is assembled, at the start of every session, from
`eva-prompt.yaml`. Nothing is added that the file does not contain. The stock
prompt is written to the file the first time EVA runs; after that the file is
what sessions read, and **Reset to defaults** on the Instructions screen writes
the compiled stock prompt again.

## Instruction source

The Instructions screen starts with this source:

`https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-prompt.yaml`

**Update instructions** downloads that raw YAML file, validates it with the same
rules as a hand-edited prompt, and writes it into the active local prompt file.
The source owns component text and order. EVA preserves the on/off choice of any
component with the same id, uses the source default for a new id, and removes
components the source no longer contains. Nothing is fetched automatically, so
a repository change cannot silently alter the prompt sent to a model.

Paste another raw HTTPS YAML URL to use a custom source. Credentials, fragments,
redirects, non-HTTPS URLs, files over 1 MiB, malformed YAML, and unknown prompt
variables are rejected without changing the current file or saved source. The
stock catalog is also compiled into EVA, so first run and **Reset to defaults**
work without the repository or a network connection.

## Where it is

- **EVA's own copy**: `Android/data/com.colonelpanic.eva/files/eva-prompt.yaml`.
  `adb pull` and `adb push` reach it; since Android 11 a file manager and a USB
  connection do not, because `Android/data` is closed to both. It is removed when
  EVA is uninstalled. This is the fallback, not the way to keep a prompt you care
  about.
- **A file you choose**: **Open a file** or **Create a file** on the Instructions
  screen uses the system picker, and EVA keeps the access it is given. This is
  the one to use: it puts the file somewhere you can sync, back up, or commit.
  **Use EVA's copy** goes back.

EVA reads the file whenever a session starts and whenever the app returns to the
front. It writes the file only when something is changed on the Instructions
screen or a repository update is requested, and it never overwrites a file it
could not parse. A file that does not parse stops sessions from starting, with
the parser's message shown on the Instructions
screen and at the connect bar, rather than falling back to a prompt you did not
write.

## Keeping it in a git repo

EVA has no git of its own. It reads and writes one file; something else owns the
repository. Three arrangements work as things stand.

**A synced folder — the simplest, and the one to reach for.** Keep the checkout
on a machine, and have something that syncs a directory into the phone's shared
storage, Syncthing for instance, carry the file. **Open a file** reaches shared
storage with nothing else installed. Commit and pull on the machine; the phone
receives the file and the next session uses it, with nothing to press in EVA.

The picked file keeps working across a sync because shared storage hands out
document ids of the form `root:path/to/file`. Syncthing replaces a changed file
by writing a temporary one and renaming it into place, and a rename onto the
same path resolves to the same id, so EVA's access survives it. There is nothing
to re-pick. If both sides change the prompt, Syncthing leaves its usual
`...sync-conflict-....yaml` beside the file; EVA goes on using the file you
picked and never sees the conflict copy, so resolve it on the machine as usual.

**A checkout on the phone, through Termux.** Termux exposes its home directory
through the Storage Access Framework, so **Open a file** can reach a checkout
under Termux's `~`. Clone the repo there, point EVA at the file inside it, and
`git pull` in Termux. Termux added that provider for exactly this reason: a git
checkout on shared storage does not work, because git needs file permissions
shared storage will not keep. Worth it only if you want to commit from the phone.

**`adb`, for a phone that is plugged in anyway.** `adb push` a file from a
checkout over EVA's own copy. Crude, but it needs nothing installed on the phone.

What makes the file behave under version control: it ends with exactly one
newline, defaults are omitted so the file says only what was chosen, long
instructions are literal blocks rather than escaped one-liners so a reworded
sentence is a one-line diff, and EVA does not write the file at all when the
result would be identical to what is already there. What does not: **the app
rewrites the whole file when you change something on the Instructions screen, so
comments do not survive that.** Edit in the file and toggle in the file if you
keep comments; use the Instructions screen if you do not.

Toggling a component in the app changes `enabled` in the file, which shows up as
a diff in your worktree. That is deliberate — which components are on is part of
the setup you are versioning — but it does mean the phone dirties the checkout.

## Format

```yaml
components:
- id: identity
  title: Identity
  summary: Who EVA is and what the tools are for
  instruction: |
    You are EVA, an assistant running on the user's Android phone. Help conversationally and use
    the supplied tools for phone actions. Say what the tool result reports.
- id: spoken-style
  title: Spoken style
  applies: voice
  instruction: This is a spoken conversation. Keep replies short.
- id: brief-actions
  title: Brief action confirmations
  applies: voice
  instruction: When performing a simple action, give only a brief confirmation unless the user asks for more detail.
- id: one-request
  title: One request
  slot: call
  applies: voice
  instruction: |
    This call is for one request. ...
  describe:
    eva.session.end: |
      Hang up this voice conversation; ...
- id: open-conversation
  title: Open conversation
  enabled: false
  slot: call
  applies: voice
  instruction: |
    This call stays open. ...
- id: clock
  title: Clock
  instruction: '{{clock}}'
```

Enabled components that apply to the session are joined in file order, each as
its own paragraph. Fields, all optional except `id`:

| Field | Meaning |
| --- | --- |
| `id` | Names the entry. Lowercase letters, digits, dashes, and slashes; unique in the file. |
| `title`, `summary` | Shown on the Instructions screen. `title` defaults to the id. |
| `enabled` | Default `true`. The switch on the Instructions screen. |
| `applies` | `voice`, `text`, or `both` (default). Which kind of session includes it. |
| `slot` | Components sharing a slot are alternatives: at most one may be enabled. Turning one on in the app turns the others off. |
| `instruction` | The text. Line breaks join into one paragraph; a blank line starts another, so wrap it like prose. |
| `describe` | Tool id → replacement description, applied while the component is on. Tools this phone does not offer are ignored. |
| `hide` | Tool ids withheld from the model while the component is on. |

`{{clock}}` becomes the current local time and `{{lookup_retries}}` the
"extra lookup attempts" setting. Any other `{{name}}` is an error.

Unknown keys are errors, so a misspelled field is caught rather than ignored.
Two enabled components in one slot, a duplicate or malformed id, and an unknown
variable are reported with the offending id.

## What is not in the file

The tools themselves. Which tools exist comes from the phone: `describe` and
`hide` can reword or withhold them, not add them. Ending a voice conversation
(`eva.session.end`) is offered in every voice session unless a component hides
it; the stock file's two `call` alternatives only change when the model is told
to use it.

## In the app

The Instructions screen lists the components as switches, opens one to edit its title,
summary, scope, and text, and adds or removes components. Slots, `describe`, and
`hide` are edited in the file. The app writes the file with its own formatting;
see [keeping it in a git repo](#keeping-it-in-a-git-repo) for what that costs a
hand-edited file.
