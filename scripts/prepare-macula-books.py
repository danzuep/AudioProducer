#!/usr/bin/env python3
"""Preserve historical recording forms while bundling the lowfat XML trees."""
import csv
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

source, xml_dir, output = map(Path, sys.argv[1:])
with source.open(encoding="utf-8", newline="") as file:
    forms = {}
    for row in csv.DictReader(file, delimiter="\t"):
        ref = row["ref"]
        if ref in forms:
            raise ValueError(f"Duplicate TSV reference: {ref}")
        forms[ref] = row["text"]

seen = set()
for path in sorted(xml_dir.glob("*.xml")):
    tree = ET.parse(path)
    root = tree.getroot()
    if root.tag != "book":
        raise ValueError(f"Expected a book in {path}")
    for word in root.iter("w"):
        ref = word.attrib["ref"]
        if ref in seen or ref not in forms:
            raise ValueError(f"Duplicate or unexpected XML reference: {ref}")
        seen.add(ref)
        surface = word.get("text", (word.text or "").strip())
        if surface != forms[ref]:
            word.set("legacy-text", forms[ref])
    tree.write(output / f"{root.attrib['id']}.xml", encoding="utf-8", xml_declaration=True)

if seen != forms.keys():
    raise ValueError(f"Missing XML references: {len(forms.keys() - seen)}")
