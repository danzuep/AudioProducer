import { matchReferences } from "./acoustic.mjs";

self.onmessage = event => {
    try {
        const { cards, blobs, samples, references } = event.data;
        const pairs = matchReferences(cards, blobs, samples, references);
        self.postMessage({ pairs: pairs.map(pair => ({ cardId: pair.card.id, blobId: pair.blob.id, score: pair.score })) });
    } catch (error) {
        self.postMessage({ error: error.message || String(error) });
    }
};
