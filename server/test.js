// Smoke test: node test.js  (starts its own server on a random port in DEV_MODE)
process.env.DEV_MODE = '1'; process.env.MAIL_RATE = '1000'; process.env.PORT = 18080; process.env.DATA_DIR = require('os').tmpdir() + '/nova-test-' + Date.now();
require('./server.js');
const WebSocket = require('ws'); const assert = require('assert');
const B = 'http://localhost:18080';
const api = async (p, o = {}, t) => { const r = await fetch(B + p, { ...o, headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: 'Bearer ' + t } : {}) } }); return { s: r.status, j: await r.json() }; };
const signup = async (email) => {
  const a = await api('/auth/email', { method: 'POST', body: JSON.stringify({ email }) }); assert(a.j.devCode);
  const v = await api('/auth/verify', { method: 'POST', body: JSON.stringify({ email, code: a.j.devCode }) }); assert(v.j.token); return v.j;
};
const open = (token) => new Promise((res) => { const ws = new WebSocket('ws://localhost:18080'); const msgs = []; ws.on('message', d => msgs.push(JSON.parse(d)));
  ws.on('open', () => res({ ws, msgs, send: (o) => ws.send(JSON.stringify({ token, ...o })) })); });
const wait = (ms) => new Promise(r => setTimeout(r, ms));
(async () => {
  await wait(300);
  const A = await signup('a@x.com'), Bb = await signup('b@x.com');
  assert.equal((await api('/auth/verify', { method: 'POST', body: JSON.stringify({ email: 'a@x.com', code: '000000' }) })).s, 400);
  let r = await api('/me', { method: 'POST', body: JSON.stringify({ name: 'Alpha', handle: 'alpha.one', setup: true }) }, A.token); assert.equal(r.j.handle, 'alpha.one');
  r = await api('/me', { method: 'POST', body: JSON.stringify({ handle: 'alpha.one' }) }, Bb.token); assert.equal(r.s, 409);
  const a = await open(A.token); a.send({ type: 'create', url: 'yt:abc', title: 'Test Song', privacy: 'public' }); await wait(200);
  const code = a.msgs[0].code; assert.equal(a.msgs[0].type, 'joined');
  r = await api('/rooms/public', {}, Bb.token); assert.equal(r.j.rooms.length, 1); assert.equal(r.j.rooms[0].title, 'Test Song');
  const b = await open(Bb.token); b.send({ type: 'join', code }); await wait(200); assert.equal(b.msgs[0].type, 'joined');
  a.ws.send(JSON.stringify({ type: 'control', action: 'play', position: 12 })); await wait(200);
  assert(b.msgs.some(m => m.type === 'state' && m.playing && m.position >= 12));
  b.ws.send(JSON.stringify({ type: 'control', action: 'pause', position: 1 })); await wait(150); // guest cannot control
  assert(!a.msgs.some(m => m.type === 'state' && !m.playing));
  b.ws.send(JSON.stringify({ type: 'pick', url: 'yt:q1', title: 'Q1' })); await wait(150);
  const q = b.msgs.filter(m => m.type === 'queue').pop(); assert.equal(q.queue.length, 1);
  a.ws.send(JSON.stringify({ type: 'vote', id: q.queue[0].id })); await wait(150);
  a.ws.send(JSON.stringify({ type: 'ended' })); await wait(200);
  assert(b.msgs.some(m => m.type === 'state' && m.url === 'yt:q1'));
  a.ws.send(JSON.stringify({ type: 'settings', privacy: 'invite', playback: 'leader' })); await wait(150);
  assert.equal((await api('/rooms/public', {}, Bb.token)).j.rooms.length, 0);
  b.ws.send(JSON.stringify({ type: 'chat', text: 'hello' })); await wait(150); assert(a.msgs.some(m => m.type === 'chat' && m.text === 'hello'));
  await api('/history', { method: 'POST', body: JSON.stringify({ url: 'yt:abc', title: 'T' }) }, A.token);
  assert.equal((await api('/history', {}, A.token)).j.items.length, 1);
  r = await api('/friends/request', { method: 'POST', body: JSON.stringify({ handle: '@alpha.one' }) }, Bb.token); assert.equal(r.j.status, 'requested');
  let fl = await api('/friends', {}, A.token); assert.equal(fl.j.incoming.length, 1); assert.equal(fl.j.friends.length, 0);
  const bUser = (await api('/me', {}, Bb.token)).j;
  r = await api('/friends/accept', { method: 'POST', body: JSON.stringify({ id: bUser.id }) }, A.token); assert.equal(r.s, 200);
  fl = await api('/friends', {}, Bb.token); assert.equal(fl.j.friends.length, 1);
  assert.equal((await api('/me', {}, 'bad')).s, 401);
  assert.equal((await api('/me', { method: 'DELETE' }, Bb.token)).s, 200);
  // voice signaling + mic + nearby + invite
  const bId = b.msgs.find(m => m.type === 'joined').id;
  b.ws.send(JSON.stringify({ type: 'rtc', to: a.msgs[0].id, data: { sdp: 'x' } })); await wait(150);
  assert(a.msgs.some(m => m.type === 'rtc' && m.from === bId && m.data.sdp === 'x'));
  b.ws.send(JSON.stringify({ type: 'mic', on: true })); await wait(150);
  assert(a.msgs.filter(m => m.type === 'members').pop().members.find(x => x.id === bId).mic === true);
  const C = await signup('c@x.com'); await api('/me/location', { method: 'POST', body: JSON.stringify({ lat: 30.9012, lon: 75.8573 }) }, C.token);
  const d = await open(C.token); d.send({ type: 'create', title: 'Near', privacy: 'nearby' }); await wait(200);
  const D = await signup('d@x.com');
  assert.equal((await api('/rooms/public', {}, D.token)).j.rooms.length, 0); // D has no location
  await api('/me/location', { method: 'POST', body: JSON.stringify({ lat: 30.95, lon: 75.9 }) }, D.token);
  assert.equal((await api('/rooms/public', {}, D.token)).j.rooms.length, 1); // within 50 km
  await api('/me', { method: 'POST', body: JSON.stringify({ flags: { restrictInvites: true } }) }, D.token);
  const dUser = (await api('/me', {}, D.token)).j; const code2 = d.msgs[0].code;
  assert.equal((await api('/rooms/invite', { method: 'POST', body: JSON.stringify({ code: code2, friendId: dUser.id }) }, C.token)).s, 403);
  await api('/friends/request', { method: 'POST', body: JSON.stringify({ handle: (await api('/me', {}, C.token)).j.handle }) }, D.token);
  await api('/friends/accept', { method: 'POST', body: JSON.stringify({ id: dUser.id }) }, C.token);
  assert.equal((await api('/rooms/invite', { method: 'POST', body: JSON.stringify({ code: code2, friendId: dUser.id }) }, C.token)).s, 200);
  // block hides chat, report is stored, extras
  const aId = (await api('/me', {}, A.token)).j.id;
  r = await api('/me', { method: 'POST', body: JSON.stringify({ channels: ['Chan1'], socials: { instagram: 'alpha', evil: 'x', x: 'javascript:alert(1)' } }) }, A.token);
  assert.deepEqual(r.j.channels, ['Chan1']); assert.equal(r.j.socials.instagram, 'alpha'); assert(!r.j.socials.evil && !r.j.socials.x);
  const e = await signup('e@x.com'); const ee = await open(e.token); ee.send({ type: 'join', code }); await wait(200);
  await api('/blocks', { method: 'POST', body: JSON.stringify({ id: aId }) }, e.token);
  const before = ee.msgs.filter(m => m.type === 'chat').length;
  a.ws.send(JSON.stringify({ type: 'chat', text: 'hidden from e' })); await wait(200);
  assert.equal(ee.msgs.filter(m => m.type === 'chat').length, before);
  r = await api('/reports', { method: 'POST', body: JSON.stringify({ target: aId, room: code, reason: 'spam' }) }, e.token); assert.equal(r.s, 200);
  r = await fetch(B + '/chat/image', { method: 'POST', headers: { Authorization: 'Bearer ' + e.token }, body: Buffer.concat([Buffer.from([0xFF, 0xD8]), Buffer.alloc(300)]) });
  const ci = await r.json(); assert(ci.path.startsWith('/chat/')); assert.equal((await fetch(B + ci.path)).status, 200);
  // reconnect grace: sole member drops and rejoins within the grace period and is host again
  const f = await signup('f@x.com'); const ff = await open(f.token); ff.send({ type: 'create', title: 'Re', privacy: 'public' }); await wait(200);
  const rcode = ff.msgs[0].code; ff.ws.close(); await wait(200);
  const ff2 = await open(f.token); ff2.send({ type: 'join', code: rcode }); await wait(200);
  assert.equal(ff2.msgs[0].type, 'joined'); assert.equal(ff2.msgs[0].hostId, ff2.msgs[0].id);
  // profanity mask + admin ban
  process.env.ADMIN_TOKEN = 'adm';
  const g = await signup('g@x.com'); const gg = await open(g.token); gg.send({ type: 'create', title: 'Mod', privacy: 'public' }); await wait(200);
  gg.send({ type: 'chat', text: 'well shit happens' }); await wait(200);
  assert(gg.msgs.some(m => m.type === 'chat' && m.text === 'well s*** happens'), 'profanity masked');
  const gId = (await api('/me', {}, g.token)).j.id;
  r = await fetch(B + '/admin/ban', { method: 'POST', headers: { 'x-admin-token': 'adm' }, body: JSON.stringify({ id: gId }) }); assert.equal(r.status, 200);
  assert.equal((await api('/me', {}, g.token)).s, 401);
  // unplayable video: host reports -> room is told, next queued video starts
  const h = await signup('h@x.com'); const hh = await open(h.token); hh.send({ type: 'create', url: 'yt:BAD00000000', title: 'Bad', privacy: 'public' }); await wait(200);
  assert.equal(hh.msgs[0].state.playing, true, 'new room with a video autoplays');
  hh.send({ type: 'settings', playback: 'leader' }); hh.send({ type: 'pick', url: 'yt:GOOD0000000', title: 'Good', enqueue: true }); await wait(200);
  hh.send({ type: 'unplayable', url: 'yt:BAD00000000', reason: '101' }); await wait(250);
  assert(hh.msgs.some(m => m.type === 'system' && /cannot be played/.test(m.text)), 'unplayable notice');
  assert(hh.msgs.some(m => m.type === 'state' && m.url === 'yt:GOOD0000000' && m.playing), 'skipped to next queued video');
  // oEmbed check validates ids
  assert.equal((await api('/yt/check?id=nope')).s, 400);
  // ---- room controls: approval, lock, capacity, chat off, mute, ban, transfer, close, preview
  const H = await signup('rc-host@x.com'), G1 = await signup('rc-g1@x.com'), G2 = await signup('rc-g2@x.com'), G3 = await signup('rc-g3@x.com');
  for (const [t, hd] of [[H, 'rchost'], [G1, 'rcg1'], [G2, 'rcg2'], [G3, 'rcg3']]) await api('/me', { method: 'POST', body: JSON.stringify({ name: hd, handle: hd, setup: true }) }, t.token);
  const rh = await open(H.token); rh.send({ type: 'create', url: 'yt:abcdefghijk', title: 'Controls', privacy: 'public' }); await wait(200);
  const rcode2 = rh.msgs[0].code;
  let pv = await api('/rooms/preview?code=' + rcode2, {}, G1.token);
  assert.equal(pv.j.canJoin, true); assert.equal(pv.j.provider, 'youtube'); assert(!JSON.stringify(pv.j).includes('abcdefghijk'), 'preview must not leak media url');
  assert.equal((await api('/rooms/preview?code=ZZZZZZ', {}, G1.token)).s, 404);
  rh.ws.send(JSON.stringify({ type: 'settings', approval: true })); await wait(150);
  const g1 = await open(G1.token); g1.send({ type: 'join', code: rcode2 }); await wait(250);
  assert(g1.msgs.some(m => m.type === 'pending'), 'guest told to wait');
  const jr = rh.msgs.find(m => m.type === 'join_request'); assert(jr && jr.handle === 'rcg1', 'host got join request');
  rh.ws.send(JSON.stringify({ type: 'approve', rid: jr.rid })); await wait(250);
  assert(g1.msgs.some(m => m.type === 'joined'), 'approved guest joined');
  const g2 = await open(G2.token); g2.send({ type: 'join', code: rcode2 }); await wait(250);
  rh.ws.send(JSON.stringify({ type: 'deny', rid: rh.msgs.filter(m => m.type === 'join_request').pop().rid })); await wait(200);
  assert(g2.msgs.some(m => m.type === 'error' && /declined/.test(m.message)), 'denied guest told');
  rh.ws.send(JSON.stringify({ type: 'settings', approval: false, locked: true })); await wait(150);
  const g3 = await open(G3.token); g3.send({ type: 'join', code: rcode2 }); await wait(200);
  assert(g3.msgs.some(m => m.type === 'error' && /locked/.test(m.message)), 'locked room refuses');
  assert.equal((await api('/rooms/preview?code=' + rcode2, {}, G3.token)).j.canJoin, false);
  rh.ws.send(JSON.stringify({ type: 'settings', locked: false, max: 2 })); await wait(150);
  const g3b = await open(G3.token); g3b.send({ type: 'join', code: rcode2 }); await wait(200);
  assert(g3b.msgs.some(m => m.type === 'error' && /full/.test(m.message)), 'capacity enforced');
  rh.ws.send(JSON.stringify({ type: 'settings', max: 50, chat: false })); await wait(150);
  g1.ws.send(JSON.stringify({ type: 'chat', text: 'hi' })); await wait(150);
  assert(g1.msgs.some(m => m.type === 'error' && /Chat is turned off/.test(m.message)), 'chat off');
  rh.ws.send(JSON.stringify({ type: 'settings', chat: true })); await wait(150);
  const g1id = g1.msgs.find(m => m.type === 'joined').id;
  rh.ws.send(JSON.stringify({ type: 'mute', id: g1id, minutes: 5 })); await wait(150);
  g1.ws.send(JSON.stringify({ type: 'chat', text: 'muted?' })); await wait(150);
  assert(g1.msgs.some(m => m.type === 'error' && /muted/.test(m.message)), 'muted user cannot chat');
  rh.ws.send(JSON.stringify({ type: 'transfer', id: g1id })); await wait(200);
  assert(rh.msgs.filter(m => m.type === 'members').pop().hostId === g1id, 'host transferred');
  g1.ws.send(JSON.stringify({ type: 'transfer', id: rh.msgs.find(m => m.type === 'joined').id })); await wait(150);   // hand it back
  rh.ws.send(JSON.stringify({ type: 'kick', id: g1id })); await wait(250);
  const g1b = await open(G1.token); g1b.send({ type: 'join', code: rcode2 }); await wait(200);
  assert(g1b.msgs.some(m => m.type === 'error' && /removed/.test(m.message)), 'kicked user is banned from rejoining');
  rh.ws.send(JSON.stringify({ type: 'close' })); await wait(250);
  assert(rh.msgs.some(m => m.type === 'closed'), 'room closed for everyone');
  assert.equal((await api('/rooms/preview?code=' + rcode2, {}, G2.token)).s, 404);
  // ---- profiles: presence, stats, Top/History/Liked, search, privacy flags, passcode, sessions
  for (let i = 0; i < 3; i++) await api('/history', { method: 'POST', body: JSON.stringify({ url: 'yt:TOPVIDEO001', title: 'Top one', thumb: '' }) }, G1.token);
  await api('/history', { method: 'POST', body: JSON.stringify({ url: 'yt:OTHERVID002', title: 'Other', thumb: '' }) }, G1.token);
  await api('/likes', { method: 'POST', body: JSON.stringify({ url: 'yt:OTHERVID002', title: 'Other', thumb: '' }) }, G1.token);
  let lk = await api('/users/lookup?handle=rcg1', {}, G2.token);
  assert.equal(lk.s, 200); assert.equal(lk.j.relation, 'none'); assert.equal(lk.j.presence.online, true); assert.equal(lk.j.stats.videos, 2); assert.equal(lk.j.stats.providers.youtube, 4);
  let tv = await api('/users/videos?handle=rcg1&tab=top', {}, G2.token); assert.equal(tv.j.items[0].url, 'yt:TOPVIDEO001'); assert.equal(tv.j.items[0].views, 3);
  tv = await api('/users/videos?handle=rcg1&tab=liked', {}, G2.token); assert.equal(tv.j.items.length, 1);
  await api('/me', { method: 'POST', body: JSON.stringify({ flags: { privateActivity: true, hidePresence: true, notifyInvites: 0 } }) }, G1.token);
  tv = await api('/users/videos?handle=rcg1&tab=top', {}, G2.token); assert.equal(tv.j.hidden, true); assert.equal(tv.j.items.length, 0);
  assert.equal((await api('/users/videos?handle=rcg1&tab=top', {}, G1.token)).j.hidden, false, 'owner still sees own activity');
  lk = await api('/users/lookup?handle=rcg1', {}, G2.token); assert.equal(lk.j.presence, null, 'hidden presence'); 
  assert.strictEqual((await api('/me', {}, G1.token)).j.flags.notifyInvites, false, 'flag coerced to boolean');
  let sr = await api('/users/search?q=rcg', {}, G2.token); assert(sr.j.users.length >= 2 && sr.j.users.every(u => u.handle.includes('rcg') && u.id !== (lk.j.id === undefined ? '' : '')));
  assert.equal((await api('/users/search?q=r', {}, G2.token)).j.users.length, 0, 'min 2 chars');
  await api('/blocks', { method: 'POST', body: JSON.stringify({ id: lk.j.id }) }, G2.token);
  assert(!(await api('/users/search?q=rcg1', {}, G2.token)).j.users.some(u => u.handle === 'rcg1'), 'blocked users are not searchable');
  assert.equal((await api('/users/lookup?handle=rcg2', {}, G1.token)).s, 404, 'someone who blocked you is invisible to you');
  // passcode
  const rpc = await open(H.token); rpc.send({ type: 'create', url: 'yt:abcdefghijk', title: 'Secret', privacy: 'public' }); await wait(200);
  const pcode = rpc.msgs[0].code; rpc.ws.send(JSON.stringify({ type: 'settings', passcode: 'open-sesame' })); await wait(150);
  assert.equal((await api('/rooms/preview?code=' + pcode, {}, G3.token)).j.hasPasscode, true);
  const pj = await open(G3.token); pj.send({ type: 'join', code: pcode }); await wait(200);
  assert(pj.msgs.some(m => m.type === 'error' && m.code === 'passcode'), 'passcode required');
  const pj2 = await open(G3.token); pj2.send({ type: 'join', code: pcode, passcode: 'nope-nope' }); await wait(200);
  assert(pj2.msgs.some(m => m.type === 'error' && /Wrong passcode/.test(m.message)), 'wrong passcode');
  const pj3 = await open(G3.token); pj3.send({ type: 'join', code: pcode, passcode: 'open-sesame' }); await wait(200);
  assert(pj3.msgs.some(m => m.type === 'joined'), 'right passcode joins');
  // sign out other devices
  const A2 = await api('/auth/verify', { method: 'POST', body: JSON.stringify({ email: 'rc-g3@x.com', code: (await api('/auth/email', { method: 'POST', body: JSON.stringify({ email: 'rc-g3@x.com' }) })).j.devCode }) });
  assert.equal((await api('/sessions', {}, G3.token)).j.count, 2);
  assert.equal((await api('/auth/logout-others', { method: 'POST', body: '{}' }, A2.j.token)).j.signedOut, 1);
  assert.equal((await api('/me', {}, G3.token)).s, 401, 'old session signed out');
  assert.equal((await api('/me', {}, A2.j.token)).s, 200, 'current session kept');
  // ---- admin console + audit + app links
  process.env.ADMIN_TOKEN = 'adm';
  const adm = (p, o = {}) => fetch(B + p, { ...o, headers: { 'x-admin-token': 'adm', 'Content-Type': 'application/json' } }).then(async r => ({ s: r.status, j: await r.json() }));
  assert.equal((await fetch(B + '/admin/reports')).status, 401);
  const g2Id = (await api('/me', {}, G2.token)).j.id;
  await api('/reports', { method: 'POST', body: JSON.stringify({ target: g2Id, room: rcode2, reason: 'spam', text: 'x' }) }, A2.j.token);
  let rl = await adm('/admin/reports'); const rp = rl.j.reports.find(x => x.target === g2Id); assert(rp && rp.status === 'open');
  assert.equal((await adm('/admin/reports/resolve', { method: 'POST', body: JSON.stringify({ id: rp.id, action: 'mute24h', note: 'test' }) })).s, 200);
  assert.equal((await adm('/admin/reports?status=open')).j.reports.some(x => x.id === rp.id), false);
  assert.equal((await adm('/admin/audit')).j.audit[0].action, 'mute24h');
  assert.equal((await adm('/admin/metrics')).j.users > 5, true);
  assert.equal((await fetch(B + '/admin')).status, 200);
  assert.deepEqual(await (await fetch(B + '/.well-known/assetlinks.json')).json(), []);
  console.log('ALL SERVER TESTS PASSED'); process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
