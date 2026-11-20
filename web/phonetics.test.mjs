import { test } from "node:test";
import assert from "node:assert/strict";
import { greekPhones, hebrewPhones, normalizePhones, decodeCtc, phoneCandidates, editDistance } from "./phonetics.mjs";

test("Greek pronunciation retains historical and Koine distinctions", () => {
    assert.equal(greekPhones("λόγος"), "loɡos");
    assert.equal(greekPhones("λόγος", true), "loɣos");
    assert.equal(greekPhones("οὕτως"), "hutɔs");
    assert.equal(greekPhones("οὕτως", true), "utos");
});

test("Hebrew pronunciation ignores cantillation but preserves vowels", () => {
    assert.equal(hebrewPhones("בָּ֑רָא"), hebrewPhones("בָּרָא"));
    assert.notEqual(hebrewPhones("בָּרָא"), hebrewPhones("בֶּרָא"));
    assert.notEqual(hebrewPhones("אֵת"), hebrewPhones("אֵת", true));
    assert.equal(hebrewPhones("ברא"), "");
    assert.equal(hebrewPhones("שׁוּב"), "ʃuv");
});

test("CTC decoding collapses repeats and blanks without hiding malformed vocabularies", () => {
    const vocab = { "<pad>": 0, "a": 1, "b": 2 };
    const logits = Float32Array.from([0, 3, 0, 0, 3, 0, 3, 0, 0, 0, 3, 0, 0, 0, 3]);
    assert.equal(decodeCtc(logits, 5, vocab), "aab");
    assert.throws(() => decodeCtc(logits, 5, { "<pad>": 0, "a": 0, "b": 2 }), /vocabulary/);
    assert.throws(() => decodeCtc(Float32Array.from([0, NaN, 0]), 1, vocab), /invalid logits/);
    assert.equal(normalizePhones("ˈloɡoːs"), "logos");
    assert.ok(editDistance("kosmos", "ɔsmɔs") / 6 < .25);
});

test("phoneme matching is non-monotonic and leaves ambiguous or unmapped blobs alone", () => {
    const cards = [
        { id: "word", ancient: "λόγος", spans: [] },
        { id: "was", ancient: "ἦν", spans: [] },
        { id: "empty", ancient: "", spans: [] }
    ];
    const blobs = [{ id: "first" }, { id: "second" }, { id: "orphan" }];
    const pairs = phoneCandidates(cards, blobs, ["ɛn", "loɡos", "t"], "greek-classical");
    assert.equal(pairs.find(pair => pair.cardId === "word").blobId, "second");
    assert.equal(pairs.find(pair => pair.cardId === "was").blobId, "first");
    assert.ok(pairs.every(pair => pair.blobId !== "orphan"));
    const repeated = [...cards, { id: "repeat", ancient: "λόγος", spans: [] }];
    assert.ok(phoneCandidates(repeated, blobs, ["ɛn", "loɡos", "t"], "greek-classical").every(pair => pair.cardId !== "word" && pair.cardId !== "repeat"));
});
