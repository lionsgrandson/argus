import { DurableObject } from "cloudflare:workers";

// Camera frames are JPEGs at roughly 640x480 / low quality. Keep hard limits so
// a broken or malicious client still cannot turn a room into an unbounded relay.
const MAX_FRAME_BYTES = 768 * 1024;
const MAX_BYTES_PER_10S = 8 * 1024 * 1024;

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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json(
        { ok: true, protocol: 2, relay: "cloudflare-durable-object", audio: true, video: true },
        { headers: { "cache-control": "no-store" } },
      );
    }

    if (url.pathname !== "/ws") {
      return new Response("Baby Monitor relay. Use WebSocket /ws.", {
        status: 404,
        headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
      });
    }

    if (request.method !== "GET" || (request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }

    const roomId = url.searchParams.get("room") || "";
    const role = url.searchParams.get("role") || "";
    const version = url.searchParams.get("v") || "";
    const auth = authFrom(request);

    if (!validToken(roomId, 12, 32) || !validToken(auth, 16, 64) || !["baby", "parent"].includes(role) || version !== "2") {
      return new Response("Unauthorized", { status: 401 });
    }

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
    const role = url.searchParams.get("role") || "";
    const auth = authFrom(request);

    if (!["baby", "parent"].includes(role) || !validToken(auth, 16, 64)) {
      return new Response("Unauthorized", { status: 401 });
    }

    const suppliedHash = await sha256Hex(auth);
    const storedHash = await this.ctx.storage.get("authHash");
    if (storedHash && storedHash !== suppliedHash) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (!storedHash) await this.ctx.storage.put("authHash", suppliedHash);

    for (const [socket, session] of this.sessions) {
      if (session.role === role) {
        try { socket.close(4001, "Replaced by new same-role connection"); } catch {}
        this.sessions.delete(socket);
      }
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);

    const session = {
      role,
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
    } else {
      try { server.send("PEER:OFFLINE"); } catch {}
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    if (!session || session.role !== "baby") return;
    if (!(message instanceof ArrayBuffer)) return;

    const size = message.byteLength;
    if (size > MAX_FRAME_BYTES) {
      try { ws.close(4002, "Frame too large"); } catch {}
      this.sessions.delete(ws);
      return;
    }

    const now = Date.now();
    if (!session.windowStarted || now - session.windowStarted >= 10_000) {
      session.windowStarted = now;
      session.windowBytes = 0;
    }
    session.windowBytes += size;
    if (session.windowBytes > MAX_BYTES_PER_10S) {
      try { ws.close(4003, "Rate limit"); } catch {}
      this.sessions.delete(ws);
      return;
    }
    ws.serializeAttachment(session);
    this.sessions.set(ws, session);

    for (const [socket, state] of this.sessions) {
      if (state.role === "parent") {
        try { socket.send(message); } catch {}
      }
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    this.sessions.delete(ws);
    try { ws.close(code || 1000, reason || "Closed"); } catch {}
    this.notifyOffline(session?.role);
  }

  async webSocketError(ws) {
    const session = this.sessions.get(ws) || wsAttachment(ws);
    this.sessions.delete(ws);
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
