// Fire TV interface checks in Chromium. Mocks only window.CleanFrameAndroid (the Android bridge)
// and the Open-Meteo response; never contacts a device or the weather service.
import { mkdirSync, writeFileSync } from 'node:fs';
import { openPreview, root } from './browser.mjs';

const out = root + '.local/qa/firetv/';
mkdirSync(out, { recursive: true });
const APPS = [
  ['com.netflix.ninja', 'Netflix'], ['com.amazon.avod', 'Prime Video'], ['com.amazon.firetv.youtube', 'YouTube'],
  ['com.disney.disneyplus', 'Disney+'], ['de.swr.avp.ard.tv', 'ARD Mediathek'], ['com.zdf.android.mediathek', 'ZDFmediathek'],
  ['de.prosiebensat1digital.seventv', 'Joyn'], ['de.cbc.rtlplus', 'RTL+'], ['de.sky.wow', 'WOW'], ['de.exaring.waipu', 'waipu.tv'],
  ['com.dazn', 'DAZN'], ['org.videolan.vlc', 'VLC for Fire'], ['com.amazon.venezia', 'Appstore'],
  ['io.github.toolicious.homeonfire', 'Home on Fire'], ['com.amazon.tv.launcher', 'Home'], ['com.cleanframe.firetv', 'CleanFrame']
].map(([appId, name]) => ({ appId, name, icon: '/appicon/' + appId + '.png' }));
const COLOURS = { 'de.swr.avp.ard.tv': '#003b75', 'de.prosiebensat1digital.seventv': '#e6007e', 'de.sky.wow': '#3c146e', 'de.exaring.waipu': '#18181c', 'org.videolan.vlc': '#ff8800', 'com.amazon.venezia': '#232f3e', 'io.github.toolicious.homeonfire': '#3c3c3c' };
const failures = [], checks = [];
function check(name, pass, detail) {
  checks.push({ name, passed: !!pass });
  if (pass) console.log('ok - ' + name); else { failures.push({ name, detail }); console.error('FAIL - ' + name + ' ' + JSON.stringify(detail ?? '').slice(0, 400)); }
}
function banner(appId) {
  const app = APPS.find(a => a.appId === appId), fill = COLOURS[appId] || '#2b2f36';
  return '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180"><rect width="320" height="180" rx="16" fill="' + fill + '"/><text x="160" y="104" font-family="Arial" font-size="40" fill="#fff" text-anchor="middle">' + (app ? app.name.split(' ')[0] : '?') + '</text></svg>';
}
function installBridge(apps) {
  window.__bridge = { launches: [], settings: 0, home: 0, smart: 0, smartResult: '', airShow: 0, airStop: 0, airCalls: [], starts: 0, stops: 0, commits: [], discarded: [] };
  window.__air = { enabled: true, running: true, starting: false, error: '', name: 'Wohnzimmer', systemName: 'Jasons Fire TV', requireCode: false, code: null, session: null };
  window.__walls = {}; window.__files = {};
  window.CleanFrameAndroid = {
    smartHome: () => { window.__bridge.smart++; return window.__bridge.smartResult; },
    airplayState: () => JSON.stringify(window.__air),
    airplaySetEnabled: on => { window.__bridge.airCalls.push('enabled:' + on); window.__air.enabled = on === 'true'; return ''; },
    airplaySetName: name => { window.__bridge.airCalls.push('name:' + name); window.__air.name = name || window.__air.systemName; return ''; },
    airplaySetCode: on => { window.__bridge.airCalls.push('code:' + on); window.__air.requireCode = on === 'true'; return ''; },
    airplayShow: () => { window.__bridge.airShow++; return window.__air.session && window.__air.session.mirroring ? '' : 'missing'; },
    airplayStop: () => { window.__bridge.airStop++; return ''; },
    wallpapers: () => JSON.stringify(Object.keys(window.__walls).map(id => ({ id, state: 'ready', meta: window.__walls[id] }))),
    photoServerStart: () => { window.__bridge.starts++; return JSON.stringify({ url: 'http://192.168.178.20:8765/?pin=4821', address: 'http://192.168.178.20:8765', pin: '4821' }); },
    photoServerStop: () => { window.__bridge.stops++; return ''; },
    saveWallpaperFile: (id, name, base64) => { window.__files[id + '/' + name] = base64; return ''; },
    commitWallpaper: (id, meta) => { window.__walls[id] = meta; window.__bridge.commits.push({ id, meta: JSON.parse(meta) }); return ''; },
    removeWallpaper: id => { delete window.__walls[id]; return ''; },
    discardIncoming: id => { window.__bridge.discarded.push(id); return ''; },
    apps: () => JSON.stringify(apps),
    selfId: () => 'com.cleanframe.firetv',
    locale: () => 'de-DE',
    launch: id => { window.__bridge.launches.push(id); return apps.some(a => a.appId === id) ? '' : 'missing'; },
    settings: () => { window.__bridge.settings++; return ''; },
    systemHome: () => { window.__bridge.home++; return ''; }
  };
}

const preview = await openPreview({ publicFonts: true });
let page, weatherRequests = [];
async function open(viewport = { width: 1920, height: 1080 }, seed) {
  const context = await preview.browser.newContext({ viewport, locale: 'de-DE' });
  page = await context.newPage();
  page.on('pageerror', error => failures.push({ name: 'page error', detail: error.message }));
  await page.clock.setFixedTime(new Date('2026-10-29T14:32:00'));
  await page.route('**/appicon/**', route => route.fulfill({ contentType: 'image/svg+xml', body: banner(decodeURIComponent(route.request().url().split('/appicon/')[1].replace(/\.png$/, ''))) }));
  await page.route('https://api.open-meteo.com/**', route => {
    weatherRequests.push(route.request().url());
    route.fulfill({ contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*' }, body: JSON.stringify({ current: { temperature_2m: 12.4, weather_code: 3, is_day: 1 } }) });
  });
  await page.route('**/userwall/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/userwall/', '');
    if (path.startsWith('incoming/')) return route.fulfill({ path: root + 'app/assets/wallpapers/golden.jpg', contentType: 'image/jpeg' });
    const [id, file] = path.split('/'), base64 = await page.evaluate(key => window.__files[key], id + '/' + file.replace(/\.jpg$/, ''));
    return base64 ? route.fulfill({ contentType: 'image/jpeg', body: Buffer.from(base64, 'base64') }) : route.fulfill({ status: 404, body: '' });
  });
  await page.addInitScript(installBridge, APPS);
  if (seed) await page.addInitScript(value => { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('cleanframe.home.v1', JSON.stringify(value)); sessionStorage.setItem('seeded', '1'); } }, seed);
  await page.goto(preview.baseURL + '/home/');
  await page.waitForFunction(() => window.ShoreQA && ShoreQA.ready() && ShoreQA.state().catalog.loaded, null, { timeout: 15000 });
  await page.waitForFunction(() => !!document.querySelector('.cap-weather'), null, { timeout: 5000 }).catch(() => {});
  await page.waitForLoadState('networkidle');
  return page;
}
const state = () => page.evaluate(() => ShoreQA.state());
const nav = (...keys) => page.evaluate(k => ShoreQA.nav(k), keys);
const remote = (method, arg) => page.evaluate(([m, a]) => window.CleanFrameRemote[m](a), [method, arg]);
const act = action => page.evaluate(a => ShoreQA.activate(a), action);
const text = selector => page.locator(selector).innerText();
const shot = name => page.screenshot({ path: out + name + '.jpg', quality: 88 });
const settle = () => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(r, 250)))));

try {
  await open();
  let s = await state();
  check('First-run Home lists installed services in preferred order', JSON.stringify(s.dock) === JSON.stringify(['netflix', 'primevideo', 'youtube', 'disneyplus', 'ardmediathek', 'zdf', 'joyn', 'rtlplus', 'wow']), s.dock);
  check('Defaults: German, 24-hour clock, date and Frankfurt weather', s.committed.lang === 'auto' && s.committed.clock === '24' && s.committed.date === 'on' && s.committed.place === 'frankfurt', s.committed);
  check('AirPlay sits between Smart Home and Settings; Art Mode is hidden', JSON.stringify(await page.evaluate(() => ShoreQA.actions().filter(a => a.startsWith('util.')))) === JSON.stringify(['util.apps', 'util.smarthome', 'util.airplay', 'util.settings']));
  await act('util.airplay'); await settle();
  let air = await text('.appearance');
  check('AirPlay opens the built-in receiver panel (no app needed)', (await state()).view === 'appearance' && (await state()).settingsGroup === 'airplay' && /Bereit für AirPlay/.test(air) && /Wohnzimmer/.test(air) && /Bildschirmsynchronisierung/.test(air) && /Kontrollzentrum/.test(air), air);
  check('AirPlay panel starts on the switched-on choice', (await state()).focusAction === 'airplay.on', (await state()).focusAction);
  await shot('airplay-panel');
  check('AirPlay rows end above the hint line', await page.evaluate(() => Math.max(...[...document.querySelectorAll('#appearanceRows .setting-choice, #appearanceRows .setting-reason')].map(e => e.getBoundingClientRect().bottom / (document.getElementById('stage').getBoundingClientRect().height / 1080)))) <= 966);
  await nav('right', 'ok'); await settle();
  air = await text('.appearance');
  check('Aus switches the receiver off and greys out name and code', (await page.evaluate(() => window.__bridge.airCalls.join(','))) === 'enabled:false' && /AirPlay ist aus/.test(air)
    && (await page.locator('[data-action="airplay.name"]').getAttribute('aria-disabled')) === 'true', air);
  await nav('left', 'ok', 'down', 'ok'); await settle();
  check('An switches it on again; the name opens a list of names', (await page.evaluate(() => window.__bridge.airCalls.join(','))) === 'enabled:false,enabled:true' && (await state()).settingsGroup === 'airplayname'
    && /Jasons Fire TV/.test(await text('.appearance')) && /Küche/.test(await text('.appearance')), await text('.appearance'));
  await shot('airplay-names');
  await act('airplay.setname.4'); await settle();
  check('Choosing Küche renames the receiver and returns to the AirPlay panel', (await page.evaluate(() => window.__bridge.airCalls.slice(-1)[0])) === 'name:Küche' && (await state()).settingsGroup === 'airplay' && /Wähle „Küche“/.test(await text('.appearance')), await text('.appearance'));
  await act('airplay.code.on'); await settle();
  check('Mit Code is saved', (await page.evaluate(() => window.__air.requireCode)) === true && (await page.locator('[data-action="airplay.code.on"]').getAttribute('aria-checked')) === 'true');
  await page.evaluate(() => { window.__air.code = '4821'; window.CleanFrameRemote.airplayEvent(JSON.stringify(window.__air)); }); await settle();
  air = await text('.appearance');
  check('A code from the iPhone appears in the open panel', /4821/.test(air) && /Code für das iPhone/.test(air), air);
  await shot('airplay-code');
  await page.evaluate(() => { window.__air.code = null; window.__air.session = { client: 'Jasons iPhone', model: 'iPhone15,2', mirroring: true, paused: false }; window.CleanFrameRemote.airplayEvent(JSON.stringify(window.__air)); }); await settle();
  air = await text('.appearance');
  check('A mirroring iPhone shows with Anzeigen and Trennen', /Jasons iPhone spiegelt gerade/.test(air) && /Verbunden: Jasons iPhone/.test(air) && /Anzeigen/.test(air) && /Trennen/.test(air), air);
  await nav('down', 'down', 'down', 'down', 'right'); await settle();
  await shot('airplay-live');
  const airplayFits = await page.evaluate(() => Math.max(...[...document.querySelectorAll('#appearanceRows .setting-choice, #appearanceRows .setting-reason')].map(e => e.getBoundingClientRect().bottom / (document.getElementById('stage').getBoundingClientRect().height / 1080))));
  check('AirPlay rows end above the hint line, also with a phone connected', airplayFits <= 966, airplayFits);
  const airplayLabels = await page.evaluate(() => [...document.querySelectorAll('#appearanceRows .setting-choice')].filter(b => b.scrollWidth > b.clientWidth + 1 || [...b.querySelectorAll('.setting-name')].some(n => n.scrollWidth > n.clientWidth + 1)).map(b => b.textContent));
  check('Every AirPlay label fits its control', airplayLabels.length === 0, airplayLabels);
  await act('airplay.show'); await act('airplay.stop');
  check('Zeigen brings the mirror screen up; Trennen ends the session', (await page.evaluate(() => [window.__bridge.airShow, window.__bridge.airStop].join())) === '1,1');
  await page.evaluate(() => { window.__air.session = null; window.CleanFrameRemote.airplayEvent('not json'); window.CleanFrameRemote.airplayEvent(JSON.stringify(window.__air)); }); await settle();
  check('Ignores a broken event and clears the session row', !/Trennen/.test(await text('.appearance')));
  await remote('press', 'back'); await settle();
  check('Back returns to Home on the AirPlay control', (await state()).view === 'home' && (await state()).focusAction === 'util.airplay', (await state()).focusAction);
  await nav('down');
  await act('util.smarthome'); await page.waitForTimeout(100);
  check('Smart Home opens the Fire TV dashboard', (await page.evaluate(() => window.__bridge.smart)) === 1 && (await state()).view === 'home');
  await page.evaluate(() => { window.__bridge.smartResult = 'missing'; });
  await act('util.smarthome'); await page.waitForTimeout(100);
  check('Without a dashboard it explains the Alexa command', (await state()).view === 'message' && /Alexa, zeig mein Smart Home/.test(await text('#sheet')), await text('#sheet'));
  await shot('smarthome-hint');
  await remote('press', 'back');
  await nav('down');
  check('Back returns to Home with focus on the apps', (await state()).view === 'home' && (await state()).focusAction === 'home.app.netflix', (await state()).focusAction);
  check('Clock uses 24 hours', (await text('.clock')).replace(/\s/g, '') === '14:32', await text('.clock'));
  check('Date reads in German', (await text('.cap-date')) === 'Do., 29. Oktober', await text('.cap-date'));
  check('Weather shows Frankfurt conditions', /12°/.test(await text('.cap-weather')) && /Bedeckt/.test(await text('.cap-weather')) && weatherRequests.some(u => u.includes('latitude=50.1109')), { text: await text('.cap-weather'), weatherRequests });
  check('Catalogue hides CleanFrame and Amazon Home', !s.catalog.ids.includes('com.cleanframe.firetv') && !s.catalog.ids.includes('com.amazon.tv.launcher') && s.catalog.ids.includes('org.videolan.vlc'), s.catalog.ids);
  await settle(); await shot('home-dock-1920');

  await nav('ok');
  check('OK launches the focused app by package name', JSON.stringify(await page.evaluate(() => window.__bridge.launches)) === JSON.stringify(['com.netflix.ninja']));
  await nav('right', 'right', 'right', 'right', 'ok');
  check('Native-banner apps launch too', (await page.evaluate(() => window.__bridge.launches)).includes('de.swr.avp.ard.tv'));
  await settle(); await shot('home-dock-ard-focus-1920');

  await remote('press', 'back');
  check('Back from Android opens the German leave message', (await state()).view === 'message' && /Startseite verlassen\?/.test(await text('#sheet')) && /Amazon-Startseite/.test(await text('#sheet')), await text('#sheet'));
  await shot('leave-message');
  await remote('press', 'back');
  check('Second Back closes it', (await state()).view === 'home');
  await remote('press', 'menu');
  check('Menu opens Settings', (await state()).view === 'settings');
  const groups = await page.locator('.settings-group-name').allTextContents();
  check('Settings overview is German and includes the new group', JSON.stringify(groups) === JSON.stringify(['Hintergrund', 'Layout', 'Schrift', 'Fokus', 'Uhr, Wetter & Sprache', 'Apps auf der Startseite', 'Fire TV']), groups);
  const overflow = await page.evaluate(() => [...document.querySelectorAll('.settings-group')].map(e => e.getBoundingClientRect().bottom / (document.getElementById('stage').getBoundingClientRect().height / 1080)));
  check('Seven settings groups end above the hint line', Math.max(...overflow) <= 966, overflow);
  await settle(); await shot('settings-overview');

  await act('settings.group.info'); await settle();
  check('Clock, weather & language detail opens', (await state()).settingsGroup === 'info');
  await shot('settings-info');
  const fits = await page.evaluate(() => [...document.querySelectorAll('#appearance .setting-name')].map(e => ({ t: e.textContent, over: e.scrollWidth - e.clientWidth, right: e.getBoundingClientRect().right / (document.getElementById('stage').getBoundingClientRect().width / 1920) })));
  check('Every German choice label fits its control', fits.every(f => f.over <= 1 && f.right <= 790), fits);
  await act('choice.clock.12');
  check('12-hour choice applies immediately', (await text('.clock')).replace(/\s/g, '') === '2:32' && (await state()).committed.clock === '12', await text('.clock'));
  await act('choice.clock.24');
  await act('info.weather'); await settle();
  check('Weather places open as their own list', (await state()).settingsGroup === 'weather');
  await shot('settings-weather');
  await act('choice.place.berlin'); await page.waitForTimeout(300);
  check('Choosing Berlin fetches Berlin weather', weatherRequests.some(u => u.includes('latitude=52.5200')) && (await state()).committed.place === 'berlin', weatherRequests);
  await act('choice.place.off'); await settle();
  check('Weather can be switched off', !(await page.locator('.cap-weather').count()) && (await state()).committed.place === 'off');
  await act('choice.place.frankfurt');
  await nav('back');
  check('Back returns from places to the parent group', (await state()).settingsGroup === 'info' && (await state()).focusAction === 'info.weather', await state().then(x => x.focusAction));
  await act('choice.lang.en'); await settle();
  check('English switches the interface', (await page.locator('#appearanceTitle').innerText()) === 'Clock, weather & language');
  await act('choice.lang.de'); await settle();
  check('Deutsch switches back', (await page.locator('#appearanceTitle').innerText()) === 'Uhr, Wetter & Sprache' && (await state()).committed.lang === 'de');

  await remote('home');
  check('Home from Android leaves Settings', (await state()).view === 'home');
  await act('util.apps'); await page.waitForTimeout(300); await settle();
  check('All apps opens in German', (await text('.s-title')) === 'Alle Apps');
  await shot('all-apps');
  await remote('home');

  // Own photos: phone link, preparation with the build recipe, use and removal.
  await act('util.settings'); await act('settings.group.background'); await settle();
  check('Background offers Own photos', (await page.evaluate(() => ShoreQA.actions())).includes('background.photos'));
  await shot('settings-background');
  const backgroundFits = await page.evaluate(() => Math.max(...[...document.querySelectorAll('#appearanceRows .setting-choice, #appearanceRows .setting-reason')].map(e => e.getBoundingClientRect().bottom / (document.getElementById('stage').getBoundingClientRect().height / 1080))));
  check('Background rows end above the hint line', backgroundFits <= 966, backgroundFits);
  await act('background.photos');
  await page.waitForFunction(() => !!document.querySelector('.photo-qr svg path'), null, { timeout: 5000 }).catch(() => {});
  const link = await text('#appearanceStatus');
  check('Own photos starts the phone link with QR code, address and code', (await page.evaluate(() => window.__bridge.starts)) === 1 && /192\.168\.178\.20:8765/.test(link) && /4821/.test(link) && (await page.locator('.photo-qr svg path').count()) === 1, link);
  await settle(); await shot('settings-photos-empty');
  await remote('photoEvent', JSON.stringify({ type: 'visited' }));
  check('The phone visit is shown', /Handy verbunden/.test(await text('#appearanceStatus')));
  const started = Date.now(), id = 'u0123456789ab';
  await remote('photoEvent', JSON.stringify({ type: 'received', id }));
  await page.waitForFunction(() => window.__bridge.commits.length === 1, null, { timeout: 60000 });
  const prepared = await page.evaluate(async id => {
    const names = ['photo', 'thumb', 'panel', 'field-blur', 'field-frost', 'field-clear', 'study-soft'], out = {};
    for (const name of names) {
      const image = new Image(); image.src = 'data:image/jpeg;base64,' + window.__files[id + '/' + name]; await image.decode();
      out[name] = [image.naturalWidth, image.naturalHeight];
    }
    return { sizes: out, meta: window.__bridge.commits[0].meta };
  }, id);
  console.log('   prepared in ' + (Date.now() - started) + ' ms', JSON.stringify(prepared));
  for (const [name, base64] of Object.entries(await page.evaluate(id => Object.fromEntries(Object.entries(window.__files).filter(([k]) => k.startsWith(id + '/')).map(([k, v]) => [k.split('/')[1], v])), id))) writeFileSync(out + 'prepared-' + name + '.jpg', Buffer.from(base64, 'base64'));
  const want = { photo: [1920, 1080], thumb: [512, 288], panel: [320, 180], 'field-blur': [1920, 1080], 'field-frost': [1920, 1080], 'field-clear': [1920, 1080], 'study-soft': [1920, 1080] };
  check('All seven images are prepared at their gallery sizes', JSON.stringify(prepared.sizes) === JSON.stringify(want), prepared.sizes);
  check('Foreground choices are recorded', prepared.meta.number === 1 && ['light', 'dark'].includes(prepared.meta.tone) && prepared.meta.ut && [.12, .2, .3].includes(prepared.meta.frostVeil), prepared.meta);
  await page.waitForFunction(() => ShoreQA.actions().includes('photos.item.u0123456789ab'), null, { timeout: 5000 });
  check('The new photo is listed with its thumbnail', /Eigenes Foto 1/.test(await text('#appearanceRows')) && /Hinzugefügt: Eigenes Foto 1/.test(await text('#appearanceStatus')));
  await settle(); await shot('settings-photos-one');
  await act('photos.item.' + id);
  check('A photo offers Use and Remove', (await state()).view === 'message' && /Als Hintergrund/.test(await text('#sheet')) && /Entfernen/.test(await text('#sheet')));
  check('The phone link stays open behind the menu', (await page.evaluate(() => window.__bridge.stops)) === 0);
  await act('message.use');
  await page.waitForFunction(id => ShoreQA.ready() && ShoreQA.state().committed.wall === id, id, { timeout: 10000 });
  check('Use as background applies the own photo', (await state()).committed.bg === 'photo');
  await nav('back');
  check('Leaving Own photos stops the phone link', (await page.evaluate(() => window.__bridge.stops)) === 1 && (await state()).settingsGroup === 'background');
  await act('background.wallpaper'); await settle();
  check('The wallpaper list ends with the own photo', /Eigenes Foto 1/.test(await text('#appearanceRows')));
  await remote('home'); await settle();
  check('Home shows the own photo and its materials', await page.evaluate(id => document.getElementById('photo').src.includes('/userwall/' + id + '/photo.jpg') && document.getElementById('matImg').src.includes('/userwall/' + id + '/'), id));
  await shot('home-own-photo');
  await page.evaluate(id => { delete window.__walls[id]; }, id);
  await remote('photoEvent', JSON.stringify({ type: 'removed', id }));
  await page.waitForFunction(() => ShoreQA.ready(), null, { timeout: 10000 });
  check('Removing the current photo falls back to a bundled one', (await state()).committed.wall === 'misty' && !(await state()).error);
  await remote('home');
  await page.evaluate(() => ShoreQA.look({ layout: 'list' })); await page.waitForFunction(() => ShoreQA.ready()); await settle();
  await shot('home-list-1920');
  s = await state();
  await page.context().close();

  // Fire TV renders the page at 960 x 540 CSS pixels (2x density); check the same Home there.
  for (const viewport of [{ width: 960, height: 540 }, { width: 1280, height: 720 }]) {
    await open(viewport, s);
    const g = await page.evaluate(() => { const b = document.getElementById('stage').getBoundingClientRect(); return { w: b.width, h: b.height, focus: ShoreQA.where().focusCount }; });
    check('Stage fits ' + viewport.width + 'x' + viewport.height + ' with one focus', Math.abs(g.w - viewport.width) < 1 && Math.abs(g.h - viewport.height) < 1 && g.focus === 1, g);
    await page.evaluate(() => ShoreQA.look({ layout: 'dock' })); await page.waitForFunction(() => ShoreQA.ready()); await settle();
    await shot('home-dock-' + viewport.width);
    await page.context().close();
  }
} catch (error) {
  failures.push({ name: 'exception', detail: error.stack });
  console.error(error);
} finally {
  await preview.close();
}
writeFileSync(out + 'report.json', JSON.stringify({ checks, failures }, null, 2));
console.log(checks.filter(c => c.passed).length + ' Fire TV checks passed; ' + failures.length + ' failure(s). Screenshots: .local/qa/firetv/');
process.exit(failures.length ? 1 : 0);
