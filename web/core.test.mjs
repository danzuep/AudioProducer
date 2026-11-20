import { test } from "node:test";
import assert from "node:assert/strict";
import { SAMPLE_RATE, normalizeAudio, detectBlobs, zeroCrossing, renderSlice, wav, validateScript, assignIndexed, pairTranscript, manifest, restoreAlignment, zip } from "./core.mjs";

test("segmentation keeps separated speech and rejects silence", () => {
    const raw = new Float32Array(SAMPLE_RATE);
    raw.fill(0.5, 1600, 4800);
    raw.fill(-0.4, 8000, 11200);
    const normalized = normalizeAudio(raw);
    const blobs = detectBlobs(normalized);
    assert.equal(blobs.length, 2);
    assert.ok(blobs[0].start < 1600 && blobs[0].end > 4800);
    assert.ok(blobs[1].start < 8000 && blobs[1].end > 11200);
    assert.throws(() => normalizeAudio(new Float32Array(10)), /silent/);
});

test("target transcript maps phrases non-monotonically and leaves unspoken and orphan audio", () => {
    const cards = validateScript([
        { target_text: "the word", ancient_text: "λόγος" },
        { target_text: "was", ancient_text: "ἦν" },
        { target_text: "", ancient_text: "δέ" }
    ]);
    const blobs = [0, 1, 2, 3].map(spokenIndex => ({ spokenIndex, start: spokenIndex * 100, end: spokenIndex * 100 + 50 }));
    const pairs = pairTranscript(cards, blobs, "Was, the word extra");
    assert.equal(pairs.length, 2);
    assert.equal(pairs[0][0], cards[0]);
    assert.deepEqual(pairs[0][1], [blobs[1], blobs[2]]);
    assert.equal(pairs[1][0], cards[1]);
    assert.deepEqual(pairs[1][1], [blobs[0]]);
    assert.throws(() => pairTranscript(cards, blobs, "was"), /exactly one/);
});

test("target transcript does not guess repeated, overlapping, or already assigned matches", () => {
    const blobs = [0, 1, 2].map(spokenIndex => ({ spokenIndex }));
    const cards = validateScript([
        { target_text: "love", ancient_text: "λόγος" },
        { target_text: "LOVE", ancient_text: "ἦν" },
        { target_text: "heart", ancient_text: "" }
    ]);
    assert.deepEqual(pairTranscript(cards, blobs, "love heart love"), [[cards[2], [blobs[1]]]]);
    cards[2].spans = [blobs[1]];
    assert.deepEqual(pairTranscript(cards, blobs, "love heart love"), []);
    const overlapping = validateScript([
        { target_text: "the", ancient_text: "ὁ" },
        { target_text: "the word", ancient_text: "λόγος" }
    ]);
    assert.deepEqual(pairTranscript(overlapping, blobs.slice(0, 2), "the word"), []);
});

test("target matching preserves meaningful accents and supports non-Latin scripts", () => {
    const blobs = [{ spokenIndex: 0 }, { spokenIndex: 1 }];
    const cards = validateScript([
        { target_text: "café", ancient_text: "καφές" },
        { target_text: "سلام", ancient_text: "שלום" }
    ]);
    assert.deepEqual(pairTranscript(cards, blobs, "سَلَام cafe"), []);
    assert.deepEqual(pairTranscript(cards, blobs, "سلام cafe\u0301!"), [
        [cards[0], [blobs[1]]], [cards[1], [blobs[0]]]
    ]);
});

test("index mapping is non-monotonic and refuses duplicate index assignments", () => {
    const cards = validateScript([
        { target_text: "second", ancient_text: "b", spoken_index: 2 },
        { target_text: "first", ancient_text: "a", spoken_index: 1 },
        { target_text: "duplicate", ancient_text: "a", spoken_index: 1 },
        { target_text: "empty", ancient_text: "" }
    ]);
    const blobs = [0, 1].map(spokenIndex => ({ spokenIndex, start: spokenIndex * 100, end: spokenIndex * 100 + 50 }));
    assignIndexed(cards, blobs);
    assert.deepEqual(cards.map(card => card.spans.length), [1, 0, 0, 0]);
    assert.equal(cards[0].spans[0], blobs[1]);
});

test("slice rendering fades without modifying source, and WAV contains PCM", () => {
    const input = Float32Array.from({ length: 300 }, () => 1);
    const result = renderSlice(input, [{ start: 20, end: 220 }], 1);
    assert.equal(result.length, 232);
    assert.equal(result[0], 0);
    assert.equal(result.at(-1), 0);
    assert.equal(input[20], 1);
    const bytes = wav(result), view = new DataView(bytes.buffer);
    assert.equal(view.getUint32(24, true), SAMPLE_RATE);
    assert.equal(view.getUint32(40, true), result.length * 2);
    assert.throws(() => renderSlice(input, [{ start: 1, end: 2 }], 101), /Padding/);
    assert.equal(zeroCrossing(new Float32Array([1, 1, -1, -1]), 1), 2);
});

test("manifest round-trips empty, merged, and unassigned slices at sample precision", () => {
    const cards = validateScript([{ target_text: "first", ancient_text: "a" }, { target_text: "empty", ancient_text: "" }]);
    cards[0].spans = [{ spokenIndex: 2, start: 17, end: 36 }, { spokenIndex: 0, start: 1, end: 11 }];
    const data = { sample_rate: SAMPLE_RATE, source_samples: 100, source_sha256: "hash", cards: manifest(cards),
        unassigned: [{ spoken_index: 2, start_ms: 0.75, end_ms: 1 }] };
    const restored = restoreAlignment(data, 100, "hash");
    assert.deepEqual(restored.cards[0].spans.map(s => [s.start, s.end]), [[17, 36], [1, 11]]);
    assert.deepEqual(restored.cards[1].spans, []);
    assert.equal(restored.blobs.length, 3);
    assert.throws(() => restoreAlignment(data, 99, "hash"), /original source/);
    assert.throws(() => restoreAlignment(data, 100, "other"), /original source/);
});

test("zip stores file content and central directory", async () => {
    const archive = new Uint8Array(await zip([{ name: "01_λόγος.wav", data: new Uint8Array([1, 2, 3]) }]).arrayBuffer());
    const view = new DataView(archive.buffer);
    assert.equal(view.getUint32(0, true), 0x04034b50);
    assert.equal(view.getUint32(archive.length - 22, true), 0x06054b50);
    const nameLength = view.getUint16(26, true);
    assert.deepEqual([...archive.slice(30 + nameLength, 33 + nameLength)], [1, 2, 3]);
});
