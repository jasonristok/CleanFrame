/* Fire TV adapter. Active only inside the CleanFrame Fire TV app (android/), which exposes
   window.CleanFrameAndroid. In a browser or on Samsung this file does nothing and device.js stays in charge. */
(function () {
  'use strict';
  var bridge = window.CleanFrameAndroid;
  if (!bridge || typeof bridge.apps !== 'function') return;

  /* Preferred order for a first-run Home, then brand matching for All apps. Package names are
     hints; the installed label is the fallback because Fire TV builds of an app can differ.
     mark: a monochrome SVG exists in assets/icons/<id>.svg. Others use the app's own banner. */
  var BRANDS = [
    { id: 'netflix', name: 'Netflix', mark: true, packages: ['com.netflix.ninja'], label: /^netflix\b/i },
    { id: 'primevideo', name: 'Prime Video', mark: true, packages: ['com.amazon.avod', 'com.amazon.firebat'], label: /^(amazon )?prime video\b/i },
    { id: 'youtube', name: 'YouTube', mark: true, packages: ['com.amazon.firetv.youtube', 'com.google.android.youtube.tv'], label: /^youtube( for fire tv)?$/i },
    { id: 'disneyplus', name: 'Disney+', mark: true, packages: ['com.disney.disneyplus'], label: /^disney\s*\+/i },
    { id: 'ardmediathek', name: 'ARD Mediathek', mark: false, packages: [], label: /^ard[\s-]?mediathek/i },
    { id: 'zdf', name: 'ZDFmediathek', mark: true, packages: [], label: /^zdf\s?(mediathek)?$/i },
    { id: 'joyn', name: 'Joyn', mark: false, packages: [], label: /^joyn\b/i },
    { id: 'rtlplus', name: 'RTL+', mark: true, packages: [], label: /^rtl\s*\+/i },
    { id: 'wow', name: 'WOW', mark: false, packages: [], label: /^wow\b/i },
    { id: 'waipu', name: 'waipu.tv', mark: false, packages: ['de.exaring.waipu'], label: /^waipu/i },
    { id: 'magentatv', name: 'MagentaTV', mark: false, packages: [], label: /^magenta\s*tv/i },
    { id: 'dazn', name: 'DAZN', mark: true, packages: ['com.dazn'], label: /^dazn\b/i },
    { id: 'appletv', name: 'Apple TV', mark: true, packages: ['com.apple.atve.amazon.appletv'], label: /^apple tv\b/i },
    { id: 'paramountplus', name: 'Paramount+', mark: true, packages: [], label: /^paramount\s*\+/i },
    { id: 'hbomax', name: 'HBO Max', mark: true, packages: [], label: /^(hbo\s*)?max$/i },
    { id: 'arte', name: 'ARTE', mark: false, packages: [], label: /^arte\b/i },
    { id: 'spotify', name: 'Spotify', mark: true, packages: ['com.spotify.tv.android'], label: /^spotify\b/i },
    { id: 'plex', name: 'Plex', mark: true, packages: ['com.plexapp.android'], label: /^plex$/i },
    { id: 'kodi', name: 'Kodi', mark: true, packages: ['org.xbmc.kodi'], label: /^kodi\b/i },
    { id: 'stremio', name: 'Stremio', mark: true, packages: ['com.stremio.one'], label: /^stremio\b/i },
    { id: 'jellyfin', name: 'Jellyfin', mark: true, packages: ['org.jellyfin.androidtv'], label: /^jellyfin\b/i },
    { id: 'crunchyroll', name: 'Crunchyroll', mark: true, packages: ['com.crunchyroll.crunchyroid'], label: /^crunchyroll\b/i },
    { id: 'twitch', name: 'Twitch', mark: true, packages: [], label: /^twitch\b/i },
    /* IPTV player. Its mark is CleanFrame's own TV glyph (assets/icons/televizo.svg), not the app's logo. */
    { id: 'televizo', name: 'Televizo', mark: true, packages: [], label: /^televizo\b/i }
  ];
  /* Fire OS screens that are not apps a person opens from Home. */
  var HIDDEN = ['com.amazon.tv.launcher', 'com.amazon.tv.settings', 'com.amazon.tv.settings.v2', 'com.android.settings', 'com.android.tv.settings',
    'com.amazon.ftv.screensaver', 'com.amazon.tv.ime', 'com.amazon.device.sale.service', 'com.amazon.tv.notificationcenter',
    'com.amazon.tv.oobe', 'com.amazon.tv.parentalcontrols', 'com.amazon.tv.legal.notices'];

  function call(name, arg) {
    // Android matches Java methods by argument count, so pass exactly as many as the method takes.
    try {
      if (arg === undefined) return bridge[name]();
      if (!Array.isArray(arg)) return bridge[name](arg);
      return arg.length === 2 ? bridge[name](arg[0], arg[1]) : bridge[name](arg[0], arg[1], arg[2]);
    }
    catch (err) { return 'failed'; }
  }
  function selfId() { var id = typeof bridge.selfId === 'function' ? call('selfId') : ''; return id === 'failed' ? '' : id; }
  function brandFor(appId, name) {
    var i;
    for (i = 0; i < BRANDS.length; i++) if (BRANDS[i].packages.indexOf(appId) >= 0) return BRANDS[i];
    for (i = 0; i < BRANDS.length; i++) if (BRANDS[i].label.test(name)) return BRANDS[i];
    return null;
  }
  /* Read the installed catalogue synchronously; the Android side only queries the package manager.
     A short cache lets start-up (first-run Home, then discovery) share one read. */
  var cache = null;
  function installed() {
    var now = Date.now(), list;
    if (cache && now - cache.at < 5000) return cache.list;
    list = readInstalled();
    cache = list ? { at: now, list: list } : null;
    return list;
  }
  function readInstalled() {
    var raw = call('apps'), list, seen = {}, self = selfId(), result = [];
    try { list = JSON.parse(raw); } catch (err) { return null; }
    if (!Array.isArray(list)) return null;
    list.forEach(function (a) {
      var name, brand;
      if (!a || typeof a.appId !== 'string' || typeof a.name !== 'string') return;
      name = a.name.replace(/\s+/g, ' ').trim();
      if (!name || seen[a.appId] || a.appId === self || HIDDEN.indexOf(a.appId) >= 0) return;
      seen[a.appId] = true; brand = brandFor(a.appId, name);
      result.push({ appId: a.appId, name: name, brand: brand ? brand.id : '', mark: !!(brand && brand.mark), icon: typeof a.icon === 'string' ? a.icon : '', rank: brand ? BRANDS.indexOf(brand) : 100 });
    });
    result.sort(function (a, b) { return a.rank - b.rank || a.name.toLowerCase().localeCompare(b.name.toLowerCase()); });
    return result;
  }
  function failure(code) {
    if (code === 'missing') return { message: 'This app is no longer installed.', detail: 'Install it again from the Appstore, or remove it from Home.' };
    return { message: 'Fire TV could not open this.', detail: 'Try again, or press the remote’s Home button to return to CleanFrame.' };
  }
  function finish(done, code, value) { setTimeout(function () { done(code ? failure(code) : null, value); }, 0); }

  function launchApp(id, done) { finish(done, call('launch', String(id))); }
  function launch(brand, done) {
    var apps = installed() || [], i;
    for (i = 0; i < apps.length; i++) if (apps[i].brand === brand) { launchApp(apps[i].appId, done); return; }
    finish(done, 'missing');
  }
  function apps(done) {
    var list = installed();
    setTimeout(function () { if (list) done(null, list); else done({ message: 'Could not read installed apps.' }); }, 0);
  }
  /* First-run Home: installed streaming apps in the order above. Saved choices take over afterwards. */
  function primaryApps() {
    var list = installed() || [], out = [];
    BRANDS.forEach(function (brand) {
      var i;
      for (i = 0; i < list.length; i++) if (list[i].brand === brand.id) {
        out.push({ id: brand.id, name: brand.name, mark: brand.mark, appId: list[i].appId, brand: brand.id, icon: list[i].icon });
        return;
      }
    });
    return out;
  }

  /* Fire TV's own Smart Home dashboard; without a launchable one, explain the voice command. */
  function smartHome(done) {
    var code = call('smartHome');
    setTimeout(function () {
      done(code ? { message: 'Say “Alexa, show my smart home”.', detail: 'Hold the Alexa button on the remote while you speak. A short press on the Alexa button also shows the Smart Home dashboard in its shortcuts.' } : null);
    }, 0);
  }

  /* Built-in AirPlay receiver (android/.../airplay): the Fire TV appears in the iPhone's
     Screen Mirroring list like a smart TV. The page reads its state and changes its settings. */
  var airplay = typeof bridge.airplayState === 'function' ? {
    /* { enabled, running, starting, error, name, systemName, requireCode, code, session: { client, model, mirroring, paused } | null } */
    state: function () { var v = parseJson(call('airplayState'), null); return v && typeof v === 'object' ? v : null; },
    setEnabled: function (on) { return call('airplaySetEnabled', on ? 'true' : 'false') === ''; },
    setName: function (name) { return call('airplaySetName', String(name || '')) === ''; },
    setCode: function (on) { return call('airplaySetCode', on ? 'true' : 'false') === ''; },
    show: function () { return call('airplayShow') === ''; },
    stop: function () { call('airplayStop'); }
  } : null;

  /* Own wallpapers kept by the Android app. Prepared images are written back by app/photos.js. */
  function parseJson(raw, fallback) { try { return JSON.parse(raw); } catch (err) { return fallback; } }
  var photos = {
    /* [{ id, state: 'ready'|'pending', meta: {...} }] */
    list: function () {
      var list = parseJson(call('wallpapers'), []);
      if (!Array.isArray(list)) return [];
      return list.filter(function (p) { return p && typeof p.id === 'string' && /^u[0-9a-f]{12}$/.test(p.id); }).map(function (p) {
        var meta = p.state === 'ready' ? parseJson(p.meta, null) : null;
        return { id: p.id, state: meta || p.state !== 'ready' ? p.state : 'broken', meta: meta && typeof meta === 'object' ? meta : {} };
      });
    },
    /* { url, address, pin } or { error: 'network'|'failed' } */
    start: function () { var value = parseJson(call('photoServerStart'), null); return value && typeof value === 'object' ? value : { error: 'failed' }; },
    stop: function () { call('photoServerStop'); },
    save: function (id, name, base64) { return call('saveWallpaperFile', [id, name, base64]) === ''; },
    commit: function (id, meta) { return call('commitWallpaper', [id, JSON.stringify(meta)]) === ''; },
    remove: function (id) { return call('removeWallpaper', id) === ''; },
    discard: function (id) { call('discardIncoming', id); }
  };

  window.FrameDevice = {
    platform: 'firetv',
    features: { art: false, smarthome: true, airplay: !!airplay, photos: typeof bridge.wallpapers === 'function' },
    defaults: { date: true, weather: true },
    refreshOnStart: true,
    system: { name: 'Fire TV', value: 'Amazon Home and settings', home: 'Amazon Home', homeDescription: 'Open Amazon’s Home screen', settings: 'Fire TV settings', settingsDescription: 'Display, sound, network and apps' },
    markBrands: BRANDS.filter(function (b) { return b.mark; }).map(function (b) { return b.id; }).concat(['applemusic', 'tubi']),
    locale: function () { var value = typeof bridge.locale === 'function' ? call('locale') : ''; return value === 'failed' ? '' : value; },
    primaryApps: primaryApps,
    launch: launch,
    launchApp: launchApp,
    art: function (done) { setTimeout(function () { done({ message: 'Art Mode is not available on Fire TV.' }); }, 0); },
    home: function (done) { finish(done, call('systemHome')); },
    settings: function (done) { finish(done, call('settings')); },
    smartHome: smartHome,
    airplay: airplay,
    photos: photos,
    apps: apps
  };
}());
