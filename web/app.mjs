import { SAMPLE_RATE, normalizeAudio, detectBlobs, zeroCrossing, renderSlice, wav, validateScript, assignIndexed, pairTranscript, manifest, restoreAlignment, zip } from "./core.mjs";

const $ = id => document.getElementById(id);
const colors = ["#007b89", "#d05b32", "#7863bb", "#a08009", "#ba4775", "#3b8260"];
let samples = null, blobs = [], cards = [], selected = null, cursor = 0, player = null;
let sourceBytes = null, sourceName = "", sourceHash = "";
let recorder = null, chunks = [], drag = null, movingHandle = null;
const context = new AudioContext();

function status(message) { $("status").textContent = message; }
function stop() {
    if (player) { player.stop(); player = null; }
}
function play(data) {
    if (!data?.length) throw new Error("No audio to play.");
    stop();
    const buffer = context.createBuffer(1, data.length, SAMPLE_RATE);
    buffer.copyToChannel(data, 0);
    player = context.createBufferSource();
    player.buffer = buffer;
    player.connect(context.destination);
    const source = player;
    source.onended = () => { if (player === source) player = null; };
    context.resume();
    source.start();
}
function action(fn) {
    return async event => {
        try { await fn(event); } catch (error) { status(error.message || String(error)); }
    };
}
function owner(blob) { return cards.find(card => card.spans.includes(blob)); }
function setSelection(blob) {
    blobs.sort((a, b) => a.start - b.start);
    blobs.forEach((item, index) => { item.spokenIndex = index; });
    selected = blob;
    $("selection").textContent = blob ? `Spoken slice ${blob.spokenIndex + 1}${owner(blob) ? "" : " (unassigned)"}` : "No slice selected";
    $("start").value = blob ? Math.round(blob.start * 1000 / SAMPLE_RATE) : "";
    $("end").value = blob ? Math.round(blob.end * 1000 / SAMPLE_RATE) : "";
    draw(); renderDeck();
}
function draw() {
    const canvas = $("wave"), scale = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.round(canvas.clientWidth * scale));
    const height = Math.round(160 * scale);
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, width, height);
    if (!samples) return;
    for (const blob of blobs) {
        const card = owner(blob);
        const index = card ? cards.indexOf(card) : -1;
        ctx.fillStyle = index < 0 ? "#a8afb766" : `${colors[index % colors.length]}55`;
        ctx.fillRect(blob.start / samples.length * width, 0, (blob.end - blob.start) / samples.length * width, height);
    }
    ctx.strokeStyle = "#304957"; ctx.lineWidth = scale;
    ctx.beginPath();
    for (let x = 0; x < width; x++) {
        const from = Math.floor(x * samples.length / width);
        const to = Math.max(from + 1, Math.floor((x + 1) * samples.length / width));
        let min = 0, max = 0;
        for (let i = from; i < Math.min(to, samples.length); i++) { min = Math.min(min, samples[i]); max = Math.max(max, samples[i]); }
        ctx.moveTo(x, height / 2 - max * height * .48);
        ctx.lineTo(x, height / 2 - min * height * .48);
    }
    ctx.stroke();
    if (selected) {
        ctx.strokeStyle = "#111"; ctx.lineWidth = 3 * scale;
        for (const point of [selected.start, selected.end]) {
            const x = point / samples.length * width;
            ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, height); ctx.stroke();
        }
    }
}
function button(text, label, handler) {
    const element = document.createElement("button");
    element.type = "button"; element.textContent = text; element.title = label; element.setAttribute("aria-label", label);
    element.addEventListener("click", action(handler));
    return element;
}
function textInput(value, label, update) {
    const input = document.createElement("input");
    input.value = value; input.placeholder = label; input.setAttribute("aria-label", label);
    input.addEventListener("input", () => update(input.value));
    return input;
}
function splitText(text) {
    const words = text.trim().split(/\s+/);
    if (words.length > 1) {
        const middle = Math.ceil(words.length / 2);
        return [words.slice(0, middle).join(" "), words.slice(middle).join(" ")];
    }
    const chars = typeof Intl.Segmenter === "function"
        ? Array.from(new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(text), part => part.segment)
        : Array.from(text);
    const middle = Math.ceil(chars.length / 2);
    return [chars.slice(0, middle).join(""), chars.slice(middle).join("")];
}
function split(card) {
    const [target, target2] = splitText(card.target), [ancient, ancient2] = splitText(card.ancient);
    const joinParent = { id: card.id, target: !/\s/.test(card.target.trim()), ancient: !/\s/.test(card.ancient.trim()) };
    let first = [], second = [];
    if (card.spans.length === 1) {
        const span = card.spans[0];
        if (span.end - span.start < 2) throw new Error("The slice is too short to split.");
        const midpoint = zeroCrossing(samples, (span.start + span.end) / 2);
        const point = Math.max(span.start + 1, Math.min(span.end - 1, midpoint));
        const next = { ...span, id: crypto.randomUUID(), start: point };
        span.end = point;
        blobs.splice(blobs.indexOf(span) + 1, 0, next);
        first = [span]; second = [next];
    } else {
        const mid = Math.ceil(card.spans.length / 2);
        first = card.spans.slice(0, mid); second = card.spans.slice(mid);
    }
    card.target = target; card.ancient = ancient; card.spans = first;
    cards.splice(cards.indexOf(card) + 1, 0, { id: crypto.randomUUID(), target: target2, ancient: ancient2, requestedIndex: null, joinParent, spans: second });
    setSelection(first[0] || null);
}
function merge(card) {
    const index = cards.indexOf(card), next = cards[index + 1];
    if (!next) throw new Error("There is no following card to merge.");
    const join = next.joinParent?.id === card.id ? next.joinParent : null;
    card.target = [card.target, next.target].filter(Boolean).join(join?.target ? "" : " ");
    card.ancient = [card.ancient, next.ancient].filter(Boolean).join(join?.ancient ? "" : " ");
    card.spans.push(...next.spans); cards.splice(index + 1, 1);
    setSelection(card.spans[0] || null);
}
async function reviewAudio(oldAudio, newAudio) {
    const dialog = $("review");
    $("review-old").onclick = action(() => play(oldAudio));
    $("review-new").onclick = action(() => play(newAudio));
    dialog.returnValue = "cancel";
    dialog.showModal();
    const result = await new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue), { once: true }));
    stop();
    return result === "replace";
}
async function attach(card, spans) {
    if (!spans.length || spans.every(span => card.spans.includes(span))) return;
    if (card.spans.length && !await reviewAudio(renderSlice(samples, card.spans), renderSlice(samples, spans))) return;
    for (const other of cards) other.spans = other.spans.filter(span => !spans.includes(span));
    card.spans = [...spans];
    setSelection(spans[0]);
}
function renderDeck() {
    const deck = $("deck");
    deck.replaceChildren();
    deck.dir = $("direction").value;
    cards.forEach((card, index) => {
        const element = document.createElement("article");
        element.className = "card" + (card.spans.includes(selected) ? " selected" : "");
        element.style.setProperty("--color", colors[index % colors.length]);
        const header = document.createElement("header"); header.draggable = true;
        const handle = document.createElement("span"); handle.textContent = "⠿"; handle.title = "Drag to reorder";
        const badge = document.createElement("span");
        const delta = card.spans.length === 1 ? index - card.spans[0].spokenIndex : null;
        badge.textContent = card.spans.length > 1 ? "[multi]" : delta === null ? "[∅]" : `[${delta > 0 ? "+" : ""}${delta}]`;
        header.append(handle, badge);
        header.addEventListener("dragstart", event => { drag = { type: "card", card }; event.dataTransfer.setData("text/plain", "card"); });
        element.append(header,
            textInput(card.target, "Target text", value => { card.target = value; }),
            textInput(card.ancient, "Ancient text", value => { card.ancient = value; }));
        const footer = document.createElement("footer"); footer.draggable = true;
        footer.title = "Hold Shift and drag to move audio";
        footer.addEventListener("dragstart", event => {
            if (!event.shiftKey || !card.spans.length) { event.preventDefault(); return; }
            drag = { type: "audio", card }; event.dataTransfer.setData("text/plain", "audio");
        });
        const duration = card.spans.reduce((sum, span) => sum + span.end - span.start, 0);
        const label = document.createElement("span"); label.textContent = card.spans.length ? `${Math.round(duration * 1000 / SAMPLE_RATE)} ms` : "∅";
        footer.append(label,
            button("←", "Move card earlier", () => {
                const i = cards.indexOf(card);
                if (i > 0) { cards.splice(i, 1); cards.splice(i - 1, 0, card); setSelection(selected); }
            }),
            button("→", "Move card later", () => {
                const i = cards.indexOf(card);
                if (i < cards.length - 1) { cards.splice(i, 1); cards.splice(i + 1, 0, card); setSelection(selected); }
            }),
            button("▶", "Play card audio", () => play(renderSlice(samples, card.spans))),
            button("+ Audio", "Attach selected slice", () => { if (!selected) throw new Error("Select a waveform slice first."); return attach(card, [selected]); }),
            button("✂", "Split card", () => split(card)),
            button("🔗", "Merge with following card", () => merge(card)),
            button("×", "Delete card", () => { cards.splice(cards.indexOf(card), 1); setSelection(selected); }));
        element.append(footer);
        element.addEventListener("dragover", event => event.preventDefault());
        element.addEventListener("drop", action(async event => {
            event.preventDefault();
            if (!drag || drag.card === card) return;
            if (drag.type === "audio") await attach(card, [...drag.card.spans]);
            else {
                const bounds = element.getBoundingClientRect();
                const after = $("direction").value === "rtl"
                    ? event.clientX < bounds.left + bounds.width / 2
                    : event.clientX > bounds.left + bounds.width / 2;
                const source = cards.indexOf(drag.card);
                cards.splice(source, 1); cards.splice(cards.indexOf(card) + (after ? 1 : 0), 0, drag.card);
                setSelection(selected);
            }
            drag = null;
        }));
        deck.append(element);
    });
}
async function loadAudio(data, name) {
    stop();
    const decoded = await context.decodeAudioData(data.slice(0));
    if (!decoded.length || decoded.duration > 180) throw new Error("Audio must be nonempty and at most 180 seconds.");
    const offline = new OfflineAudioContext(1, Math.ceil(decoded.duration * SAMPLE_RATE), SAMPLE_RATE);
    const source = offline.createBufferSource(); source.buffer = decoded; source.connect(offline.destination); source.start();
    const rendered = await offline.startRendering();
    const next = normalizeAudio(rendered.getChannelData(0));
    const detected = detectBlobs(next);
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", data))].map(byte => byte.toString(16).padStart(2, "0")).join("");
    if (samples && !await reviewAudio(samples, next)) { status("Kept the current recording."); return; }
    samples = next; blobs = detected; sourceBytes = new Uint8Array(data); sourceName = name; sourceHash = hash;
    cards.forEach(card => { card.spans = []; });
    assignIndexed(cards, blobs);
    setSelection(null);
    status(`Loaded ${(samples.length / SAMPLE_RATE).toFixed(2)} seconds; detected ${blobs.length} provisional slices. Review all pairings.`);
}
$("audio-file").addEventListener("change", action(async event => {
    const file = event.target.files[0];
    if (file) {
        event.target.value = "";
        const ext = file.name.match(/\.(wav|mp3|m4a|mp4|ogg|webm|aac|flac)$/i)?.[1]?.toLowerCase() || "audio";
        await loadAudio(await file.arrayBuffer(), `source.${ext}`);
    }
}));
$("script-file").addEventListener("change", action(async event => {
    const file = event.target.files[0]; if (!file) return;
    const data = JSON.parse(await file.text());
    const restored = Array.isArray(data) ? null : restoreAlignment(data, samples?.length || 0, sourceHash);
    const next = restored?.cards || validateScript(data);
    event.target.value = "";
    if (cards.length && !confirm("Replace the current script and discard its card edits and pairings?")) return;
    cards = next;
    if (restored) blobs = restored.blobs;
    else assignIndexed(cards, blobs);
    setSelection(null);
    status(`Loaded ${cards.length} cards. Check provisional assignments.`);
}));
$("example").addEventListener("click", action(() => {
    if (cards.length && !confirm("Replace the current script and discard its card edits and pairings?")) return;
    cards = validateScript([
        { target_text: "the word", ancient_text: "ὁ λόγος" },
        { target_text: "was", ancient_text: "ἦν" },
        { target_text: "in the beginning", ancient_text: "ἐν ἀρχῇ" }
    ]);
    setSelection(null); status("Example cards loaded. Select waveform slices to attach them.");
}));
$("record").addEventListener("click", action(async () => {
    if (recorder?.state === "recording") { recorder.stop(); return; }
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    try { recorder = new MediaRecorder(stream); } catch (error) { stream.getTracks().forEach(track => track.stop()); throw error; }
    chunks = [];
    recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
    recorder.onerror = event => status(event.error?.message || "Microphone recording failed.");
    recorder.onstop = action(async () => {
        stream.getTracks().forEach(track => track.stop());
        $("record").textContent = "Record microphone";
        const type = recorder.mimeType.toLowerCase();
        const ext = type.includes("ogg") ? "ogg" : type.includes("mp4") ? "m4a" : "webm";
        await loadAudio(await new Blob(chunks, { type: recorder.mimeType }).arrayBuffer(), `source.${ext}`);
    });
    recorder.start(); $("record").textContent = "Stop recording"; status("Recording microphone…");
}));
$("play-spoken").addEventListener("click", action(() => play(samples)));
$("play-deck").addEventListener("click", action(() => play(renderSlice(samples, cards.flatMap(card => card.spans)))));
$("stop").addEventListener("click", stop);
$("direction").addEventListener("change", renderDeck);
$("match-transcript").addEventListener("click", action(() => {
    if (!samples) throw new Error("Load audio first.");
    const pairs = pairTranscript(cards, blobs, $("transcript").value);
    for (const [card, blob] of pairs) card.spans = [blob];
    setSelection(selected);
    status(`Paired ${pairs.length} unique transcript matches. Review every pairing.`);
}));
$("add-card").addEventListener("click", () => { cards.push({ id: crypto.randomUUID(), target: "", ancient: "", spans: [] }); renderDeck(); });
$("detach").addEventListener("click", action(() => {
    if (!selected) throw new Error("Select a slice first.");
    const card = owner(selected); if (card) card.spans = card.spans.filter(span => span !== selected);
    setSelection(selected);
}));
$("delete-blob").addEventListener("click", action(() => {
    if (!selected) throw new Error("Select a slice first.");
    if (owner(selected)) throw new Error("Unassign the slice before removing it.");
    blobs.splice(blobs.indexOf(selected), 1);
    setSelection(null);
}));
$("add-blob").addEventListener("click", action(() => {
    if (!samples) throw new Error("Load audio first.");
    const start = Math.min(cursor, samples.length - 1), end = Math.min(samples.length, start + SAMPLE_RATE / 4);
    const blob = { id: crypto.randomUUID(), start, end, spokenIndex: blobs.length };
    blobs.push(blob);
    setSelection(blob);
}));
function applyBoundaries(start, end) {
    if (!samples || !selected || !Number.isFinite(start) || !Number.isFinite(end) ||
        start < 0 || end > samples.length || Math.round(start) >= Math.round(end)) {
        throw new Error("Enter valid boundaries within the audio.");
    }
    selected.start = Math.round(start); selected.end = Math.round(end);
    setSelection(selected);
}
$("apply-boundaries").addEventListener("click", action(() => {
    if (!$("start").value || !$("end").value) throw new Error("Enter both boundaries.");
    applyBoundaries(Number($("start").value) * SAMPLE_RATE / 1000, Number($("end").value) * SAMPLE_RATE / 1000);
}));
const canvas = $("wave");
function position(event) {
    const rect = canvas.getBoundingClientRect();
    return Math.max(0, Math.min(samples.length, Math.round((event.clientX - rect.left) / rect.width * samples.length)));
}
canvas.addEventListener("pointerdown", event => {
    if (!samples) return;
    const point = position(event); cursor = point;
    const tolerance = samples.length * 12 / canvas.clientWidth;
    if (selected && Math.min(Math.abs(point - selected.start), Math.abs(point - selected.end)) <= tolerance) {
        movingHandle = Math.abs(point - selected.start) <= Math.abs(point - selected.end) ? "start" : "end";
        canvas.setPointerCapture(event.pointerId);
    } else {
        const hit = [...blobs].reverse().find(blob => point >= blob.start && point <= blob.end);
        setSelection(hit || null);
    }
});
canvas.addEventListener("pointermove", event => {
    if (!movingHandle || !selected) return;
    const point = zeroCrossing(samples, position(event));
    if (movingHandle === "start") selected.start = Math.min(point, selected.end - 1);
    else selected.end = Math.max(point, selected.start + 1);
    setSelection(selected);
});
canvas.addEventListener("pointerup", () => { movingHandle = null; });
canvas.addEventListener("pointercancel", () => { movingHandle = null; });
window.addEventListener("resize", draw);
$("export").addEventListener("click", action(() => {
    if (!samples || !cards.length) throw new Error("Load audio and cards first.");
    const value = $("padding").value;
    if (value === "") throw new Error("Enter padding between 0 and 100 ms.");
    const padding = Number(value), records = manifest(cards);
    if (!Number.isFinite(padding) || padding < 0 || padding > 100) throw new Error("Enter padding between 0 and 100 ms.");
    const files = [{ name: sourceName, data: sourceBytes }];
    let clipCount = 0;
    cards.forEach((card, index) => {
        if (!card.spans.length) return;
        const name = card.target.normalize("NFC").replace(/[^\p{L}\p{N}_-]+/gu, "_").replace(/^_+|_+$/g, "").slice(0, 60) || "word";
        files.push({ name: `${String(index + 1).padStart(2, "0")}_${name}.wav`, data: wav(renderSlice(samples, card.spans, padding)) });
        clipCount++;
    });
    const unassigned = blobs.filter(blob => !owner(blob)).map(blob => ({
        spoken_index: blob.spokenIndex + 1, start_ms: blob.start * 1000 / SAMPLE_RATE,
        end_ms: blob.end * 1000 / SAMPLE_RATE
    }));
    files.push({ name: "alignment.json", data: new TextEncoder().encode(JSON.stringify({ sample_rate: SAMPLE_RATE, source_samples: samples.length, source_sha256: sourceHash, cards: records, unassigned }, null, 2)) });
    const url = URL.createObjectURL(zip(files));
    const link = document.createElement("a"); link.href = url; link.download = "interlinear-audio.zip"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    status(`Exported ${clipCount} WAV clips, source audio, and alignment.json.`);
}));
renderDeck();
