import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join, resolve, extname } from "node:path";

test("browser workflow: import, pair, review, split, merge, and export", { timeout: 30000 }, async () => {
    const root = resolve("web");
    const server = createServer(async (req, res) => {
        const name = new URL(req.url, "http://localhost").pathname.replace(/^\/web\/?/, "") || "index.html";
        const path = resolve(root, name);
        if (!path.startsWith(root + "/")) { res.writeHead(403).end(); return; }
        try {
            const type = { ".html": "text/html", ".mjs": "text/javascript", ".css": "text/css" }[extname(path)] || "application/octet-stream";
            res.writeHead(200, { "content-type": type }); res.end(await readFile(path));
        } catch { res.writeHead(404).end(); }
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const profile = await mkdtemp(join(tmpdir(), "interlinear-browser-test-"));
    const chrome = spawn(process.env.CHROME || "google-chrome", [
        "--headless", "--no-sandbox", "--disable-gpu", "--remote-debugging-port=0",
        `--user-data-dir=${profile}`, "about:blank"
    ], { stdio: "ignore" });
    let socket;
    try {
        let port;
        for (let i = 0; i < 100; i++) {
            try { port = (await readFile(join(profile, "DevToolsActivePort"), "utf8")).split("\n")[0]; break; }
            catch { await new Promise(resolve => setTimeout(resolve, 50)); }
        }
        assert.ok(port, "Chrome did not start");
        const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
        socket = new WebSocket(targets.find(target => target.type === "page").webSocketDebuggerUrl);
        await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
        let id = 0;
        const pending = new Map();
        socket.onmessage = event => {
            const message = JSON.parse(event.data), item = pending.get(message.id);
            if (!item) return;
            pending.delete(message.id);
            if (message.error) item.reject(new Error(message.error.message)); else item.resolve(message.result);
        };
        const send = (method, params = {}) => new Promise((resolve, reject) => {
            const next = ++id; pending.set(next, { resolve, reject });
            socket.send(JSON.stringify({ id: next, method, params }));
        });
        const evaluate = async expression => {
            const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
            if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
            return result.result.value;
        };
        await send("Page.navigate", { url: `http://127.0.0.1:${server.address().port}/web/` });
        for (let i = 0; i < 100; i++) {
            if (await evaluate("document.readyState === 'complete' && !!document.querySelector('#example')")) break;
            await new Promise(resolve => setTimeout(resolve, 50));
        }
        await evaluate(`(async () => {
            const core = await import('/web/core.mjs');
            const data = new Float32Array(16000);
            for (let i = 2000; i < 5000; i++) data[i] = Math.sin(i / 10) * .5;
            for (let i = 8000; i < 11000; i++) data[i] = Math.sin(i / 6) * .5;
            const transfer = new DataTransfer();
            transfer.items.add(new File([core.wav(data)], 'source.wav', {type:'audio/wav'}));
            const input = document.querySelector('#audio-file'); input.files = transfer.files;
            input.dispatchEvent(new Event('change'));
        })()`);
        for (let i = 0; i < 100; i++) {
            if ((await evaluate("document.querySelector('#status').textContent")).includes("detected 2")) break;
            await new Promise(resolve => setTimeout(resolve, 50));
        }
        assert.match(await evaluate("document.querySelector('#status').textContent"), /detected 2/);
        await evaluate(`document.querySelector('#example').click();
            (async () => {
                const core = await import('/web/core.mjs'), data = new Float32Array(3000);
                for (let i = 0; i < data.length; i++) data[i] = Math.sin((i + 8000) / 6) * .5;
                const transfer = new DataTransfer();
                transfer.items.add(new File([core.wav(data)], 'was.wav', {type:'audio/wav'}));
                const input = document.querySelector('#references'); input.files = transfer.files;
                input.dispatchEvent(new Event('change'));
            })()`);
        for (let i = 0; i < 100; i++) {
            if ((await evaluate("document.querySelector('#status').textContent")).includes("Loaded 1 reference")) break;
            await new Promise(resolve => setTimeout(resolve, 50));
        }
        await evaluate("document.querySelector('#match-references').click()");
        for (let i = 0; i < 100; i++) {
            if ((await evaluate("document.querySelector('#status').textContent")).includes("Suggested")) break;
            await new Promise(resolve => setTimeout(resolve, 50));
        }
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Suggested 1 acoustic matches/);
        await evaluate(`window.confirm = () => true;
            document.querySelector('#example').click();
            document.querySelector('#transcript').value = 'word was';
            document.querySelector('#match-transcript').click()`);
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Paired 1/);
        await evaluate(`{
            const canvas = document.querySelector('#wave'), r = canvas.getBoundingClientRect();
            canvas.dispatchEvent(new PointerEvent('pointerdown', {clientX:r.left + r.width * .2, clientY:r.top + 10}));
            document.querySelectorAll('.card footer button[aria-label="Attach selected slice"]')[1].click();
        }`);
        assert.equal(await evaluate("document.querySelector('#review').open"), true);
        await evaluate(`document.querySelector('#review button[value="cancel"]').click()`);
        await new Promise(resolve => setTimeout(resolve, 50));
        assert.equal(await evaluate("document.querySelectorAll('.card header span')[3].textContent"), "[0]");
        await evaluate(`document.querySelectorAll('.card footer button[aria-label="Attach selected slice"]')[1].click()`);
        assert.equal(await evaluate("document.querySelector('#review').open"), true);
        await evaluate(`document.querySelector('#review button[value="replace"]').click()`);
        await new Promise(resolve => setTimeout(resolve, 50));
        assert.equal(await evaluate("document.querySelectorAll('.card header span')[3].textContent"), "[+1]");
        await evaluate(`document.querySelectorAll('.card footer button[aria-label="Split card"]')[1].click()`);
        assert.equal(await evaluate("document.querySelectorAll('.card').length"), 4);
        await evaluate(`document.querySelectorAll('.card footer button[aria-label="Merge with following card"]')[1].click()`);
        assert.equal(await evaluate("document.querySelectorAll('.card').length"), 3);
        await evaluate(`document.querySelectorAll('.card footer button[aria-label="Move card earlier"]')[1].click()`);
        assert.equal(await evaluate("document.querySelector('.card input').value"), "was");
        await evaluate(`{
            const create = URL.createObjectURL.bind(URL);
            URL.createObjectURL = blob => { window.exported = blob; return create(blob); };
        }`);
        await evaluate("document.querySelector('#export').click()");
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Exported 1 WAV clips/);
        const exported = await evaluate(`(async () => {
            const bytes = new Uint8Array(await window.exported.arrayBuffer()), view = new DataView(bytes.buffer);
            let offset = 0, files = {};
            while (view.getUint32(offset, true) === 0x04034b50) {
                const size = view.getUint32(offset + 18, true), length = view.getUint16(offset + 26, true);
                const extra = view.getUint16(offset + 28, true);
                const name = new TextDecoder().decode(bytes.slice(offset + 30, offset + 30 + length));
                const start = offset + 30 + length + extra;
                files[name] = Array.from(bytes.slice(start, start + size));
                offset = start + size;
            }
            return files;
        })()`);
        assert.deepEqual(Object.keys(exported).sort(), ["01_was.wav", "alignment.json", "source.wav"]);
        const alignment = JSON.parse(new TextDecoder().decode(new Uint8Array(exported["alignment.json"])));
        assert.equal(alignment.cards[0].target_text, "was");
        assert.equal(alignment.unassigned.length, 1);
        await evaluate(`window.confirm = () => true;
            (() => {
                const transfer = new DataTransfer();
                transfer.items.add(new File([window.exportedManifest], 'alignment.json', {type:'application/json'}));
                const input = document.querySelector('#script-file'); input.files = transfer.files;
                input.dispatchEvent(new Event('change'));
            })()`.replace("window.exportedManifest", JSON.stringify(JSON.stringify(alignment))));
        for (let i = 0; i < 100; i++) {
            if ((await evaluate("document.querySelector('#status').textContent")).includes("Loaded 3 cards")) break;
            await new Promise(resolve => setTimeout(resolve, 50));
        }
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Loaded 3 cards/);
        assert.equal(await evaluate("document.querySelector('.card input').value"), "was");
        await evaluate(`document.querySelector('#example').click();
            document.querySelector('#transcript').value = 'λόγος ἦν καί';
            document.querySelector('#match-transcript').click()`);
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Paired 0/);
        await evaluate(`document.querySelector('#transcript').value = 'the word extra';
            document.querySelector('#match-transcript').click()`);
        assert.match(await evaluate("document.querySelector('#status').textContent"), /Paired 1/);
        assert.equal(await evaluate("document.querySelectorAll('.card header span')[1].textContent"), "[multi]");
    } finally {
        socket?.close();
        chrome.kill();
        await new Promise(resolve => chrome.once("exit", resolve));
        await new Promise(resolve => server.close(resolve));
        await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    }
});
