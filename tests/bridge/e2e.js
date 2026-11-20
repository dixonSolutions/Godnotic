'use strict';
const {chromium} = require('playwright');
const {spawn} = require('child_process');
const dgram = require('dgram');
const http = require('http');
const dns = require('dns').promises;
const fs = require('fs');
const os = require('os');
const path = require('path');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const median = a => { a.sort((x,y) => x-y); return (a[24] + a[25]) / 2; };
async function direct(socket, port, text, timeout = 5000) {
  const start = process.hrtime.bigint();
  const reply = new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.off('message', onmessage); reject(Error('direct UDP timeout')); }, timeout);
    function onmessage(data) { clearTimeout(timer); resolve(data); }
    socket.once('message', onmessage);
  });
  socket.send(Buffer.concat([Buffer.alloc(4, 255), Buffer.from(text)]), port, '127.0.0.1');
  const data = await reply;
  if (!data.subarray(4).toString().startsWith('infoResponse')) throw Error('invalid direct getinfo response');
  return Number(process.hrtime.bigint() - start) / 1e6;
}
async function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'godnotic-bridge-e2e-'));
  const port = 26000 + Math.floor(Math.random() * 10000);
  const master = await dns.lookup(process.env.BRIDGE_MASTER || 'dpm4.xonotic.xyz', {family: 4});
  const masterPort = Number(process.env.BRIDGE_MASTER_PORT || 27777);
  const log = fs.openSync(path.join(tmp, 'server.log'), 'w');
  const server = spawn(process.env.BRIDGE_DEDICATED, ['-xonotic', '-basedir', process.env.XONOTIC_BASEDIR,
    '-userdir', path.join(tmp, 'user'), '-port', String(port), '+sv_public', '0', '+map', process.env.BRIDGE_MAP || 'boil'],
    {stdio: ['pipe', log, log]});
  let browser;
  const root = path.resolve(__dirname, '../..');
  const files = {'/': path.join(__dirname, 'e2e.html'), '/web/bridge-channel.mjs': path.join(root, 'web/bridge-channel.mjs'), '/web/bridge-frame.mjs': path.join(root, 'web/bridge-frame.mjs')};
  const web = http.createServer((req, res) => { const file = files[req.url]; if (!file) { res.writeHead(404).end(); return; } res.setHeader('Content-Type', file.endsWith('.mjs') ? 'text/javascript' : 'text/html'); fs.createReadStream(file).pipe(res); });
  await new Promise(resolve => web.listen(0, '127.0.0.1', resolve));
  const socket = dgram.createSocket('udp4');
  try {
    for (let i = 0; ; i++) {
      if (server.exitCode !== null) throw Error('dedicated server exited: ' + server.exitCode);
      try { await direct(socket, port, 'getinfo ready', 300); break; }
      catch (e) { if (i >= 99) throw Error('dedicated server did not become ready'); await sleep(200); }
    }
    browser = await chromium.launch({headless: true, ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {})});
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${web.address().port}/`);
    await page.waitForFunction(() => typeof window.offer === 'function');
    const offer = await page.evaluate(() => window.offer());
    const offerPath = path.join(tmp, 'offer.sdp'), answerPath = path.join(tmp, 'answer.sdp');
    fs.writeFileSync(offerPath, offer);
    server.stdin.write(`net_getstatusfloodblockingtimeout 0; bridge_allow_loopback 1; sv_master1 ${master.address}:${masterPort}; bridge_offer "${offerPath}" "${answerPath}"\n`);
    for (let i = 0; !fs.existsSync(answerPath); i++) {
      if (i >= 150 || server.exitCode !== null) throw Error('bridge answer not produced');
      await sleep(100);
    }
    await page.evaluate(sdp => window.answer(sdp), fs.readFileSync(answerPath, 'utf8'));
    if (!process.env.BRIDGE_SKIP_MASTER) {
      const result = await page.evaluate(([ip, port]) => window.request(ip, port, 'getservers Xonotic 3 empty full\n', 'getserversResponse'), [master.address, masterPort]);
      if (result.bytes < 29 || result.data[22] !== 92 ||
          (result.data[23] === 69 && result.data[24] === 79 && result.data[25] === 84) ||
          !(result.data[27] || result.data[28])) throw Error('master returned empty or malformed server list');
      console.log(`master getservers: PASS (${result.bytes} bytes from ${master.address}:${masterPort})`);
    }
    const bridged = [], directTimes = [];
    for (let i = 0; i < 50; i++) {
      const response = await page.evaluate(([port, i]) => window.request('127.0.0.1', port, 'getinfo bridge' + i, 'infoResponse'), [port, i]);
      bridged.push(response.ms);
      directTimes.push(await direct(socket, port, 'getinfo direct' + i));
    }
    console.log(`local getinfo: PASS (50 replies); median bridge=${median(bridged).toFixed(3)} ms direct UDP=${median(directTimes).toFixed(3)} ms`);
  } catch (e) {
    console.error(`server log: ${path.join(tmp, 'server.log')}`);
    throw e;
  } finally {
    if (browser) await browser.close();
    web.close();
    socket.close();
    server.kill('SIGTERM');
    await new Promise(resolve => { if (server.exitCode !== null || server.signalCode !== null) resolve(); else server.once('exit', resolve); });
    fs.closeSync(log);
  }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
