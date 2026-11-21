// rxConnect push server (Cloudflare Worker at rxsas.it/rxconnect-push/): relays between the phones of a room, through
// Firebase. The server never sees room names, only their SHA-256 (announce in web/index.html).
//
// POST /room  {room, token, here, name}  An Android phone (token = its Firebase token) signs up for the room, leaving
//             the one it was in before. here: someone joined, so the room's other phones get {kind: 'here', name}.
//             Windows and browsers send token "" (they only join, they get no pushes).
// POST /send  {room, token, kind, name}  kind: 'gone' (left the room), 'nudge', 'think' (thinking of you), 'call'
//             (wanna call?): passed on to the room's other phones as {kind, name}.
// POST /image {room, token, which, name, data}  which: 'photo' or 'board'; data: the image, encrypted on the phone with
//             a key from the room name (the server can't read it), base64. Kept 30 days; the other phones get
//             {kind: 'image', which, name} and fetch it with GET /image?room=…&which=….
//
// KV (binding ROOMS): "r:<room>" -> {tokens: [...]}, "t:<token>" -> its room, "i:<room>:<which>" -> the image.
// Secret FCM_SERVICE_ACCOUNT: the Firebase service account key (JSON), used to sign Google's OAuth token.

const MAX_PHONES = 5; // per room: the two of you, plus reinstalls; the oldest drop off
const KINDS = ['gone', 'nudge', 'think', 'call'];
const MAX_IMAGE = 1_500_000; // base64 chars: a 600x600 JPEG is ~100 KB

export default {
  async fetch(req, env) {
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type' };
    const reply = (body, status = 200) => new Response(body, { status, headers: cors });
    if (req.method === 'OPTIONS') return reply(null);
    const url = new URL(req.url), path = url.pathname.replace(/^.*\//, '');
    if (req.method === 'GET' && path === 'image') {
      const room = url.searchParams.get('room'), which = url.searchParams.get('which');
      if (!isRoom(room) || !['photo', 'board'].includes(which)) return reply('Bad request', 400);
      const data = await env.ROOMS.get(`i:${room}:${which}`);
      return data ? reply(data) : reply('Not found', 404);
    }
    if (req.method !== 'POST' || !['room', 'send', 'image'].includes(path)) return reply('Not found', 404);
    let b;
    try { b = await req.json(); } catch { return reply('Bad JSON', 400); }
    const { room, token = '', name = '' } = b || {};
    if (!isRoom(room) || typeof token !== 'string' || token.length > 4096 || typeof name !== 'string' || name.length > 40)
      return reply('Bad request', 400);
    if (path === 'room') {
      if (typeof b.here !== 'boolean') return reply('Bad request', 400);
      await signUp(env, room, token);
      if (b.here) await relay(env, room, token, { kind: 'here', name });
    } else if (path === 'send') {
      if (!KINDS.includes(b.kind)) return reply('Bad request', 400);
      await relay(env, room, token, { kind: b.kind, name });
    } else {
      if (!['photo', 'board'].includes(b.which) || typeof b.data !== 'string' || !b.data || b.data.length > MAX_IMAGE)
        return reply('Bad request', 400);
      await env.ROOMS.put(`i:${room}:${b.which}`, b.data, { expirationTtl: 30 * 24 * 3600 });
      await relay(env, room, token, { kind: 'image', which: b.which, name });
    }
    return reply('ok');
  },
};

const isRoom = r => typeof r === 'string' && /^[0-9a-f]{64}$/.test(r);

// This phone signs up for the room (and leaves the one it was in). No writes when nothing changed.
async function signUp(env, room, token) {
  if (!token) return;
  const old = await env.ROOMS.get('t:' + token);
  if (old !== room) {
    if (old) {
      const o = await env.ROOMS.get('r:' + old, 'json');
      if (o) await env.ROOMS.put('r:' + old, JSON.stringify({ tokens: o.tokens.filter(t => t !== token) }));
    }
    await env.ROOMS.put('t:' + token, room);
  }
  const rec = (await env.ROOMS.get('r:' + room, 'json')) || { tokens: [] };
  if (!rec.tokens.includes(token)) await env.ROOMS.put('r:' + room, JSON.stringify({ tokens: [...rec.tokens, token].slice(-MAX_PHONES) }));
}

// Sends data to the room's phones except the sender's; forgets phones Firebase says are gone (app uninstalled).
async function relay(env, room, token, data) {
  const rec = await env.ROOMS.get('r:' + room, 'json');
  const others = rec ? rec.tokens.filter(t => t !== token) : [];
  if (!others.length) return;
  const gone = await send(env, others, data);
  if (gone.length) await env.ROOMS.put('r:' + room, JSON.stringify({ tokens: rec.tokens.filter(t => !gone.includes(t)) }));
}

// A data-only message: the phone writes the text itself (PushService).
async function send(env, tokens, data) {
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const auth = 'Bearer ' + await accessToken(sa);
  const gone = [];
  await Promise.all(tokens.map(async token => {
    // A nudge waits for a phone that's off (FCM keeps it up to 28 days); the rest are stale after 10 minutes.
    const ttl = data.kind === 'nudge' ? '2419200s' : '600s';
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: 'POST',
      headers: { authorization: auth, 'content-type': 'application/json' },
      body: JSON.stringify({ message: { token, data, android: { priority: 'high', ttl } } }),
    });
    if (r.status === 404) gone.push(token);
  }));
  return gone;
}

// Google OAuth token for FCM, from a JWT signed with the service account key; kept for the hour it lasts.
let cached = null;
async function accessToken(sa) {
  if (cached && cached.until > Date.now()) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const part = o => b64url(new TextEncoder().encode(JSON.stringify(o)));
  const unsigned = part({ alg: 'RS256', typ: 'JWT' }) + '.' + part({
    iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600,
  });
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, '')), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const jwt = unsigned + '.' + b64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=' + jwt,
  });
  if (!r.ok) throw new Error('Google OAuth: ' + r.status + ' ' + await r.text());
  const { access_token, expires_in } = await r.json();
  cached = { token: access_token, until: Date.now() + (expires_in - 300) * 1000 };
  return access_token;
}

function b64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
