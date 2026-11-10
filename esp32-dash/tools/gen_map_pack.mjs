// gen_map_pack.mjs - builds the topo map pack stored in the dash's own flash memory.
//
//   cd tools/mapgen-deps   (npm install pdfjs-dist@4.10.38 @napi-rs/canvas@0.1.65)
//   node ../gen_map_pack.mjs <folder of AUSTopo GeoPDFs> <output .bin>
//
// Each zoom level is a grid of TILE x TILE tiles, 8-bit colour from one shared 256-colour
// palette, each tile compressed separately with raw deflate. North is up.
//
// File layout, little-endian:
//   header   "HMAP", u16 version, u16 levelCount, i32 centreLatE7, i32 centreLonE7, u16 tileSize, u16 pad
//   palette  256 x u16 RGB565, in display byte order (high byte first)
//   levels   levelCount x { f32 mPerPx, u16 tilesAcross, u16 tilesDown, u32 offsetTableOffset }
//   tables   for each level: (tilesAcross x tilesDown + 1) x u32 file offset. Equal neighbours = empty tile
//   data     compressed tiles
import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import {createRequire} from 'module';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';

const require = createRequire(import.meta.url);
const {createCanvas} = require('@napi-rs/canvas');

const [SHEET_DIR, OUT_FILE] = process.argv.slice(2);
const CENTRE = {lat: -33.899, lon: 151.234};
const METERS_PER_DEG = 111319.491;      // must match NavModel::offsetMeters on the dash
const COS_CENTRE = Math.cos(CENTRE.lat * Math.PI / 180);
const TILE = 128;
const LEVELS = [
    {mPerPx: 25,  halfKm: 12.5, circle: false},
    {mPerPx: 50,  halfKm: 25,   circle: false},
    {mPerPx: 100, halfKm: 200,  circle: true},
    {mPerPx: 250, halfKm: 200,  circle: true},
    {mPerPx: 500, halfKm: 200,  circle: true},
];

// ---- the georeferenced main map frame in a GeoPDF ----
function readFrame(file) {
    const text = fs.readFileSync(file).toString('latin1');
    const re = /\/BBox\s*\[([^\]]+)\]\s*\/Name\s*\(([^)]*)\)\s*\/Measure\s*<<.*?\/GPTS\s*\[([^\]]+)\]/gs;
    let m;
    while ((m = re.exec(text))) {
        const name = m[2].replace(/[^A-Za-z ]/g, '');
        if (!/M\s*a\s*i\s*n\s+M\s*a\s*p/.test(name)) continue;
        const bbox = m[1].trim().split(/\s+/).map(Number);
        const g = m[3].trim().split(/\s+/).map(Number);
        return {bbox, sw: [g[0], g[1]], nw: [g[2], g[3]], ne: [g[4], g[5]], se: [g[6], g[7]]};
    }
    return null;
}

const fwd = (f, u, v) => [
    (1 - u) * (1 - v) * f.sw[0] + (1 - u) * v * f.nw[0] + u * v * f.ne[0] + u * (1 - v) * f.se[0],
    (1 - u) * (1 - v) * f.sw[1] + (1 - u) * v * f.nw[1] + u * v * f.ne[1] + u * (1 - v) * f.se[1],
];

function inv(f, lat, lon, guess) {
    let [u, v] = guess;
    for (let i = 0; i < 4; i++) {
        const [la, lo] = fwd(f, u, v);
        const [la1, lo1] = fwd(f, u + 1e-3, v);
        const [la2, lo2] = fwd(f, u, v + 1e-3);
        const a = (lo1 - lo) / 1e-3, b = (lo2 - lo) / 1e-3, c = (la1 - la) / 1e-3, d = (la2 - la) / 1e-3;
        const det = a * d - b * c, x = lon - lo, y = lat - la;
        u += (d * x - b * y) / det;
        v += (-c * x + a * y) / det;
    }
    return [u, v];
}

const pixelLat = (y, size, mPerPx) => CENTRE.lat + (size / 2 - y - 0.5) * mPerPx / METERS_PER_DEG;
const pixelLon = (x, size, mPerPx) => CENTRE.lon + (x + 0.5 - size / 2) * mPerPx / (METERS_PER_DEG * COS_CENTRE);

// ---- draw one level by sampling every sheet that overlaps it ----
async function renderLevel(level, sheets) {
    const size = Math.round(level.halfKm * 2000 / level.mPerPx);
    const rgb = new Uint8Array(size * size * 3);
    const filled = new Uint8Array(size * size);
    const halfPx = size / 2;

    for (const sheet of sheets) {
        const f = sheet.frame;
        const lats = [f.sw[0], f.nw[0], f.ne[0], f.se[0]], lons = [f.sw[1], f.nw[1], f.ne[1], f.se[1]];
        const x0 = Math.max(0, Math.floor((Math.min(...lons) - CENTRE.lon) * METERS_PER_DEG * COS_CENTRE / level.mPerPx + halfPx));
        const x1 = Math.min(size, Math.ceil((Math.max(...lons) - CENTRE.lon) * METERS_PER_DEG * COS_CENTRE / level.mPerPx + halfPx));
        const y0 = Math.max(0, Math.floor(halfPx - (Math.max(...lats) - CENTRE.lat) * METERS_PER_DEG / level.mPerPx));
        const y1 = Math.min(size, Math.ceil(halfPx - (Math.min(...lats) - CENTRE.lat) * METERS_PER_DEG / level.mPerPx));
        if (x1 <= x0 || y1 <= y0) continue;

        // the part of the page this level needs, in PDF points
        const corners = [[x0, y0], [x1, y0], [x0, y1], [x1, y1]].map(([x, y]) => {
            const [u, v] = inv(f, pixelLat(y, size, level.mPerPx), pixelLon(x, size, level.mPerPx), [0.5, 0.5]);
            return [f.bbox[0] + u * (f.bbox[2] - f.bbox[0]), f.bbox[1] + v * (f.bbox[3] - f.bbox[1])];
        });
        const pad = 20;
        const px0 = Math.max(f.bbox[0], Math.min(...corners.map(c => c[0])) - pad);
        const px1 = Math.min(f.bbox[2], Math.max(...corners.map(c => c[0])) + pad);
        const py0 = Math.max(f.bbox[1], Math.min(...corners.map(c => c[1])) - pad);
        const py1 = Math.min(f.bbox[3], Math.max(...corners.map(c => c[1])) + pad);

        const [a, b] = fwd(f, 0, 0.5), [c, d] = fwd(f, 1, 0.5);
        const mPerPt = Math.hypot((d - b) * 111320 * Math.cos(a * Math.PI / 180), (c - a) * 110574) / (f.bbox[2] - f.bbox[0]);
        const scale = mPerPt / level.mPerPx * 2;     // draw at twice the size, for clean text
        const srcW = Math.ceil((px1 - px0) * scale), srcH = Math.ceil((py1 - py0) * scale);

        const page = await sheet.doc.getPage(1);
        const pageH = page.view[3];
        const offsetX = -px0 * scale, offsetY = -(pageH - py1) * scale;
        const canvas = createCanvas(srcW, srcH);
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, srcW, srcH);
        await page.render({canvasContext: ctx, viewport: page.getViewport({scale, offsetX, offsetY})}).promise;
        const src = ctx.getImageData(0, 0, srcW, srcH).data;

        let guess = [0.5, 0.5];
        for (let y = y0; y < y1; y++) {
            const lat = pixelLat(y, size, level.mPerPx);
            for (let x = x0; x < x1; x++) {
                const o = y * size + x;
                if (filled[o]) continue;
                if (level.circle && (x + 0.5 - halfPx) ** 2 + (y + 0.5 - halfPx) ** 2 > halfPx * halfPx) continue;
                guess = inv(f, lat, pixelLon(x, size, level.mPerPx), guess);
                if (guess[0] < 0 || guess[0] > 1 || guess[1] < 0 || guess[1] > 1) {
                    guess = [Math.min(1, Math.max(0, guess[0])), Math.min(1, Math.max(0, guess[1]))];
                    continue;
                }
                const pdfX = f.bbox[0] + guess[0] * (f.bbox[2] - f.bbox[0]);
                const pdfY = f.bbox[1] + guess[1] * (f.bbox[3] - f.bbox[1]);
                const sx = pdfX * scale + offsetX - 0.5, sy = (pageH - pdfY) * scale + offsetY - 0.5;
                const ix = Math.floor(sx), iy = Math.floor(sy);
                if (ix < 0 || iy < 0 || ix + 1 >= srcW || iy + 1 >= srcH) continue;
                const fx = sx - ix, fy = sy - iy;
                for (let k = 0; k < 3; k++) {
                    const p00 = src[(iy * srcW + ix) * 4 + k], p10 = src[(iy * srcW + ix + 1) * 4 + k];
                    const p01 = src[((iy + 1) * srcW + ix) * 4 + k], p11 = src[((iy + 1) * srcW + ix + 1) * 4 + k];
                    rgb[o * 3 + k] = (1 - fx) * (1 - fy) * p00 + fx * (1 - fy) * p10 + (1 - fx) * fy * p01 + fx * fy * p11;
                }
                filled[o] = 1;
            }
        }
    }
    return {size, rgb, filled};
}

// ---- one 256-colour palette for all levels, by median cut ----
function buildPalette(levels) {
    const hist = new Uint32Array(65536);
    for (const {size, rgb, filled} of levels) {
        for (let i = 0; i < size * size; i++) {
            if (!filled[i]) continue;
            hist[((rgb[i * 3] & 0xF8) << 8) | ((rgb[i * 3 + 1] & 0xFC) << 3) | (rgb[i * 3 + 2] >> 3)]++;
        }
    }
    const colours = [];
    for (let c = 0; c < 65536; c++) {
        if (hist[c]) colours.push({r: (c >> 11) << 3, g: ((c >> 5) & 63) << 2, b: (c & 31) << 3, n: hist[c], c});
    }
    const boxes = [colours];
    while (boxes.length < 256) {
        let best = -1, bestScore = -1, bestAxis = 'r';
        boxes.forEach((box, i) => {
            if (box.length < 2) return;
            for (const axis of ['r', 'g', 'b']) {
                let lo = 255, hi = 0, weight = 0;
                for (const p of box) { lo = Math.min(lo, p[axis]); hi = Math.max(hi, p[axis]); weight += p.n; }
                const score = (hi - lo) * Math.sqrt(weight);
                if (score > bestScore) { bestScore = score; best = i; bestAxis = axis; }
            }
        });
        if (best < 0) break;
        const box = boxes[best].sort((p, q) => p[bestAxis] - q[bestAxis]);
        const total = box.reduce((s, p) => s + p.n, 0);
        let run = 0, cut = 1;
        for (let i = 0; i < box.length - 1; i++) {
            run += box[i].n;
            if (run >= total / 2) { cut = i + 1; break; }
        }
        boxes.splice(best, 1, box.slice(0, cut), box.slice(cut));
    }
    const palette = boxes.map(box => {
        const n = box.reduce((s, p) => s + p.n, 0);
        return [0, 1, 2].map((_, k) => Math.round(box.reduce((s, p) => s + p[['r', 'g', 'b'][k]] * p.n, 0) / n));
    });
    while (palette.length < 256) palette.push([0, 0, 0]);
    const lookup = new Uint8Array(65536);
    boxes.forEach((box, i) => { for (const p of box) lookup[p.c] = i; });
    return {palette, lookup};
}

// ---- main ----
const sheets = [];
for (const name of fs.readdirSync(SHEET_DIR).filter(n => n.endsWith('.pdf'))) {
    const file = path.join(SHEET_DIR, name);
    const frame = readFrame(file);
    if (!frame) { console.log('no map frame found in', name); process.exit(1); }
    const doc = await pdfjs.getDocument({
        data: new Uint8Array(fs.readFileSync(file)), useSystemFonts: true, verbosity: 0,
        standardFontDataUrl: path.resolve('node_modules/pdfjs-dist/standard_fonts') + '/',
        cMapUrl: path.resolve('node_modules/pdfjs-dist/cmaps') + '/', cMapPacked: true,
    }).promise;
    sheets.push({name, frame, doc});
}
// the main sheet for home goes first, so it wins where sheets overlap
sheets.sort((a, b) => (b.name.startsWith('Sydney') ? 1 : 0) - (a.name.startsWith('Sydney') ? 1 : 0));
console.log(sheets.length, 'sheets');

const rendered = [];
for (const level of LEVELS) {
    const t0 = Date.now();
    rendered.push(await renderLevel(level, sheets));
    console.log(`rendered ${level.mPerPx} m/px, ${rendered.at(-1).size} px square, ${((Date.now() - t0) / 1000).toFixed(0)} s`);
}

const {palette, lookup} = buildPalette(rendered);

const chunks = [];
const levelInfo = [];
let dataStart = 16 + 512 + LEVELS.length * 12;
LEVELS.forEach((level, i) => {
    const across = Math.ceil(rendered[i].size / TILE);
    dataStart += (across * across + 1) * 4;
});
let offset = dataStart;

rendered.forEach(({size, rgb, filled}, i) => {
    const across = Math.ceil(size / TILE);
    const table = [];
    const tile = Buffer.alloc(TILE * TILE);
    let bytes = 0, empty = 0;
    for (let ty = 0; ty < across; ty++) {
        for (let tx = 0; tx < across; tx++) {
            table.push(offset);
            let any = false;
            for (let y = 0; y < TILE; y++) {
                for (let x = 0; x < TILE; x++) {
                    const px = tx * TILE + x, py = ty * TILE + y;
                    let idx = 0;
                    if (px < size && py < size && filled[py * size + px]) {
                        const o = (py * size + px) * 3;
                        idx = lookup[((rgb[o] & 0xF8) << 8) | ((rgb[o + 1] & 0xFC) << 3) | (rgb[o + 2] >> 3)];
                        any = true;
                    }
                    tile[y * TILE + x] = idx;
                }
            }
            if (!any) { empty++; continue; }
            const packed = zlib.deflateRawSync(tile, {level: 9});
            chunks.push(packed);
            offset += packed.length;
            bytes += packed.length;
        }
    }
    table.push(offset);
    levelInfo.push({mPerPx: LEVELS[i].mPerPx, across, table});
    console.log(`${LEVELS[i].mPerPx} m/px: ${across}x${across} tiles, ${empty} empty, ${(bytes / 1e6).toFixed(2)} MB compressed`);
});

const header = Buffer.alloc(16);
header.write('HMAP', 0, 'latin1');
header.writeUInt16LE(1, 4);
header.writeUInt16LE(LEVELS.length, 6);
header.writeInt32LE(Math.round(CENTRE.lat * 1e7), 8);
header.writeInt32LE(Math.round(CENTRE.lon * 1e7), 12);
const tileInfo = Buffer.alloc(4);
tileInfo.writeUInt16LE(TILE, 0);

const pal = Buffer.alloc(512);
palette.forEach(([r, g, b], i) => {
    const c = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
    pal[i * 2] = c >> 8;
    pal[i * 2 + 1] = c & 255;
});

const levelTable = Buffer.alloc(LEVELS.length * 12);
let tableOffset = 16 + 4 + 512 + LEVELS.length * 12;
levelInfo.forEach((l, i) => {
    levelTable.writeFloatLE(l.mPerPx, i * 12);
    levelTable.writeUInt16LE(l.across, i * 12 + 4);
    levelTable.writeUInt16LE(l.across, i * 12 + 6);
    levelTable.writeUInt32LE(tableOffset, i * 12 + 8);
    tableOffset += l.table.length * 4;
});
// everything after the header moved 4 bytes for tileInfo
const tables = levelInfo.map(l => {
    const t = Buffer.alloc(l.table.length * 4);
    l.table.forEach((v, i) => t.writeUInt32LE(v + 4, i * 4));
    return t;
});

const out = Buffer.concat([header, tileInfo, pal, levelTable, ...tables, ...chunks]);
fs.writeFileSync(OUT_FILE, out);
console.log(`\nmap pack ${(out.length / 1e6).toFixed(2)} MB`);

// a preview of how the 256 colours look
{
    const {size, rgb, filled} = rendered[0];
    const w = 320, h = 170, x0 = size / 2 - w / 2, y0 = size / 2 - h / 2;
    const canvas = createCanvas(w, h);
    const ctx = canvas.getContext('2d');
    const img = ctx.createImageData(w, h);
    for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
            const o = (y + y0) * size + x + x0;
            const c = filled[o] ? palette[lookup[((rgb[o * 3] & 0xF8) << 8) | ((rgb[o * 3 + 1] & 0xFC) << 3) | (rgb[o * 3 + 2] >> 3)]] : [0, 0, 0];
            const d = (y * w + x) * 4;
            img.data[d] = c[0]; img.data[d + 1] = c[1]; img.data[d + 2] = c[2]; img.data[d + 3] = 255;
        }
    }
    ctx.putImageData(img, 0, 0);
    fs.writeFileSync(OUT_FILE.replace(/\.bin$/, '_preview.png'), canvas.toBuffer('image/png'));
}
process.exit(0);
