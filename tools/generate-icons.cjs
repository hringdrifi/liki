// Run with Node.js and sharp available in NODE_PATH, or pass its module path as the first argument.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require(process.argv[2] || 'sharp');

const root = path.resolve(__dirname, '..');
const res = path.join(root, 'app', 'src', 'main', 'res');
const icon = fs.readFileSync(path.join(root, 'artwork', 'liki-icon.svg'), 'utf8');
const foreground = icon.replace(/<rect width="512" height="512" rx="112" fill="url\(#bg\)"\s*\/>/, '');
if (foreground === icon) throw new Error('Could not separate icon foreground');

const background = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop stop-color="#1F2834"/><stop offset="1" stop-color="#10151C"/>
  </linearGradient></defs>
  <rect width="512" height="512" fill="url(#bg)"/>
</svg>`;

async function save(svg, directory, name, size) {
  const target = path.join(res, directory, `${name}.png`);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  await sharp(Buffer.from(svg), { density: 384 }).resize(size, size).png().toFile(target);
}

(async () => {
  for (const [density, scale] of Object.entries({ mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 })) {
    await save(icon, `mipmap-${density}`, 'ic_launcher', Math.round(48 * scale));
    await save(foreground, `drawable-${density}`, 'ic_launcher_foreground', Math.round(108 * scale));
    await save(background, `drawable-${density}`, 'ic_launcher_background', Math.round(108 * scale));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
