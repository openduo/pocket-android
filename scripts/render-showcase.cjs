#!/usr/bin/env node
// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
// Compose documentation pages from unmodified device screenshots using browser layout.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const { chromium } = require(process.env.POCKET_PLAYWRIGHT_MODULE || path.join(root, '.build/showcase-tools/node_modules/playwright'));
const screens = path.join(root, 'docs/screens');
const selections = {
  phone: ['conversation', 'schedule', 'files', 'ambient'],
  e2: ['onboarding', 'conversation', 'ambient'],
  tablet: ['conversation'],
  landscape: ['conversation'],
};
const provenance = {};
for (const [profile, names] of Object.entries(selections)) {
  const source = path.join(root, '.build/showcase', profile);
  provenance[profile] = JSON.parse(fs.readFileSync(path.join(source, 'capture.json'), 'utf8'));
  if (provenance[profile].language !== 'en') throw new Error('Public screenshots must use English');
  const target = path.join(screens, 'raw', profile);
  fs.mkdirSync(target, { recursive: true });
  for (const name of names) fs.copyFileSync(path.join(source, `${name}.png`), path.join(target, `${name}.png`));
}
fs.writeFileSync(path.join(screens, 'provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
function image(profile, name) {
  const bytes = fs.readFileSync(path.join(screens, 'raw', profile, `${name}.png`));
  return `data:image/png;base64,${bytes.toString('base64')}`;
}
const css = `*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif;color:#242421;background:#f5f4ed}.page{padding:32px}.phones{display:grid;grid-template-columns:repeat(4,1fr);gap:24px}.screen{margin:0;text-align:center}.screen img{display:block;width:100%;height:auto;border-radius:22px;border:1px solid #dad8cc;box-shadow:0 8px 22px #0000000b}figcaption{font-size:19px;margin-top:18px;color:#57574f;line-height:1.5}.hardware{display:grid;grid-template-columns:1fr 1fr;gap:24px}.card{background:#eeede5;border:1px solid #deddd3;border-radius:24px;padding:24px;min-height:435px;display:flex;align-items:center;gap:28px}.card img{max-height:360px;max-width:100%;height:auto;width:auto;border-radius:12px}.card.wide{flex-direction:column;align-items:center;justify-content:center;gap:18px}.card.wide img{width:100%;height:auto;max-height:360px;object-fit:contain}.card h2{font-size:26px;line-height:1.25;margin:0 0 12px}.card p{font-size:18px;color:#62625b;line-height:1.6;margin:0}.eyebrow{font-size:14px;letter-spacing:1px;text-transform:uppercase;color:#176e72;margin-bottom:12px}.e2{display:grid;grid-template-columns:repeat(3,1fr);gap:28px;background:#161615;color:#faf9f5}.e2 figcaption{color:#c9c9c1}.e2 img{border-color:#353532}.e2-title{margin:0 0 24px;font-size:27px}.e2-wrap{padding:32px;background:#161615;color:#faf9f5}`;
const app = `<div class="page phones">${['conversation','schedule','files','ambient'].map((name,i)=>`<figure class="screen"><img src="${image('phone',name)}"><figcaption>${['Conversation & tools','Markdown & plans','Files & sharing','Ambient controls'][i]}</figcaption></figure>`).join('')}</div>`;
const devices = `<div class="page hardware">
<div class="card"><img src="${image('e2','conversation')}"><div><div class="eyebrow">Physical device</div><h2>E2</h2><p>Android 8.1 · narrow viewport<br>Full-width chat bubbles<br>Local array build</p></div></div>
<div class="card"><img src="${image('phone','conversation')}"><div><div class="eyebrow">Emulator</div><h2>Phone</h2><p>390 × 840 dp<br>Settings and Ambient in the top bar<br>English interface</p></div></div>
<div class="card wide"><img src="${image('tablet','conversation')}"><div><h2>Tablet</h2><p>1280 × 800 dp · bounded reading column · emulator</p></div></div>
<div class="card wide"><img src="${image('landscape','conversation')}"><div><h2>Landscape display</h2><p>800 × 480 dp · emulator · target speaker hardware unverified</p></div></div>
</div>`;
const e2 = `<div class="e2-wrap"><h2 class="e2-title">E2 · physical-device captures · English UI</h2><div class="e2">${['onboarding','conversation','ambient'].map((name,i)=>`<figure class="screen"><img src="${image('e2',name)}"><figcaption>${['Welcome','Prepared conversation','Ambient controls'][i]}</figcaption></figure>`).join('')}</div></div>`;
(async()=>{
  const browser = await chromium.launch({headless:true, ...(process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {})});
  try {
    for (const [name,html,width] of [['app',app,1480],['devices',devices,1480],['e2',e2,1160]]) {
      const page = await browser.newPage({viewport:{width,height:100},deviceScaleFactor:1});
      await page.setContent(`<html lang="en"><head><meta charset="utf-8"><style>${css}</style></head><body>${html}</body></html>`);
      await page.evaluate(()=>Promise.all([...document.images].map(img=>img.decode())));
      await page.screenshot({path:path.join(screens,`${name}.png`),fullPage:true});
      await page.close();
      console.log(`Rendered docs/screens/${name}.png`);
    }
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1});
