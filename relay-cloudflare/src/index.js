import { DurableObject } from "cloudflare:workers";

const PROTOCOL_VERSION = "4";
const PAIRING_EPOCH = "reset-2026-09-01-v4";
const MAX_FRAME_BYTES = 768 * 1024;
const MAX_BYTES_PER_10S = 32 * 1024 * 1024;

function validToken(value, min, max) {
  return typeof value === "string" && value.length >= min && value.length <= max && /^[A-Za-z0-9_-]+$/.test(value);
}

async function sha256Hex(value) {
  const data = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function authFrom(request) {
  const header = request.headers.get("Authorization") || "";
  return header.startsWith("Bearer ") ? header.slice(7) : "";
}

function wsAttachment(ws) {
  try {
    return ws.deserializeAttachment() || {};
  } catch {
    return {};
  }
}

function roomHint(roomId) {
  return roomId ? roomId.slice(0, 6) : "none";
}

function logEvent(event, data = {}) {
  console.log(JSON.stringify({ event, ...data, at: new Date().toISOString() }));
}

function errorResponse(code, status, message) {
  return new Response(message, {
    status,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
      "x-argus-error": code,
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json(
        {
          ok: true,
          protocol: Number(PROTOCOL_VERSION),
          pairingEpoch: PAIRING_EPOCH,
          relay: "cloudflare-durable-object",
          pairing: "persistent-within-current-epoch",
          reconnect: true,
          audio: true,
          video: true,
          parentStreamControl: true,
          errorCodes: true,
        },
        { headers: { "cache-control": "no-store" } },
      );
    }

    if (url.pathname !== "/ws") {
      return errorResponse("E204", 404, "ARGUS relay. Use WebSocket /ws.");
    }

    if (request.method !== "GET" || (request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      logEvent("upgrade_required");
      return errorResponse("E204", 426, "WebSocket upgrade required");
    }

    const roomId = url.searchParams.get("room") || "";
    const role = url.searchParams.get("role") || "";
    const version = url.searchParams.get("v") || "";
    const auth = authFrom(request);
    const hint = roomHint(roomId);

    if (version !== PROTOCOL_VERSION) {
      logEvent("connection_rejected", { reason: "pairing_epoch_reset", role, room: hint, version });
      return errorResponse("E102", 426, "Old ARGUS pairing protocol was reset. Create a new pairing code.");
    }
    if (!validToken(roomId, 12, 32)) {
      logEvent("connection_rejected", { reason: "invalid_room", role, room: hint });
      return errorResponse("E205", 401, "Invalid room");
    }
    if (!validToken(auth, 16, 64)) {
      logEvent("connection_rejected", { reason: "invalid_auth", role, room: hint });
      return errorResponse("E205", 401, "Invalid authorization");
    }
    if (!["baby", "parent"].includes(role)) {
      logEvent("connection_rejected", { reason: "invalid_role", role, room: hint });
      return errorResponse("E205", 401, "Invalid role");
    }

    logEvent("connection_attempt", { role, room: hint, epoch: PAIRING_EPOCH });
    const stub = env.ROOMS.getByName(`${PAIRING_EPOCH}:${roomId}`);
    return stub.fetch(request);
  },
};

export class RoomV4 extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sessions = new Map();

    for (const ws of this.ctx.getWebSockets()) {
      const attachment = wsAttachment(ws);
      if (attachment.role) this.sessions.set(ws, attachment);
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.searchParams.get("room") || "";
    const role = url.searchParams.get("role") || "";
    const version = url.searchParams.get("v") || "";
    const auth = authFrom(request);
    const hint = roomHint(roomId);

    if (version !== PROTOCOL_VERSION) {
      logEvent("room_connection_rejected", { role, room: hint, reason: "pairing_epoch_reset" });
      return errorResponse("E102", 426, "Old ARGUS pairing protocol was reset");
    }
    if (!["baby", "parent"].includes(role) || !validToken(auth, 16, 64)) {
      logEvent("room_connection_rejected", { role, room: hint, reason: "invalid_credentials" });
      return errorResponse("E205", 401, "Unauthorized");
    }

    const suppliedHash = await sha256Hex(auth);
    const storedHash = await this.ctx.storage.get("authHash");

    if (storedHash && storedHash !== suppliedHash) {
      logEvent("room_connection_rejected", { role, room: hint, reason: "pairing_mismatch" });
      return errorResponse("E205", 409, "Pairing mismatch");
    }

    if (!storedHash) {
      await this.ctx.storage.put("authHash", suppliedHash);
      await this.ctx.storage.put("pairedAt", Date.now());
      await this.ctx.storage.put("pairingEpoch", PAIRING_EPOCH);
      logEvent("room_paired", { role, room: hint, epoch: PAIRING_EPOCH });
    }

    for (const [socket, session] of this.sessions) {
      if (session.role === role) {
        try { socket.close(4001, "Replaced by reconnect"); } catch {}
        this.sessions.delete(socket);
        logEvent("stale_connection_replaced", { role, room: hint });
      }
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);

    const session = {
      role,
      roomHint: hint,
      windowStarted: Date.now(),
      windowBytes: 0,
      pairingEpoch: PAIRING_EPOCH,
    };
    server.serializeAttachment(session);
    this.sessions.set(server, session);

    const otherRole = role === "baby" ? "parent" : "baby";
    let other = null;
    for (const [socket, state] of this.sessions) {
      if (socket !== server && state.role === otherRole) {
        other = socket;
        break;
      }
    }

    if (other) {
      try { server.send("PEER:ONLINE"); } catch {}
      try { other.send("PEER:ONLINE"); } catch {}
      logEvent("pair_online", { room: hint });
    } else {
      try { server.send("PEER:OFFLINE"); } catch {}
      logEvent("phone_connected_waiting_for_peer", { role, room: hint });
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    if (!session || !["baby", "parent"].includes(session.role)) return;
    if (!(message instanceof ArrayBuffer)) return;

    const size = message.byteLength;
    if (size > MAX_FRAME_BYTES) {
      logEvent("frame_rejected", { room: session.roomHint || "unknown", role: session.role, reason: "frame_too_large", bytes: size, code: "E209" });
      return;
    }

    const now = Date.now();
    if (!session.windowStarted || now - session.windowStarted >= 10_000) {
      session.windowStarted = now;
      session.windowBytes = 0;
    }
    session.windowBytes += size;

    if (session.windowBytes > MAX_BYTES_PER_10S) {
      logEvent("frame_rejected", { room: session.roomHint || "unknown", role: session.role, reason: "temporary_rate_limit", code: "E210" });
      return;
    }

    ws.serializeAttachment(session);
    this.sessions.set(ws, session);

    const targetRole = session.role === "baby" ? "parent" : "baby";
    for (const [socket, state] of this.sessions) {
      if (state.role === targetRole) {
        try {
          socket.send(message);
        } catch (error) {
          logEvent("forward_error", { room: session.roomHint || "unknown", role: session.role, code: "E211", message: error?.message || "send failed" });
        }
      }
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    this.sessions.delete(ws);
    logEvent("phone_disconnected", {
      role: session?.role || "unknown",
      room: session?.roomHint || "unknown",
      code,
      clean: Boolean(wasClean),
    });
    try { ws.close(code || 1000, reason || "Closed"); } catch {}
    this.notifyOffline(session?.role);
  }

  async webSocketError(ws, error) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    this.sessions.delete(ws);
    logEvent("socket_error", {
      role: session?.role || "unknown",
      room: session?.roomHint || "unknown",
      code: "E212",
      message: error?.message || "websocket error",
    });
    this.notifyOffline(session?.role);
  }

  notifyOffline(closedRole) {
    if (!closedRole) return;

    for (const [, state] of this.sessions) {
      if (state.role === closedRole) return;
    }

    const otherRole = closedRole === "baby" ? "parent" : "baby";
    for (const [socket, state] of this.sessions) {
      if (state.role === otherRole) {
        try { socket.send("PEER:OFFLINE"); } catch {}
      }
    }
  }
}

// Existing v1 Durable Objects still reference this export. Keep it available
// while all new connections use the RoomV4 binding and fresh v4 namespace.
export class Room extends RoomV4 {}
