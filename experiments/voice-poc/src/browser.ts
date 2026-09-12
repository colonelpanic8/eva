import { CounterDisplay } from "./counter-display.ts";
import { Lifetime, waitForState } from "./lifetime.ts";

function element<T extends HTMLElement>(id: string): T {
  const result = document.getElementById(id);
  if (!result) throw new Error(`Missing ${id}`);
  return result as T;
}
const connect = element<HTMLButtonElement>("connect");
const disconnect = element<HTMLButtonElement>("disconnect");
const mic = element<HTMLInputElement>("mic");
const status = element("status");
const errors = element("errors");
const audio = element<HTMLAudioElement>("audio");
const transcript = element("transcript");
const events = element("events");
const diagnostics: Record<string, unknown>[] = [];
let lifetime: Lifetime | undefined;
let socket: WebSocket | undefined;
let stream: MediaStream | undefined;
let lastInputAt: number | undefined;
let firstAudioSeen = false;
let setupAt = 0;
let generation = 0;
let counterDisplay = new CounterDisplay();
const accessToken = location.hash.slice(1);
history.replaceState(null, "", location.pathname);
element<HTMLInputElement>("access").value = accessToken;

function log(event: Record<string, unknown>): void {
  const { sdp: _sdp, text: _text, ...safe } = event;
  diagnostics.push({ browserAtMs: Math.round(performance.now()), ...safe });
  if (diagnostics.length > 1000) diagnostics.shift();
  events.textContent = diagnostics
    .slice(-60)
    .map((row) => JSON.stringify(row))
    .join("\n");
}
function message(role: string, text: string): void {
  const row = document.createElement("p");
  row.textContent = `${role}: ${text}`;
  transcript.append(row);
  while (transcript.childElementCount > 100) transcript.firstElementChild?.remove();
}
function showError(error: unknown): void {
  errors.textContent = error instanceof Error ? error.message : String(error);
}
function send(value: unknown): void {
  if (socket?.readyState !== WebSocket.OPEN) throw new Error("Control connection is not open");
  socket.send(JSON.stringify(value));
}
function stop(): void {
  generation++;
  lifetime?.close();
  lifetime = undefined;
  stream = undefined;
  socket = undefined;
  audio.pause();
  audio.srcObject = null;
  connect.disabled = false;
  disconnect.disabled = true;
  mic.disabled = false;
  element<HTMLButtonElement>("send").disabled = true;
  status.textContent = "Disconnected";
}

async function start(): Promise<void> {
  errors.textContent = "";
  if (!window.isSecureContext)
    throw new Error(
      "Microphone/WebRTC testing requires desktop localhost or HTTPS. This tailnet HTTP URL is a preview only.",
    );
  const scope = new Lifetime();
  lifetime = scope;
  setupAt = performance.now();
  counterDisplay = new CounterDisplay();
  element("counter").textContent = "0";
  element("turns").textContent = "0";
  element("action").textContent = "No tool has executed in this session.";
  audio.muted = false;
  connect.disabled = true;
  disconnect.disabled = false;
  mic.disabled = true;
  status.textContent = "Connecting…";
  const pc = new RTCPeerConnection();
  scope.add(() => pc.close());
  const listeners = new AbortController();
  scope.add(() => listeners.abort());
  let disconnectTimer: ReturnType<typeof setTimeout> | undefined;
  scope.add(() => clearTimeout(disconnectTimer));
  pc.addEventListener(
    "connectionstatechange",
    () => {
      log({
        kind: "webrtc-state",
        state: pc.connectionState,
        setupMs: Math.round(performance.now() - setupAt),
      });
      clearTimeout(disconnectTimer);
      if (pc.connectionState === "connected") status.textContent = "Connected · WebRTC";
      else if (pc.connectionState === "failed") {
        showError("WebRTC failed");
        stop();
      } else if (pc.connectionState === "disconnected")
        disconnectTimer = setTimeout(() => {
          showError("WebRTC remained disconnected for 8 seconds");
          stop();
        }, 8000);
    },
    { signal: listeners.signal },
  );
  pc.addEventListener(
    "track",
    (event) => {
      audio.srcObject = event.streams[0] ?? new MediaStream([event.track]);
      void audio.play().catch(() => showError("Playback blocked. Press Resume speaker."));
    },
    { signal: listeners.signal },
  );
  let acquired: MediaStream;
  if (mic.checked) {
    acquired = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true },
    });
  } else {
    // A silent sendrecv track makes the text smoke independent of any microphone.
    const context = new AudioContext();
    scope.add(() => {
      void context.close();
    });
    const destination = context.createMediaStreamDestination();
    const silence = context.createConstantSource();
    silence.offset.value = 0;
    silence.connect(destination);
    silence.start();
    scope.add(() => silence.stop());
    await context.resume();
    acquired = destination.stream;
  }
  scope.add(() => {
    for (const track of acquired.getTracks()) track.stop();
  });
  scope.assertOpen();
  stream = acquired;
  for (const track of acquired.getTracks()) pc.addTrack(track, acquired);
  // Paseo's validated ordering: audio + oai-events before the fully gathered offer.
  const channel = pc.createDataChannel("oai-events");
  scope.add(() => channel.close());
  channel.addEventListener("open", () => log({ kind: "data-channel-open" }), {
    signal: listeners.signal,
  });
  channel.addEventListener(
    "message",
    (event) => {
      try {
        const data = JSON.parse(String(event.data));
        log({
          kind: "data-channel-event",
          type: typeof data.type === "string" ? data.type : "unknown",
          bytes: String(event.data).length,
        });
      } catch {
        log({ kind: "data-channel-non-json", bytes: String(event.data).length });
      }
    },
    { signal: listeners.signal },
  );
  await pc.setLocalDescription(await pc.createOffer());
  await waitForState(
    pc,
    "icegatheringstatechange",
    () => pc.iceGatheringState === "complete",
    scope,
    10000,
  );
  scope.assertOpen();
  const ws = new WebSocket(
    `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/control`,
  );
  socket = ws;
  scope.add(() => ws.close());
  ws.addEventListener(
    "close",
    () => {
      if (!scope.closed) {
        showError("Control connection closed; reconnect to start a fresh counter/session.");
        stop();
      }
    },
    { signal: listeners.signal },
  );
  ws.addEventListener("error", () => showError("Cannot reach the EVA host"), {
    signal: listeners.signal,
  });
  ws.addEventListener(
    "message",
    (event) => {
      void (async () => {
        scope.assertOpen();
        const data = JSON.parse(String(event.data));
        log(data);
        if (data.kind === "authorized") {
          send({
            type: "start",
            sdp: pc.localDescription?.sdp,
            instructions: element<HTMLTextAreaElement>("instructions").value,
          });
        } else if (data.kind === "answer") {
          await pc.setRemoteDescription({ type: "answer", sdp: data.sdp });
        } else if (data.kind === "started") {
          element<HTMLButtonElement>("send").disabled = false;
        } else if (data.kind === "transcript") message(data.role, data.text);
        else if (data.kind === "backend-output") message("executor", data.text);
        else if (data.kind === "dispatch") {
          element("counter").textContent = String(counterDisplay.apply(data));
          element("action").textContent = JSON.stringify(data, null, 2);
        } else if (data.kind === "backend-turn") element("turns").textContent = String(data.count);
        else if (data.kind === "error" || data.kind === "turn-error") showError(data.message);
        else if (data.kind === "closed") stop();
      })().catch((error) => {
        if (!scope.closed) {
          showError(error);
          stop();
        }
      });
    },
    { signal: listeners.signal },
  );
  await waitForState(ws, "open", () => ws.readyState === WebSocket.OPEN, scope, 10000);
  send({ token: element<HTMLInputElement>("access").value });
  const statsTimer = setInterval(() => {
    void pc
      .getStats()
      .then((stats) => {
        if (scope.closed) return;
        for (const row of stats.values()) {
          if ((row.type === "inbound-rtp" || row.type === "outbound-rtp") && row.kind === "audio") {
            log({
              kind: "webrtc-audio",
              direction: row.type,
              packets: row.packetsReceived ?? row.packetsSent,
              bytes: row.bytesReceived ?? row.bytesSent,
              energy: row.totalAudioEnergy,
            });
            if (
              row.type === "inbound-rtp" &&
              row.audioLevel > 0 &&
              lastInputAt &&
              !firstAudioSeen
            ) {
              firstAudioSeen = true;
              log({
                kind: "first-audio-sample",
                inputToAudioMs: Math.round(performance.now() - lastInputAt),
                samplingIntervalMs: 1000,
              });
            }
          } else if (row.type === "candidate-pair" && row.state === "succeeded" && row.nominated) {
            const remote = stats.get(row.remoteCandidateId);
            log({
              kind: "selected-pair",
              protocol: remote?.protocol,
              candidateType: remote?.candidateType,
              rttSeconds: row.currentRoundTripTime,
            });
          }
        }
      })
      .catch(() => {});
  }, 1000);
  scope.add(() => clearInterval(statsTimer));
}
connect.onclick = () => {
  const attempt = ++generation;
  void start().catch((error) => {
    if (attempt !== generation) return;
    showError(error);
    stop();
  });
};
disconnect.onclick = () => {
  try {
    send({ type: "stop" });
  } catch {}
  stop();
};
element("mute").onclick = () => {
  for (const track of stream?.getAudioTracks() ?? []) track.enabled = !track.enabled;
  element("mute").textContent = stream?.getAudioTracks()[0]?.enabled
    ? "Mute microphone"
    : "Unmute microphone";
};
element("interrupt").onclick = () => {
  const at = performance.now();
  audio.muted = true;
  log({ kind: "local-interrupt", muteMs: performance.now() - at, providerCancelVerified: false });
  try {
    send({
      type: "text",
      route: "realtime-context",
      text: "Stop speaking now. Wait for my next request.",
    });
  } catch (error) {
    showError(error);
  }
};
element("resume").onclick = () => {
  audio.muted = false;
  void audio.play().catch(showError);
};
element<HTMLFormElement>("text-form").onsubmit = (event) => {
  event.preventDefault();
  const input = element<HTMLInputElement>("text");
  try {
    lastInputAt = performance.now();
    firstAudioSeen = false;
    audio.muted = false;
    send({
      type: "text",
      route: element<HTMLSelectElement>("text-route").value,
      text: input.value,
    });
    message("typed", input.value);
    input.value = "";
  } catch (error) {
    showError(error);
  }
};
element("export").onclick = () => {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(diagnostics, null, 2)], { type: "application/json" }),
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "eva-voice-diagnostics.json";
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
window.addEventListener("pagehide", stop);
if (!isSecureContext)
  showError("HTTP tailnet preview: open desktop localhost or use HTTPS for microphone testing.");
// Exposes sanitized metadata for the separate, explicitly invoked smoke runner.
Object.assign(window, { evaDiagnostics: diagnostics });
