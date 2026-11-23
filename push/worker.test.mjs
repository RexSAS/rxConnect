// node --test push/worker.test.mjs  (no dependencies): the Worker against a fake KV and a fake Google that checks the
// JWT signature.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

const keys = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
const pkcs8 = Buffer.from(await crypto.subtle.exportKey('pkcs8', keys.privateKey)).toString('base64');
const kv = new Map();
const env = {
  FCM_SERVICE_ACCOUNT: JSON.stringify({ project_id: 'p', client_email: 'sa@p', private_key: `-----BEGIN PRIVATE KEY-----\n${pkcs8}\n-----END PRIVATE KEY-----\n` }),
  ROOMS: {
    get: async (k, type) => kv.has(k) ? (type === 'json' ? JSON.parse(kv.get(k)) : kv.get(k)) : null,
    put: async (k, v) => { kv.set(k, v); },
  },
};
let sent = [], uninstalled = new Set();
globalThis.fetch = async (url, init) => {
  if (url === 'https://oauth2.googleapis.com/token') {
    const [h, p, sig] = new URLSearchParams(init.body).get('assertion').split('.');
    const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', keys.publicKey, Buffer.from(sig, 'base64url'), new TextEncoder().encode(h + '.' + p));
    assert.ok(ok, 'JWT signature');
    assert.equal(JSON.parse(Buffer.from(p, 'base64url')).iss, 'sa@p');
    return Response.json({ access_token: 'AT', expires_in: 3600 });
  }
  assert.equal(url, 'https://fcm.googleapis.com/v1/projects/p/messages:send');
  assert.equal(init.headers.authorization, 'Bearer AT');
  const { token, data } = JSON.parse(init.body).message;
  sent.push({ token, ...data });
  return new Response('{}', { status: uninstalled.has(token) ? 404 : 200 });
};
const R1 = 'a'.repeat(64), R2 = 'b'.repeat(64);
const call = (method, path, body) => worker.fetch(new Request('https://x/rxconnect-push/' + path, { method, body: body && JSON.stringify(body) }), env);
const tokens = r => JSON.parse(kv.get('r:' + r)).tokens;
const take = () => { const s = sent; sent = []; return s; };

test('a phone signs up without pinging anyone', async () => {
  assert.equal((await call('POST', 'room', { room: R1, token: 'A', here: false })).status, 200);
  assert.deepEqual(tokens(R1), ['A']);
  assert.deepEqual(take(), []);
});
test('joining tells the other phones, with the name, not yourself; every time', async () => {
  await call('POST', 'room', { room: R1, token: 'B', here: true, name: 'Andrea' });
  assert.deepEqual(take(), [{ token: 'A', kind: 'here', name: 'Andrea' }]);
  assert.deepEqual(tokens(R1), ['A', 'B']);
  await call('POST', 'room', { room: R1, token: 'B', here: true, name: 'Andrea' });
  assert.equal(take().length, 1);
});
test('joining can carry a note; too long is refused', async () => {
  await call('POST', 'room', { room: R1, token: 'B', here: true, name: 'Andrea', text: 'come say hi' });
  assert.deepEqual(take(), [{ token: 'A', kind: 'here', name: 'Andrea', text: 'come say hi' }]);
  assert.equal((await call('POST', 'room', { room: R1, token: 'B', here: true, text: 'x'.repeat(201) })).status, 400);
});
test('leaving, nudges, thinking of you and wanna call are passed on', async () => {
  for (const kind of ['gone', 'nudge', 'think', 'call']) {
    assert.equal((await call('POST', 'send', { room: R1, token: 'A', kind, name: 'Alex' })).status, 200);
    assert.deepEqual(take(), [{ token: 'B', kind, name: 'Alex' }]);
  }
  assert.equal((await call('POST', 'send', { room: R1, token: 'A', kind: 'spam' })).status, 400);
});
test('an image is kept for the room and announced; anyone with the room hash can fetch it', async () => {
  assert.equal((await call('POST', 'image', { room: R1, token: 'B', which: 'photo', name: 'Andrea', data: 'ENCRYPTED' })).status, 200);
  assert.deepEqual(take(), [{ token: 'A', kind: 'image', which: 'photo', name: 'Andrea' }]);
  const r = await call('GET', `image?room=${R1}&which=photo`);
  assert.equal(await r.text(), 'ENCRYPTED');
  assert.equal((await call('GET', `image?room=${R1}&which=board`)).status, 404);
  assert.equal((await call('POST', 'image', { room: R1, token: 'B', which: 'photo', data: 'x'.repeat(1_500_001) })).status, 400);
});
test('Windows/browser (no token) reach every phone; uninstalled ones are forgotten', async () => {
  uninstalled.add('A');
  await call('POST', 'room', { room: R1, token: '', here: true, name: 'PC' });
  assert.deepEqual(take().map(s => s.token).sort(), ['A', 'B']);
  assert.deepEqual(tokens(R1), ['B']);
});
test('a phone that changes room leaves the old one', async () => {
  await call('POST', 'room', { room: R2, token: 'B', here: false });
  assert.deepEqual(tokens(R1), []);
  assert.deepEqual(tokens(R2), ['B']);
});
test('bad requests are refused', async () => {
  assert.equal((await call('POST', 'room', { room: 'not-a-hash', token: '', here: true })).status, 400);
  assert.equal((await call('POST', 'room', { room: R1, token: '', here: false, name: 'x'.repeat(41) })).status, 400);
  assert.equal((await worker.fetch(new Request('https://x/rxconnect-push/room', { method: 'POST', body: 'nope' }), env)).status, 400);
  assert.equal((await call('GET', 'image?room=zz&which=photo')).status, 400);
});
