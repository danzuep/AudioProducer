import { SAMPLE_RATE } from "./core.mjs";
import { decodeCtc, phoneCandidates } from "./phonetics.mjs";

self.onmessage = async event => {
    let session;
    try {
        const { cards, blobs, samples, model, vocabulary, language } = event.data;
        let ort;
        try {
            ort = await import("./node_modules/onnxruntime-web/dist/ort.min.mjs");
        } catch (error) {
            throw new Error(`Unable to load ONNX Runtime. Run npm ci --prefix web: ${error.message}`);
        }
        ort.env.wasm.numThreads = 1;
        ort.env.wasm.wasmPaths = new URL("./node_modules/onnxruntime-web/dist/", import.meta.url).href;
        const vocab = JSON.parse(await vocabulary.text());
        decodeCtc(new Float32Array(0), 0, vocab);
        session = await ort.InferenceSession.create(new Uint8Array(await model.arrayBuffer()), { executionProviders: ["wasm"] });
        if (session.inputNames.length !== 1 || session.outputNames.length !== 1) throw new Error("The model must have one audio input and one CTC logits output.");
        const observations = [];
        for (let i = 0; i < blobs.length; i++) {
            const blob = blobs[i], previous = blobs[i - 1], next = blobs[i + 1];
            const start = Math.max(0, blob.start - 1920,
                previous ? (previous.end <= blob.start ? Math.floor((previous.end + blob.start) / 2) : blob.start) : 0);
            const end = Math.min(samples.length, blob.end + 1920,
                next ? (next.start >= blob.end ? Math.ceil((blob.end + next.start) / 2) : blob.end) : samples.length);
            const part = samples.slice(start, end);
            if (part.length < 400 || part.length > SAMPLE_RATE * 12 || cards.some(card => card.spans.includes(blob))) {
                observations.push("");
                continue;
            }
            let mean = 0, variance = 0;
            for (const sample of part) mean += sample;
            mean /= part.length;
            for (const sample of part) variance += (sample - mean) ** 2;
            const std = Math.sqrt(variance / part.length + 1e-7);
            for (let j = 0; j < part.length; j++) part[j] = (part[j] - mean) / std;
            const input = new ort.Tensor("float32", part, [1, part.length]);
            const output = await session.run({ [session.inputNames[0]]: input });
            const logits = output[session.outputNames[0]];
            if (logits.dims.length !== 3 || logits.dims[0] !== 1 || logits.dims[2] !== Object.keys(vocab).length) {
                throw new Error("Model output must have shape [1, frames, vocabulary size].");
            }
            observations.push(decodeCtc(logits.data, logits.dims[1], vocab));
            self.postMessage({ progress: `${i + 1} / ${blobs.length} slices analyzed` });
        }
        self.postMessage({ pairs: phoneCandidates(cards, blobs, observations, language), observations });
    } catch (error) {
        self.postMessage({ error: error.message || String(error) });
    } finally {
        await session?.release();
    }
};
