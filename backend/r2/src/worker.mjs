import { createRemoteJWKSet, jwtVerify } from 'jose';

const googleKeys = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));
const safeId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const limits = { 'preview.jpg': 6 * 1024 * 1024, original: 20 * 1024 * 1024, 'back-preview.jpg': 6 * 1024 * 1024, 'back-original': 20 * 1024 * 1024 };
class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new HttpError(status, message); };
const headers = { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' };

export async function verifyFirebaseToken(token, projectId, keys = googleKeys) {
  const { payload, protectedHeader } = await jwtVerify(token, keys, {
    algorithms: ['RS256'], audience: projectId, issuer: `https://securetoken.google.com/${projectId}`,
    requiredClaims: ['exp', 'iat', 'sub', 'auth_time'],
  });
  const now = Math.floor(Date.now() / 1000);
  if (!protectedHeader.kid || !safeId(payload.sub) || !Number.isInteger(payload.iat) || payload.iat > now ||
      !Number.isInteger(payload.auth_time) || payload.auth_time > now || payload.auth_time < 0) {
    throw new Error('Invalid identity');
  }
  return payload.sub;
}

export async function readManifest(token, projectId, uid, cardId, fetcher = fetch) {
  const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/databases/(default)/documents/users/${encodeURIComponent(uid)}/cards/${encodeURIComponent(cardId)}`;
  const result = await fetcher(url, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10000) });
  if ([401, 403, 404].includes(result.status)) fail(404, 'Card unavailable');
  if (!result.ok) fail(503, 'Image service temporarily unavailable');
  const document = await result.json();
  const value = field => document.fields?.[field]?.stringValue;
  const previousFields = document.fields?.previousRecord?.mapValue?.fields;
  const previousRecord = previousFields ? Object.fromEntries(['imagePath','originalPath','backImagePath','backOriginalPath'].map(key => [key, previousFields[key]?.stringValue])) : undefined;
  return { status: value('status'), imagePath: value('imagePath'), originalPath: value('originalPath'), backImagePath: value('backImagePath'), backOriginalPath: value('backOriginalPath'), previousRecord };
}

async function boundedBody(request, limit) {
  const declared = request.headers.get('Content-Length');
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > limit)) fail(413, 'Image exceeds size limit');
  if (!request.body) fail(400, 'Empty image');
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) { await reader.cancel(); fail(413, 'Image exceeds size limit'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  if (!length) fail(400, 'Empty image');
  const data = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength; }
  return data;
}

function validSignature(data, type) {
  if (type === 'image/jpeg') return data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff;
  if (type === 'image/png') return [137,80,78,71,13,10,26,10].every((v, i) => data[i] === v);
  return data.length >= 12 && new TextDecoder().decode(data.slice(0,4)) === 'RIFF' && new TextDecoder().decode(data.slice(8,12)) === 'WEBP';
}

// Dependencies are injectable for local verification; the deployed export uses only real verifiers.
export function createWorker({ verify = verifyFirebaseToken, manifest = readManifest } = {}) {
  return {
    async fetch(request, env) {
      try {
        const url = new URL(request.url);
        const match = /^\/v1\/cards\/([A-Za-z0-9_-]{1,128})\/((?:back-)?(?:original(?:-r[a-f0-9]{32})?|preview(?:-r[a-f0-9]{32})?\.jpg))$/.exec(url.pathname);
        if (!match || url.search) fail(404, 'Not found');
        if (!['GET', 'PUT', 'DELETE'].includes(request.method)) return new Response(null, { status: 405, headers: { ...headers, Allow: 'GET, PUT, DELETE' } });
        const bearer = /^Bearer ([A-Za-z0-9_.-]+)$/.exec(request.headers.get('Authorization') || '');
        if (!bearer || bearer[1].length > 8192) fail(401, 'Authentication required');
        let uid;
        try { uid = await verify(bearer[1], env.FIREBASE_PROJECT_ID); } catch { fail(401, 'Authentication required'); }
        if (!safeId(uid)) fail(401, 'Authentication required');
        const [, cardId, file] = match;
        const kind = file.replace(/-r[a-f0-9]{32}/, '');
        const field = ({original:'originalPath','preview.jpg':'imagePath','back-original':'backOriginalPath','back-preview.jpg':'backImagePath'})[kind];
        const key = `users/${uid}/cards/${cardId}/${file}`;
        const state = await manifest(bearer[1], env.FIREBASE_PROJECT_ID, uid, cardId);
        const expectedStatus = { GET: 'ready', PUT: 'uploading', DELETE: 'deleting' }[request.method];
        const current = state[field] === key;
        const previous = state.previousRecord?.[field] === key;
        const priorRead = request.method === 'GET' && ['uploading','rollingBack'].includes(state.status) && previous;
        const cleanup = request.method === 'DELETE' && ((state.status === 'ready' && previous && !current) || (state.status === 'rollingBack' && current && !previous));
        if (state.status !== expectedStatus && !priorRead && !cleanup) fail(409, 'Card is not ready for this operation');
        if (!current && !priorRead && !cleanup && !(request.method === 'DELETE' && state.status === 'deleting' && previous)) fail(404, 'Image unavailable');
        if (request.method === 'DELETE') {
          await env.CARD_IMAGES.delete(key);
          return new Response(null, { status: 204, headers });
        }
        if (request.method === 'GET') {
          const object = await env.CARD_IMAGES.get(key);
          if (!object) fail(404, 'Image unavailable');
          return new Response(object.body, { headers: { ...headers, 'Content-Type': object.httpMetadata?.contentType || 'application/octet-stream', 'Content-Length': String(object.size) } });
        }
        const type = request.headers.get('Content-Type')?.toLowerCase();
        const allowed = kind.endsWith('preview.jpg') ? ['image/jpeg'] : ['image/jpeg', 'image/png', 'image/webp'];
        if (!allowed.includes(type)) fail(415, 'Unsupported image type');
        const data = await boundedBody(request, limits[kind]);
        if (!validSignature(data, type)) fail(415, 'Image content does not match its type');
        // Recheck after reading the bounded body so a concurrent delete is less likely to race an upload.
        const latest = await manifest(bearer[1], env.FIREBASE_PROJECT_ID, uid, cardId);
        if (latest.status !== 'uploading' || latest[field] !== key || latest.previousRecord?.[field] === key) fail(409, 'Card changed during upload');
        await env.CARD_IMAGES.put(key, data, { httpMetadata: { contentType: type, cacheControl: 'private, no-store' } });
        // A timeout or unavailable manifest is ambiguous: another retry may already
        // have committed this revision. Preserve it for retry/durable cleanup.
        const after = await manifest(bearer[1], env.FIREBASE_PROJECT_ID, uid, cardId);
        if (!['uploading', 'ready', 'rollingBack', 'deleting'].includes(after.status)) fail(503, 'Image service temporarily unavailable');
        const stillCurrent = ['uploading', 'ready'].includes(after.status) && after[field] === key;
        // A late response from the preceding upload can overlap a later replacement.
        const stillRetained = ['uploading', 'ready', 'rollingBack'].includes(after.status) && after.previousRecord?.[field] === key;
        if (!stillCurrent && !stillRetained) {
          await env.CARD_IMAGES.delete(key);
          fail(409, 'Card changed during upload');
        }
        return new Response(null, { status: 204, headers });
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 503;
        return Response.json({ error: error instanceof HttpError ? error.message : 'Image service temporarily unavailable' }, { status, headers });
      }
    },
  };
}
export default createWorker();
