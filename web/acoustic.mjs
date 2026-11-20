import { SAMPLE_RATE } from "./core.mjs";

// Log-mel spectra provide a speaker-dependent acoustic comparison, not phone recognition.
const FRAME = 400, FFT = 512, HOP = 160, BANDS = 24;
const mel = frequency => 2595 * Math.log10(1 + frequency / 700);
const hz = value => 700 * (10 ** (value / 2595) - 1);
const edges = Array.from({ length: BANDS + 2 }, (_, i) =>
    hz(mel(80) + (mel(7600) - mel(80)) * i / (BANDS + 1)) * FFT / SAMPLE_RATE);

function spectrum(frame) {
    const real = new Float64Array(FFT), imag = new Float64Array(FFT);
    for (let i = 0; i < frame.length; i++) real[i] = frame[i] * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
    for (let i = 1, j = 0; i < FFT; i++) {
        let bit = FFT >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) [real[i], real[j]] = [real[j], real[i]];
    }
    for (let size = 2; size <= FFT; size *= 2) {
        const angle = -2 * Math.PI / size;
        for (let start = 0; start < FFT; start += size) {
            for (let j = 0; j < size / 2; j++) {
                const cosine = Math.cos(angle * j), sine = Math.sin(angle * j);
                const k = start + j, l = k + size / 2;
                const r = real[l] * cosine - imag[l] * sine;
                const im = real[l] * sine + imag[l] * cosine;
                real[l] = real[k] - r; imag[l] = imag[k] - im;
                real[k] += r; imag[k] += im;
            }
        }
    }
    const result = new Float64Array(BANDS);
    for (let band = 0; band < BANDS; band++) {
        let energy = 0;
        for (let bin = Math.ceil(edges[band]); bin <= Math.floor(edges[band + 2]); bin++) {
            const weight = bin < edges[band + 1]
                ? (bin - edges[band]) / (edges[band + 1] - edges[band])
                : (edges[band + 2] - bin) / (edges[band + 2] - edges[band + 1]);
            energy += Math.max(0, weight) * (real[bin] ** 2 + imag[bin] ** 2);
        }
        result[band] = Math.log(energy + 1e-10);
    }
    const mean = result.reduce((sum, value) => sum + value, 0) / BANDS;
    let norm = 0;
    for (let i = 0; i < BANDS; i++) { result[i] -= mean; norm += result[i] ** 2; }
    norm = Math.sqrt(norm);
    if (norm) for (let i = 0; i < BANDS; i++) result[i] /= norm;
    return result;
}

export function features(samples) {
    if (!samples || samples.length < FRAME || samples.length > SAMPLE_RATE * 12) return [];
    const frames = [];
    let peak = 0;
    for (let start = 0; start + FRAME <= samples.length; start += HOP) {
        let energy = 0;
        for (let i = start; i < start + FRAME; i++) energy += samples[i] ** 2;
        energy = Math.sqrt(energy / FRAME);
        peak = Math.max(peak, energy);
        frames.push({ start, energy });
    }
    if (peak < 1e-5) return [];
    const active = frames.filter(frame => frame.energy >= peak * .1);
    const stride = Math.ceil(active.length / 80);
    return active.filter((_, index) => index % stride === 0)
        .map(frame => spectrum(samples.subarray(frame.start, frame.start + FRAME)));
}

// DTW handles different speaking rates, with a penalty for repeated frames.
export function distance(first, second) {
    if (!first.length || !second.length || Math.max(first.length, second.length) > 3 * Math.min(first.length, second.length)) return Infinity;
    let previous = new Float64Array(second.length + 1).fill(Infinity);
    previous[0] = 0;
    for (const a of first) {
        const current = new Float64Array(second.length + 1).fill(Infinity);
        for (let j = 1; j <= second.length; j++) {
            let dot = 0;
            for (let k = 0; k < BANDS; k++) dot += a[k] * second[j - 1][k];
            const cost = Math.max(0, 1 - dot);
            current[j] = cost + Math.min(previous[j - 1], previous[j] + .02, current[j - 1] + .02);
        }
        previous = current;
    }
    return previous[second.length] / Math.max(first.length, second.length);
}

export function tokenKey(text) {
    return text.toLowerCase().normalize("NFD").replace(/[\u0591-\u05af]/g, "")
        .replace(/([\u0370-\u03ff])[\u0300-\u036f]*/g, "$1")
        .replace(/[^\p{L}\p{M}]/gu, "");
}

export function matchReferences(cards, blobs, samples, references) {
    const available = blobs.filter(blob => !cards.some(card => card.spans.includes(blob)));
    const keys = cards.map(card => tokenKey(card.ancient));
    const candidates = cards.flatMap((card, i) => {
        const key = keys[i], matching = references.filter(reference => tokenKey(reference.token) === key);
        if (!key || card.spans.length || keys.filter(value => value === key).length !== 1 || matching.length !== 1) return [];
        const vectors = features(matching[0].samples);
        return vectors.length ? [{ card, vectors }] : [];
    });
    const acoustic = available.map(blob => features(samples.subarray(blob.start, blob.end)));
    const matrix = candidates.map(candidate => acoustic.map(blob => distance(candidate.vectors, blob)));
    const pairs = [];
    for (let i = 0; i < candidates.length; i++) {
        const ordered = matrix[i].map((score, j) => ({ score, j })).sort((a, b) => a.score - b.score);
        const best = ordered[0];
        if (!best || !Number.isFinite(best.score)) continue;
        const rivals = matrix.map(row => row[best.j]).filter((_, index) => index !== i);
        const other = Math.min(...rivals, Infinity);
        const next = ordered[1]?.score ?? Infinity;
        const long = candidates[i].vectors.length >= 40;
        const maxDistance = long ? .20 : .12;
        const margin = long ? .05 : .08;
        if (best.score <= maxDistance && next - best.score >= margin && other - best.score >= margin) {
            pairs.push({ card: candidates[i].card, blob: available[best.j], score: best.score, long });
        }
    }
    return pairs.sort((a, b) => Number(b.long) - Number(a.long) || a.score - b.score);
}
