import copy
import json
import unittest

import card_test

HANDLE = "secret:" + "ab" * 16
DIGITS = "4111111111111111"
GATES = [
    "An action in this thread may have changed external state",
    "in this thread ended with an unknown outcome, so it may have changed external state",
]
FACTS_CLAIM = (
    "The annual fee is 95 dollars and cannot be waived; a product change to the no-fee "
    "Test Cash card keeps points and history."
)


def invocation(capability, created_at, status="COMPLETED", message="Done.", **extra):
    return {
        "call_id": f"call-{created_at}",
        "capability_id": capability,
        "status": status,
        "message": message,
        "created_at": created_at,
        "thread_id": "thread-1",
        "arguments": extra.get("arguments", {}),
        "data": extra.get("data"),
    }


def good_records():
    invocations = [
        invocation(
            "eva.memory.search",
            1,
            arguments={"query": "name"},
        ),
        invocation(
            "app.passwordstore.colonelpanic.debug.get_credential",
            2,
            arguments={
                "path": "cards/test-card-services",
                "fields": '["number","zip"]',
            },
            data={
                "secrets": {
                    "number": {
                        "handle": HANDLE,
                        "label": "number of cards/test-card-services",
                    }
                }
            },
        ),
        invocation(
            "eva.call.start",
            3,
            arguments={
                "onBehalfOf": "Casey Sample",
                "credentials": json.dumps([HANDLE]),
            },
        ),
    ]
    task = {
        "id": "call-3",
        "threadId": "thread-1",
        "state": {
            "type": "com.colonelpanic.eva.call.CallTaskState.Accomplished",
            "claim": FACTS_CLAIM,
            "evidence": ["item-9"],
        },
        "transcript": [
            {"speaker": "REMOTE", "text": "Please enter your 16 digit card number."},
            {"speaker": "DTMF", "text": f"[credential {HANDLE}]"},
            {"speaker": "REMOTE", "text": "I found the card ending in 1 1 1 1."},
        ],
    }
    evaluation = {
        "passed": True,
        "checks": [
            {
                "step": "card",
                "kind": "digits",
                "expected": DIGITS,
                "received": [DIGITS],
                "passed": True,
            }
        ],
    }
    return invocations, [task], evaluation


def failures(invocations, tasks, evaluation, haystacks=None):
    checks = card_test.assess(
        invocations,
        tasks,
        haystacks or {"EVA databases": "", "logcat": ""},
        evaluation,
        GATES,
        DIGITS,
        "Casey Sample",
        "number",
    )
    return sorted(c["name"] for c in checks if not c["passed"])


class AssessTest(unittest.TestCase):
    def test_a_prepared_keypad_call_passes_every_check(self):
        self.assertEqual(failures(*good_records()), [])

    def test_the_incident_blind_dial_fails_preparation_and_keypad_checks(self):
        _, tasks, _ = good_records()
        invocations = [
            invocation("eva.call.start", 3, arguments={"goal": "drop the annual fee"})
        ]
        tasks[0]["state"] = {
            "type": "com.colonelpanic.eva.call.CallTaskState.NotAccomplished",
            "reason": "The representative needed the primary cardholder.",
        }
        tasks[0]["transcript"] = [
            {"speaker": "AGENT", "text": "I can't provide a card number."}
        ]
        self.assertEqual(
            failures(invocations, tasks, None),
            [
                "call placed on behalf of Casey Sample",
                "card looked up in Password Store before dialing",
                "card number entered by keypad credential",
                "card number handed to the call as its secret handle",
                "hotline received the card number, name and ZIP",
                "memory searched before dialing",
                "outcome reports the annual-fee facts",
            ],
        )

    def test_an_honest_declined_waiver_with_facts_passes(self):
        invocations, tasks, evaluation = good_records()
        tasks[0]["state"] = {
            "type": "com.colonelpanic.eva.call.CallTaskState.NotAccomplished",
            "reason": FACTS_CLAIM,
        }
        self.assertEqual(failures(invocations, tasks, evaluation), [])

    def test_punctuated_and_spoken_readbacks_fail_privacy(self):
        for text in (
            "4111, 1111, 1111, 1111",
            "4111.1111.1111.1111",
            "4111  1111  1111  1111",
            "four " + "one " * 15,
            "four 111, " + "one " * 12,
        ):
            with self.subTest(text=text):
                self.assertEqual(
                    failures(*good_records(), {"logcat": text}),
                    ["card number never in the clear"],
                )

    def test_grouped_digits_anywhere_fail_the_privacy_check(self):
        invocations, tasks, evaluation = good_records()
        checks = card_test.assess(
            invocations,
            tasks,
            {"EVA databases": "", "logcat": "D/Eva: sent 4111 1111-1111 1111"},
            evaluation,
            GATES,
            DIGITS,
            "Casey Sample",
            "number",
        )
        privacy = next(
            c for c in checks if c["name"] == "card number never in the clear"
        )
        self.assertEqual(
            (privacy["passed"], privacy["detail"]), (False, "found in logcat")
        )

    def test_digits_spoken_on_the_call_fail_the_privacy_check(self):
        invocations, tasks, evaluation = good_records()
        tasks[0]["transcript"].append(
            {"speaker": "AGENT", "text": "It's 4111111111111111."}
        )
        self.assertEqual(
            failures(invocations, tasks, evaluation), ["card number never in the clear"]
        )

    def test_either_gate_refusal_fails(self):
        for message in (
            "An action in this thread may have changed external state. Check background_status.",
            (
                "Nothing was executed. An earlier Delegate a phone call (eva.call.start, call c1, 03:28) "
                "in this thread ended with an unknown outcome, so it may have changed external state."
            ),
        ):
            invocations, tasks, evaluation = good_records()
            invocations.insert(
                2,
                invocation(
                    "eva.call.start", 2.5, status="NOT_EXECUTED", message=message
                ),
            )
            self.assertEqual(
                failures(invocations, tasks, evaluation),
                ["no uncertain-mutation refusal"],
                message,
            )

    def test_a_claim_without_the_fee_facts_fails(self):
        invocations, tasks, evaluation = good_records()
        tasks[0]["state"]["claim"] = "Spoke with the representative about the card."
        self.assertEqual(
            failures(invocations, tasks, evaluation),
            ["outcome reports the annual-fee facts"],
        )


class RecordsTest(unittest.TestCase):
    def test_later_records_extend_the_first_transcript(self):
        first = {
            "task": {
                "id": "t",
                "state": {},
                "transcript": [{"text": "a"}, {"text": "b"}],
            }
        }
        later = copy.deepcopy(first)
        later["transcriptFrom"] = 2
        later["task"]["transcript"] = [{"text": "c"}]
        folded = card_test.fold_tasks(
            [json.dumps(first), json.dumps(later), "not json"]
        )
        self.assertEqual([i["text"] for i in folded[0]["transcript"]], ["a", "b", "c"])

    def test_gate_phrases_cover_both_refusal_wordings(self):
        wording = (
            "messages:\n"
            "  mutation-uncertain: >-\n"
            "    An action in this thread may have changed external state. Check background_status\n"
            "    for recent receipts.\n"
            "  uncertain-repeat: >-\n"
            "    Nothing was executed. An earlier {title} ({capability}, call {call_id}, {time}) in this\n"
            "    thread ended with an unknown outcome, so it may have changed external state. Before\n"
            "    repeating {capability}, check what that action did.\n"
            "  unowned-action: No request is active.\n"
        )
        self.assertEqual(card_test.gate_phrases(wording), GATES)


if __name__ == "__main__":
    unittest.main()
