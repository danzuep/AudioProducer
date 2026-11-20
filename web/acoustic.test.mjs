import { test } from "node:test";
import assert from "node:assert/strict";
import { features, distance, matchReferences, tokenKey } from "./acoustic.mjs";
import { SAMPLE_RATE } from "./core.mjs";

const tone = frequency => Float32Array.from({ length: 8000 }, (_, i) =>
    Math.sin(i * 2 * Math.PI * frequency / SAMPLE_RATE) * .6);

test("acoustic comparison distinguishes different spectra and rejects silence", () => {
    const first = features(tone(200)), second = features(tone(400));
    assert.ok(distance(first, first) < .001);
    assert.ok(distance(first, second) > .2);
    assert.equal(features(new Float32Array(4000)).length, 0);
    assert.equal(distance(first, []), Infinity);
});

test("reference matching pairs non-monotonic clips without attaching unmatched audio", () => {
    const low = tone(200), high = tone(400), unknown = tone(700);
    const samples = new Float32Array(low.length * 3);
    samples.set(low); samples.set(high, low.length); samples.set(unknown, low.length * 2);
    const blobs = [0, 1, 2].map((i) => ({ id: `${i}`, start: i * low.length, end: (i + 1) * low.length }));
    const cards = [
        { ancient: "ἦν", spans: [] },
        { ancient: "λόγος", spans: [] },
        { ancient: "", spans: [] }
    ];
    const refs = [{ token: "λόγος", samples: low }, { token: "ἦν", samples: high }];
    const pairs = matchReferences(cards, blobs, samples, refs);
    assert.equal(pairs.length, 2);
    assert.equal(pairs.find(pair => pair.card === cards[0]).blob, blobs[1]);
    assert.equal(pairs.find(pair => pair.card === cards[1]).blob, blobs[0]);
    assert.ok(pairs.every(pair => pair.blob !== blobs[2]));
});

test("reference matching refuses ambiguous words and already attached blobs", () => {
    const samples = tone(200);
    const blob = { id: "a", start: 0, end: samples.length };
    const refs = [{ token: "λόγος", samples }];
    assert.deepEqual(matchReferences([
        { ancient: "λόγος", spans: [] }, { ancient: "λογος", spans: [] }
    ], [blob], samples, refs), []);
    assert.deepEqual(matchReferences([
        { ancient: "λόγος", spans: [] }
    ], [blob], samples, [...refs, ...refs]), []);
    assert.deepEqual(matchReferences([
        { ancient: "λόγος", spans: [] }, { ancient: "other", spans: [blob] }
    ], [blob], samples, refs), []);
    assert.deepEqual(matchReferences([
        { ancient: "λόγος", spans: [] }, { ancient: "ἦν", spans: [] }
    ], [blob], samples, [...refs, { token: "ἦν", samples }]), []);
    assert.equal(tokenKey("בָּ֑רָא"), tokenKey("בָּרָא"));
    assert.notEqual(tokenKey("בָּרָא"), tokenKey("בֶּרָא"));
});
