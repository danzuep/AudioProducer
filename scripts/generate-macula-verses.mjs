import { strict as assert } from "node:assert";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [source, output] = process.argv.slice(2);
const plan = JSON.parse(readFileSync(join(output, "recording-plan.json"), "utf8"));
const books = new Set([
  "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH",
  "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS",
  "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV"
]);
assert.equal(plan.summary.tokens, 133115, "Expected the full pinned Greek New Testament");
assert.equal(plan.summary.cards, 4917);
assert.equal(plan.summary.coverage, 1);
assert.equal(plan.cards.length, plan.summary.cards);
assert.deepEqual(new Set(plan.stages.map((stage) => stage.book)), books);

const [header, ...lines] = readFileSync(source, "utf8").trim().split("\n");
const columns = header.split("\t");
const fields = ["ref", "text", "lemma", "class", "type", "role", "english", "case", "mood", "mandarin", "morph"];
const indexes = fields.map((field) => {
  const index = columns.indexOf(field);
  assert.notEqual(index, -1, `Missing TSV column: ${field}`);
  return index;
});
const rowsByBook = new Map([...books].map((book) => [book, []]));
for (const line of lines) {
  const cells = line.split("\t");
  const row = indexes.map((index) => cells[index] ?? "");
  const book = row[0].split(" ")[0];
  assert.ok(rowsByBook.has(book), `Unexpected book: ${book}`);
  rowsByBook.get(book).push(row);
}
assert.equal(lines.length, plan.summary.tokens + plan.summary.proper_nouns_excluded);
const verses = join(output, "books");
mkdirSync(verses, { recursive: true });
for (const [book, rows] of rowsByBook) {
  assert.ok(rows.length, `Missing book: ${book}`);
  writeFileSync(join(verses, `${book}.json`), JSON.stringify(rows));
}
console.log(`Generated verse data for ${rowsByBook.size} books and ${lines.length} tokens.`);
