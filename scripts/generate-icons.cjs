// Запуск из корня проекта: node scripts/generate-icons.cjs; модуль sharp должен быть доступен Node.js.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '../bundles/io.github.zhumaniezov.codex.edt/icons');
(async () => {
    for (const file of fs.readdirSync(path.join(root, 'source')).filter(file => file.endsWith('.svg'))) {
        const source = fs.readFileSync(path.join(root, 'source', file), 'utf8');
        for (const [theme, color] of [['light', '#376676'], ['dark', '#A5C7D0']]) {
            fs.mkdirSync(path.join(root, theme), {recursive: true});
            const svg = source.replace(/color="#[a-f0-9]{6}"/i, `color="${color}"`);
            for (const size of [16, 24, 32, 48, 64]) {
                await sharp(Buffer.from(svg), {density: 72 * size / 16}).png()
                    .toFile(path.join(root, theme, `${path.basename(file, '.svg')}-${size}.png`));
            }
        }
    }
    fs.copyFileSync(path.join(root, 'light/codex-16.png'), path.join(root, 'codex.png'));
    fs.copyFileSync(path.join(root, 'light/codex-32.png'), path.join(root, 'codex@2x.png'));
})().catch(error => { console.error(error); process.exitCode = 1; });
