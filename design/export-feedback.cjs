const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium,packages,executablePath}=require('./browser-runtime.cjs');

async function main() {
  const fragment = await fs.readFile(path.join(__dirname, 'home-ui.fragment.html'), 'utf8');
  const lucide = await fs.readFile(path.join(packages, 'lucide/dist/umd/lucide.min.js'), 'utf8');
  const { palettes } = JSON.parse(await fs.readFile(path.join(__dirname, 'palettes.json'), 'utf8'));
  const output = path.join(__dirname, 'screenshots', 'feedback-v2');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({
    executablePath,
    headless: true,
  });
  try {
    for (const palette of palettes.filter(p => p.selected)) {
      for (const orientation of ['landscape', 'portrait']) {
        const width = orientation === 'landscape' ? 3408 : 2272;
        const height = orientation === 'landscape' ? 2272 : 3408;
        const page = await browser.newPage({ viewport: { width, height }, colorScheme: 'light', reducedMotion: 'reduce' });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.route('**/*', route => route.abort());
        const saved = { privateContent: { version: 'o3-home-v1', palette: palette.id } };
        await page.setContent(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>html,body{margin:0;padding:0}#o3-home-concept{width:${width}px}#o3-home-concept .o3-frame-portrait{max-width:none!important}</style></head><body><script>${lucide}</script><script>window.openai={widgetState:${JSON.stringify(saved)},setWidgetState:async value=>{window.__savedState=structuredClone(value)}};</script>${fragment}</body></html>`);
        await page.evaluate(async orientation => {
          document.querySelectorAll('[data-variant]').forEach(section => section.hidden = section.querySelector('[data-view]').dataset.view !== orientation);
          await document.fonts.ready;
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        }, orientation);
        const app = page.locator(`[data-view="${orientation}"]`);
        const overlay = app.locator('.o3-overlay');
        const dialog = app.locator('.o3-dialog');
        const open = () => app.locator('[data-action="feedback"]').click();
        const cancel = () => dialog.locator('[data-action="close"]').click();
        const inspect = async () => {
          const result = await dialog.evaluate(el => {
            const app = el.closest('[data-view]');
            const box = el.getBoundingClientRect();
            const frame = app.getBoundingClientRect();
            const header = el.querySelector('.o3-dialog-header');
            const submit = el.querySelector('[data-action="save-feedback"]');
            const contact = el.querySelector('[name="feedback-contact"]');
            const body = el.querySelector('.o3-dialog-body');
            return {
              fields: [...el.querySelectorAll('textarea,input')].map(e => e.name),
              leftControl: header.firstElementChild.textContent,
              submitAtBottom: body.lastElementChild === submit &&
                submit.getBoundingClientRect().top >= contact.getBoundingClientRect().bottom &&
                submit.offsetWidth === body.clientWidth && !header.contains(submit),
              buttonsTooSmall: [...el.querySelectorAll('button')].some(e => e.offsetWidth < 60 || e.offsetHeight < 60),
              fits: box.left >= frame.left && box.right <= frame.right && box.top >= frame.top && box.bottom <= frame.bottom &&
                el.scrollHeight <= el.clientHeight && el.scrollWidth <= el.clientWidth,
              backdropBlocked: app.querySelector('.o3-side').inert && app.querySelector('.o3-main').inert,
              font: getComputedStyle(el).fontFamily,
            };
          });
          if (result.fields.join(',') !== 'feedback,feedback-contact' || !result.submitAtBottom || result.buttonsTooSmall || !result.fits || !result.backdropBlocked) {
            throw new Error(`Invalid feedback dialog: ${JSON.stringify({ orientation, palette: palette.id, ...result })}`);
          }
          return result;
        };
        await open();
        const checks = await inspect();
        if (checks.leftControl !== '取消') throw new Error('Cancel must be at top left');
        const file = path.join(output, `annota-${palette.id}-feedback${orientation === 'portrait' ? '-portrait' : ''}.png`);
        await page.screenshot({ path: file, clip: { x: 0, y: 0, width, height }, animations: 'disabled' });

        // Clicking inside never dismisses; both requested cancellation routes do.
        await dialog.locator('h2').click();
        if (!await overlay.isVisible()) throw new Error('Inside click dismissed feedback');
        await dialog.locator('[name="feedback"]').fill('取消测试');
        await dialog.locator('[name="feedback-contact"]').fill('test@example.com');
        await cancel();
        if (await overlay.isVisible()) throw new Error('Cancel did not dismiss');
        if (!await app.locator('[data-action="feedback"]').evaluate(el => el === document.activeElement)) throw new Error('Focus was not restored');
        await open();
        if (await dialog.locator('[name="feedback"]').inputValue() || await dialog.locator('[name="feedback-contact"]').inputValue()) {
          throw new Error('Cancelled input was saved');
        }
        await overlay.click({ position: { x: 20, y: 20 } });
        if (await overlay.isVisible()) throw new Error('Outside click did not dismiss');
        if (await app.locator('.o3-main').evaluate(el => el.inert)) throw new Error('Background stayed inert');

        if (palette.id === 'cobalt-sage') {
          for (const size of [60, 68, 76]) {
            await page.evaluate(({ saved, size }) => {
              window.dispatchEvent(new CustomEvent('openai:set_globals', { detail: { globals: { widgetState: {
                privateContent: { ...saved.privateContent, buttonSize: size },
              } } } }));
            }, { saved, size });
            await open();
            await inspect();
            await page.keyboard.press('Escape');
            if (await overlay.isVisible()) throw new Error('Escape did not dismiss');
          }
          await app.locator('[data-action="language"]').click();
          await open();
          const english = await inspect();
          if (english.leftControl !== 'Cancel') throw new Error('English label missing');
          await cancel();
          await app.locator('[data-action="language"]').click();
          await open();
          await dialog.locator('[data-action="save-feedback"]').click();
          if (!await dialog.locator('.o3-feedback-error').isVisible() || !await overlay.isVisible()) throw new Error('Empty feedback was accepted');
          await inspect();
          await dialog.locator('[name="feedback"]').fill('希望能调整手写笔工具栏的位置。');
          await dialog.locator('[name="feedback-contact"]').fill('reader@example.com');
          await dialog.locator('[data-action="save-feedback"]').click();
          if (await overlay.isVisible()) throw new Error('Draft action did not finish');
          const draft = await page.evaluate(() => window.__savedState);
          if (draft.privateContent.feedbackContact !== 'reader@example.com' ||
              draft.privateContent.feedback !== '希望能调整手写笔工具栏的位置。' ||
              JSON.stringify(draft.modelContent).includes('reader@example.com')) {
            throw new Error('Draft content was not saved privately');
          }
          if (!(await app.locator('.o3-toast').innerText()).includes('尚未发送')) throw new Error('Preview incorrectly claims delivery');
          await open();
          if (await dialog.locator('[name="feedback-contact"]').inputValue() !== 'reader@example.com') throw new Error('Draft did not reopen');
          await cancel();
          // The shared header still returns to the existing filter dialog.
          await app.locator('[data-action="filter"]').click();
          if (await dialog.locator('h2').innerText() !== '筛选' || await dialog.locator('[data-action="close"]').innerText() !== '关闭') {
            throw new Error('Feedback header leaked into filter');
          }
          await cancel();
        }
        if (errors.length) throw new Error(errors.join('\n'));
        console.log(JSON.stringify({ file, orientation, palette: palette.id, cancellation: 'passed', ...checks }));
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
