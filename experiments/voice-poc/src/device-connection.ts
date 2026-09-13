import { timingSafeEqual } from "node:crypto";
import { WebSocket, type WebSocketServer } from "ws";
import { record, safeError } from "./rpc.ts";
import { Session } from "./session.ts";
import { deviceTools, ToolRelay } from "./tool-relay.ts";

const instructions = `You are EVA, a concise assistant connected to a phone.
Answer conversational requests normally. For phone actions, use only the supplied tools.
Tool descriptions explain the available actions and their limits. Never claim an action
happened without its tool result. HANDED_OFF means Android opened another app, not that
the destination was reached or a message was sent. UNKNOWN means do not retry: ask the
user to check the action history or target app. Ask for missing information in conversation.
ACCEPTED confirms job acceptance only; COMPLETED confirms completion. Preserve structured
result evidence and distinguish required user resolution from completed execution.
This first integration permits one phone action per user request. Do not chain phone actions.
Use ordinary language; the user does not need command syntax.`;

const voiceInstructions = `${instructions}
This is a spoken conversation. Keep replies brief. When a phone action is requested, delegate
it and then say in one short sentence what the tool result reports.`;

export function attachDeviceConnections(wss: WebSocketServer, token: string): void {
  let owner: WebSocket | undefined;
  wss.on("connection", (ws) => {
    let authorized = false;
    let starting = false;
    let session: Session | undefined;
    let relay: ToolRelay | undefined;
    let inputId: string | undefined;
    let voice = false;
    let commands = 0;
    let alive = true;
    const send = (event: unknown) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(event));
    };
    const authTimer = setTimeout(() => ws.close(1008, "Authentication required"), 5000);
    const heartbeat = setInterval(() => {
      if (!alive) ws.terminate();
      else {
        alive = false;
        ws.ping();
      }
    }, 10_000);
    ws.on("pong", () => {
      alive = true;
    });
    ws.on("message", (raw) => {
      void (async () => {
        const msg = record(JSON.parse(raw.toString()));
        if (!authorized) {
          const supplied = Buffer.from(typeof msg.token === "string" ? msg.token : "");
          if (
            msg.version !== 1 ||
            supplied.length !== token.length ||
            !timingSafeEqual(supplied, Buffer.from(token))
          )
            throw new Error("Invalid broker access code or protocol version");
          if (owner) throw new Error("Another phone is connected");
          owner = ws;
          authorized = true;
          clearTimeout(authTimer);
          send({ kind: "authorized", version: 1 });
          return;
        }
        if (++commands > 300) throw new Error("Message limit reached");
        if (msg.type === "start") {
          if (starting) throw new Error("Catalog is fixed until disconnect");
          starting = true;
          if (
            typeof msg.catalogRevision !== "string" ||
            !msg.catalogRevision ||
            msg.catalogRevision.length > 128 ||
            typeof msg.instructions !== "string" ||
            msg.instructions.length > 4000
          )
            throw new Error("Invalid catalog revision or instructions");
          if (msg.mode !== undefined && msg.mode !== "voice")
            throw new Error("Unknown conversation mode");
          voice = msg.mode === "voice";
          if (
            voice &&
            (typeof msg.sdp !== "string" ||
              msg.sdp.length < 10 ||
              msg.sdp.length > 48_000 ||
              !Array.isArray(msg.tools))
          )
            throw new Error("Voice requires SDP and a catalog array");
          if (!voice && msg.sdp !== undefined) throw new Error("SDP requires voice mode");
          // An empty catalog in voice mode is a chat-only session; the model may not act.
          const tools =
            voice && Array.isArray(msg.tools) && msg.tools.length === 0
              ? []
              : deviceTools(msg.tools);
          const chatOnly = voice && tools.length === 0;
          relay = new ToolRelay(
            tools,
            (event) => {
              send(event);
              if (event.kind === "error") {
                void session?.close();
                ws.close(1011, "Phone result missing");
              }
            },
            30_000,
            msg.catalogRevision,
          );
          session = new Session(
            (event) => {
              if (
                chatOnly &&
                ["backend-turn", "backend-completed", "backend-output", "dispatch"].includes(
                  event.kind,
                )
              )
                return;
              try {
                if (event.kind === "backend-turn") {
                  const providerTurnId = String(event.providerTurnId ?? "");
                  // A spoken request has no typed input. The delegated backend turn is
                  // the unit the phone correlates against, so it becomes the input.
                  if (voice && !inputId) {
                    inputId = `voice:${providerTurnId}`;
                    relay?.beginInput(inputId);
                  }
                  relay?.startGeneration(providerTurnId);
                }
                if (event.kind === "backend-completed") relay?.completeInput();
              } catch (error) {
                session?.fail(error);
                return;
              }
              send({
                ...event,
                sessionId: relay?.sessionId,
                catalogRevision: relay?.catalogRevision,
                inputId,
                generationId: inputId,
              });
              if (event.kind === "backend-completed") inputId = undefined;
              if (event.kind === "closed") ws.close(1000, "Session ended");
            },
            {
              tools,
              chatOnly,
              instructions: chatOnly
                ? "You are EVA. Have a concise voice conversation. No phone actions are available in this session."
                : voice
                  ? voiceInstructions
                  : instructions,
              execute: relay.execute,
            },
          );
          await session.start(voice ? String(msg.sdp) : undefined, msg.instructions);
        } else if (msg.type === "text") {
          if (voice) throw new Error("Voice chat accepts microphone input only");
          if (
            !session ||
            !relay ||
            msg.sessionId !== relay.sessionId ||
            msg.catalogRevision !== relay.catalogRevision ||
            typeof msg.inputId !== "string" ||
            typeof msg.text !== "string" ||
            !msg.text.trim() ||
            msg.text.length > 1000
          )
            throw new Error("Invalid input or session");
          relay.beginInput(msg.inputId);
          inputId = msg.inputId;
          await session.text(msg.text);
        } else if (msg.type === "tool-result") {
          if (!relay) throw new Error("Start a session first");
          relay.accept(msg);
        } else if (msg.type === "stop") {
          relay?.close();
          await session?.close();
          ws.close(1000, "Disconnected");
        } else throw new Error("Unknown device command");
      })().catch((error) => {
        send({ kind: "error", message: safeError(error) });
        ws.close(1008, "Request rejected");
      });
    });
    ws.on("error", () => ws.terminate());
    ws.on("close", () => {
      clearTimeout(authTimer);
      clearInterval(heartbeat);
      if (owner === ws) owner = undefined;
      relay?.close();
      void session?.close();
    });
  });
}
