/* Own photos. A photo received from the phone is prepared once, right after it arrives, with the
   same recipe as scripts/build-materials.mjs: background, thumbnail, panel and the four registered
   material fields. Nothing here runs while Home is being navigated. */
(function () {
  'use strict';
  var W = 1920, H = 1080, P = 96, LINEAR = [], i, v;
  for (i = 0; i < 256; i++) { v = i / 255; LINEAR.push(v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)); }
  function encode(x) { return 255 * (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055); }
  function canvas(w, h) { var c = document.createElement('canvas'); c.width = w || W; c.height = h || H; return c; }
  function release(c) { if (c) { c.width = 0; c.height = 0; } }
  function jpeg(c, quality) { return c.toDataURL('image/jpeg', quality).split(',')[1]; }
  var FILTER = 'filter' in canvas(1, 1).getContext('2d');

  /* Canvas blur and saturation; older engines without ctx.filter approximate the blur by scaling. */
  function filtered(source, blur, saturation) {
    var out = canvas(source.width, source.height), ctx = out.getContext('2d'), factor, small, sc;
    if (FILTER) {
      ctx.filter = 'blur(' + blur + 'px) saturate(' + saturation + ')';
      ctx.drawImage(source, 0, 0); ctx.filter = 'none';
      return out;
    }
    factor = Math.max(2, Math.round(blur / 2));
    small = canvas(Math.ceil(source.width / factor), Math.ceil(source.height / factor)); sc = small.getContext('2d');
    sc.drawImage(source, 0, 0, small.width, small.height);
    ctx.drawImage(small, 0, 0, out.width, out.height);
    release(small);
    return out;
  }
  /* Highlight soft clipping, as in the build recipe. */
  function softClip(ctx) {
    var pixels = ctx.getImageData(0, 0, W, H), d = pixels.data, k, r, g, b, l, ratio;
    for (k = 0; k < d.length; k += 4) {
      r = LINEAR[d[k]]; g = LINEAR[d[k + 1]]; b = LINEAR[d[k + 2]]; l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      if (l > 0.82) { ratio = (0.82 + 0.4 * (l - 0.82)) / l; d[k] = encode(r * ratio); d[k + 1] = encode(g * ratio); d[k + 2] = encode(b * ratio); }
      d[k + 3] = 255;
    }
    ctx.putImageData(pixels, 0, 0);
  }
  /* Foreground choices from coarse brightness: where text sits on a bright photo, use the mist
     halos and heavier veils the curated gallery uses for its bright pictures. */
  function analyse(photo) {
    var small = canvas(192, 108), ctx = small.getContext('2d'), data, meta;
    ctx.drawImage(photo, 0, 0, 192, 108);
    data = ctx.getImageData(0, 0, 192, 108).data;
    function mean(x0, y0, x1, y1) {
      var sum = 0, n = 0, x, y, k;
      for (y = y0; y < y1; y++) for (x = x0; x < x1; x++) { k = (y * 192 + x) * 4; sum += (0.2126 * data[k] + 0.7152 * data[k + 1] + 0.0722 * data[k + 2]) / 255; n++; }
      return n ? sum / n : 0;
    }
    var top = mean(0, 0, 192, 30), clock = mean(8, 4, 70, 20), tr = mean(120, 4, 184, 17), br = mean(140, 82, 184, 100), left = mean(10, 15, 100, 95), all = mean(0, 0, 192, 108);
    meta = { tone: top > 0.55 ? 'light' : 'dark', ut: { tr: tr > 0.5 ? 'mist' : 'light', br: br > 0.5 ? 'mist' : 'light' },
      frostVeil: all > 0.68 ? 0.30 : all > 0.52 ? 0.20 : 0.12, clockMist: clock > 0.5, listColumn: left > 0.62 ? 3 : left > 0.5 ? 2 : 0,
      brightness: Math.round(all * 100) / 100 };
    release(small);
    return meta;
  }

  /* Runs one heavy step per task so the TV can repaint progress in between. */
  function sequence(steps, progress, done) {
    var index = 0;
    (function next() {
      if (index >= steps.length) { done(null); return; }
      try { progress(index / steps.length); steps[index++](); }
      catch (err) { done(err); return; }
      setTimeout(next, 16);
    }());
  }

  function prepare(src, progress, done) {
    var image = new Image(), files = {}, state = {};
    image.onerror = function () { done(new Error('unreadable')); };
    image.onload = function () {
      if (!image.naturalWidth) { done(new Error('unreadable')); return; }
      sequence([
        function () {
          var photo = canvas(), ctx = photo.getContext('2d'), scale = Math.max(W / image.naturalWidth, H / image.naturalHeight), thumb = canvas(512, 288);
          ctx.drawImage(image, (W - image.naturalWidth * scale) / 2, (H - image.naturalHeight * scale) / 2, image.naturalWidth * scale, image.naturalHeight * scale);
          files.photo = jpeg(photo, 0.9);
          thumb.getContext('2d').drawImage(photo, 0, 0, 512, 288);
          files.thumb = jpeg(thumb, 0.85); release(thumb);
          state.photo = photo; state.meta = analyse(photo);
        },
        function () {
          var padded = canvas(W + 2 * P, H + 2 * P), pad = padded.getContext('2d'), x, y;
          for (y = -1; y <= 1; y++) for (x = -1; x <= 1; x++) {
            pad.save(); pad.translate(P + (x === 1 ? 2 * W : 0), P + (y === 1 ? 2 * H : 0));
            pad.scale(x === 0 ? 1 : -1, y === 0 ? 1 : -1); pad.drawImage(state.photo, 0, 0); pad.restore();
          }
          state.padded = padded;
        },
        field('field-blur', 28, 0.92, 0.82),
        field('field-frost', 42, 0.92, 0.82),
        field('field-clear', 2, 1.06, 0.86),
        function () {
          var base = canvas(), bc = base.getContext('2d'), scrim, soft, sc, pixels, d, mean = [0, 0, 0], count = 0, lumas = [], x, y, k, c, high, tint;
          release(state.padded); state.padded = null;
          bc.drawImage(state.photo, 0, 0);
          scrim = bc.createLinearGradient(0, 560, 0, H);
          scrim.addColorStop(0, 'rgba(4,10,12,0)'); scrim.addColorStop(0.7, 'rgba(4,10,12,.42)'); scrim.addColorStop(1, 'rgba(4,10,12,.52)');
          bc.fillStyle = scrim; bc.fillRect(0, 560, W, 520);
          soft = filtered(base, 14, 1.12); sc = soft.getContext('2d'); release(base);
          pixels = sc.getImageData(0, 0, W, H); d = pixels.data;
          for (y = 676; y < 908; y++) for (x = 96; x < 1824; x++) { k = (y * W + x) * 4; for (c = 0; c < 3; c++) mean[c] += d[k + c]; count++; }
          for (c = 0; c < 3; c++) mean[c] /= count;
          for (k = 0; k < d.length; k += 4) { for (c = 0; c < 3; c++) d[k + c] = d[k + c] * 0.65 + mean[c] * 0.35; d[k + 3] = 255; }
          for (y = 720; y < 908; y += 4) for (x = 112; x < 1808; x += 4) { k = (y * W + x) * 4; lumas.push(0.2126 * d[k] + 0.7152 * d[k + 1] + 0.0722 * d[k + 2]); }
          lumas.sort(function (a, b) { return a - b; });
          high = lumas[Math.floor(lumas.length * 0.95)];
          tint = Math.max(0.28, Math.min(0.6, (high - 94) / (high - 8.87)));
          sc.putImageData(pixels, 0, 0); sc.fillStyle = 'rgba(4,10,12,' + tint + ')'; sc.fillRect(0, 0, W, H);
          files['study-soft'] = jpeg(soft, 0.94); release(soft);
          state.meta.softTint = Math.round(tint * 1000) / 1000;
          release(state.photo); state.photo = null;
        }
      ], progress, function (err) {
        release(state.photo); release(state.padded);
        if (err) done(err); else done(null, { files: files, meta: state.meta });
      });
    };
    function field(suffix, blur, saturation, quality) {
      return function () {
        var blurred = filtered(state.padded, blur, saturation), output = canvas(), out = output.getContext('2d'), panel;
        out.drawImage(blurred, P, P, W, H, 0, 0, W, H); release(blurred);
        if (suffix !== 'field-clear') softClip(out);
        files[suffix] = jpeg(output, quality);
        if (suffix === 'field-frost') { panel = canvas(320, 180); panel.getContext('2d').drawImage(output, 0, 0, 320, 180); files.panel = jpeg(panel, 0.85); release(panel); }
        release(output);
      };
    }
    image.src = src;
  }

  /* QR codes for the phone link; the generator loads only when the Own photos screen needs it. */
  function withQr(done) {
    var script;
    if (window.qrcode) { done(); return; }
    script = document.createElement('script');
    script.src = 'assets/vendor/qrcode.js';
    script.onload = function () { done(); };
    script.onerror = function () { done(); };
    document.head.appendChild(script);
  }
  function qrSvg(text) {
    var q, n, path = '', r, c;
    if (!window.qrcode) return '';
    q = window.qrcode(0, 'M'); q.addData(text); q.make(); n = q.getModuleCount();
    for (r = 0; r < n; r++) for (c = 0; c < n; c++) if (q.isDark(r, c)) path += 'M' + (c + 3) + ' ' + (r + 3) + 'h1v1h-1z';
    return '<svg class="qr" viewBox="0 0 ' + (n + 6) + ' ' + (n + 6) + '" shape-rendering="crispEdges" aria-hidden="true"><rect width="' + (n + 6) + '" height="' + (n + 6) + '" fill="#fff"/><path fill="#111" d="' + path + '"/></svg>';
  }

  window.CleanFramePhotos = { prepare: prepare, withQr: withQr, qrSvg: qrSvg, files: ['photo', 'thumb', 'panel', 'field-blur', 'field-frost', 'field-clear', 'study-soft'] };
}());
