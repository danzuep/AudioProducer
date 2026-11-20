#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
output="$1"
source_dir="$root/app/build/macula-source"
macula_revision=dcfd3c024b3a00b51fcabc7e154129958500c1f9
greek_revision=8423afe47b9e8f24b7772e808af45c7159a6fe7e

mkdir -p "$source_dir/scripts" "$source_dir/site/static" "$output"
for file in LICENSE scripts/generate-recording-plan.mjs scripts/macula-tokens.mjs site/static/recording-prompts.js; do
  curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 --silent --show-error \
    "https://raw.githubusercontent.com/danzuep/MaculaScripta/$macula_revision/$file" \
    --output "$source_dir/$file"
done
cp "$source_dir/LICENSE" "$output/MaculaScripta-LICENSE.txt"
curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 --silent --show-error \
  "https://raw.githubusercontent.com/Clear-Bible/macula-greek/$greek_revision/SBLGNT/tsv/macula-greek-SBLGNT.tsv" \
  --output "$source_dir/macula-greek-SBLGNT.tsv"

curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 --silent --show-error \
  "https://raw.githubusercontent.com/Clear-Bible/macula-greek/$greek_revision/SBLGNT/lowfat/sblgntlowfat.xml" \
  --output "$source_dir/lowfat-index.xml"
mkdir -p "$source_dir/lowfat" "$source_dir/syntax"
python3 - "$source_dir/lowfat-index.xml" <<'PY' | while IFS= read -r file; do
import sys
import xml.etree.ElementTree as ET
for child in ET.parse(sys.argv[1]).getroot():
    print(child.attrib["href"])
PY
  curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 --silent --show-error \
    "https://raw.githubusercontent.com/Clear-Bible/macula-greek/$greek_revision/SBLGNT/lowfat/$file" \
    --output "$source_dir/lowfat/$file"
  python3 "$root/scripts/macula-syntax.py" "$source_dir/lowfat/$file" "$source_dir/syntax/${file%.xml}.json"
done

printf '%s\n' '{"type":"module"}' > "$source_dir/package.json"
node "$source_dir/scripts/generate-recording-plan.mjs" \
  "$source_dir/macula-greek-SBLGNT.tsv" lemma "$output/recording-plan.json"
node "$root/scripts/generate-macula-verses.mjs" \
  "$source_dir/macula-greek-SBLGNT.tsv" "$output" "$source_dir/syntax"
