// fetch_austopo.js - downloads the Geoscience Australia AUSTopo 1:250 000 map sheets
// that touch a circle around a point, and writes a manifest with the source details.
//
//   node tools/fetch_austopo.js
//
// Sheets are published as files for download, under CC BY 4.0. Files already
// present are skipped, and one file is fetched at a time.
const fs = require('fs');
const path = require('path');
const https = require('https');

const CENTRE = {lat: -33.899, lon: 151.234, name: 'Centennial Park NSW'};
const RADIUS_KM = 200;
const OUT_DIR = path.join(__dirname, '..', 'Maps', 'AUSTopo-250k');

// The 1:250 000 grid: blocks 4 deg high and 6 deg wide (SH55 = row H, zone 55),
// each split into 16 sheets, 1 deg high and 1.5 deg wide, numbered west to east, north to south.
function sheetBounds(row, zone, number) {
    const rowIndex = row.charCodeAt(0) - 'A'.charCodeAt(0);
    const blockNorth = -(rowIndex * 4);
    const blockWest = (zone - 31) * 6;
    const n = number - 1;
    const north = blockNorth - Math.floor(n / 4);
    const west = blockWest + (n % 4) * 1.5;
    return {north, south: north - 1, west, east: west + 1.5};
}

function distanceToSheetKm(b) {
    const lat = Math.min(b.north, Math.max(b.south, CENTRE.lat));
    const lon = Math.min(b.east, Math.max(b.west, CENTRE.lon));
    const midLat = (lat + CENTRE.lat) / 2 * Math.PI / 180;
    const dx = (lon - CENTRE.lon) * 111.32 * Math.cos(midLat);
    const dy = (lat - CENTRE.lat) * 110.57;
    return Math.hypot(dx, dy);
}

function sheetsInCircle() {
    const out = [];
    for (const row of ['H', 'I']) {
        for (const zone of [55, 56]) {
            for (let number = 1; number <= 16; number++) {
                const b = sheetBounds(row, zone, number);
                const km = distanceToSheetKm(b);
                if (km <= RADIUS_KM) {
                    out.push({code: `S${row}${zone}-${String(number).padStart(2, '0')}`, bounds: b, km});
                }
            }
        }
    }
    return out;
}

function request(url, body) {
    return new Promise((resolve, reject) => {
        const u = new URL(url);
        const req = https.request({
            hostname: u.hostname, path: u.pathname + u.search, method: body ? 'POST' : 'GET',
            headers: {'User-Agent': 'husky701-map-fetch', 'Accept': 'application/json', 'Content-Type': 'application/json'},
        }, res => {
            let data = '';
            res.on('data', d => { data += d; });
            res.on('end', () => resolve({status: res.statusCode, data}));
        });
        req.on('error', reject);
        if (body) req.write(JSON.stringify(body));
        req.end();
    });
}

async function findSheet(code) {
    const res = await request('https://ecat.ga.gov.au/geonetwork/srv/api/search/records/_search', {
        size: 10,
        query: {query_string: {query: `"${code}" AUSTopo`}},
        _source: ['resourceTitleObject', 'link'],
    });
    if (res.status !== 200) throw new Error(`catalogue HTTP ${res.status}`);
    for (const hit of JSON.parse(res.data).hits.hits) {
        const title = (hit._source.resourceTitleObject && hit._source.resourceTitleObject.default) || '';
        if (!title.includes(code) || !title.includes('AUSTopo')) continue;
        const pdf = (hit._source.link || []).find(l => {
            const u = (l.urlObject && l.urlObject.default) || '';
            return /\.pdf$/i.test(u);
        });
        if (pdf) return {title, url: pdf.urlObject.default, record: hit._id};
    }
    return null;
}

function download(url, file) {
    return new Promise((resolve, reject) => {
        const tmp = file + '.part';
        https.get(url, {headers: {'User-Agent': 'husky701-map-fetch'}}, res => {
            if (res.statusCode !== 200) {
                res.resume();
                reject(new Error(`HTTP ${res.statusCode}`));
                return;
            }
            const out = fs.createWriteStream(tmp);
            res.pipe(out);
            out.on('finish', () => out.close(() => {
                fs.renameSync(tmp, file);
                resolve();
            }));
            out.on('error', reject);
        }).on('error', reject);
    });
}

function isPdf(file) {
    const fd = fs.openSync(file, 'r');
    const head = Buffer.alloc(5);
    fs.readSync(fd, head, 0, 5, 0);
    fs.closeSync(fd);
    return head.toString() === '%PDF-';
}

(async () => {
    fs.mkdirSync(OUT_DIR, {recursive: true});
    const manifest = [];
    let failed = 0;

    for (const sheet of sheetsInCircle()) {
        const label = `${sheet.code} (${sheet.km.toFixed(0)} km away)`;
        let found;
        try {
            found = await findSheet(sheet.code);
        } catch (e) {
            console.log(`${label}: catalogue error: ${e.message}`);
            failed++;
            continue;
        }
        if (!found) {
            console.log(`${label}: no separate sheet (open sea, or part of a larger "Special" sheet)`);
            continue;
        }

        const name = found.title.replace(/ - AUSTopo.*/, '').replace(/[^A-Za-z0-9-]+/g, '_');
        const file = path.join(OUT_DIR, `${name}.pdf`);
        try {
            if (!fs.existsSync(file)) await download(found.url, file);
            if (!isPdf(file)) throw new Error('not a PDF');
            const mb = fs.statSync(file).size / 1e6;
            console.log(`${label}: ${found.title}, ${mb.toFixed(1)} MB`);
            manifest.push({code: sheet.code, title: found.title, file: path.basename(file), megabytes: +mb.toFixed(1),
                           source: found.url, record: found.record});
        } catch (e) {
            console.log(`${label}: download failed: ${e.message}`);
            failed++;
        }
    }

    fs.writeFileSync(path.join(OUT_DIR, 'manifest.json'), JSON.stringify({
        centre: CENTRE, radiusKm: RADIUS_KM, downloaded: new Date().toISOString(),
        licence: 'Creative Commons Attribution 4.0. AUSTopo 1:250 000 digital maps, Geoscience Australia.',
        sheets: manifest,
    }, null, 2));

    const total = manifest.reduce((sum, s) => sum + s.megabytes, 0);
    console.log(`\n${manifest.length} sheets, ${total.toFixed(0)} MB, ${failed} failed`);
    process.exitCode = failed ? 1 : 0;
})();
