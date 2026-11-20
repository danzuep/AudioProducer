function clusters(text) {
    const result = [];
    for (const char of text.toLowerCase().normalize("NFD")) {
        if (/\p{M}/u.test(char) && result.length) result[result.length - 1].marks += char;
        else result.push({ base: char, marks: "" });
    }
    return result;
}

export function greekPhones(text, koine = false) {
    const letters = clusters(text), out = [];
    const consonants = koine
        ? { β: "v", γ: "ɣ", δ: "ð", ζ: "z", θ: "θ", κ: "k", λ: "l", μ: "m", ν: "n", ξ: "ks", π: "p", ρ: "r", σ: "s", ς: "s", τ: "t", φ: "f", χ: "x", ψ: "ps" }
        : { β: "b", γ: "ɡ", δ: "d", ζ: "zd", θ: "th", κ: "k", λ: "l", μ: "m", ν: "n", ξ: "ks", π: "p", ρ: "r", σ: "s", ς: "s", τ: "t", φ: "ph", χ: "kh", ψ: "ps" };
    const vowels = koine ? { α: "a", ε: "e", η: "i", ι: "i", ο: "o", υ: "i", ω: "o" }
        : { α: "a", ε: "e", η: "ɛ", ι: "i", ο: "o", υ: "y", ω: "ɔ" };
    const diphthongs = koine
        ? { αι: "e", ει: "i", οι: "i", ου: "u", υι: "i" }
        : { αι: "ai", ει: "e", οι: "oi", αυ: "au", ευ: "eu", ηυ: "ɛu", ου: "u", υι: "yi" };
    for (let i = 0; i < letters.length; i++) {
        const current = letters[i], next = letters[i + 1], pair = current.base + (next?.base || "");
        const wordStart = i === 0 || !/\p{L}/u.test(letters[i - 1].base);
        if (wordStart && !koine && (current.marks.includes("\u0314") || (next && next.marks.includes("\u0314") && diphthongs[pair]))) out.push("h");
        if (next && !current.marks.includes("\u0308") && !next.marks.includes("\u0308")) {
            if (koine && (pair === "αυ" || pair === "ευ" || pair === "ηυ")) {
                const after = letters[i + 2]?.base || "";
                out.push((pair === "αυ" ? "a" : "e") + ("θκξπστφχψ".includes(after) ? "f" : "v"));
                i++; continue;
            }
            if (diphthongs[pair]) { out.push(diphthongs[pair]); i++; continue; }
        }
        if (current.base === "γ" && next && "γκξχ".includes(next.base)) {
            out.push("n");
            continue;
        }
        if (vowels[current.base]) {
            out.push(vowels[current.base]);
            if (!koine && current.marks.includes("\u0345")) out.push("i");
        } else if (consonants[current.base]) out.push(consonants[current.base]);
    }
    return out.join("");
}

export function hebrewPhones(text, modern = false) {
    const letters = clusters(text), out = [];
    if (!letters.some(letter => /[\u05b0-\u05bb\u05c7]/u.test(letter.marks) ||
        (letter.base === "ו" && letter.marks.includes("\u05bc")))) return "";
    const vowels = { "\u05b0": "ə", "\u05b1": "e", "\u05b2": "a", "\u05b3": "o",
        "\u05b4": "i", "\u05b5": "e", "\u05b6": "ɛ", "\u05b7": "a",
        "\u05b8": "a", "\u05b9": "o", "\u05ba": "o", "\u05bb": "u", "\u05c7": "o" };
    const consonants = { א: "ʔ", ב: "v", ג: "ɣ", ד: "ð", ה: "h", ו: "v", ז: "z",
        ח: modern ? "x" : "ħ", ט: "t", י: "j", כ: "x", ך: "x", ל: "l", מ: "m", ם: "m",
        נ: "n", ן: "n", ס: "s", ע: modern ? "ʔ" : "ʕ", פ: "f", ף: "f", צ: "ts", ץ: "ts",
        ק: modern ? "k" : "q", ר: "r", ש: "ʃ", ת: modern ? "t" : "θ" };
    for (let i = 0; i < letters.length; i++) {
        const { base, marks } = letters[i];
        if (!consonants[base]) continue;
        const previous = letters[i - 1];
        const previousVowel = previous && /[\u05b4-\u05ba]/u.test(previous.marks);
        if (previousVowel && !/[\u05b0-\u05bc]/u.test(marks) &&
            (base === "א" || base === "י" || base === "ו" || (base === "ה" && i === letters.length - 1))) continue;
        if (base === "ו" && marks.includes("\u05bc") && !/[\u05b0-\u05bb]/u.test(marks)) {
            out.push("u"); continue;
        }
        let sound = consonants[base];
        if (base === "ש" && marks.includes("\u05c2")) sound = "s";
        if (marks.includes("\u05bc")) sound = { ב: "b", ג: "ɡ", ד: "d", כ: "k", ך: "k", פ: "p", ף: "p", ת: "t" }[base] || sound;
        if (modern && base === "ג") sound = "ɡ";
        if (modern && base === "ד") sound = "d";
        out.push(sound);
        for (const mark of marks) if (vowels[mark]) out.push(vowels[mark]);
    }
    return out.join("");
}

export function normalizePhones(text) {
    return text.toLowerCase().normalize("NFD").replace(/ɡ/g, "g").replace(/[ɾɹ]/g, "r")
        .replace(/ʰ/g, "h").replace(/[\p{M}ːˑˈˌ0-9\s.]/gu, "")
        .replace(/[^\p{L}]/gu, "");
}

export function editDistance(a, b) {
    let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 0; i < a.length; i++) {
        const current = [i + 1];
        for (let j = 0; j < b.length; j++) {
            const close = ["oɔ", "eɛ", "iɪ", "uʊ", "aɑɐ"].some(group => group.includes(a[i]) && group.includes(b[j]));
            current.push(Math.min(current[j] + 1, previous[j + 1] + 1,
                previous[j] + (a[i] === b[j] ? 0 : close ? .15 : 1)));
        }
        previous = current;
    }
    return previous[b.length];
}

export function phoneCandidates(cards, blobs, observations, language) {
    const expected = cards.map(card => normalizePhones(language === "greek-koine" ? greekPhones(card.ancient, true)
        : language === "greek-classical" ? greekPhones(card.ancient) : hebrewPhones(card.ancient, language === "hebrew-modern")));
    const actual = observations.map(normalizePhones);
    const available = blobs.map(blob => !cards.some(card => card.spans.includes(blob)));
    const score = expected.map((word, i) => actual.map((heard, j) => {
        if (!word || !heard || cards[i].spans.length || !available[j]) return Infinity;
        return editDistance(word, heard) / Math.max(word.length, heard.length);
    }));
    const result = [];
    for (let i = 0; i < cards.length; i++) {
        const best = score[i].map((value, j) => ({ value, j })).sort((a, b) => a.value - b.value);
        if (!best.length || !Number.isFinite(best[0].value)) continue;
        const alternatives = score.map(row => row[best[0].j]).filter((_, index) => index !== i);
        const competing = Math.min(...alternatives, Infinity), next = best[1]?.value ?? Infinity;
        const long = expected[i].length >= 5, max = long ? .25 : .1, margin = long ? .15 : .2;
        if (best[0].value <= max && next - best[0].value >= margin && competing - best[0].value >= margin) {
            result.push({ cardId: cards[i].id, blobId: blobs[best[0].j].id, score: best[0].value, long });
        }
    }
    return result.sort((a, b) => Number(b.long) - Number(a.long) || a.score - b.score);
}

export function decodeCtc(data, frames, vocab) {
    const tokens = Object.entries(vocab);
    if (tokens.length === 0 || tokens.some(([, id]) => !Number.isInteger(id) || id < 0 || id >= tokens.length) ||
        new Set(tokens.map(([, id]) => id)).size !== tokens.length || !Number.isInteger(vocab["<pad>"])) {
        throw new Error("Invalid phoneme vocabulary.");
    }
    if (data.length !== frames * tokens.length) throw new Error("Model output does not match the vocabulary.");
    const byId = Array(tokens.length);
    for (const [token, id] of tokens) byId[id] = token;
    let last = -1, result = "";
    for (let i = 0; i < frames; i++) {
        if (!Number.isFinite(data[i * tokens.length])) throw new Error("The model returned invalid logits.");
        let best = 0;
        for (let j = 1; j < tokens.length; j++) {
            const value = data[i * tokens.length + j];
            if (!Number.isFinite(value)) throw new Error("The model returned invalid logits.");
            if (value > data[i * tokens.length + best]) best = j;
        }
        const token = byId[best];
        if (best !== last && best !== vocab["<pad>"] && !token.startsWith("<")) result += token;
        last = best;
    }
    return result;
}
