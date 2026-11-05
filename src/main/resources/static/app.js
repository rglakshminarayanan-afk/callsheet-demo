'use strict';

/* ============================================================
   CallSheet demo front end. Plain JavaScript, no build step.
   Everything talks to the Spring Boot API under /api.
   ============================================================ */

// ---------- small utilities ----------
const $ = (sel, el = document) => el.querySelector(sel);
const pad = n => String(n).padStart(2, '0');
const iso = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const TODAY = parse(iso(new Date()));
const fmtShort = s => parse(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
const fmtLong = s => parse(s).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
const fmtRange = (a, b) => a === b ? fmtShort(a) : `${fmtShort(a)} to ${fmtShort(b)}`;
const fmtWhen = iso8601 => new Date(iso8601).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Tiny element builder: h('div', {class:'x', onclick: fn}, child, child...) */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') {
      for (const [sk, sv] of Object.entries(v)) sk.startsWith('--') ? el.style.setProperty(sk, sv) : (el.style[sk] = sv);
    } else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  (function add(list) {
    for (const c of list) {
      if (c == null || c === false) continue;
      if (Array.isArray(c)) add(c);
      else el.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
  })(kids);
  return el;
}

// ---------- state ----------
const S = {
  token: sessionStorage.getItem('cs_token'),
  user: JSON.parse(sessionStorage.getItem('cs_user') || 'null'),
  tab: 'home',
  meetings: [],
  // production house
  filters: { q: '', language: '', from: '', to: '' },
  tlStart: TODAY,
  // manager
  actors: [],
  actorId: null,
  month: new Date(TODAY.getFullYear(), TODAY.getMonth(), 1),
  sel: { start: null, end: null },
  paintType: 'SHOOTING',
};

// ---------- api ----------
async function api(path, { method = 'GET', body, raw = false } = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  const res = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  if (res.status === 401 && S.token) {
    signOut();
    throw new Error('Your session ended. Please sign in again');
  }
  if (!res.ok) {
    let msg = 'Something went wrong. Try again';
    try { const j = await res.json(); if (j.message) msg = j.message; } catch (e) { /* keep default */ }
    throw new Error(msg);
  }
  return raw ? res : res.json();
}

// ---------- toast + dialog ----------
function toast(msg, isErr = false) {
  const t = h('div', { class: 'toast' + (isErr ? ' err' : ''), role: isErr ? 'alert' : 'status' }, msg);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), isErr ? 6000 : 3200);
}

function openDialog({ title, sub, body, actions }) {
  const dlg = $('#dlg');
  dlg.replaceChildren(
    h('div', { class: 'dlg-head' }, h('h2', {}, title), sub ? h('p', { class: 'muted small', style: { marginTop: '6px' } }, sub) : null),
    h('div', { class: 'dlg-body' }, body),
    h('div', { class: 'dlg-foot' }, actions.map(a => h('button', {
      class: 'btn ' + (a.kind || ''),
      onclick: async ev => {
        if (a.close) { dlg.close(); return; }
        const btn = ev.currentTarget;
        btn.disabled = true;
        try { if (await a.run()) dlg.close(); }
        catch (e) { toast(e.message, true); }
        finally { btn.disabled = false; }
      },
    }, a.label)))
  );
  dlg.showModal();
}

const field = (label, input) => h('label', { class: 'field' }, h('span', {}, label), input);
const closeBtn = (label = 'Cancel') => ({ label, close: true });

// ---------- sign in / out ----------
async function signIn(userId) {
  const r = await api('/login', { method: 'POST', body: { userId } });
  S.token = r.token; S.user = r.user;
  sessionStorage.setItem('cs_token', r.token);
  sessionStorage.setItem('cs_user', JSON.stringify(r.user));
  S.tab = 'home'; S.actorId = null; S.sel = { start: null, end: null };
  render();
}

function signOut() {
  S.token = null; S.user = null;
  sessionStorage.removeItem('cs_token');
  sessionStorage.removeItem('cs_user');
  render();
}

// ---------- login screen ----------
function heroStrip() {
  const rows = [
    ['Aarav', [[2, 9, 's']]], ['Meera', [[4, 5, 's'], [11, 17, 's']]],
    ['Vikram', [[0, 6, 's'], [14, 18, 's']]], ['Tara', [[7, 12, 's']]],
  ];
  const N = 21;
  const grid = h('div', { class: 'tl', style: { '--n': N }, 'aria-hidden': 'true' });
  for (const [name, spans] of rows) {
    grid.append(h('div', { class: 'tl-name' }, name));
    for (let i = 0; i < N; i++) {
      const busy = spans.some(([a, b]) => i >= a && i <= b);
      grid.append(h('div', { class: 'tl-cell' + (busy ? ' busy' : '') }));
    }
  }
  return h('div', { class: 'tl-wrap hero-strip' }, grid);
}

async function renderLogin() {
  const root = $('#app');
  root.replaceChildren(h('div', { class: 'login' }, h('p', { class: 'muted' }, 'Loading demo accounts...')));
  let users;
  try { users = await (await fetch('/api/demo-users')).json(); }
  catch (e) { root.replaceChildren(h('div', { class: 'login' }, h('p', {}, 'The server is not responding yet. Wait a few seconds and refresh.'))); return; }

  const group = (role, heading, hint) => h('div', {},
    h('h3', {}, heading), h('p', { class: 'muted small', style: { margin: '4px 0 12px' } }, hint),
    users.filter(u => u.role === role).map(u => h('button', { class: 'account-btn', onclick: () => signIn(u.id).catch(e => toast(e.message, true)) },
      h('b', {}, u.name), h('span', { class: 'muted small' }, u.org))));

  root.replaceChildren(h('div', { class: 'login' },
    h('div', { class: 'brand', style: { color: 'var(--ink)', marginBottom: '28px' } }, logo('#1B1F3B'), 'CallSheet'),
    h('h1', {}, "Know who's free before you call."),
    h('p', { class: 'lead' }, 'Managers keep availability up to date. Production houses find the right actor, propose a meeting, and follow the reply.'),
    heroStrip(),
    h('div', { class: 'accounts' },
      group('PRODUCTION', 'Sign in as a production house', 'Search talent, see free dates, send meeting requests.'),
      group('MANAGER', 'Sign in as an actor manager', 'Mark shooting, vacation and free dates. Answer requests.')),
    h('p', { class: 'muted small', style: { marginTop: '28px' } }, 'This is a demo with fictional people and no passwords. Changes are shared by everyone using this link and are cleared when the server restarts.')));
}

function logo(color) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 28 28'); svg.setAttribute('width', '26'); svg.setAttribute('height', '26');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = `<rect x="2" y="10" width="24" height="15" rx="3" fill="${color}"/><path d="M2 10 L5 3 L26 7 L24 10 Z" fill="#F2A516"/><path d="M8 4 L11 10 M14 5 L17 10 M20 6 L23 10" stroke="${color}" stroke-width="2"/>`;
  return svg;
}

// ---------- shell ----------
function render() {
  if (!S.user) { renderLogin(); return; }
  const isMgr = S.user.role === 'MANAGER';
  const tabBtn = (id, label, extra) => h('button', {
    class: 'tab', 'aria-current': S.tab === id ? 'page' : null,
    onclick: () => { S.tab = id; render(); },
  }, label, extra);

  $('#app').replaceChildren(
    h('header', { class: 'topbar' },
      h('div', { class: 'topbar-inner' },
        h('div', { class: 'brand' }, logo('#FFFFFF'), 'CallSheet'),
        h('div', { class: 'who' },
          h('div', {}, h('div', { class: 'name' }, S.user.name), h('div', { class: 'org' }, S.user.org)),
          h('span', { class: 'pill' }, isMgr ? 'Actor manager' : 'Production house'),
          h('button', { class: 'btn small', onclick: signOut }, 'Sign out'))),
      h('nav', { class: 'tabs', 'aria-label': 'Main' },
        tabBtn('home', isMgr ? 'My actors' : 'Find talent'),
        tabBtn('meetings', 'Meetings', h('span', { class: 'badge-count', id: 'mbadge', hidden: true }, '0')))),
    h('main', { id: 'main' }),
    h('footer', {}, h('p', { class: 'muted small' }, 'Fictional demo data. ',
      h('button', { class: 'btn small quiet', onclick: resetDemo }, 'Reset demo data'))));

  if (S.tab === 'meetings') viewMeetings(); else (isMgr ? viewCalendar() : viewFind());
  refreshBadge();
}

async function resetDemo() {
  try { await api('/demo/reset', { method: 'POST' }); toast('Demo data reset'); S.sel = { start: null, end: null }; render(); }
  catch (e) { toast(e.message, true); }
}

async function refreshBadge() {
  try {
    S.meetings = await api('/meetings');
    const n = S.meetings.filter(m => m.canRespond).length;
    const b = $('#mbadge');
    if (b) { b.textContent = n; b.hidden = n === 0; b.title = n + ' waiting for your reply'; }
  } catch (e) { /* badge is optional */ }
}

const busyAt = (actor, ds) => actor.blocks.find(b => b.start <= ds && ds <= b.end);

// =====================================================================
// PRODUCTION HOUSE: find talent (schedule strip)
// =====================================================================
const LANGS = ['Hindi', 'English', 'Tamil', 'Telugu', 'Malayalam', 'Kannada', 'Marathi', 'Punjabi', 'Urdu'];

function viewFind() {
  const main = $('#main');
  const results = h('div', {});
  let timer;
  const refresh = () => { clearTimeout(timer); timer = setTimeout(loadResults, 200); };

  const q = h('input', { type: 'search', placeholder: 'Name, genre, language or city', value: S.filters.q,
    oninput: e => { S.filters.q = e.target.value; refresh(); } });
  const lang = h('select', { onchange: e => { S.filters.language = e.target.value; refresh(); } },
    h('option', { value: '' }, 'Any language'), LANGS.map(l => h('option', { value: l }, l)));
  lang.value = S.filters.language;
  const from = h('input', { type: 'date', min: iso(TODAY), value: S.filters.from, onchange: e => {
    S.filters.from = e.target.value;
    if (S.filters.from) {
      S.tlStart = parse(S.filters.from) > TODAY ? parse(S.filters.from) : TODAY;
      if (S.filters.to && S.filters.to < S.filters.from) { S.filters.to = ''; to.value = ''; }
    }
    refresh();
  } });
  const to = h('input', { type: 'date', min: iso(TODAY), value: S.filters.to, onchange: e => { S.filters.to = e.target.value; refresh(); } });
  const clear = h('button', { class: 'btn quiet', onclick: () => {
    S.filters = { q: '', language: '', from: '', to: '' }; S.tlStart = TODAY; viewFind();
  } }, 'Clear filters');

  main.replaceChildren(
    h('div', { class: 'page-head' },
      h('div', { class: 'grow' }, h('h2', {}, 'Find talent'),
        h('p', { class: 'muted' }, 'Green days are free. Click a free day to propose a meeting for that date.')),
      h('button', { class: 'btn primary', onclick: () => openRequestDialog() }, 'New meeting request')),
    h('div', { class: 'filters' },
      h('div', { class: 'field wide' }, h('span', {}, 'Search'), q),
      field('Language', lang), field('Free from', from), field('Free until', to), clear),
    results);

  async function loadResults() {
    const p = new URLSearchParams();
    if (S.filters.q) p.set('q', S.filters.q);
    if (S.filters.language) p.set('language', S.filters.language);
    if (S.filters.from) { p.set('from', S.filters.from); p.set('to', S.filters.to || S.filters.from); }
    try {
      const actors = await api('/actors?' + p.toString());
      drawStrip(results, actors);
    } catch (e) { toast(e.message, true); }
  }
  loadResults();
}

function drawStrip(container, actors) {
  const N = 28;
  const days = [...Array(N)].map((_, i) => addDays(S.tlStart, i));
  const range = `${fmtShort(iso(days[0]))} to ${fmtShort(iso(days[N - 1]))}`;

  const nav = h('div', { class: 'row', style: { margin: '4px 0 10px' } },
    h('strong', {}, actors.length === 1 ? '1 actor' : `${actors.length} actors`),
    h('span', { class: 'muted' }, range),
    h('span', { class: 'grow' }),
    h('div', { class: 'legend' },
      h('span', {}, h('i', { class: 'swatch free' }), 'Free'), h('span', {}, h('i', { class: 'swatch busy' }), 'Busy')),
    h('button', { class: 'btn small', disabled: S.tlStart <= TODAY, onclick: () => { S.tlStart = addDays(S.tlStart, -N) < TODAY ? TODAY : addDays(S.tlStart, -N); drawStrip(container, actors); } }, 'Previous 4 weeks'),
    h('button', { class: 'btn small', onclick: () => { S.tlStart = addDays(S.tlStart, N); drawStrip(container, actors); } }, 'Next 4 weeks'));

  if (!actors.length) {
    container.replaceChildren(nav, h('div', { class: 'empty' }, 'No actors match. Try a wider date range or clear a filter.'));
    return;
  }

  const grid = h('div', { class: 'tl', style: { '--n': N } });
  grid.append(h('div', { class: 'tl-corner' }, 'Actor'));
  days.forEach((d, i) => {
    const first = d.getDate() === 1 || i === 0;
    grid.append(h('div', { class: 'tl-day' + (d.getDay() % 6 === 0 ? ' weekend' : '') + (iso(d) === iso(TODAY) ? ' today' : '') + (d.getDate() === 1 ? ' monthstart' : '') },
      h('small', {}, first ? MONTHS[d.getMonth()] : ''), 'SMTWTFS'[d.getDay()], h('b', {}, d.getDate())));
  });
  for (const a of actors) {
    grid.append(h('button', { class: 'tl-name', onclick: () => openProfile(a) },
      h('b', {}, a.name), h('span', {}, a.city), h('span', {}, a.languages.join(', '))));
    for (const d of days) {
      const ds = iso(d);
      const busy = busyAt(a, ds);
      grid.append(h('button', {
        class: 'tl-cell' + (busy ? ' busy' : '') + (d.getDay() % 6 === 0 ? ' weekend' : '') + (d.getDate() === 1 ? ' monthstart' : ''),
        'aria-label': `${a.name}, ${fmtLong(ds)}: ${busy ? 'busy' : 'free'}`,
        title: `${a.name}, ${fmtShort(ds)}: ${busy ? 'busy' : 'free'}`,
        onclick: () => busy ? toast(`${a.name} is busy on ${fmtShort(ds)}. Pick a green day.`) : openRequestDialog({ actorId: a.id, date: ds }),
      }));
    }
  }
  container.replaceChildren(nav, h('div', { class: 'tl-wrap' }, grid));
}

function openProfile(a) {
  const upcoming = a.blocks.filter(b => b.end >= iso(TODAY));
  const isMgr = S.user.role === 'MANAGER';
  openDialog({
    title: a.name,
    sub: `${a.city}. Managed by ${a.managerName}.`,
    body: h('div', { class: 'stack' },
      h('p', {}, a.bio),
      h('div', {}, h('strong', { class: 'small' }, 'Languages'), h('div', { class: 'tagrow', style: { marginTop: '4px' } }, a.languages.map(l => h('span', { class: 'tag-pill' }, l)))),
      h('div', {}, h('strong', { class: 'small' }, 'Genres'), h('div', { class: 'tagrow', style: { marginTop: '4px' } }, a.genres.map(l => h('span', { class: 'tag-pill' }, l)))),
      h('div', {}, h('strong', { class: 'small' }, 'Busy periods'),
        upcoming.length
          ? h('ul', { class: 'block-list' }, upcoming.slice(0, 6).map(b => h('li', {}, h('i', { class: 'dot busy' }), fmtRange(b.start, b.end))))
          : h('p', { class: 'muted' }, 'Free for the next 8 months.'))),
    actions: [closeBtn('Close'), { label: 'Request a meeting', kind: 'primary', run: async () => { setTimeout(() => openRequestDialog({ actorId: a.id }), 50); return true; } }],
  });
}

// =====================================================================
// ACTOR MANAGER: availability calendar
// =====================================================================
async function viewCalendar() {
  const main = $('#main');
  main.replaceChildren(h('p', { class: 'muted' }, 'Loading your actors...'));
  try { S.actors = await api('/actors'); } catch (e) { toast(e.message, true); return; }
  if (!S.actors.length) { main.replaceChildren(h('div', { class: 'empty' }, 'No actors are linked to your account yet.')); return; }
  if (!S.actors.some(a => a.id === S.actorId)) S.actorId = S.actors[0].id;
  drawCalendar();
}

function drawCalendar() {
  const main = $('#main');
  const actor = S.actors.find(a => a.id === S.actorId);
  const m = S.month;
  const first = new Date(m.getFullYear(), m.getMonth(), 1);
  const lead = (first.getDay() + 6) % 7; // Monday first
  const count = new Date(m.getFullYear(), m.getMonth() + 1, 0).getDate();
  const { start, end } = S.sel;
  const inSel = ds => start && (end ? ds >= start && ds <= end : ds === start);

  const cal = h('div', { class: 'cal', role: 'grid' },
    ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map(d => h('div', { class: 'cal-dow' }, d)),
    [...Array(lead)].map(() => h('div', { class: 'cal-cell empty' })),
    [...Array(count)].map((_, i) => {
      const d = new Date(m.getFullYear(), m.getMonth(), i + 1);
      const ds = iso(d);
      const b = busyAt(actor, ds);
      const past = ds < iso(TODAY);
      const cls = 'cal-cell' + (b ? (b.type === 'SHOOTING' ? ' shoot' : ' vac') : '') + (past ? ' past' : '') + (ds === iso(TODAY) ? ' today' : '') + (inSel(ds) ? ' sel' : '');
      const label = b ? (b.type === 'SHOOTING' ? 'Shooting' : 'Vacation') : '';
      return h('button', {
        class: cls, disabled: past, title: b && b.note ? `${label}: ${b.note}` : label,
        'aria-label': `${fmtLong(ds)}${label ? ', ' + label : ', available'}`,
        onclick: () => pickDay(ds),
      }, h('span', { class: 'num' }, i + 1), h('span', { class: 'tag' }, b && (b.start === ds || d.getDay() === 1 || i === 0) ? label : ''));
    }));

  const note = h('input', { type: 'text', maxlength: '120', placeholder: 'For example: Outdoor schedule, Ooty' });
  const radio = (value, label) => h('label', {},
    h('input', { type: 'radio', name: 'ptype', value, checked: S.paintType === value, onchange: () => { S.paintType = value; } }),
    h('span', {}, label));

  const upcoming = actor.blocks.filter(b => b.end >= iso(TODAY));

  const side = h('div', { class: 'panel stack' },
    h('h3', {}, 'Mark dates'),
    start
      ? h('p', {}, h('strong', {}, end ? fmtRange(start, end) : fmtShort(start)), end ? '' : h('span', { class: 'muted' }, '. Click an end date, or apply for this one day.'))
      : h('p', { class: 'muted' }, 'Click a start date on the calendar, then an end date.'),
    h('div', { class: 'radios', role: 'radiogroup', 'aria-label': 'Status' },
      radio('SHOOTING', 'Shooting'), radio('VACATION', 'Vacation'), radio('AVAILABLE', 'Available')),
    field('Note (only you and your team see this)', note),
    h('div', { class: 'row' },
      h('button', { class: 'btn primary', disabled: !start, onclick: () => applyRange(actor, note.value) }, 'Save dates'),
      h('button', { class: 'btn quiet', disabled: !start, onclick: () => { S.sel = { start: null, end: null }; drawCalendar(); } }, 'Clear selection')),
    h('p', { class: 'muted small' }, 'Production houses see these days only as busy. They never see the reason or your note.'),
    h('h3', { style: { marginTop: '22px' } }, 'Coming up'),
    upcoming.length
      ? h('ul', { class: 'block-list' }, upcoming.map(b => h('li', {},
          h('i', { class: 'dot ' + (b.type === 'SHOOTING' ? 'shoot' : 'vac') }),
          h('div', { class: 'grow' }, h('div', {}, h('strong', {}, b.type === 'SHOOTING' ? 'Shooting' : 'Vacation'), ', ', fmtRange(b.start, b.end)), b.note ? h('div', { class: 'muted small' }, b.note) : null),
          h('button', { class: 'btn small danger', 'aria-label': `Remove ${fmtRange(b.start, b.end)}`, onclick: () => removeBlock(actor, b.id) }, 'Remove'))))
      : h('p', { class: 'muted' }, 'Nothing marked. This actor shows as free every day.'));

  main.replaceChildren(
    h('div', { class: 'page-head' },
      h('div', { class: 'grow' }, h('h2', {}, 'My actors'), h('p', { class: 'muted' }, 'Keep these dates current so production houses only ask for days you can do.')),
      h('button', { class: 'btn primary', onclick: () => openRequestDialog({ actorId: actor.id }) }, 'Request a meeting')),
    h('div', { class: 'chips', role: 'group', 'aria-label': 'Choose an actor', style: { marginBottom: '16px' } },
      S.actors.map(a => h('button', { class: 'chip', 'aria-pressed': a.id === S.actorId ? 'true' : 'false',
        onclick: () => { S.actorId = a.id; S.sel = { start: null, end: null }; drawCalendar(); } }, a.name))),
    h('div', { class: 'split' },
      h('div', { class: 'panel' },
        h('div', { class: 'cal-head' },
          h('button', { class: 'btn small', onclick: () => { S.month = new Date(m.getFullYear(), m.getMonth() - 1, 1); drawCalendar(); } }, 'Previous'),
          h('h3', {}, `${MONTHS[m.getMonth()]} ${m.getFullYear()}`),
          h('button', { class: 'btn small', onclick: () => { S.month = new Date(m.getFullYear(), m.getMonth() + 1, 1); drawCalendar(); } }, 'Next')),
        cal,
        h('div', { class: 'legend', style: { marginTop: '12px' } },
          h('span', {}, h('i', { class: 'swatch shoot' }), 'Shooting'), h('span', {}, h('i', { class: 'swatch vac' }), 'Vacation'), h('span', {}, h('i', { class: 'swatch free' }), 'Available'))),
      side));
}

function pickDay(ds) {
  const s = S.sel;
  if (!s.start || s.end) { S.sel = { start: ds, end: null }; }
  else if (ds < s.start) { S.sel = { start: ds, end: s.start }; }
  else { S.sel = { start: s.start, end: ds }; }
  drawCalendar();
}

async function applyRange(actor, note) {
  const { start, end } = S.sel;
  try {
    const updated = await api(`/actors/${actor.id}/availability`, { method: 'POST', body: { start, end: end || start, type: S.paintType, note } });
    S.actors = S.actors.map(a => a.id === updated.id ? updated : a);
    S.sel = { start: null, end: null };
    toast(S.paintType === 'AVAILABLE' ? 'Dates marked available' : 'Dates saved');
    drawCalendar();
  } catch (e) { toast(e.message, true); }
}

async function removeBlock(actor, blockId) {
  try {
    const updated = await api(`/actors/${actor.id}/availability/${blockId}`, { method: 'DELETE' });
    S.actors = S.actors.map(a => a.id === updated.id ? updated : a);
    toast('Entry removed');
    drawCalendar();
  } catch (e) { toast(e.message, true); }
}

// =====================================================================
// MEETING REQUESTS (both roles)
// =====================================================================
function firstFree(actor, from) {
  for (let i = 0; i < 200; i++) { const ds = iso(addDays(from, i)); if (!busyAt(actor, ds)) return ds; }
  return iso(from);
}

async function openRequestDialog({ actorId = null, date = null } = {}) {
  const isMgr = S.user.role === 'MANAGER';
  let actors, prods = [];
  try { actors = await api('/actors'); if (isMgr) prods = await api('/production-users'); }
  catch (e) { toast(e.message, true); return; }
  if (!actors.length) { toast('There are no actors to request a meeting for', true); return; }

  const actorSel = h('select', {}, actors.map(a => h('option', { value: a.id }, a.name)));
  if (actorId) actorSel.value = actorId;
  const current = () => actors.find(a => a.id === actorSel.value);
  const prodSel = isMgr ? h('select', {}, prods.map(p => h('option', { value: p.id }, `${p.name}, ${p.org}`))) : null;
  const title = h('input', { type: 'text', maxlength: '80', placeholder: 'Working title of the project' });
  const dateIn = h('input', { type: 'date', min: iso(TODAY), value: date || firstFree(current(), addDays(TODAY, 3)) });
  const timeIn = h('input', { type: 'time', value: '11:00' });
  const msg = h('textarea', { rows: '3', placeholder: isMgr ? 'Why now, and what should they know?' : 'Role, schedule and anything the manager should know before replying.' });
  const warn = h('div', { class: 'warn', hidden: true });

  const check = () => {
    const b = busyAt(current(), dateIn.value);
    warn.hidden = !b;
    if (b) warn.textContent = isMgr
      ? `${current().name} is marked ${b.type.toLowerCase()} on this date. You may want to pick another day.`
      : `${current().name} shows as busy on this date. You can still send it. The manager may suggest another day.`;
  };
  actorSel.addEventListener('change', () => { if (!date) dateIn.value = firstFree(current(), addDays(TODAY, 3)); check(); });
  dateIn.addEventListener('change', check);
  check();

  openDialog({
    title: 'New meeting request',
    sub: isMgr ? 'Their next 60 days of availability are attached automatically.' : 'The actor’s manager will accept, decline or suggest a new date.',
    body: h('div', { class: 'stack' },
      field('Actor', actorSel), isMgr ? field('Send to', prodSel) : null,
      field('Project', title),
      h('div', { class: 'grid2' }, field('Date', dateIn), field('Time (IST)', timeIn)),
      warn, field('Message', msg)),
    actions: [closeBtn(), { label: 'Send request', kind: 'primary', run: async () => {
      await api('/meetings', { method: 'POST', body: {
        actorId: actorSel.value, projectTitle: title.value, message: msg.value,
        date: dateIn.value, time: timeIn.value, productionUserId: prodSel ? prodSel.value : null } });
      toast('Request sent');
      S.tab = 'meetings'; render();
      return true;
    } }],
  });
}

const STATUS = { PENDING: 'Awaiting reply', ACCEPTED: 'Confirmed', DECLINED: 'Declined', COUNTER_PROPOSED: 'New date proposed', CANCELLED: 'Cancelled' };

async function viewMeetings() {
  const main = $('#main');
  main.replaceChildren(h('p', { class: 'muted' }, 'Loading meetings...'));
  try { S.meetings = await api('/meetings'); } catch (e) { toast(e.message, true); return; }
  const isMgr = S.user.role === 'MANAGER';
  const need = S.meetings.filter(m => m.canRespond);
  const waiting = S.meetings.filter(m => !m.canRespond && (m.status === 'PENDING' || m.status === 'COUNTER_PROPOSED'));
  const confirmed = S.meetings.filter(m => m.status === 'ACCEPTED');
  const closed = S.meetings.filter(m => m.status === 'DECLINED' || m.status === 'CANCELLED');

  const section = (heading, list, emptyText) => h('section', {},
    h('h3', { class: 'section-title' }, `${heading} (${list.length})`),
    list.length ? list.map(meetingCard) : h('div', { class: 'empty' }, emptyText));

  main.replaceChildren(
    h('div', { class: 'page-head' },
      h('div', { class: 'grow' }, h('h2', {}, 'Meetings'),
        h('p', { class: 'muted' }, isMgr ? 'Requests for your actors, and the ones you have sent.' : 'Every request you and your colleagues have sent, and where it stands.')),
      h('button', { class: 'btn primary', onclick: () => openRequestDialog() }, 'New meeting request')),
    section('Needs your reply', need, 'Nothing is waiting on you.'),
    section('Waiting on the other side', waiting, 'No open requests are waiting.'),
    section('Confirmed', confirmed, 'No confirmed meetings yet.'),
    closed.length ? section('Closed', closed, '') : null);
  refreshBadge();
}

function meetingCard(m) {
  const isMgr = S.user.role === 'MANAGER';
  const who = isMgr
    ? `${m.productionOrg}, ${m.productionUserName}`
    : `${m.actorName}, managed by ${m.managerName}`;
  const reload = () => { viewMeetings(); };
  const act = (path, body) => async () => { await api(`/meetings/${m.id}/${path}`, { method: 'POST', body: body || {} }); reload(); return true; };

  const buttons = [];
  if (m.canRespond) {
    buttons.push(h('button', { class: 'btn primary', onclick: () => act('accept')().then(() => toast('Accepted. A calendar invite is ready.')).catch(e => toast(e.message, true)) }, 'Accept'));
    buttons.push(h('button', { class: 'btn', onclick: () => counterDialog(m, reload) }, 'Propose new date'));
    buttons.push(h('button', { class: 'btn danger', onclick: () => declineDialog(m, reload) }, 'Decline'));
  }
  if (m.status === 'ACCEPTED') buttons.push(h('button', { class: 'btn', onclick: () => downloadInvite(m) }, 'Download calendar invite'));
  if (m.canCancel && !m.canRespond) buttons.push(h('button', { class: 'btn quiet danger', onclick: () => cancelDialog(m, reload) }, 'Cancel meeting'));

  return h('article', { class: 'mcard ' + m.status },
    h('div', { class: 'row' },
      h('h3', { class: 'grow' }, m.projectTitle),
      h('span', { class: 'status ' + m.status }, STATUS[m.status])),
    h('p', { class: 'muted' }, who),
    h('p', { style: { margin: '8px 0' } }, h('span', { class: 'when' }, `${fmtLong(m.date)}, ${m.time} IST`),
      m.status === 'COUNTER_PROPOSED' ? h('span', { class: 'muted small' }, '  (new date)') : null),
    m.dateConflict && isMgr ? h('p', { class: 'warn', style: { margin: '8px 0' } }, `${m.actorName} is marked busy on this date.`) : null,
    m.message ? h('p', {}, m.message) : null,
    m.sharedAvailability.length ? h('details', { style: { marginTop: '10px' } },
      h('summary', {}, 'Availability shared with this request'),
      h('ul', { class: 'block-list' }, m.sharedAvailability.map(b => h('li', {},
        h('i', { class: 'dot ' + (b.type === 'SHOOTING' ? 'shoot' : b.type === 'VACATION' ? 'vac' : 'busy') }),
        `${b.type === 'BUSY' ? 'Busy' : b.type === 'SHOOTING' ? 'Shooting' : 'Vacation'}, ${fmtRange(b.start, b.end)}`))),
      h('p', { class: 'muted small' }, 'All other days in the next 60 days are free.')) : null,
    h('details', { style: { marginTop: '10px' } },
      h('summary', {}, `History (${m.history.length})`),
      h('ul', { class: 'history' }, m.history.map(e => h('li', {},
        h('strong', {}, e.action), ` by ${e.by}`, h('span', { class: 'muted small' }, `  ${fmtWhen(e.at)}`),
        e.action === 'Proposed a new date' || e.action === 'Requested a meeting' ? h('div', { class: 'muted small' }, `${fmtLong(e.date)}, ${e.time}`) : null,
        e.comment ? h('div', { class: 'small' }, `"${e.comment}"`) : null)))),
    buttons.length ? h('div', { class: 'row', style: { marginTop: '14px' } }, buttons) : null,
    m.status === 'PENDING' || m.status === 'COUNTER_PROPOSED' ? (m.canRespond ? null : h('p', { class: 'muted small', style: { marginTop: '10px' } }, `Waiting for ${m.awaiting === 'MANAGER' ? m.managerName : m.productionUserName} to reply.`)) : null,
    null);
}

function counterDialog(m, reload) {
  const d = h('input', { type: 'date', min: iso(TODAY), value: m.date });
  const t = h('input', { type: 'time', value: m.time });
  const c = h('textarea', { rows: '3', placeholder: 'Why this date works better' });
  openDialog({
    title: 'Propose a new date', sub: m.projectTitle,
    body: h('div', { class: 'stack' }, h('div', { class: 'grid2' }, field('Date', d), field('Time (IST)', t)), field('Message', c)),
    actions: [closeBtn(), { label: 'Send new date', kind: 'primary', run: async () => {
      await api(`/meetings/${m.id}/counter`, { method: 'POST', body: { date: d.value, time: t.value, comment: c.value } });
      toast('New date sent'); reload(); return true; } }],
  });
}

function declineDialog(m, reload) {
  const c = h('textarea', { rows: '3', placeholder: 'Optional. A short reason helps them plan.' });
  openDialog({
    title: 'Decline this meeting?', sub: m.projectTitle,
    body: field('Message', c),
    actions: [closeBtn('Keep it'), { label: 'Decline meeting', kind: 'danger', run: async () => {
      await api(`/meetings/${m.id}/decline`, { method: 'POST', body: { comment: c.value } });
      toast('Declined'); reload(); return true; } }],
  });
}

function cancelDialog(m, reload) {
  openDialog({
    title: 'Cancel this meeting?', sub: m.projectTitle,
    body: h('p', {}, 'The other side will see it as cancelled in the history.'),
    actions: [closeBtn('Keep it'), { label: 'Cancel meeting', kind: 'danger', run: async () => {
      await api(`/meetings/${m.id}/cancel`, { method: 'POST', body: {} });
      toast('Meeting cancelled'); reload(); return true; } }],
  });
}

async function downloadInvite(m) {
  try {
    const res = await api(`/meetings/${m.id}/invite.ics`, { raw: true });
    const url = URL.createObjectURL(await res.blob());
    const a = h('a', { href: url, download: `${m.id}.ics` });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  } catch (e) { toast(e.message, true); }
}

// ---------- boot ----------
render();
