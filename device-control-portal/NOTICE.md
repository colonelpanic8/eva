PortalScreen.kt and PrimitivePlanner.kt are adapted from voice-device-agent's
Apache-2.0 Kotlin companion loopback implementation. PortalClient.kt and
PortalBackend.kt port the Python bridge/portal/{client,mapping,adapter}.py
behavior to Kotlin. No companion app or Portal modification is required.

Target checks and bounds validation describe executable input semantics. The
prototype's approval, risk, evidence, injection and root-scope policies are not
included. Protocol fields remain wire-compatible.

Port baseline: `22e38e1ab688c647118ea7b67e7b5812d7abe76c`.
