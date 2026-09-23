// Run with the bundled sharp module path as the first argument, or with sharp on NODE_PATH.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require(process.argv[2] || 'sharp');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'artwork', 'play');
fs.mkdirSync(output, { recursive: true });

async function render(source, target, width, height) {
  await sharp(path.join(root, 'artwork', source))
    .resize(width, height)
    .flatten({ background: '#10151C' })
    .png()
    .toFile(path.join(output, target));
}

Promise.all([
  sharp(Buffer.from(fs.readFileSync(path.join(root, 'artwork', 'liki-icon.svg'), 'utf8')
    .replace('width="512" height="512" rx="112"', 'width="512" height="512"')))
    .resize(512, 512)
    .ensureAlpha()
    .png()
    .toFile(path.join(output, 'store-icon-512.png')),
  render('play-feature-graphic.svg', 'feature-graphic-1024x500.png', 1024, 500),
]).catch(error => { console.error(error); process.exitCode = 1; });
