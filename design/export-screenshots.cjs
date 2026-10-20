const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium,packages,executablePath}=require('./browser-runtime.cjs');

async function main() {
  const fragment = await fs.readFile(path.join(__dirname, 'home-ui.fragment.html'), 'utf8');
  const lucide = await fs.readFile(path.join(packages, 'lucide/dist/umd/lucide.min.js'), 'utf8');
  const { palettes } = JSON.parse(await fs.readFile(path.join(__dirname, 'palettes.json'), 'utf8'));
  const output = path.join(__dirname, 'screenshots', 'settings-v1');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({
    executablePath,
    headless: true,
  });
  try {
    for (const {variant, orientation} of palettes.filter(palette => palette.selected).flatMap(variant =>
      ['landscape', 'portrait'].map(orientation => ({variant, orientation})))) {
      const { id: palette, background } = variant;
      const name = `annota-${palette}${orientation === 'portrait' ? '-portrait' : ''}`;
      if (variant.colors.length !== 10 || variant.colors.filter(color => color.reserved).length !== 2 ||
          new Set(variant.colors.map(color => color.hex)).size !== 10) {
        throw new Error(`Palette ${palette} must have eight distinct core colors and two reserves`);
      }
      const width = orientation === 'landscape' ? 3408 : 2272;
      const height = orientation === 'landscape' ? 2272 : 3408;
      const page = await browser.newPage({
        viewport: { width: Math.ceil(width), height: Math.ceil(height) },
        deviceScaleFactor: 1,
        colorScheme: 'light',
        reducedMotion: 'reduce',
      });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      // The design is rendered entirely in memory, with embedded fonts and icons.
      await page.route('**/*', route => route.abort());
      const state = JSON.stringify({ privateContent: { version: 'o3-home-v1', palette } });
      await page.setContent(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;background:${background}}#o3-home-concept{width:${width}px}#o3-home-concept .o3-frame-portrait{max-width:none!important}</style></head><body><script>${lucide}</script><script>window.openai={widgetState:${state},setWidgetState:async value=>{window.__savedState=structuredClone(value)}};</script>${fragment}</body></html>`);
      await page.evaluate(async orientation => {
        document.querySelectorAll('[data-variant]').forEach(section => {
          section.hidden = section.querySelector('[data-view]').dataset.view !== orientation;
        });
        await document.fonts.ready;
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      }, orientation);
      const checks = await page.evaluate(orientation => {
        const app = document.querySelector(`[data-view="${orientation}"]`);
        const main = app.querySelector('.o3-main');
        const scale = app.getBoundingClientRect().width / app.offsetWidth;
        // MiSans Latin and Chinese glyphs have different descenders.
        // Compare the visible ink bottoms instead of the line boxes.
        const ctx = document.createElement('canvas').getContext('2d');
        const inkBottom = selector => {
          const element = app.querySelector(selector);
          const css = getComputedStyle(element);
          ctx.font = `${css.fontWeight} ${css.fontSize} ${css.fontFamily}`;
          const descent = ctx.measureText(element.textContent).actualBoundingBoxDescent;
          const probe = document.createElement('span');
          probe.style.cssText = 'display:inline-block;width:0;height:0;padding:0;margin:0';
          element.append(probe);
          const bottom = probe.getBoundingClientRect().top + descent * scale;
          probe.remove();
          return bottom;
        };
        const lastIcon = app.querySelector('[data-action="language"] svg').getBoundingClientRect();
        const content = app.querySelector('.o3-create').getBoundingClientRect();
        return {
          controls: app.querySelectorAll('[data-action]').length - 1,
          icons: app.querySelectorAll('svg').length,
          notes: app.querySelectorAll('.o3-doc').length,
          font: getComputedStyle(app).fontFamily,
          fontsLoaded: document.fonts.status,
          brand: app.querySelector('.o3-brand').innerText,
          palette: document.querySelector('#o3-home-concept').dataset.palette,
          sideColor: getComputedStyle(app.querySelector('.o3-side')).backgroundColor,
          utilities: Array.from(app.querySelectorAll('.o3-utilities button'), button => ({
            action: button.dataset.action,
            text: button.textContent.trim(),
            label: button.getAttribute('aria-label'),
            width: button.offsetWidth,
            height: button.offsetHeight,
          })),
          filterIcon: app.querySelector('[data-action="filter"] svg')?.getAttribute('data-lucide'),
          dates: Array.from(app.querySelectorAll('.o3-doc-date'), date => ({
            value: date.getAttribute('datetime'), text: date.textContent,
            fits: date.scrollWidth <= date.clientWidth,
          })),
          topPadding: getComputedStyle(main).paddingTop,
          sideTopPadding: getComputedStyle(app.querySelector('.o3-side')).paddingTop,
          utilityGap: getComputedStyle(app.querySelector('.o3-utilities')).gap,
          utilityBackground: getComputedStyle(app.querySelector('.o3-utilities')).backgroundColor,
          bottomNavGap: getComputedStyle(app.querySelector('.o3-nav-bottom')).gap,
          sidebarOrder: Array.from(app.querySelectorAll('.o3-nav-primary button'), button => button.dataset.action),
          titleBottomDelta: Math.abs(inkBottom('.o3-brand strong') - inkBottom('h1')) / scale,
          rightEdgeDelta: Math.abs(lastIcon.right - content.right) / scale,
          contentHeight: main.scrollHeight,
          viewportHeight: main.clientHeight,
        };
      }, orientation);
      if (errors.length) throw new Error(errors.join('\n'));
      if (checks.icons < 13 || checks.notes !== 4 || checks.fontsLoaded !== 'loaded') {
        throw new Error(`Incomplete render: ${JSON.stringify(checks)}`);
      }
      if (checks.brand !== 'Annota' || checks.palette !== palette ||
          checks.controls !== 11 ||
          checks.utilities.length !== 3 ||
          checks.utilities.some(button => button.text || !button.label || button.width < 60 || button.height < 60)) {
        throw new Error(`Incorrect homepage: ${JSON.stringify(checks)}`);
      }
      if (checks.utilities.map(button => button.action).join(',') !== 'filter,trash,language' ||
          checks.filterIcon !== 'funnel' || checks.utilityGap !== '0px' || checks.bottomNavGap !== '0px' ||
          checks.utilityBackground !== 'rgba(0, 0, 0, 0)' ||
          checks.sidebarOrder.join(',') !== 'search,home,favorites' ||
          checks.titleBottomDelta > 1 || checks.rightEdgeDelta > 1 ||
          checks.sideTopPadding !== '48px' ||
          checks.contentHeight > checks.viewportHeight ||
          checks.dates.some(date => !date.fits || !/^\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}$/.test(date.text))) {
        throw new Error(`Layout revision did not fit: ${JSON.stringify(checks)}`);
      }
      const colorMismatches = await page.evaluate(colors => {
        const css = getComputedStyle(document.querySelector('#o3-home-concept'));
        return colors.filter(color => css.getPropertyValue(color.token).trim().toUpperCase() !== color.hex);
      }, variant.colors);
      if (colorMismatches.length) throw new Error(`Palette does not match UI: ${JSON.stringify(colorMismatches)}`);
      const file = path.join(output, `${name}.png`);
      await page.screenshot({
        path: file,
        clip: { x: 0, y: 0, width, height },
        animations: 'disabled',
      });
      const app = page.locator(`[data-view="${orientation}"]`);
      await app.locator('[data-action="settings"]').click();
      const settingsPages = palette === 'cobalt-sage' ? ['appearance', 'stylus', 'gestures', 'about'] : ['appearance'];
      for (const category of settingsPages) {
        await app.locator(`[data-settings-tab="${category}"]`).click();
        const settingsChecks = await page.evaluate(({ orientation, category }) => {
          const app = document.querySelector(`[data-view="${orientation}"]`);
          const panel = app.querySelector(`[data-settings-panel="${category}"]`);
          const main = app.querySelector('.o3-main');
          return {
            tab: app.querySelector('[data-settings-tab][aria-pressed="true"]').dataset.settingsTab,
            panelVisible: !panel.hidden,
            sidebarSelected: app.querySelector('[data-action="settings"]').getAttribute('aria-current'),
            themeCount: app.querySelectorAll('[data-theme]').length,
            selectedThemes: app.querySelectorAll('[data-theme][aria-pressed="true"]').length,
            previewTooSmall: category === 'appearance' && [...panel.querySelectorAll('.o3-theme-preview')]
              .some(el => el.offsetWidth < 150 || el.offsetHeight < 100),
            tooSmall: [...app.querySelectorAll('.o3-settings-content button,.o3-settings-content input,.o3-settings-content select')]
              .filter(el => el.getClientRects().length && (el.offsetWidth < 60 || el.offsetHeight < 60))
              .map(el => el.outerHTML.slice(0, 160)),
            overflow: main.scrollHeight > main.clientHeight || main.scrollWidth > main.clientWidth,
            bounds: { content: main.scrollHeight, viewport: main.clientHeight, width: main.scrollWidth, availableWidth: main.clientWidth,
              grid: app.querySelector('.o3-theme-grid').offsetHeight, preview: app.querySelector('.o3-theme-preview').offsetHeight },
          };
        }, { orientation, category });
        if (settingsChecks.tab !== category || !settingsChecks.panelVisible ||
            settingsChecks.sidebarSelected !== 'page' || settingsChecks.themeCount !== 4 ||
            settingsChecks.selectedThemes !== 1 || settingsChecks.previewTooSmall ||
            settingsChecks.tooSmall.length || settingsChecks.overflow) {
          throw new Error(`Settings layout: ${JSON.stringify({ orientation, category, ...settingsChecks })}`);
        }
        await page.screenshot({
          path: path.join(output, `${name}-settings-${category}.png`),
          clip: { x: 0, y: 0, width, height }, animations: 'disabled',
        });
      }
      if (palette === 'cobalt-sage') {
        await app.locator('[data-settings-tab="appearance"]').click();
        for (const theme of palettes.filter(p => p.selected)) {
          await app.locator(`[data-theme="${theme.id}"]`).click();
          const current = await page.evaluate(() => ({
            palette: document.querySelector('#o3-home-concept').dataset.palette,
            saved: window.__savedState.privateContent.palette,
          }));
          if (current.palette !== theme.id || current.saved !== theme.id) throw new Error('Theme switching failed');
        }
        await app.locator(`[data-theme="${palette}"]`).click();
        for (const size of [60, 68, 76]) {
          await app.locator('[data-settings-tab="appearance"]').click();
          await app.locator(`[data-size="${size}"]`).click();
          for (const category of settingsPages) {
            await app.locator(`[data-settings-tab="${category}"]`).click();
            const overflow = await app.locator('.o3-main').evaluate(el => el.scrollHeight > el.clientHeight || el.scrollWidth > el.clientWidth);
            if (overflow) throw new Error(`Settings overflow: ${orientation}, ${size}, ${category}`);
          }
        }
        await app.locator('[data-action="language"]').click();
        for (const category of settingsPages) {
          await app.locator(`[data-settings-tab="${category}"]`).click();
          const overflow = await app.locator('.o3-main').evaluate(el => el.scrollHeight > el.clientHeight || el.scrollWidth > el.clientWidth);
          if (overflow) throw new Error(`English settings overflow: ${orientation}, ${category}`);
        }
        await app.locator('[data-action="language"]').click();
        await app.locator('[data-settings-tab="appearance"]').click();
        await app.locator('[data-size="68"]').click();
        await app.locator('[data-settings-tab="stylus"]').click();
        await app.locator('[data-preference="haptics"]').uncheck();
        await app.locator('[data-preference="doubleTap"]').selectOption('previous-tool');
        await app.locator('[data-settings-tab="gestures"]').click();
        await app.locator('[data-preference="twoFinger"]').selectOption('toolbar');
        await app.locator('[data-preference="threeFinger"]').selectOption('none');
        const saved = await page.evaluate(() => structuredClone(window.__savedState));
        if (JSON.stringify(saved.privateContent.preferences) !== JSON.stringify({
          haptics: false, doubleTap: 'previous-tool', twoFinger: 'toolbar', threeFinger: 'none',
        })) throw new Error('Preference state did not persist');
        await page.evaluate(saved => {
          window.dispatchEvent(new CustomEvent('openai:set_globals', { detail: { globals: { widgetState: saved } } }));
        }, saved);
        if (await app.locator('[data-preference="twoFinger"]').inputValue() !== 'toolbar' ||
            await app.locator('[data-preference="threeFinger"]').inputValue() !== 'none') {
          throw new Error('Preference restore failed');
        }
        await app.locator('[data-settings-tab="stylus"]').click();
        if (await app.locator('[data-preference="haptics"]').isChecked() ||
            await app.locator('[data-preference="doubleTap"]').inputValue() !== 'previous-tool') {
          throw new Error('Stylus restore failed');
        }
      }
      await app.locator('[data-action="home"]').click();
      if (errors.length) throw new Error(errors.join('\n'));
      if (orientation === 'portrait') {
        console.log(JSON.stringify({ file, orientation, ...checks }));
        await page.close();
        continue;
      }
      await page.addStyleTag({ content: `
        .palette-sheet{position:absolute;top:2272px;left:0;width:3408px;height:520px;box-sizing:border-box;background:#fff;color:#24272a;padding:46px 80px 40px;border-top:2px solid #e5e7e5;font-family:"MiSans VF",sans-serif}
        .palette-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:34px}
        .palette-heading strong{font-size:48px;font-weight:500;letter-spacing:1px}
        .palette-heading span{font-size:28px;color:#697169;letter-spacing:1px}
        .palette-grid{display:grid;grid-template-columns:repeat(10,minmax(0,1fr));gap:22px}
        .palette-swatch{height:112px;border-radius:9px;box-shadow:inset 0 0 0 2px #00000014}
        .palette-role{font-size:26px;letter-spacing:.7px;margin-top:18px;color:#5a635d}
        .palette-hex{font-size:37px;font-variant-numeric:tabular-nums;margin-top:5px;font-weight:500}
        .palette-note{font-size:24px;color:#727b74;margin-top:5px}
        .palette-reserved .palette-swatch{outline:2px dashed #a7aea8;outline-offset:6px}
        .palette-caption{font-size:25px;color:#6a746d;margin-top:31px}
      ` });
      await page.evaluate(variant => {
        const sheet = document.createElement('section');
        sheet.className = 'palette-sheet';
        sheet.setAttribute('aria-label', `${variant.name} palette`);
        const header = document.createElement('div');
        header.className = 'palette-heading';
        const title = document.createElement('strong');
        title.textContent = `${variant.number}  ${variant.title}`;
        const summary = document.createElement('span');
        summary.textContent = '8 CORE COLORS  +  2 RESERVED';
        header.append(title, summary);
        const grid = document.createElement('div');
        grid.className = 'palette-grid';
        for (const color of variant.colors) {
          const item = document.createElement('div');
          item.className = color.reserved ? 'palette-reserved' : '';
          const swatch = document.createElement('div');
          swatch.className = 'palette-swatch';
          swatch.style.backgroundColor = color.hex;
          item.append(swatch);
          for (const [className, text] of [
            ['palette-role', color.role],
            ['palette-hex', color.hex],
            ['palette-note', color.reserved ? (color.role === 'RESERVE A' ? 'Annotations' : 'Translation') : 'Home interface'],
          ]) {
            const label = document.createElement('div');
            label.className = className;
            label.textContent = text;
            item.append(label);
          }
          grid.append(item);
        }
        const caption = document.createElement('div');
        caption.className = 'palette-caption';
        caption.textContent = 'Dashed swatches are reserved for future pages and are not used on this home screen.';
        sheet.append(header, grid, caption);
        document.body.append(sheet);
      }, variant);
      await page.evaluate(async () => { await document.fonts.ready; });
      await page.setViewportSize({ width, height: height + 520 });
      const board = path.join(output, `annota-${variant.number}-${palette}-board.png`);
      const boardPixels = await page.screenshot({ path: board, clip: { x: 0, y: 0, width, height: height + 520 }, animations: 'disabled' });
      if (boardPixels.readUInt32BE(16) !== width || boardPixels.readUInt32BE(20) !== height + 520) {
        throw new Error(`Palette strip was cropped for ${palette}`);
      }
      console.log(JSON.stringify({ file, board, orientation, paletteUnchanged: true, coreColors: 8, reservedColors: 2, ...checks }));
      await page.close();
    }
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
