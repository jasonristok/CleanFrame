// Test the Fire TV adapter with a synthetic Android bridge. No device access.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../app/device-firetv.js', import.meta.url), 'utf8');
const icons = new URL('../app/assets/icons/', import.meta.url);

function harness(catalog, overrides = {}) {
  const launches = [], timers = [];
  const bridge = {
    apps: () => JSON.stringify(catalog),
    selfId: () => 'com.cleanframe.firetv',
    locale: () => 'de-DE',
    launch: id => { launches.push(id); return catalog.some(a => a.appId === id) ? '' : 'missing'; },
    settings: () => '',
    systemHome: () => '',
    smartHome: () => '',
    airplayState: () => JSON.stringify({ enabled: true, running: true, name: 'Wohnzimmer', systemName: 'Fire TV', requireCode: false, code: null, session: null }),
    airplaySetEnabled: () => '',
    airplaySetName: () => '',
    airplaySetCode: () => '',
    airplayShow: () => 'missing',
    airplayStop: () => '',
    ...overrides
  };
  const window = { CleanFrameAndroid: bridge, FrameDevice: { platform: 'tizen' } };
  vm.runInNewContext(source, { window, setTimeout: callback => timers.push(callback), JSON });
  const flush = () => { while (timers.length) timers.shift()(); };
  return { device: window.FrameDevice, launches, flush };
}
const CATALOG = [
  { appId: 'com.cleanframe.firetv', name: 'CleanFrame', icon: '/appicon/com.cleanframe.firetv.png' },
  { appId: 'com.amazon.tv.launcher', name: 'Home', icon: '' },
  { appId: 'com.netflix.ninja', name: 'Netflix', icon: '/appicon/com.netflix.ninja.png' },
  { appId: 'de.swr.avp.ard.tv', name: 'ARD Mediathek', icon: '/appicon/de.swr.avp.ard.tv.png' },
  { appId: 'com.zdf.android.mediathek', name: 'ZDFmediathek', icon: '/appicon/com.zdf.android.mediathek.png' },
  { appId: 'de.prosiebensat1digital.seventv', name: 'Joyn', icon: '' },
  { appId: 'de.cbc.rtlplus', name: 'RTL+', icon: '' },
  { appId: 'com.amazon.firetv.youtube', name: 'YouTube', icon: '' },
  { appId: 'com.amazon.firetv.youtube.kids', name: 'YouTube Kids', icon: '' },
  { appId: 'org.videolan.vlc', name: '  VLC   for Fire  ', icon: '' }
];

test('the adapter stays inactive without the Android bridge', () => {
  const window = { FrameDevice: { platform: 'tizen' } };
  vm.runInNewContext(source, { window, setTimeout, JSON });
  assert.equal(window.FrameDevice.platform, 'tizen');
});

test('installed apps hide CleanFrame and Fire OS screens, and recognise German services', () => {
  const h = harness(CATALOG);
  let result;
  h.device.apps((error, apps) => { assert.equal(error, null); result = apps; });
  h.flush();
  const ids = result.map(a => a.appId);
  assert.ok(!ids.includes('com.cleanframe.firetv'));
  assert.ok(!ids.includes('com.amazon.tv.launcher'));
  assert.equal(result.find(a => a.name === 'ARD Mediathek').brand, 'ardmediathek');
  assert.equal(result.find(a => a.name === 'ARD Mediathek').mark, false);
  assert.equal(result.find(a => a.name === 'ZDFmediathek').brand, 'zdf');
  assert.equal(result.find(a => a.name === 'RTL+').mark, true);
  assert.equal(result.find(a => a.name === 'YouTube Kids').brand, '');
  assert.equal(result.find(a => a.appId === 'org.videolan.vlc').name, 'VLC for Fire');
});

test('first-run Home follows the preferred order and keeps real package names', () => {
  const h = harness(CATALOG);
  const primary = h.device.primaryApps();
  assert.deepEqual([...primary.map(a => a.id)], ['netflix', 'youtube', 'ardmediathek', 'zdf', 'joyn', 'rtlplus']);
  assert.equal(primary.find(a => a.id === 'joyn').appId, 'de.prosiebensat1digital.seventv');
  assert.equal(primary.find(a => a.id === 'ardmediathek').icon, '/appicon/de.swr.avp.ard.tv.png');
});

test('launch reports uninstalled apps and bridge failures as messages', () => {
  const h = harness(CATALOG), results = [];
  h.device.launchApp('com.netflix.ninja', error => results.push(error));
  h.device.launchApp('com.example.gone', error => results.push(error));
  h.flush();
  assert.deepEqual([...h.launches], ['com.netflix.ninja', 'com.example.gone']);
  assert.equal(results[0], null);
  assert.match(results[1].message, /no longer installed/);
  const broken = harness(CATALOG, { launch: () => { throw Error('bridge gone'); } });
  broken.device.launchApp('com.netflix.ninja', error => results.push(error));
  broken.flush();
  assert.match(results[2].message, /could not open/);
});

test('an unreadable catalogue becomes a visible error and an empty first-run Home', () => {
  const h = harness([], { apps: () => 'not json' });
  let failure;
  h.device.apps(error => { failure = error; });
  h.flush();
  assert.match(failure.message, /Could not read installed apps/);
  assert.equal(h.device.primaryApps().length, 0);
});

test('Fire TV hides Art Mode and every monochrome brand mark exists', () => {
  const h = harness(CATALOG);
  assert.equal(h.device.features.art, false);
  for (const brand of h.device.markBrands) assert.ok(existsSync(new URL(brand + '.svg', icons)), 'Missing icon: ' + brand);
});

test('Smart Home opens the dashboard or returns the voice hint', () => {
  const results = [];
  const found = harness(CATALOG, { smartHome: () => '' });
  found.device.smartHome(error => results.push(error));
  const missing = harness(CATALOG, { smartHome: () => 'missing' });
  missing.device.smartHome(error => results.push(error));
  found.flush(); missing.flush();
  assert.equal(results[0], null);
  assert.match(results[1].message, /Alexa, show my smart home/);
});

test('own photos: list parsing, argument counts and failures', () => {
  const calls = [];
  const h = harness(CATALOG, {
    wallpapers: () => JSON.stringify([
      { id: 'u0123456789ab', state: 'ready', meta: '{"number":2,"tone":"light"}' },
      { id: 'u0123456789ac', state: 'pending' },
      { id: 'u0123456789ad', state: 'ready', meta: 'not json' },
      { id: '../../evil', state: 'ready', meta: '{}' }
    ]),
    saveWallpaperFile: (...args) => { calls.push(['save', args.length]); return ''; },
    commitWallpaper: (...args) => { calls.push(['commit', args.length, args[1]]); return 'failed'; },
    photoServerStart: () => '{"url":"http://192.168.1.5:8765/?pin=1234","pin":"1234"}'
  });
  const list = h.device.photos.list();
  assert.deepEqual(list.map(p => p.id + ':' + p.state), ['u0123456789ab:ready', 'u0123456789ac:pending', 'u0123456789ad:broken']);
  assert.equal(list[0].meta.number, 2);
  assert.equal(h.device.photos.save('u0123456789ab', 'thumb', 'AAAA'), true);
  assert.equal(h.device.photos.commit('u0123456789ab', { number: 3 }), false);
  assert.deepEqual(calls, [['save', 3], ['commit', 2, '{"number":3}']]);
  assert.equal(h.device.photos.start().pin, '1234');
  const broken = harness(CATALOG, { photoServerStart: () => { throw Error('no'); } });
  assert.equal(broken.device.photos.start().error, 'failed');
  assert.equal(broken.device.features.photos, false);
  assert.equal(h.device.features.photos, true);
});

test('AirPlay is the built-in receiver: state, settings and session controls', () => {
  const calls = [];
  const h = harness(CATALOG, {
    airplaySetEnabled: on => { calls.push(['enabled', on]); return ''; },
    airplaySetName: name => { calls.push(['name', name]); return ''; },
    airplaySetCode: on => { calls.push(['code', on]); return ''; },
    airplayStop: () => { calls.push(['stop']); return ''; }
  });
  const a = h.device.airplay;
  assert.equal(h.device.features.airplay, true);
  assert.equal(a.state().name, 'Wohnzimmer');
  assert.equal(a.setEnabled(false), true);
  assert.equal(a.setName('Küche'), true);
  assert.equal(a.setCode(true), true);
  assert.equal(a.show(), false); // nothing to show without a mirroring phone
  a.stop();
  assert.equal(JSON.stringify(calls), JSON.stringify([['enabled', 'false'], ['name', 'Küche'], ['code', 'true'], ['stop']]));
  assert.equal(harness(CATALOG, { airplayState: () => 'not json' }).device.airplay.state(), null);
  assert.equal(harness(CATALOG, { airplayState: () => { throw Error('gone'); } }).device.airplay.state(), null);
  assert.equal(harness(CATALOG, { airplayState: undefined }).device.features.airplay, false);
});
