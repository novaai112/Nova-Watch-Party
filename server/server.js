// Nova Watch Party - backend: REST (accounts, profiles, rooms list) + WebSocket (rooms, sync, chat)
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DEV = process.env.DEV_MODE === '1'; // DEV_MODE=1 returns the login code in the API response (never in production)
fs.mkdirSync(path.join(DATA_DIR, 'avatars'), { recursive: true });
fs.mkdirSync(path.join(DATA_DIR, 'chat'), { recursive: true });
fs.mkdirSync(path.join(DATA_DIR, 'gallery'), { recursive: true });

/* ---------- persistent store: JSON file by default, Postgres when DATABASE_URL is set ---------- */
const DB_FILE = path.join(DATA_DIR, 'db.json');
let db = { users: {}, sessions: {}, history: {}, likes: {}, follows: {}, requests: {}, blocks: {}, reports: [], audit: [] };
let pool = null;
if (process.env.DATABASE_URL) {
  try {
    const { Pool } = require('pg'); // optional dependency: npm install pg
    pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.DATABASE_SSL === '0' ? false : { rejectUnauthorized: false }, max: 3 });
  } catch (e) { console.error('DATABASE_URL set but the "pg" package is missing. Run: npm install pg'); process.exit(1); }
}
async function loadDb() {
  if (pool) {
    await pool.query('create table if not exists nova_kv (id int primary key, data jsonb not null)');
    const r = await pool.query('select data from nova_kv where id = 1');
    if (r.rows[0]) db = { ...db, ...r.rows[0].data };
  } else { try { db = { ...db, ...JSON.parse(fs.readFileSync(DB_FILE, 'utf8')) }; } catch {} }
}
let saveTimer = null;
const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => {
  if (pool) return pool.query('insert into nova_kv (id, data) values (1, $1) on conflict (id) do update set data = $1', [JSON.stringify(db)]).catch((e) => console.error('db save failed', e.message));
  fs.writeFile(DB_FILE + '.tmp', JSON.stringify(db), (e) => { if (!e) fs.rename(DB_FILE + '.tmp', DB_FILE, () => {}); });
}, 300); };
const clean = (s, n) => String(s ?? '').trim().slice(0, n);
const rid = (n = 12) => crypto.randomBytes(n).toString('hex');
const handles = () => new Set(Object.values(db.users).map(u => u.handle));

/* ---------- email login codes ---------- */
const codes = new Map(); // email -> {hash, exp, tries}
const hits = new Map();  // ip -> [timestamps]
function limited(ip, max = 20, windowMs = 60000) {
  const now = Date.now(); const a = (hits.get(ip) || []).filter(t => now - t < windowMs);
  a.push(now); hits.set(ip, a); return a.length > max;
}
async function sendMail(to, code) {
  const url = process.env.SMTP_URL; // e.g. smtps://user:pass@smtp.example.com
  if (url) {
    try {
      const nm = require('nodemailer');
      await nm.createTransport(url).sendMail({
        from: process.env.MAIL_FROM || 'Nova Watch Party <no-reply@example.com>', to,
        subject: 'Your Nova Watch Party login code',
        text: `Your login code is ${code}. It expires in 10 minutes.\nIf you did not try to sign in, ignore this email.`,
      });
      return true;
    } catch (e) { console.error('mail error', e.message); return false; }
  }
  console.log(`[mail disabled] login code for ${to}: ${code}`);
  return DEV;
}

/* ---------- users ---------- */
const ADJ = ['agreeable','assumable','aerial','awake','abysmal','brisk','calm','daring','eager','fuzzy','gentle','happy','jolly','keen','lucky','mellow','noble','plucky','quick','sunny'];
function suggestHandles(name) {
  const base = clean(name, 20).toLowerCase().replace(/[^a-z0-9]/g, '') || 'nova';
  const taken = handles(); const out = [];
  while (out.length < 8) { const h = `${ADJ[crypto.randomInt(ADJ.length)]}-${base}`; if (!taken.has(h) && !out.includes(h)) out.push(h); }
  return out;
}
function publicUser(u) {
  return { id: u.id, name: u.name, handle: u.handle, bio: u.bio || '', avatar: u.avatar || '', interests: u.interests || [], gallery: u.gallery || [], channels: u.channels || [], socials: u.socials || {}, country: u.country || '',
    joined: u.joined, minutes: Math.round(u.minutes || 0) };
}
const inAnyRoom = (id) => { for (const r of rooms.values()) for (const m of r.members.values()) if (m.user.id === id) return true; return false; };
const presenceOf = (u) => (u.flags && u.flags.hidePresence) ? null : { online: inAnyRoom(u.id) || Date.now() - (u.lastActive || 0) < 120000, lastActive: u.lastActive || 0 };
function privateUser(u) { return { ...publicUser(u), email: u.email || '', flags: u.flags, guest: !!u.guest, premium: false }; }
const defaultFlags = () => ({ quickReaction: '❤️', restrictInvites: false, hideMature: true, floatingPlayer: false,
  autoTranslate: false, hideLocation: false, language: 'device',
  hidePresence: false, privateActivity: false, notifyInvites: true, notifyFriends: true });
function newUser({ email, name, guest }) {
  const id = rid(8);
  const handle = suggestHandles(name || 'nova')[0];
  const u = { id, email: email || '', name: clean(name, 24) || 'Nova', handle, bio: '', avatar: '', joined: Date.now(),
    minutes: 0, flags: defaultFlags(), guest: !!guest, setup: false };
  db.users[id] = u; save(); return u;
}
function login(u) { const token = rid(24); db.sessions[token] = u.id; save(); return { token, user: privateUser(u) }; }
const authUser = (token) => { const u = db.users[db.sessions[token]]; return u && !u.banned ? u : null; };
// Simple profanity mask. Extend with BAD_WORDS="word1,word2" (comma separated) in the environment.
const BAD = (process.env.BAD_WORDS || 'fuck,shit,bitch,asshole,cunt,nigger,faggot').split(',').map(w => w.trim().toLowerCase()).filter(Boolean);
const BAD_RE = BAD.length ? new RegExp('\\b(' + BAD.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')\\b', 'gi') : null;
const maskBad = (t) => BAD_RE ? t.replace(BAD_RE, (m) => m[0] + '*'.repeat(m.length - 1)) : t;

/* ---------- push (Firebase Cloud Messaging HTTP v1, free) ---------- */
let fcmTok = { v: null, exp: 0 };
function fcmAccount() {
  const raw = process.env.FCM_SERVICE_ACCOUNT_JSON; if (!raw) return null;
  try { return JSON.parse(raw.trim().startsWith('{') ? raw : fs.readFileSync(raw, 'utf8')); } catch { return null; }
}
async function fcmToken(acc) {
  if (fcmTok.v && fcmTok.exp > Date.now() + 60000) return fcmTok.v;
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url'); const now = Math.floor(Date.now() / 1000);
  const unsigned = b64({ alg: 'RS256', typ: 'JWT' }) + '.' + b64({ iss: acc.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  const sig = crypto.sign('RSA-SHA256', Buffer.from(unsigned), acc.private_key).toString('base64url');
  const r = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=' + unsigned + '.' + sig });
  const j = await r.json(); fcmTok = { v: j.access_token, exp: Date.now() + (j.expires_in || 3000) * 1000 }; return fcmTok.v;
}
async function pushTo(userId, title, body, data = {}) {
  const acc = fcmAccount(); const u = db.users[userId]; if (!acc || !u || !(u.push || []).length) return 0;
  if ((data.kind === 'invite' && u.flags?.notifyInvites === false) || (data.kind === 'friend' && u.flags?.notifyFriends === false)) return 0; // notification preferences
  let sent = 0;
  try {
    const tok = await fcmToken(acc);
    for (const t of [...u.push]) {
      const r = await fetch(`https://fcm.googleapis.com/v1/projects/${acc.project_id}/messages:send`, { method: 'POST',
        headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: { token: t, notification: { title, body }, data, android: { priority: 'HIGH' } } }) });
      if (r.ok) sent++; else if (r.status === 404 || r.status === 400) u.push = u.push.filter(x => x !== t);
    }
    save();
  } catch (e) { console.error('push error', e.message); }
  return sent;
}
function km(a, b) {
  if (a.lat == null || b.lat == null) return Infinity;
  const R = 6371, d = Math.PI / 180, dLat = (b.lat - a.lat) * d, dLon = (b.lon - a.lon) * d;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * d) * Math.cos(b.lat * d) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

/* ---------- rooms ---------- */
const rooms = new Map();
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function newCode() {
  let c; do { c = Array.from({ length: 6 }, () => ALPHABET[crypto.randomInt(ALPHABET.length)]).join(''); } while (rooms.has(c));
  return c;
}
const logEvt = (evt, o = {}) => console.log(JSON.stringify({ t: new Date().toISOString(), evt, ...o }));
const adminOk = (req) => { const a = Buffer.from(String(req.headers['x-admin-token'] || '')), b = Buffer.from(process.env.ADMIN_TOKEN || '');
  return b.length > 0 && a.length === b.length && crypto.timingSafeEqual(a, b); };
const startedAt = Date.now();
const providerOf = (u) => (u || '').startsWith('yt:') ? 'youtube' : (u || '').startsWith('web:') ? 'web' : u ? 'direct' : 'none';
const PRIVACY = ['public', 'nearby', 'friends', 'invite'];
const PLAYBACK = ['vote', 'play', 'autoplay', 'leader'];
const nowPos = (r) => r.state.playing ? r.state.position + (Date.now() - r.state.updatedAt) / 1000 : r.state.position;
const send = (ws, o) => { if (ws.readyState === 1) ws.send(JSON.stringify(o)); };
const memberList = (r) => [...r.members.values()].map(m => ({ id: m.id, uid: m.user.id, name: m.user.name, handle: m.user.handle, avatar: m.user.avatar || '', host: m.id === r.hostId, mic: !!m.mic }));
const stateMsg = (r) => ({ type: 'state', url: r.state.url, title: r.state.title, thumb: r.state.thumb, playing: r.state.playing, position: nowPos(r) });
const settingsOf = (r) => ({ privacy: r.privacy, playback: r.playback, voice: r.voice, locked: !!r.locked, approval: !!r.approval, chat: r.chat !== false, max: r.max || 100, passcode: !!r.pass });
const queueOf = (r) => r.queue.map(q => ({ id: q.id, url: q.url, title: q.title, thumb: q.thumb, by: q.by, votes: q.votes.size }));
function broadcast(r, o, exceptId) { for (const m of r.members.values()) if (m.id !== exceptId) send(m.ws, o); }
function roomSummary(r) {
  return { code: r.code, title: r.state.title || 'Waiting for a video', thumb: r.state.thumb || '', count: r.members.size,
    members: memberList(r).slice(0, 6).map(m => ({ name: m.name, avatar: m.avatar })), privacy: r.privacy };
}
function loadVideo(r, url, title, thumb) {
  const s = r.state; s.url = clean(url, 2000); s.title = clean(title, 200); s.thumb = clean(thumb, 500);
  s.position = 0; s.playing = true; s.updatedAt = Date.now();
  r.unplayable = null; r.unplayableHandled = null;
  broadcast(r, stateMsg(r));
}
// force = skip a broken video: take the next queued item whatever the playback mode is
function advance(r, force = false) {
  if (!r.queue.length) return false;
  let idx = 0;
  if (r.playback === 'vote' || force) { let best = -1; r.queue.forEach((q, i) => { if (q.votes.size > best) { best = q.votes.size; idx = i; } }); }
  else if (r.playback !== 'autoplay') return false;
  const [q] = r.queue.splice(idx, 1);
  loadVideo(r, q.url, q.title, q.thumb);
  broadcast(r, { type: 'queue', queue: queueOf(r) });
  return true;
}

/* ---------- HTTP ---------- */
function readBody(req, max) {
  return new Promise((res, rej) => {
    const chunks = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > max) { rej(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => res(Buffer.concat(chunks)));
    req.on('error', rej);
  });
}
const ytCache = new Map();
const ADMIN_HTML = `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Nova moderation</title>
<style>body{font:14px sans-serif;background:#14102b;color:#fff;margin:20px}input,select,button{font:inherit;padding:6px}table{border-collapse:collapse;width:100%;margin-top:12px}td,th{border-bottom:1px solid #3a3566;padding:6px;text-align:left;vertical-align:top}button{margin:2px}.m{color:#aab}</style>
<h2>Nova moderation console</h2>
<input id=t type=password placeholder="admin token"> <input id=q placeholder="search handle / room / report id"> <select id=s><option>open</option><option>resolved</option><option>all</option></select> <button id=go>Load</button> <span id=metrics class=m></span>
<table><thead><tr><th>When</th><th>Reporter</th><th>Target</th><th>Room</th><th>Reason</th><th>Status</th><th>Actions</th></tr></thead><tbody id=rows></tbody></table>
<script>
var T=document.getElementById('t'),rows=document.getElementById('rows');T.value=sessionStorage.getItem('nt')||'';
function api(p,o){o=o||{};o.headers={'x-admin-token':T.value,'Content-Type':'application/json'};return fetch(p,o).then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.error||r.status);return j;});});}
function cell(tr,txt){var td=document.createElement('td');td.textContent=txt==null?'':String(txt);tr.appendChild(td);return td;}
function act(id,a){var note=a==='dismiss'?'':prompt('Note for the audit log (optional)')||'';api('/admin/reports/resolve',{method:'POST',body:JSON.stringify({id:id,action:a,note:note})}).then(load).catch(function(e){alert(e.message);});}
function load(){sessionStorage.setItem('nt',T.value);
 api('/admin/metrics').then(function(m){document.getElementById('metrics').textContent=m.users+' users, '+m.rooms+' rooms, '+m.members+' people online, '+m.openReports+' open reports';}).catch(function(){});
 api('/admin/reports?status='+encodeURIComponent(document.getElementById('s').value)+'&q='+encodeURIComponent(document.getElementById('q').value)).then(function(j){rows.textContent='';
  j.reports.forEach(function(r){var tr=document.createElement('tr');cell(tr,new Date(r.ts).toLocaleString());cell(tr,'@'+(r.byHandle||'?'));cell(tr,'@'+(r.targetHandle||'?')+(r.targetBanned?' (banned)':''));cell(tr,r.room);cell(tr,r.reason+(r.text?': '+r.text:''));cell(tr,r.status+(r.action?' / '+r.action:''));
   var td=document.createElement('td');if(r.status==='open')['dismiss','warn','mute24h','ban'].forEach(function(a){var b=document.createElement('button');b.textContent=a;b.onclick=function(){act(r.id,a);};td.appendChild(b);});tr.appendChild(td);rows.appendChild(tr);});}).catch(function(e){rows.textContent='';var tr=document.createElement('tr');cell(tr,e.message);rows.appendChild(tr);});}
document.getElementById('go').onclick=load;
</script>`;

const json = (res, code, o) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(o)); };

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x'); const p = url.pathname; const ip = req.socket.remoteAddress;
  try {
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,DELETE' }); return res.end(); }
    const page = (title, body) => { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>${title}</title><body style="font-family:sans-serif;background:#14102b;color:#fff;max-width:640px;margin:40px auto;padding:0 20px;line-height:1.5">${body}`); };
    const mr = p.match(/^\/r\/([A-Za-z0-9]{4,8})$/);
    if (mr) { const c = mr[1].toUpperCase();
      return page('Join on Nova Watch Party', `<h1>Nova Watch Party</h1><p>You're invited to watch together. Room code: <b>${c}</b></p><p><a style="color:#8ef" href="intent://room/${c}#Intent;scheme=novaparty;package=com.nova.watchparty;end">Open in the app</a></p><p>Don't have the app yet? Install Nova Watch Party, then open this link again.</p>`); }
    if (p === '/.well-known/assetlinks.json') { // Android App Links: set ANDROID_SHA256=AA:BB:... (comma separated for several keys)
      const fp = (process.env.ANDROID_SHA256 || '').split(',').map(x => x.trim()).filter(Boolean);
      return json(res, 200, fp.length ? [{ relation: ['delegate_permission/common.handle_all_urls'], target: { namespace: 'android_app', package_name: 'com.nova.watchparty', sha256_cert_fingerprints: fp } }] : []);
    }
    if (p === '/privacy') return page('Privacy Policy', process.env.PRIVACY_HTML || '<h1>Privacy Policy</h1><p><b>Template - replace with your own policy reviewed by a lawyer.</b> Nova Watch Party stores your email, display name, handle, optional avatar and bio, your watch history and likes, and room chat in memory only while a room is open. You can delete your account and all stored data from Settings.</p>');
    if (p === '/terms') return page('Terms of Service', process.env.TERMS_HTML || '<h1>Terms of Service</h1><p><b>Template - replace with your own terms reviewed by a lawyer.</b> Be respectful in rooms. Do not share illegal or harmful content. You are responsible for the content you share and for following the terms of any third-party service you watch.</p>');
    if (p === '/health') return json(res, 200, { ok: true, rooms: rooms.size });
    const mf = p.match(/^\/(chat|gallery)\/([\w.-]+\.jpg)$/);
    if (mf) {
      const f = path.join(DATA_DIR, mf[1], mf[2]);
      if (!fs.existsSync(f)) return json(res, 404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=86400' }); return fs.createReadStream(f).pipe(res);
    }
    if (p.startsWith('/avatars/')) {
      const f = path.join(DATA_DIR, 'avatars', path.basename(p));
      if (!fs.existsSync(f)) return json(res, 404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=3600' }); return fs.createReadStream(f).pipe(res);
    }
    if (limited(ip, 120)) return json(res, 429, { error: 'Too many requests' });
    if (p === '/yt/check' && req.method === 'GET') { // can this YouTube video be embedded? (oEmbed answers 401 when the owner disabled embedding)
      const id = url.searchParams.get('id') || '';
      if (!/^[\w-]{11}$/.test(id)) return json(res, 400, { error: 'Bad video id' });
      const hit = ytCache.get(id); if (hit && Date.now() - hit.at < 30 * 60000) return json(res, 200, hit.v);
      let v = { embeddable: true, unknown: true };
      try {
        const r = await fetch('https://www.youtube.com/oembed?format=json&url=' + encodeURIComponent('https://www.youtube.com/watch?v=' + id), { signal: AbortSignal.timeout(4000) });
        if (r.status === 200) { const o = await r.json(); v = { embeddable: true, title: String(o.title || '').slice(0, 200) }; }
        else if (r.status === 401 || r.status === 403) v = { embeddable: false, reason: 'embed_disabled' };
        else if (r.status === 404) v = { embeddable: false, reason: 'not_found' };
      } catch {}
      if (!v.unknown) { if (ytCache.size > 2000) ytCache.clear(); ytCache.set(id, { at: Date.now(), v }); }
      return json(res, 200, v);
    }

    // ----- auth (no token needed)
    if (p === '/auth/email' && req.method === 'POST') {
      if (limited(ip + ':mail', Number(process.env.MAIL_RATE) || 5)) return json(res, 429, { error: 'Too many attempts, try again in a minute' });
      const { email } = JSON.parse(await readBody(req, 4096) || '{}');
      const e = clean(email, 120).toLowerCase();
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) return json(res, 400, { error: 'Enter a valid email' });
      const code = String(crypto.randomInt(100000, 999999));
      codes.set(e, { hash: crypto.createHash('sha256').update(code).digest('hex'), exp: Date.now() + 600000, tries: 0 });
      const ok = await sendMail(e, code);
      if (!ok) return json(res, 502, { error: 'Email could not be sent. Server mail is not configured.' });
      return json(res, 200, DEV ? { ok: true, devCode: code } : { ok: true });
    }
    if (p === '/auth/verify' && req.method === 'POST') {
      const { email, code } = JSON.parse(await readBody(req, 4096) || '{}');
      const e = clean(email, 120).toLowerCase(); const c = codes.get(e);
      if (!c || c.exp < Date.now()) return json(res, 400, { error: 'Code expired. Request a new one.' });
      if (++c.tries > 5) { codes.delete(e); return json(res, 429, { error: 'Too many wrong codes' }); }
      const h = crypto.createHash('sha256').update(clean(code, 10)).digest('hex');
      if (!crypto.timingSafeEqual(Buffer.from(h), Buffer.from(c.hash))) return json(res, 400, { error: 'Wrong code' });
      codes.delete(e);
      let u = Object.values(db.users).find(x => x.email === e);
      if (!u) u = newUser({ email: e, name: e.split('@')[0] });
      return json(res, 200, { ...login(u), newUser: !u.setup });
    }
    if (p === '/auth/guest' && req.method === 'POST') {
      const { name } = JSON.parse(await readBody(req, 2048) || '{}');
      const u = newUser({ name, guest: true }); u.setup = true;
      return json(res, 200, { ...login(u), newUser: false });
    }
    if (p === '/auth/google' && req.method === 'POST') {
      const cid = process.env.GOOGLE_WEB_CLIENT_ID;
      if (!cid) return json(res, 501, { error: 'Google sign-in is not configured on the server' });
      const { idToken } = JSON.parse(await readBody(req, 8192) || '{}');
      const r = await fetch('https://oauth2.googleapis.com/tokeninfo?id_token=' + encodeURIComponent(clean(idToken, 4000)));
      const t = await r.json();
      if (!r.ok || t.aud !== cid || String(t.email_verified) !== 'true') return json(res, 401, { error: 'Google sign-in failed' });
      const e = String(t.email).toLowerCase();
      let u = Object.values(db.users).find(x => x.email === e);
      if (!u) u = newUser({ email: e, name: t.given_name || t.name || e.split('@')[0] });
      return json(res, 200, { ...login(u), newUser: !u.setup });
    }
    if (p === '/handles/suggest') return json(res, 200, { handles: suggestHandles(url.searchParams.get('name') || '') });

    if ((p === '/admin/ban' || p === '/admin/unban') && req.method === 'POST' && adminOk(req)) {
      const { id } = JSON.parse(await readBody(req, 1024) || '{}'); const t = db.users[id];
      if (!t) return json(res, 404, { error: 'No such user' });
      t.banned = p === '/admin/ban';
      if (t.banned) { for (const [tok, uid] of Object.entries(db.sessions)) if (uid === id) delete db.sessions[tok]; for (const r of rooms.values()) for (const mm of r.members.values()) if (mm.user.id === id) try { mm.ws.close(4003, 'banned'); } catch {} }
      save(); return json(res, 200, { ok: true, banned: t.banned });
    }
    if (p === '/admin' && req.method === 'GET') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'" }); return res.end(ADMIN_HTML); }
    if (p.startsWith('/admin/') && !adminOk(req)) return json(res, 401, { error: 'Admin token required' });
    if (p === '/admin/reports' && req.method === 'GET') {
      const q = clean(url.searchParams.get('q'), 60).toLowerCase(), status = url.searchParams.get('status') || 'open';
      const list = db.reports.map(r => ({ ...r, status: r.status || 'open', byHandle: db.users[r.by]?.handle, targetHandle: db.users[r.target]?.handle, targetBanned: !!db.users[r.target]?.banned }))
        .filter(r => status === 'all' || r.status === status)
        .filter(r => !q || [r.id, r.room, r.byHandle, r.targetHandle, r.target, r.reason].some(x => String(x || '').toLowerCase().includes(q)));
      return json(res, 200, { reports: list.slice(-200).reverse() });
    }
    if (p === '/admin/reports/resolve' && req.method === 'POST') { // dismiss | warn | mute24h | ban  (every action is written to the audit log)
      const { id, action, note } = JSON.parse(await readBody(req, 2048) || '{}');
      const rep = db.reports.find(r => r.id === id); if (!rep) return json(res, 404, { error: 'No such report' });
      if (!['dismiss', 'warn', 'mute24h', 'ban'].includes(action)) return json(res, 400, { error: 'Unknown action' });
      const t = db.users[rep.target];
      if (action !== 'dismiss' && !t) return json(res, 404, { error: 'Target user no longer exists' });
      if (action === 'warn') pushTo(t.id, 'Community guidelines reminder', 'A report about your behavior was reviewed. Please keep Nova friendly.', {});
      if (action === 'mute24h') t.mutedUntil = Date.now() + 86400000;
      if (action === 'ban') { t.banned = true; for (const [tok, uid] of Object.entries(db.sessions)) if (uid === t.id) delete db.sessions[tok];
        for (const r of rooms.values()) for (const mm of r.members.values()) if (mm.user.id === t.id) try { mm.ws.close(4003, 'banned'); } catch {} }
      rep.status = 'resolved'; rep.action = action; rep.resolvedAt = Date.now();
      db.audit.push({ id: rid(4), ts: Date.now(), actor: 'admin', action, report: rep.id, target: rep.target, note: clean(note, 200) }); db.audit = db.audit.slice(-5000);
      save(); logEvt('admin_action', { action, report: rep.id }); return json(res, 200, { ok: true });
    }
    if (p === '/admin/audit' && req.method === 'GET') return json(res, 200, { audit: db.audit.slice(-200).reverse() });
    if (p === '/admin/metrics' && req.method === 'GET') return json(res, 200, { uptimeSec: Math.round((Date.now() - startedAt) / 1000), users: Object.keys(db.users).length,
      rooms: rooms.size, members: [...rooms.values()].reduce((n, r) => n + r.members.size, 0), openReports: db.reports.filter(r => (r.status || 'open') === 'open').length, rssMB: Math.round(process.memoryUsage().rss / 1048576) });
    // ----- authenticated
    const token = (req.headers.authorization || '').replace('Bearer ', '');
    const me = authUser(token);
    if (!me) return json(res, 401, { error: 'Not signed in' });
    me.lastActive = Date.now(); // presence (kept in memory, persisted with the next save)

    if (p === '/sessions' && req.method === 'GET') return json(res, 200, { count: Object.values(db.sessions).filter(id => id === me.id).length });
    if (p === '/auth/logout-others' && req.method === 'POST') { // "sign out of other devices"
      let n = 0; for (const [tok, id] of Object.entries(db.sessions)) if (id === me.id && tok !== token) { delete db.sessions[tok]; n++; }
      save(); return json(res, 200, { signedOut: n });
    }
    if (p === '/me' && req.method === 'GET') return json(res, 200, privateUser(me));
    if (p === '/me' && req.method === 'POST') {
      const b = JSON.parse(await readBody(req, 8192) || '{}');
      if (b.name !== undefined) me.name = clean(b.name, 24) || me.name;
      if (b.bio !== undefined) me.bio = clean(b.bio, 300);
      if (b.country !== undefined) me.country = clean(b.country, 2).toUpperCase();
      if (b.handle !== undefined) {
        const h = clean(b.handle, 30).toLowerCase().replace(/[^a-z0-9_.-]/g, '');
        if (h.length < 3) return json(res, 400, { error: 'Handle must be at least 3 characters' });
        if (h !== me.handle && handles().has(h)) return json(res, 409, { error: 'That handle is taken' });
        me.handle = h;
      }
      if (b.flags && typeof b.flags === 'object') { const d = defaultFlags(); for (const k of Object.keys(d)) if (k in b.flags) me.flags[k] = typeof d[k] === 'boolean' ? !!b.flags[k] : clean(b.flags[k], 24); }
      if (Array.isArray(b.channels)) me.channels = b.channels.slice(0, 8).map(x => clean(x, 40)).filter(Boolean);
      if (b.socials && typeof b.socials === 'object') {
        const ok = ['instagram', 'x', 'youtube', 'tiktok', 'twitch', 'discord', 'website']; const out = {};
        for (const k of ok) { const v = clean(b.socials[k], 120); if (v && !/^(javascript|data):/i.test(v)) out[k] = v; }
        me.socials = out;
      }
      if (Array.isArray(b.interests)) me.interests = b.interests.slice(0, 12).map(x => clean(x, 24)).filter(Boolean);
      if (b.setup) me.setup = true;
      save(); return json(res, 200, privateUser(me));
    }
    if (p === '/me/location' && req.method === 'POST') {
      const b = JSON.parse(await readBody(req, 512) || '{}');
      if (me.flags.hideLocation || typeof b.lat !== 'number' || typeof b.lon !== 'number') { delete me.lat; delete me.lon; }
      else { me.lat = Math.round(b.lat * 100) / 100; me.lon = Math.round(b.lon * 100) / 100; } // ~1 km precision only
      save(); return json(res, 200, { ok: true });
    }
    if (p === '/me/push' && req.method === 'POST') {
      const { token: t } = JSON.parse(await readBody(req, 2048) || '{}'); const tk = clean(t, 400); if (!tk) return json(res, 400, { error: 'token required' });
      me.push = [...new Set([...(me.push || []), tk])].slice(-5); save(); return json(res, 200, { ok: true });
    }
    if (p === '/rooms/invite' && req.method === 'POST') {
      const { code, friendId } = JSON.parse(await readBody(req, 1024) || '{}');
      const r = rooms.get(clean(code, 8).toUpperCase()); const t = db.users[friendId];
      if (!r || ![...r.members.values()].some(m => m.user.id === me.id)) return json(res, 400, { error: 'You are not in that room' });
      if (!t) return json(res, 404, { error: 'User not found' });
      if (t.flags.restrictInvites && !(db.follows[t.id] || []).includes(me.id)) return json(res, 403, { error: 'This user only accepts invites from friends' });
      const sent = await pushTo(t.id, `${me.name} invited you`, r.state.title ? `Watch "${r.state.title}" together` : 'Join their Nova room', { code: r.code, kind: 'invite' });
      return json(res, 200, { sent, pushConfigured: !!fcmAccount() });
    }
    if (p === '/me/gallery' && req.method === 'POST') {
      const buf = await readBody(req, 600000);
      if (buf.length < 100 || !(buf[0] === 0xFF && buf[1] === 0xD8)) return json(res, 400, { error: 'Send a JPEG image' });
      me.gallery = me.gallery || []; if (me.gallery.length >= 6) return json(res, 400, { error: 'Gallery is full (6 photos)' });
      const name = `${me.id}-${rid(4)}.jpg`; fs.writeFileSync(path.join(DATA_DIR, 'gallery', name), buf);
      me.gallery.push('/gallery/' + name); save(); return json(res, 200, privateUser(me));
    }
    if (p === '/me/gallery' && req.method === 'DELETE') {
      const g = url.searchParams.get('path'); me.gallery = (me.gallery || []).filter(x => { if (x === g) { try { fs.unlinkSync(path.join(DATA_DIR, 'gallery', path.basename(x))); } catch {} return false; } return true; });
      save(); return json(res, 200, privateUser(me));
    }
    if (p === '/chat/image' && req.method === 'POST') {
      if (limited(ip + ':img', 10)) return json(res, 429, { error: 'Slow down' });
      const buf = await readBody(req, 500000);
      if (buf.length < 100 || !(buf[0] === 0xFF && buf[1] === 0xD8)) return json(res, 400, { error: 'Send a JPEG image' });
      const name = `${rid(8)}.jpg`; fs.writeFileSync(path.join(DATA_DIR, 'chat', name), buf); return json(res, 200, { path: '/chat/' + name });
    }
    if (p === '/me/avatar' && req.method === 'POST') {
      const buf = await readBody(req, 600000);
      if (buf.length < 100 || !(buf[0] === 0xFF && buf[1] === 0xD8)) return json(res, 400, { error: 'Send a JPEG image' });
      fs.writeFileSync(path.join(DATA_DIR, 'avatars', me.id + '.jpg'), buf);
      me.avatar = `/avatars/${me.id}.jpg?v=${Date.now()}`; save(); return json(res, 200, privateUser(me));
    }
    if (p === '/me' && req.method === 'DELETE') {
      for (const g of me.gallery || []) { try { fs.unlinkSync(path.join(DATA_DIR, 'gallery', path.basename(g))); } catch {} }
      for (const k of Object.keys(db.follows)) db.follows[k] = (db.follows[k] || []).filter(x => x !== me.id);
      for (const k of Object.keys(db.requests)) db.requests[k] = (db.requests[k] || []).filter(x => x !== me.id);
      delete db.requests[me.id]; delete db.blocks[me.id];
      delete db.users[me.id]; delete db.history[me.id]; delete db.likes[me.id]; delete db.follows[me.id];
      for (const [t, id] of Object.entries(db.sessions)) if (id === me.id) delete db.sessions[t];
      try { fs.unlinkSync(path.join(DATA_DIR, 'avatars', me.id + '.jpg')); } catch {}
      save(); return json(res, 200, { ok: true });
    }
    if (p === '/rooms/public') {
      const q = clean(url.searchParams.get('q'), 60).toLowerCase();
      const mine = new Set(db.follows[me.id] || []);
      const list = [...rooms.values()].filter(r => r.members.size > 0 && (
          r.privacy === 'public' || (r.privacy === 'nearby' && km(me, r.geo || {}) <= 50) || (r.privacy === 'friends' && mine.has(r.ownerId))))
        .filter(r => !q || (r.state.title || '').toLowerCase().includes(q))
        .sort((a, b) => b.members.size - a.members.size).slice(0, 50).map(roomSummary);
      return json(res, 200, { rooms: list });
    }
    if (p === '/rooms/preview' && req.method === 'GET') { // join preview: never exposes the media URL, only what the PRD allows
      const r = rooms.get(clean(url.searchParams.get('code'), 8).toUpperCase());
      if (!r) return json(res, 404, { error: 'Room not found or it has ended' });
      const host = r.members.get(r.hostId)?.user, rejoin = (r.recent && r.recent.get(me.id)) > Date.now() - 120000, owner = r.ownerId === me.id;
      let canJoin = true, reason = '';
      if (r.bans && r.bans.has(me.id)) { canJoin = false; reason = 'You were removed from this room'; }
      else if (r.members.size >= (r.max || 100)) { canJoin = false; reason = 'Room is full'; }
      else if (r.locked && !owner && !rejoin) { canJoin = false; reason = 'This room is locked'; }
      else if (r.privacy === 'friends' && !owner && !rejoin && !(db.follows[r.ownerId] || []).includes(me.id)) { canJoin = false; reason = 'This room is for friends only'; }
      return json(res, 200, { code: r.code, title: r.state.title || '', thumb: r.state.thumb || '', provider: providerOf(r.state.url), privacy: r.privacy, count: r.members.size, max: r.max || 100,
        voice: r.voice, chat: r.chat !== false, approval: !!r.approval, locked: !!r.locked, hasPasscode: !!r.pass, host: host ? { name: host.name, handle: host.handle, avatar: host.avatar || '' } : null, canJoin, reason });
    }
    for (const kind of ['history', 'likes']) {
      if (p === `/${kind}` && req.method === 'GET') return json(res, 200, { items: db[kind][me.id] || [] });
      if (p === `/${kind}` && req.method === 'POST') {
        const b = JSON.parse(await readBody(req, 4096) || '{}');
        const it = { url: clean(b.url, 2000), title: clean(b.title, 200), thumb: clean(b.thumb, 500), ts: Date.now() };
        if (!it.url) return json(res, 400, { error: 'url required' });
        const prev = (db[kind][me.id] || []).find(x => x.url === it.url); it.views = (prev && prev.views || 0) + 1; // how often this user watched it (drives the Top tab)
        const arr = (db[kind][me.id] || []).filter(x => x.url !== it.url); arr.unshift(it);
        db[kind][me.id] = arr.slice(0, 200); save(); return json(res, 200, { ok: true });
      }
      if (p === `/${kind}` && req.method === 'DELETE') {
        const u = url.searchParams.get('url'); db[kind][me.id] = (db[kind][me.id] || []).filter(x => x.url !== u);
        save(); return json(res, 200, { ok: true });
      }
    }
    const byId = (ids) => (ids || []).map(i => db.users[i]).filter(Boolean).map(publicUser);
    const link2 = (a, b) => { for (const [x, y] of [[a, b], [b, a]]) { const set = new Set(db.follows[x] || []); set.add(y); db.follows[x] = [...set]; } };
    const clearReq = (from, to) => { db.requests[to] = (db.requests[to] || []).filter(x => x !== from); };
    if (p === '/friends' && req.method === 'GET') {
      const incoming = db.requests[me.id] || [];
      const outgoing = Object.entries(db.requests).filter(([to, froms]) => froms.includes(me.id)).map(([to]) => to);
      return json(res, 200, { friends: byId(db.follows[me.id]), incoming: byId(incoming), outgoing: byId(outgoing) });
    }
    if ((p === '/friends/request' || p === '/friends/add') && req.method === 'POST') {
      const { handle } = JSON.parse(await readBody(req, 1024) || '{}');
      const t = Object.values(db.users).find(u => u.handle === clean(handle, 30).toLowerCase().replace(/^@/, ''));
      if (!t) return json(res, 404, { error: 'No user with that handle' });
      if (t.id === me.id) return json(res, 400, { error: "That's you" });
      if ((db.blocks[t.id] || []).includes(me.id)) return json(res, 403, { error: 'This user is not accepting requests' });
      if ((db.follows[me.id] || []).includes(t.id)) return json(res, 200, { status: 'friends', user: publicUser(t) });
      if ((db.requests[me.id] || []).includes(t.id)) { clearReq(t.id, me.id); link2(me.id, t.id); save(); pushTo(t.id, `${me.name} accepted your request`, 'You are now friends', { kind: 'friend' }); return json(res, 200, { status: 'friends', user: publicUser(t) }); }
      const a = new Set(db.requests[t.id] || []); a.add(me.id); db.requests[t.id] = [...a]; save();
      pushTo(t.id, `${me.name} sent you a friend request`, 'Open Nova to accept', { kind: 'friend' });
      return json(res, 200, { status: 'requested', user: publicUser(t) });
    }
    if (p === '/friends/accept' && req.method === 'POST') {
      const { id } = JSON.parse(await readBody(req, 1024) || '{}');
      if (!(db.requests[me.id] || []).includes(id) || !db.users[id]) return json(res, 404, { error: 'No such request' });
      clearReq(id, me.id); link2(me.id, id); save(); pushTo(id, `${me.name} accepted your request`, 'You are now friends', { kind: 'friend' });
      return json(res, 200, { user: publicUser(db.users[id]) });
    }
    if (p === '/friends/decline' && req.method === 'POST') {
      const { id } = JSON.parse(await readBody(req, 1024) || '{}'); clearReq(id, me.id); save(); return json(res, 200, { ok: true });
    }
    if (p === '/friends/remove' && req.method === 'POST') {
      const { id } = JSON.parse(await readBody(req, 1024) || '{}');
      db.follows[me.id] = (db.follows[me.id] || []).filter(x => x !== id); db.follows[id] = (db.follows[id] || []).filter(x => x !== me.id);
      clearReq(id, me.id); clearReq(me.id, id); save(); return json(res, 200, { ok: true });
    }
    // ----- moderation: block + report
    if (p === '/blocks' && req.method === 'GET') return json(res, 200, { users: byId(db.blocks[me.id]) });
    if (p === '/blocks' && req.method === 'POST') {
      const { id } = JSON.parse(await readBody(req, 1024) || '{}');
      if (!db.users[id] || id === me.id) return json(res, 400, { error: 'Cannot block this user' });
      db.blocks[me.id] = [...new Set([...(db.blocks[me.id] || []), id])];
      db.follows[me.id] = (db.follows[me.id] || []).filter(x => x !== id); db.follows[id] = (db.follows[id] || []).filter(x => x !== me.id);
      clearReq(id, me.id); clearReq(me.id, id); save(); return json(res, 200, { ok: true });
    }
    if (p === '/blocks' && req.method === 'DELETE') {
      const id = url.searchParams.get('id'); db.blocks[me.id] = (db.blocks[me.id] || []).filter(x => x !== id); save(); return json(res, 200, { ok: true });
    }
    if (p === '/reports' && req.method === 'POST') {
      if (limited(ip + ':rep', 10, 3600000)) return json(res, 429, { error: 'Too many reports' });
      const b = JSON.parse(await readBody(req, 2048) || '{}');
      db.reports.push({ id: rid(4), by: me.id, target: clean(b.target, 40), room: clean(b.room, 8), reason: clean(b.reason, 40), text: clean(b.text, 300), ts: Date.now() });
      db.reports = db.reports.slice(-2000); save(); return json(res, 200, { ok: true });
    }
    const findByHandle = (h) => Object.values(db.users).find(x => x.handle === clean(h, 30).toLowerCase().replace(/^@/, '') && !x.banned);
    const blockedMe = (t) => (db.blocks[t.id] || []).includes(me.id);
    const relation = (t) => t.id === me.id ? 'self' : (db.follows[me.id] || []).includes(t.id) ? 'friends' : (db.requests[t.id] || []).includes(me.id) ? 'requested' : (db.requests[me.id] || []).includes(t.id) ? 'incoming' : 'none';
    if (p === '/users/lookup') { // profile: public fields + presence + stats + relationship to the caller
      const u = findByHandle(url.searchParams.get('handle'));
      if (!u || blockedMe(u)) return json(res, 404, { error: 'not found' });
      const hist = db.history[u.id] || [], providers = {};
      for (const h of hist) { const k = providerOf(h.url); providers[k] = (providers[k] || 0) + (h.views || 1); }
      return json(res, 200, { ...publicUser(u), presence: presenceOf(u), relation: relation(u),
        stats: { joined: u.joined, minutes: Math.round(u.minutes || 0), rooms: u.roomsJoined || 0, videos: hist.length, likes: (db.likes[u.id] || []).length, providers: (u.flags && u.flags.privateActivity && u.id !== me.id) ? {} : providers } });
    }
    if (p === '/users/videos') { // profile tabs: top (most watched) | history | liked. Private activity is visible to the owner only.
      const u = findByHandle(url.searchParams.get('handle'));
      if (!u || blockedMe(u)) return json(res, 404, { error: 'not found' });
      if (u.flags && u.flags.privateActivity && u.id !== me.id) return json(res, 200, { hidden: true, items: [] });
      const tab = url.searchParams.get('tab') || 'top', hist = db.history[u.id] || [];
      const items = tab === 'liked' ? (db.likes[u.id] || []) : tab === 'history' ? hist : [...hist].sort((a, b) => (b.views || 1) - (a.views || 1) || b.ts - a.ts);
      return json(res, 200, { hidden: false, items: items.slice(0, 30).map(x => ({ url: x.url, title: x.title, thumb: x.thumb, views: x.views || 1, ts: x.ts })) });
    }
    if (p === '/users/search') { // find people by name or @handle (min 2 chars); never returns people who blocked you
      const q = clean(url.searchParams.get('q'), 30).toLowerCase().replace(/^@/, '');
      if (q.length < 2) return json(res, 200, { users: [] });
      const users = Object.values(db.users).filter(x => x.id !== me.id && !x.banned && !x.guest && x.setup && !blockedMe(x) && !(db.blocks[me.id] || []).includes(x.id)
        && ((x.handle || '').includes(q) || (x.name || '').toLowerCase().includes(q)))
        .sort((a, b) => ((b.handle === q) - (a.handle === q)) || (a.handle || '').length - (b.handle || '').length).slice(0, 20)
        .map(x => ({ ...publicUser(x), relation: relation(x) }));
      return json(res, 200, { users });
    }
    return json(res, 404, { error: 'Not found' });
  } catch (e) { return json(res, 400, { error: 'Bad request' }); }
});

/* ---------- WebSocket ---------- */
const wss = new WebSocketServer({ server, maxPayload: 8192 });
wss.on('connection', (ws, req) => {
  if (limited((req.socket.remoteAddress || '') + ':ws', 40)) return ws.close(1013, 'Too many connections');
  let room = null, me = null, user = null, lastChat = 0, waiting = null, waitingRoom = null;
  ws.isAlive = true; ws.on('pong', () => (ws.isAlive = true));

  function enter(r) {
    clearTimeout(r.emptyTimer);
    if (db.users[user.id]) db.users[user.id].roomsJoined = (db.users[user.id].roomsJoined || 0) + 1;
    me = { id: rid(4), user, ws }; room = r;
    if (!r.hostId) { r.hostId = me.id; r.ownerId = user.id; }
    else if (!r.members.has(r.hostId)) { r.hostId = me.id; r.state.position = nowPos(r); r.state.updatedAt = Date.now(); }
    r.members.set(me.id, me);
    send(ws, { type: 'joined', code: r.code, id: me.id, hostId: r.hostId, members: memberList(r), state: stateMsg(r),
      settings: settingsOf(r), queue: queueOf(r) });
    broadcast(r, { type: 'members', hostId: r.hostId, members: memberList(r) }, me.id);
    broadcast(r, { type: 'system', text: `${user.name} joined` }, me.id);
    me.joinedAt = Date.now();
  }
  const isLeader = () => room && me && (me.id === room.hostId);

  ws.on('message', (raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (!room) {
      if (waiting) { if (m.type === 'cancel') { waitingRoom.pending.delete(waiting); const h = waitingRoom.members.get(waitingRoom.hostId); if (h) send(h.ws, { type: 'join_request_cancel', rid: waiting }); waiting = null; ws.close(1000); } return; }
      const u = authUser(m.token);
      if (!u) return send(ws, { type: 'error', message: 'Please sign in again' });
      user = u;
      if (m.type === 'create') {
        const code = newCode();
        const r = { code, hostId: null, ownerId: u.id, members: new Map(), privacy: PRIVACY.includes(m.privacy) ? m.privacy : 'public',
          playback: 'vote', voice: true, chat: true, locked: false, approval: false, max: 100, bans: new Set(), pending: new Map(), muted: new Map(), queue: [], geo: u.flags.hideLocation ? null : { lat: u.lat, lon: u.lon },
          state: { url: clean(m.url, 2000), title: clean(m.title, 200), thumb: clean(m.thumb, 500), playing: !!clean(m.url, 2000), position: 0, updatedAt: Date.now() } };
        rooms.set(code, r); logEvt('room_created', { code, privacy: r.privacy }); return enter(r);
      }
      if (m.type === 'join') {
        const r = rooms.get(clean(m.code, 8).toUpperCase());
        if (!r) return send(ws, { type: 'error', message: 'Room not found' });
        if (r.bans.has(u.id)) return send(ws, { type: 'error', message: 'You were removed from this room' });
        if (r.members.size >= (r.max || 100)) return send(ws, { type: 'error', message: 'Room is full' });
        const rejoin = (r.recent && r.recent.get(u.id)) > Date.now() - 120000, owner = r.ownerId === u.id;
        if (r.locked && !owner && !rejoin) return send(ws, { type: 'error', message: 'This room is locked' });
        if (r.pass && !owner && !rejoin) {
          if (!m.passcode) return send(ws, { type: 'error', code: 'passcode', message: 'This room needs a passcode' });
          if (crypto.createHash('sha256').update(clean(m.passcode, 12)).digest('hex') !== r.pass) return send(ws, { type: 'error', code: 'passcode', message: 'Wrong passcode' });
        }
        if (r.approval && !owner && !rejoin && r.members.has(r.hostId)) { // host must approve
          if (r.pending.size >= 20) return send(ws, { type: 'error', message: 'Too many people are waiting to join' });
          const rq = rid(3); waiting = rq; waitingRoom = r;
          r.pending.set(rq, { user: u,
            approve: () => { waiting = null; if (r.members.size >= (r.max || 100)) return send(ws, { type: 'error', message: 'Room is full' }); enter(r); },
            deny: () => { waiting = null; send(ws, { type: 'error', message: 'The host declined your request' }); } });
          send(ws, { type: 'pending' });
          return send(r.members.get(r.hostId).ws, { type: 'join_request', rid: rq, name: u.name, handle: u.handle, avatar: u.avatar || '' });
        }
        if (r.privacy === 'friends' && r.ownerId !== u.id && !(db.follows[r.ownerId] || []).includes(u.id) && !((r.recent && r.recent.get(u.id)) > Date.now() - 120000))
          return send(ws, { type: 'error', message: 'This room is for friends only' });
        return enter(r);
      }
      return;
    }

    if (m.type === 'control' && isLeader()) {
      const s = room.state; const pos = Math.max(0, Number(m.position) || 0);
      if (m.action === 'load') { loadVideo(room, m.url, m.title, m.thumb); return; }
      if (m.action === 'play') s.playing = true; else if (m.action === 'pause') s.playing = false;
      else if (m.action !== 'seek') return;
      s.position = pos; s.updatedAt = Date.now(); return broadcast(room, stateMsg(room), me.id);
    }
    if (m.type === 'ended' && isLeader()) return void advance(room);
    if (m.type === 'unplayable') { // a client could not play the current video (embedding disabled, removed, blocked on device)
      const u = clean(m.url, 2000);
      if (!u || room.state.url !== u || room.unplayableHandled === u) return;
      room.unplayable = room.unplayable || new Set(); room.unplayable.add(me.id);
      if (!isLeader() && room.unplayable.size * 2 <= room.members.size) return; // guests: need a majority
      room.unplayableHandled = u; room.state.playing = false; room.state.updatedAt = Date.now();
      const why = { 100: 'was removed or is private', 101: 'cannot be played outside YouTube', 150: 'cannot be played outside YouTube' }[String(m.reason)] || 'could not be played';
      broadcast(room, { type: 'system', text: `This video ${why}.` + (room.queue.length ? ' Playing the next one…' : ' Pick another video.') });
      if (room.queue.length) advance(room, true); else broadcast(room, stateMsg(room));
      return;
    }
    if (m.type === 'pick') { // someone suggests a video
      const q = { id: rid(4), url: clean(m.url, 2000), title: clean(m.title, 200), thumb: clean(m.thumb, 500), by: user.name, votes: new Set() };
      if (!q.url) return;
      if (m.enqueue && isLeader()) { if (room.queue.length < 30) room.queue.push(q); return broadcast(room, { type: 'queue', queue: queueOf(room) }); }
      if (isLeader() || room.playback === 'play') return loadVideo(room, q.url, q.title, q.thumb);
      if (room.playback === 'leader') return send(ws, { type: 'error', message: 'Only the host can pick videos' });
      if (room.queue.length >= 30) return send(ws, { type: 'error', message: 'Queue is full' });
      q.votes.add(me.id); room.queue.push(q);
      if (!room.state.url) return advance(room); // nothing playing yet
      return broadcast(room, { type: 'queue', queue: queueOf(room) });
    }
    if (m.type === 'vote') {
      const q = room.queue.find(x => x.id === m.id); if (!q) return;
      q.votes.has(me.id) ? q.votes.delete(me.id) : q.votes.add(me.id);
      return broadcast(room, { type: 'queue', queue: queueOf(room) });
    }
    if (m.type === 'settings' && isLeader()) {
      if (PRIVACY.includes(m.privacy)) room.privacy = m.privacy;
      if (PLAYBACK.includes(m.playback)) room.playback = m.playback;
      for (const k of ['locked', 'approval', 'chat']) if (typeof m[k] === 'boolean') room[k] = m[k];
      if (typeof m.passcode === 'string') { const pc = clean(m.passcode, 12); room.pass = pc.length >= 4 ? crypto.createHash('sha256').update(pc).digest('hex') : null; } // 4-12 chars; empty clears
      if (Number.isInteger(m.max) && m.max >= 2 && m.max <= 100) room.max = m.max;
      if (typeof m.voice === 'boolean') { room.voice = m.voice; if (!m.voice) room.members.forEach(x => (x.mic = false)); }
      return broadcast(room, { type: 'settings', settings: settingsOf(room) });
    }
    if (m.type === 'mic') { me.mic = !!m.on && room.voice; return broadcast(room, { type: 'members', hostId: room.hostId, members: memberList(room) }); }
    if (m.type === 'rtc') { // WebRTC signaling relay (offer/answer/ice) between two members
      const t = room.members.get(String(m.to)); if (!t || JSON.stringify(m.data || {}).length > 6000) return;
      return send(t.ws, { type: 'rtc', from: me.id, data: m.data });
    }
    if (m.type === 'chat') {
      if (room.chat === false && !isLeader()) return send(ws, { type: 'error', message: 'Chat is turned off in this room' });
      const muteUntil = Math.max(room.muted.get(user.id) || 0, db.users[user.id]?.mutedUntil || 0);
      if (muteUntil > Date.now() && !isLeader()) return send(ws, { type: 'error', message: 'You are muted' });
      let text = clean(m.text, 500); if (text && !/^(gif:|img:)/.test(text)) text = maskBad(text); if (!text || Date.now() - lastChat < 300) return; lastChat = Date.now();
      const msg = { type: 'chat', from: user.name, handle: user.handle, uid: user.id, avatar: user.avatar || '', text, ts: Date.now() };
      for (const mm of room.members.values()) {
        if ((db.blocks[mm.user.id] || []).includes(user.id) || (db.blocks[user.id] || []).includes(mm.user.id)) continue; // blocked either way
        send(mm.ws, msg);
      }
      return;
    }
    if (m.type === 'reaction') { const e = clean(m.emoji, 8); if (e) broadcast(room, { type: 'reaction', from: user.name, emoji: e }); return; }
    if (m.type === 'kick' && isLeader()) { // removing someone also bans them from rejoining this room
      const t = room.members.get(m.id); if (t && t.id !== me.id) { room.bans.add(t.user.id); send(t.ws, { type: 'kicked' }); t.ws.close(1000); }
    }
    if (m.type === 'approve' && isLeader()) { const q = room.pending.get(String(m.rid)); if (q) { room.pending.delete(String(m.rid)); q.approve(); } return; }
    if (m.type === 'deny' && isLeader()) { const q = room.pending.get(String(m.rid)); if (q) { room.pending.delete(String(m.rid)); q.deny(); } return; }
    if (m.type === 'mute' && isLeader()) { // host mutes someone's chat for N minutes (0 = unmute)
      const t = room.members.get(String(m.id)); if (!t || t.id === me.id) return;
      const mins = Math.max(0, Math.min(1440, Number(m.minutes) || 0));
      if (mins) room.muted.set(t.user.id, Date.now() + mins * 60000); else room.muted.delete(t.user.id);
      return broadcast(room, { type: 'system', text: mins ? `${t.user.name} was muted for ${mins} min` : `${t.user.name} can chat again` });
    }
    if (m.type === 'transfer' && isLeader()) { // explicit host handoff
      const t = room.members.get(String(m.id)); if (!t || t.id === me.id) return;
      room.state.position = nowPos(room); room.state.updatedAt = Date.now(); room.hostId = t.id;
      broadcast(room, { type: 'members', hostId: room.hostId, members: memberList(room) });
      return broadcast(room, { type: 'system', text: `${t.user.name} is now the host` });
    }
    if (m.type === 'close' && isLeader()) { // end the room for everyone
      room.closed = true; clearTimeout(room.emptyTimer); rooms.delete(room.code);
      for (const x of [...room.members.values()]) { send(x.ws, { type: 'closed' }); try { x.ws.close(1000); } catch {} }
      for (const q of room.pending.values()) q.deny(); room.pending.clear(); logEvt('room_closed', { code: room.code }); return;
    }

  });

  ws.on('close', () => {
    if (waiting && waitingRoom) { waitingRoom.pending.delete(waiting); const h = waitingRoom.members.get(waitingRoom.hostId); if (h) send(h.ws, { type: 'join_request_cancel', rid: waiting }); }
    if (!room || !me || room.closed) return;
    const mins = (Date.now() - me.joinedAt) / 60000; if (db.users[user.id]) { db.users[user.id].minutes = (db.users[user.id].minutes || 0) + mins; save(); }
    room.members.delete(me.id);
    (room.recent = room.recent || new Map()).set(user.id, Date.now());
    if (room.members.size === 0) { const r0 = room; r0.emptyTimer = setTimeout(() => { if (r0.members.size === 0) rooms.delete(r0.code); }, 60000); return; }
    if (room.hostId === me.id) {
      room.hostId = room.members.keys().next().value;
      room.state.position = nowPos(room); room.state.updatedAt = Date.now();
      const nh = room.members.get(room.hostId); // pending join requests follow the new host
      if (nh) for (const [rq, q] of room.pending) send(nh.ws, { type: 'join_request', rid: rq, name: q.user.name, handle: q.user.handle, avatar: q.user.avatar || '' });
    }
    broadcast(room, { type: 'members', hostId: room.hostId, members: memberList(room) });
    broadcast(room, { type: 'system', text: `${user.name} left` });
  });
});
setInterval(() => { // delete chat images older than 24 h
  try { const d = path.join(DATA_DIR, 'chat'); for (const f of fs.readdirSync(d)) { const p2 = path.join(d, f); if (Date.now() - fs.statSync(p2).mtimeMs > 86400000) fs.unlinkSync(p2); } } catch {}
}, 3600000);
setInterval(() => wss.clients.forEach((ws) => { if (!ws.isAlive) return ws.terminate(); ws.isAlive = false; ws.ping(); }), 30000);
loadDb().then(() => server.listen(PORT, () => console.log(`Nova Watch Party server on :${PORT} (dev mode: ${DEV}, storage: ${pool ? 'postgres' : 'file'})`)))
  .catch((e) => { console.error('startup failed', e.message); process.exit(1); });
