/* Shared helpers for every EverHungry page. */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const money = n => '\u20B9' + Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
const CENTER = [23.2599, 77.4126];
const HOME = { customer: '/', owner: '/owner.html', driver: '/driver.html', admin: '/admin.html' };
const ROLE_LABEL = { customer: 'Customer', owner: 'Restaurant', driver: 'Driver', admin: 'Admin' };

const STATUS = {
  PLACED: 'Order placed', ACCEPTED: 'Accepted', PREPARING: 'Being prepared', READY: 'Ready for pickup',
  PICKED_UP: 'Picked up', ON_THE_WAY: 'On the way', DELIVERED: 'Delivered',
  REJECTED: 'Declined by restaurant', CANCELLED: 'Cancelled', DRIVER_ASSIGNED: 'Driver assigned'
};
const FLOW = ['PLACED', 'ACCEPTED', 'PREPARING', 'READY', 'PICKED_UP', 'ON_THE_WAY', 'DELIVERED'];
const pill = s => `<span class="pill s-${s}">${STATUS[s] || s}</span>`;

async function api(path, method = 'GET', body) {
  const res = await fetch('/api' + path, {
    method, credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Request failed');
  return data;
}

function toast(msg, kind = '') {
  let box = $('#toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; document.body.appendChild(box); }
  const t = document.createElement('div');
  t.className = 'toast ' + kind; t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => t.remove(), 4200);
}
const fail = e => toast(e.message || String(e), 'bad');

function km(a, b) {
  const rad = d => d * Math.PI / 180, R = 6371;
  const dLat = rad(b[0] - a[0]), dLng = rad(b[1] - a[1]);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
const timeOf = iso => new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

/* Sign-in state + navigation. Pass the roles allowed on this page, or nothing for a public page. */
async function boot(roles) {
  let me = null;
  try { me = (await api('/me')).user; } catch (e) { /* offline */ }
  renderNav(me);
  if (roles) {
    if (!me) { location.href = '/auth.html?next=' + encodeURIComponent(location.pathname + location.search); return null; }
    if (!roles.includes(me.role)) { location.href = HOME[me.role]; return null; }
  }
  return me;
}

function renderNav(me) {
  const here = location.pathname;
  const link = (href, text) => `<a href="${href}" class="${here === href ? 'on' : ''}">${text}</a>`;
  let links = link('/', 'Restaurants');
  if (me && me.role === 'customer') links += link('/orders.html', 'My orders');
  if (me && me.role === 'owner') links += link('/owner.html', 'Kitchen');
  if (me && me.role === 'driver') links += link('/driver.html', 'Deliveries');
  if (me && me.role === 'admin') links += link('/admin.html', 'Admin');
  links += link('/creator.html', 'Creator');
  const who = me
    ? `<span class="role-tag">${ROLE_LABEL[me.role]}</span><span class="name">${esc(me.name)}</span><button class="btn sm" id="logout">Log out</button>`
    : `<a class="btn primary sm" href="/auth.html">Log in</a>`;
  $('#nav').innerHTML = `<nav class="nav glass"><a class="brand" href="/">Ever<b>Hungry</b></a><div class="links">${links}</div><div class="who">${who}</div></nav>`;
  const out = $('#logout');
  if (out) out.onclick = async () => { await api('/auth/logout', 'POST', {}); location.href = '/'; };
}

/* Live updates: server-sent events, plus a slow poll as a safety net. */
function live(handler) {
  try { const es = new EventSource('/api/events'); es.onmessage = e => handler(JSON.parse(e.data)); } catch (e) { /* ignore */ }
  setInterval(() => handler({ type: 'poll' }), 15000);
}

/* Leaflet helpers (pages that use maps load Leaflet first). */
function makeMap(el, center = CENTER, zoom = 13) {
  const map = L.map(el).setView(center, zoom);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    { attribution: '&copy; OpenStreetMap contributors &copy; CARTO', maxZoom: 19 }).addTo(map);
  return map;
}
const pinIcon = kind => L.divIcon({ className: '', html: `<span class="pin pin-${kind}"></span>`, iconSize: [22, 22], iconAnchor: [11, 11] });
const LEAFLET = '<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">';
