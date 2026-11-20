export const SAMPLE_RATE = 16000;

export function normalizeAudio(input) {
    if (!input.length || !input.every(Number.isFinite)) throw new Error("Audio is empty or invalid.");
    const peak = input.reduce((p, x) => Math.max(p, Math.abs(x)), 0);
    if (!peak) throw new Error("Audio is silent.");
    const gain = 0.9 / peak;
    return Float32Array.from(input, x => {
        const value = x * gain;
        // A gentle downward expander attenuates low-level noise without cutting it out.
        return value * (Math.abs(value) < 0.015 ? 0.5 + Math.abs(value) / 0.03 : 1);
    });
}

export function detectBlobs(samples) {
    const frame = 160;
    const energies = [];
    for (let i = 0; i < samples.length; i += frame) {
        let sum = 0;
        const end = Math.min(i + frame, samples.length);
        for (let j = i; j < end; j++) sum += samples[j] ** 2;
        energies.push(Math.sqrt(sum / (end - i)));
    }
    const max = Math.max(...energies);
    if (!max) return [];
    const sorted = [...energies].sort((a, b) => a - b);
    const threshold = Math.max(0.008, Math.min(max * 0.3, sorted[Math.floor(sorted.length * 0.2)] * 2.5), max * 0.06);
    const regions = [];
    let start = -1;
    let last = -1;
    for (let i = 0; i < energies.length; i++) {
        if (energies[i] >= threshold) {
            if (start < 0) start = i;
            last = i;
        } else if (start >= 0 && i - last > 10) {
            regions.push([start, last + 1]);
            start = -1;
        }
    }
    if (start >= 0) regions.push([start, last + 1]);
    return regions.filter(([a, b]) => b - a >= 3).map(([a, b], index) => ({
        id: crypto.randomUUID(),
        spokenIndex: index,
        start: Math.max(0, (a - 2) * frame),
        end: Math.min(samples.length, (b + 2) * frame)
    }));
}

export function zeroCrossing(samples, position, radius = 160) {
    const target = Math.max(0, Math.min(samples.length, Math.round(position)));
    let best = target;
    let distance = Infinity;
    for (let i = Math.max(1, target - radius); i < Math.min(samples.length, target + radius + 1); i++) {
        if ((samples[i - 1] <= 0 && samples[i] >= 0) || (samples[i - 1] >= 0 && samples[i] <= 0)) {
            const d = Math.abs(i - target);
            if (d < distance) { distance = d; best = i; }
        }
    }
    return best;
}

export function renderSlice(samples, spans, paddingMs = 0) {
    if (!Number.isFinite(paddingMs) || paddingMs < 0 || paddingMs > 100) throw new Error("Padding must be 0–100 ms.");
    const padding = Math.round(paddingMs * SAMPLE_RATE / 1000);
    const parts = spans.map(span => {
        const start = Math.max(0, span.start - padding);
        const end = Math.min(samples.length, span.end + padding);
        if (!Number.isInteger(start) || !Number.isInteger(end) || start >= end) throw new Error("Invalid slice.");
        const part = samples.slice(start, end);
        const fade = Math.min(80, Math.floor(part.length / 2));
        for (let i = 0; i < fade; i++) {
            const gain = i / fade;
            part[i] *= gain;
            part[part.length - 1 - i] *= gain;
        }
        return part;
    });
    const length = parts.reduce((sum, p) => sum + p.length, 0);
    if (length > SAMPLE_RATE * 600) throw new Error("Rendered audio is too long.");
    const result = new Float32Array(length);
    let offset = 0;
    for (const part of parts) { result.set(part, offset); offset += part.length; }
    return result;
}

export function wav(samples) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    const text = (offset, value) => [...value].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
    text(0, "RIFF"); view.setUint32(4, buffer.byteLength - 8, true);
    text(8, "WAVEfmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, 1, true); view.setUint32(24, SAMPLE_RATE, true);
    view.setUint32(28, SAMPLE_RATE * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    text(36, "data"); view.setUint32(40, samples.length * 2, true);
    samples.forEach((v, i) => view.setInt16(44 + i * 2, Math.round(Math.max(-1, Math.min(1, v)) * 32767), true));
    return new Uint8Array(buffer);
}

export function validateScript(data) {
    if (!Array.isArray(data) || !data.length) throw new Error("Script must be a non-empty JSON array.");
    return data.map((item, index) => {
        if (!item || typeof item !== "object" || typeof item.target_text !== "string" ||
            typeof item.ancient_text !== "string") throw new Error(`Card ${index + 1} needs target_text and ancient_text strings.`);
        if (item.spoken_index != null && (!Number.isInteger(item.spoken_index) || item.spoken_index < 1)) {
            throw new Error(`Card ${index + 1} has an invalid spoken_index (use 1-based indices).`);
        }
        return { id: crypto.randomUUID(), target: item.target_text, ancient: item.ancient_text,
            requestedIndex: item.spoken_index ?? null, spans: [] };
    });
}

export function assignIndexed(cards, blobs) {
    const counts = new Map();
    for (const card of cards) if (card.requestedIndex != null) counts.set(card.requestedIndex, (counts.get(card.requestedIndex) || 0) + 1);
    for (const card of cards) {
        if (card.requestedIndex != null && counts.get(card.requestedIndex) === 1 && blobs[card.requestedIndex - 1]) {
            card.spans = [blobs[card.requestedIndex - 1]];
        }
    }
}

export function targetTokens(text) {
    return text.normalize("NFC").toLowerCase().trim().split(/\s+/).filter(Boolean).map(token =>
        token.replace(/^[^\p{L}\p{M}\p{N}]+|[^\p{L}\p{M}\p{N}]+$/gu, ""));
}

export function pairTranscript(cards, blobs, transcript) {
    const tokens = targetTokens(transcript);
    if (tokens.length !== blobs.length || tokens.some(token => !token)) {
        throw new Error("The target-language transcript must contain exactly one word per detected slice. Adjust slices first.");
    }
    const cardTokens = cards.map(card => targetTokens(card.target));
    const keys = cardTokens.map(words => words.join(" "));
    const candidates = [];
    for (let i = 0; i < cards.length; i++) {
        const words = cardTokens[i], key = keys[i];
        if (!key || words.some(word => !word) || cards[i].spans.length || keys.filter(value => value === key).length !== 1) continue;
        const positions = [];
        for (let start = 0; start + words.length <= tokens.length; start++) {
            if (words.every((word, offset) => word === tokens[start + offset])) positions.push(start);
        }
        if (positions.length !== 1) continue;
        const spans = blobs.slice(positions[0], positions[0] + words.length);
        if (spans.some(blob => cards.some(card => card.spans.includes(blob)))) continue;
        candidates.push([cards[i], spans]);
    }
    return candidates.filter(([, spans], index) =>
        !candidates.some(([, other], otherIndex) => otherIndex !== index && spans.some(span => other.includes(span))));
}

export function manifest(cards) {
    const ms = sample => sample * 1000 / SAMPLE_RATE;
    return cards.map((card, i) => ({
        tuned_index: i + 1,
        spoken_index: card.spans.length === 1 ? card.spans[0].spokenIndex + 1 : null,
        target_text: card.target,
        ancient_text: card.ancient,
        start_ms: card.spans.length === 1 ? ms(card.spans[0].start) : null,
        end_ms: card.spans.length === 1 ? ms(card.spans[0].end) : null,
        spans: card.spans.map(s => ({ spoken_index: s.spokenIndex + 1, start_ms: ms(s.start), end_ms: ms(s.end) }))
    }));
}

export function restoreAlignment(data, sampleCount, sourceHash) {
    if (!data || !Array.isArray(data.cards) || !Array.isArray(data.unassigned) || data.sample_rate !== SAMPLE_RATE) {
        throw new Error("Invalid alignment manifest.");
    }
    if (!sampleCount || data.source_samples !== sampleCount || !sourceHash || data.source_sha256 !== sourceHash) {
        throw new Error("Load the original source audio before restoring its alignment.");
    }
    const cards = validateScript(data.cards);
    const blobs = [];
    function span(item) {
        if (!item || !Number.isFinite(item.start_ms) || !Number.isFinite(item.end_ms) ||
            !Number.isInteger(item.spoken_index) || item.spoken_index < 1) throw new Error("Invalid manifest slice.");
        const start = Math.round(item.start_ms * SAMPLE_RATE / 1000);
        const end = Math.round(item.end_ms * SAMPLE_RATE / 1000);
        if (start < 0 || end > sampleCount || start >= end) throw new Error("Manifest slice is outside the loaded audio.");
        const blob = { id: crypto.randomUUID(), start, end, spokenIndex: item.spoken_index - 1 };
        blobs.push(blob);
        return blob;
    }
    data.cards.forEach((card, index) => {
        if (!Array.isArray(card.spans)) throw new Error("Manifest card is missing its spans.");
        cards[index].spans = card.spans.map(span);
    });
    data.unassigned.forEach(span);
    return { cards, blobs };
}

export function zip(files) {
    const encoder = new TextEncoder();
    const table = Uint32Array.from({ length: 256 }, (_, n) => {
        for (let i = 0; i < 8; i++) n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1;
        return n >>> 0;
    });
    const local = [], central = [];
    let offset = 0;
    for (const file of files) {
        const name = encoder.encode(file.name), data = file.data;
        let crc = 0xffffffff;
        for (const b of data) crc = table[(crc ^ b) & 255] ^ (crc >>> 8);
        crc = (crc ^ 0xffffffff) >>> 0;
        const header = new Uint8Array(30 + name.length);
        const h = new DataView(header.buffer);
        h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x800, true);
        h.setUint32(14, crc, true); h.setUint32(18, data.length, true); h.setUint32(22, data.length, true);
        h.setUint16(26, name.length, true); header.set(name, 30);
        const dir = new Uint8Array(46 + name.length);
        const d = new DataView(dir.buffer);
        d.setUint32(0, 0x02014b50, true); d.setUint16(4, 20, true); d.setUint16(6, 20, true); d.setUint16(8, 0x800, true);
        d.setUint32(16, crc, true); d.setUint32(20, data.length, true); d.setUint32(24, data.length, true);
        d.setUint16(28, name.length, true); d.setUint32(42, offset, true); dir.set(name, 46);
        local.push(header, data); central.push(dir); offset += header.length + data.length;
    }
    const directorySize = central.reduce((sum, a) => sum + a.length, 0);
    const end = new Uint8Array(22), e = new DataView(end.buffer);
    e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true);
    e.setUint32(12, directorySize, true); e.setUint32(16, offset, true);
    return new Blob([...local, ...central, end], { type: "application/zip" });
}
