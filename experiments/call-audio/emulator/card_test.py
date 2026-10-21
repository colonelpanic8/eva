"""
The card-services regression's verdict: EVA's records (pulled databases) and the fake hotline's
`verify --scenario` evaluation, judged without a device so the rules can be tested.
"""

import json
import re
import sqlite3

START = "eva.call.start"
MEMORY_SEARCH = "eva.memory.search"
GET_CREDENTIAL = ".get_credential"
SECRET = "secret:"

# The hotline's fixed annual-fee answer, as EVA's outcome may paraphrase it.
FACTS = (
    ("the fee amount", r"\b95\b"),
    (
        "no waiver",
        (
            r"(cannot|can ?not|can't|won't|will not|isn't|is not|not) (be )?waived?"
            r"|no waiver|(declin|refus|unabl)\w* to waive"
        ),
    ),
    ("the no-fee Test Cash card", r"test cash"),
)


def card_pattern(digits):
    """Detect numeric, punctuated, and digit-by-digit spoken read-backs."""
    words = (
        "zero|oh",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
    )
    tokens = [rf"(?:{d}|(?<![a-z])(?:{words[int(d)]})(?![a-z]))" for d in digits]
    return re.compile(r"[\W_]*".join(tokens), re.IGNORECASE)


def gate_phrases(wording):
    """
    Fixed text of each uncertain-mutation refusal an eva-wording.yaml defines: `mutation-uncertain`'s
    first sentence and, where present, `uncertain-repeat`'s first templated sentence after its
    last placeholder.
    """
    phrases = []
    for key in ("mutation-uncertain", "uncertain-repeat"):
        match = re.search(rf"^ +{key}: >-\n((?: {{4,}}.*\n?)+)", wording, re.MULTILINE)
        if not match:
            continue
        text = " ".join(line.strip() for line in match[1].splitlines())
        sentences = [s.rstrip(".") for s in text.split(". ")]
        templated = next((s for s in sentences if "}" in s), None)
        phrases.append(
            templated[templated.rindex("}") + 1 :].lstrip(") ")
            if templated
            else sentences[0]
        )
    if not phrases:
        raise ValueError("eva-wording.yaml defines no uncertain-mutation refusal")
    return phrases


def _json(value):
    try:
        return json.loads(value) if value else None
    except json.JSONDecodeError:
        return None


def read_records(databases):
    """Invocations, CALL_TASK notice texts (oldest first), turns and every text cell, from EVA's databases."""
    records = {"invocations": [], "notices": [], "turns": [], "texts": []}
    for path in databases:
        try:
            db = sqlite3.connect(path)
            db.row_factory = sqlite3.Row
            tables = {
                name
                for (name,) in db.execute(
                    "select name from sqlite_master where type = 'table'"
                )
            }
            if "invocations" in tables:
                for row in db.execute("select * from invocations order by created_at"):
                    records["invocations"].append(
                        {
                            "call_id": row["call_id"],
                            "capability_id": row["capability_id"],
                            "status": row["status"],
                            "message": row["message"],
                            "created_at": row["created_at"],
                            "thread_id": row["thread_id"],
                            "arguments": _json(row["arguments_json"]) or {},
                            "data": _json(row["data_json"]),
                        }
                    )
            if "items" in tables:
                records["notices"] += [
                    text
                    for (text,) in db.execute(
                        "select text from items where type = 'NOTICE' and notice_kind = 'CALL_TASK' "
                        "order by created_at, rowid"
                    )
                ]
            if "turns" in tables:
                records["turns"] += [
                    dict(row)
                    for row in db.execute("select id, thread_id, status from turns")
                ]
            for table in tables:
                columns = [c[1] for c in db.execute(f"pragma table_info({table})")]
                for column in columns:
                    records["texts"] += [
                        value
                        for (value,) in db.execute(
                            f"select {column} from {table} where typeof({column}) = 'text'"
                        )
                    ]
            db.close()
        except sqlite3.DatabaseError:
            continue
    return records


def fold_tasks(notices):
    """Each call task's latest snapshot with its whole transcript; records after the first carry only new items."""
    tasks = {}
    for text in notices:
        record = _json(text)
        if not isinstance(record, dict) or not isinstance(record.get("task"), dict):
            continue
        task = dict(record["task"])
        task.setdefault("transcript", [])
        previous = tasks.get(task["id"])
        if record.get("transcriptFrom", 0) and previous:
            task["transcript"] = (
                previous["transcript"][: record["transcriptFrom"]] + task["transcript"]
            )
        tasks[task["id"]] = task
    return list(tasks.values())


def strings(value):
    """A list argument as EVA journals it: a JSON-encoded array, or already a list."""
    if isinstance(value, str):
        value = _json(value) if value.strip().startswith("[") else [value]
    return [
        item.get("reference", "") if isinstance(item, dict) else str(item)
        for item in value or []
    ]


def state_kind(task):
    return (task or {}).get("state", {}).get("type", "").rsplit(".", 1)[-1]


TERMINAL = {"Accomplished", "NotAccomplished", "Uncertain", "Interrupted"}


def secret_handles(data):
    """Every {name: {handle, label}} under `secrets` in an invocation's data."""
    found = {}
    if isinstance(data, dict):
        for key, value in data.items():
            if key == "secrets" and isinstance(value, dict):
                found.update(
                    {
                        name: entry
                        for name, entry in value.items()
                        if isinstance(entry, dict) and "handle" in entry
                    }
                )
            else:
                found.update(secret_handles(value))
    elif isinstance(data, list):
        for value in data:
            found.update(secret_handles(value))
    return found


def check(name, passed, detail):
    return {"name": name, "passed": bool(passed), "detail": detail}


def assess(
    invocations, tasks, haystacks, evaluation, gates, card_digits, holder, number_field
):
    """
    One PASS/FAIL per rule of the regression, over records new since the request. [haystacks]
    maps a source name (EVA's databases, logcat) to its text; the call transcript is added here.
    """
    starts = [i for i in invocations if i["capability_id"] == START]
    start = starts[-1] if starts else None
    thread = start and start["thread_id"]
    task = next(
        (t for t in reversed(tasks) if thread and t.get("threadId") == thread),
        tasks[-1] if tasks else None,
    )
    before = [
        i
        for i in invocations
        if start and i["thread_id"] == thread and i["created_at"] < start["created_at"]
    ]
    searches = [i for i in before if i["capability_id"] == MEMORY_SEARCH]
    lookups = [
        i
        for i in before
        if i["capability_id"].endswith(GET_CREDENTIAL) and i["status"] == "COMPLETED"
    ]
    handles = {
        name: entry
        for lookup in lookups
        for name, entry in secret_handles(lookup["data"]).items()
    }
    number = {
        entry["handle"]
        for entry in handles.values()
        if entry.get("label", "").lower().startswith(f"{number_field} of ")
    }
    requested = [
        f
        for lookup in lookups
        for f in strings(lookup["arguments"].get("fields"))
        if f.lower() == number_field
    ]
    args = (start or {}).get("arguments", {})
    credentials = strings(args.get("credentials"))
    transcript = (task or {}).get("transcript", [])
    keyed = [
        item["text"]
        for item in transcript
        if item.get("speaker") == "DTMF" and f"[credential {SECRET}" in item["text"]
    ]
    state = (task or {}).get("state", {})
    outcome = state.get("claim") or state.get("reason") or ""
    pattern = card_pattern(card_digits)
    sources = {
        **haystacks,
        "call transcript": "\n".join(i.get("text", "") for i in transcript),
    }
    leaks = [name for name, text in sources.items() if pattern.search(text or "")]
    refusals = [
        f"{i['capability_id']}: {i['message']}"
        for i in invocations
        if any(gate.lower() in (i["message"] or "").lower() for gate in gates)
    ]
    missing_facts = [
        label for label, regex in FACTS if not re.search(regex, outcome, re.IGNORECASE)
    ]
    no_start = "EVA never called eva.call.start"
    return [
        check(
            "memory searched before dialing",
            searches,
            f"{len(searches)} eva.memory.search before eva.call.start"
            if start
            else no_start,
        ),
        check(
            "card looked up in Password Store before dialing",
            lookups and (requested or number),
            (
                f"{len(lookups)} completed get_credential; fields requested: {requested or 'none'}; "
                f"{number_field} secrets: {len(number)}"
            )
            if start
            else no_start,
        ),
        check(
            f"call placed on behalf of {holder}",
            holder.lower() in (args.get("onBehalfOf") or "").lower(),
            f"onBehalfOf={args.get('onBehalfOf')!r}" if start else no_start,
        ),
        check(
            "card number handed to the call as its secret handle",
            number & set(credentials),
            f"credentials={credentials}; card-number handles={sorted(number)}"
            if start
            else no_start,
        ),
        check(
            "card number entered by keypad credential",
            any(handle in text for text in keyed for handle in number),
            f"DTMF credential steps: {keyed or 'none'}",
        ),
        check(
            "card number never in the clear",
            not leaks,
            f"found in {', '.join(leaks)}"
            if leaks
            else f"absent from {', '.join(sources)}",
        ),
        check(
            "no uncertain-mutation refusal",
            not refusals,
            "; ".join(refusals)
            or f"no message contains {' or '.join(map(repr, gates))}",
        ),
        check(
            "call task has a definite outcome",
            state_kind(task) in ("Accomplished", "NotAccomplished"),
            f"{state_kind(task) or 'no call task'}: "
            + (state.get("claim") or state.get("reason") or state.get("unknown") or ""),
        ),
        check(
            "outcome reports the annual-fee facts",
            state_kind(task) in ("Accomplished", "NotAccomplished")
            and not missing_facts,
            f"missing {', '.join(missing_facts)}"
            if missing_facts
            else "fee, no waiver and Test Cash all reported",
        ),
        check(
            "hotline received the card number, name and ZIP",
            evaluation and evaluation.get("passed"),
            "; ".join(
                f"{'PASS' if c['passed'] else 'FAIL'} {c['step']} {c['kind']}={c['expected']} received={c['received']}"
                for c in evaluation["checks"]
            )
            if evaluation
            else "no hotline evaluation (verify --scenario did not run or failed)",
        ),
    ]
