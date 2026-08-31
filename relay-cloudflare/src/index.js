import { DurableObject } from "cloudflare:workers";

// ARGUS pairing rule:
// Once the two phones share the QR pairing credentials, that room stays paired.
// There is no server-side expiry, login, approval step, or re-pair requirement.
// If either phone loses internet, it can reconnect to the same room with the same
// credentials as many times as needed.
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json(
        {
          ok: true,
          protocol: 2,
          relay: "cloudflare-durable-object",
          pairing: "persistent",
          reconnect: true,
          audio: true,
          video: true,
          parentStreamControl: true,
        },
        { headers: { "cache-control": "no-store" } },
      );
    }

    if (url.pathname !== "/ws") {
      return new Response("ARGUS relay. Use WebSocket /ws.", {
        status: 404,
        headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
      });
    }

    if (request.method !== "GET" || (request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      logEvent("upgrade_required");
      return new Response("WebSocket upgrade required", { status: 426 });
    }

    const roomId = url.searchParams.get("room") || "";
    const role = url.searchParams.get("role") || "";
    const version = url.searchParams.get("v") || "";
    const auth = authFrom(request);
    const hint = roomHint(roomId);

    if (!validToken(roomId, 12, 32)) {
      logEvent("connection_rejected", { reason: "invalid_room", role, room: hint });
      return new Response("Unauthorized", { status: 401 });
    }
    if (!validToken(auth, 16, 64)) {
      logEvent("connection_rejected", { reason: "invalid_auth", role, room: hint });
      return new Response("Unauthorized", { status: 401 });
    }
    if (!["baby", "parent"].includes(role)) {
      logEvent("connection_rejected", { reason: "invalid_role", role, room: hint });
      return new Response("Unauthorized", { status: 401 });
    }
    if (version !== "2") {
      logEvent("connection_rejected", { reason: "protocol_version", role, room: hint, version });
      return new Response("Unauthorized", { status: 401 });
    }

    logEvent("connection_attempt", { role, room: hint });
    const stub = env.ROOMS.getByName(roomId);
    return stub.fetch(request);
  },
};

export class Room extends DurableObject {
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
    const auth = authFrom(request);
    const hint = roomHint(roomId);

    if (!["baby", "parent"].includes(role) || !validToken(auth, 16, 64)) {
      logEvent("room_connection_rejected", { role, room: hint, reason: "invalid_credentials" });
      return new Response("Unauthorized", { status: 401 });
    }

    const suppliedHash = await sha256Hex(auth);
    const storedHash = await this.ctx.storage.get("authHash");

    if (storedHash && storedHash !== suppliedHash) {
      logEvent("room_connection_rejected", { role, room: hint, reason: "pairing_mismatch" });
      return new Response("Unauthorized", { status: 401 });
    }

    if (!storedHash) {
      await this.ctx.storage.put("authHash", suppliedHash);
      await this.ctx.storage.put("pairedAt", Date.now());
      logEvent("room_paired", { role, room: hint });
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
      logEvent("frame_rejected", { room: session.roomHint || "unknown", role: session.role, reason: "frame_too_large", bytes: size });
      return;
    }

    const now = Date.now();
    if (!session.windowStarted || now - session.windowStarted >= 10_000) {
      session.windowStarted = now;
      session.windowBytes = 0;
    }
    session.windowBytes += size;

    if (session.windowBytes > MAX_BYTES_PER_10S) {
      logEvent("frame_rejected", { room: session.roomHint || "unknown", role: session.role, reason: "temporary_rate_limit" });
      return;
    }

    ws.serializeAttachment(session);
    this.sessions.set(ws, session);

    const targetRole = session.role === "baby" ? "parent" : "baby";
    for (const [socket, state] of this.sessions) {
      if (state.role === targetRole) {
        try { socket.send(message); } catch {}
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
