const UPDATE_MANIFEST_KEY = "argus-updates/latest.json";
const UPDATE_OBJECT_PREFIX = "argus-updates/";
const MAX_APK_BYTES = 200 * 1024 * 1024;

function json(data, status = 200) {
  return Response.json(data, {
    status,
    headers: {
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function methodNotAllowed() {
  return new Response("Method not allowed", {
    status: 405,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
      "allow": "GET, HEAD",
    },
  });
}

function normalizeManifest(raw) {
  if (!raw || typeof raw !== "object") return null;

  const enabled = raw.enabled !== false;
  if (!enabled) {
    return {
      enabled: false,
      versionCode: 0,
      versionName: "",
      objectKey: "",
      sha256: "",
      sizeBytes: 0,
      required: false,
      notes: "",
    };
  }

  const versionCode = Number(raw.versionCode || 0);
  const versionName = String(raw.versionName || "").slice(0, 100);
  const objectKey = String(raw.objectKey || "").trim();
  const sha256 = String(raw.sha256 || "").trim().toLowerCase();
  const sizeBytes = Number(raw.sizeBytes || 0);
  const required = Boolean(raw.required);
  const notes = String(raw.notes || "").slice(0, 2000);

  if (!Number.isInteger(versionCode) || versionCode <= 0) return null;
  if (!objectKey.startsWith(UPDATE_OBJECT_PREFIX)) return null;
  if (objectKey.includes("..")) return null;
  if (!/^[0-9a-f]{64}$/.test(sha256)) return null;
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0 || sizeBytes > MAX_APK_BYTES) return null;

  return {
    enabled: true,
    versionCode,
    versionName,
    objectKey,
    sha256,
    sizeBytes,
    required,
    notes,
  };
}

async function readLatestManifest(env) {
  if (!env.UPDATES) return null;
  const object = await env.UPDATES.get(UPDATE_MANIFEST_KEY);
  if (!object) return null;

  try {
    const parsed = JSON.parse(await object.text());
    return normalizeManifest(parsed);
  } catch {
    return null;
  }
}

export function clientConfigResponse(request) {
  if (request.method !== "GET" && request.method !== "HEAD") return methodNotAllowed();

  const origin = new URL(request.url).origin;
  const data = {
    schema: 1,
    configVersion: 2,
    refreshSeconds: 300,
    relayUrls: [`${origin.replace(/^http:/, "ws:").replace(/^https:/, "wss:")}/ws`],
    updateManifestUrl: `${origin}/app-update`,
  };

  if (request.method === "HEAD") {
    return new Response(null, {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      },
    });
  }

  return json(data);
}

export async function updateManifestResponse(request, env) {
  if (request.method !== "GET" && request.method !== "HEAD") return methodNotAllowed();

  const manifest = await readLatestManifest(env);
  if (!manifest) {
    return json({
      enabled: false,
      versionCode: 0,
      versionName: "",
      apkUrl: "",
      sha256: "",
      sizeBytes: 0,
      required: false,
      notes: "",
    });
  }

  const origin = new URL(request.url).origin;
  const payload = {
    enabled: manifest.enabled,
    versionCode: manifest.versionCode,
    versionName: manifest.versionName,
    apkUrl: manifest.enabled ? `${origin}/app-update/apk` : "",
    sha256: manifest.sha256,
    sizeBytes: manifest.sizeBytes,
    required: manifest.required,
    notes: manifest.notes,
  };

  if (request.method === "HEAD") {
    return new Response(null, {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      },
    });
  }

  return json(payload);
}

export async function updateApkResponse(request, env) {
  if (request.method !== "GET" && request.method !== "HEAD") return methodNotAllowed();

  const manifest = await readLatestManifest(env);
  if (!manifest || !manifest.enabled) {
    return new Response("No update published", {
      status: 404,
      headers: { "cache-control": "no-store" },
    });
  }

  const apk = await env.UPDATES.get(manifest.objectKey);
  if (!apk) {
    return new Response("Update APK missing", {
      status: 404,
      headers: { "cache-control": "no-store" },
    });
  }

  const headers = new Headers();
  headers.set("content-type", "application/vnd.android.package-archive");
  headers.set("content-disposition", `attachment; filename="app-update-${manifest.versionCode}.apk"`);
  headers.set("cache-control", "private, no-store");
  headers.set("x-content-type-options", "nosniff");
  headers.set("x-argus-version-code", String(manifest.versionCode));
  headers.set("x-argus-sha256", manifest.sha256);
  if (apk.httpEtag) headers.set("etag", apk.httpEtag);
  if (apk.size) headers.set("content-length", String(apk.size));

  if (request.method === "HEAD") return new Response(null, { status: 200, headers });
  return new Response(apk.body, { status: 200, headers });
}
