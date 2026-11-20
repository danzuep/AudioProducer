#!/usr/bin/env python3
"""Extract word-group ancestry from pinned MACULA lowfat XML."""
import json
import sys
import xml.etree.ElementTree as ET

def extract(source):
    stack = []
    paths = {}
    group_verses = {}
    next_id = 0
    for event, element in ET.iterparse(source, events=("start", "end")):
        if event == "start" and element.tag == "wg":
            next_id += 1
            stack.append((next_id, element.get("class", ""), element.get("role", ""),
                          element.get("rule", element.get("Rule", ""))))
            group_verses[next_id] = set()
        elif event == "end":
            if element.tag == "w" and element.get("ref"):
                ref = element.get("ref")
                if ref in paths:
                    raise ValueError(f"Duplicate XML reference: {ref}")
                paths[ref] = list(stack)
                for group_id, _, _, _ in stack:
                    group_verses[group_id].add(ref.split("!", 1)[0])
            if element.tag == "wg":
                stack.pop()
            element.clear()
    return {
        ref: "/".join(
            f"{group_id},{'!' if len(group_verses[group_id]) > 1 else ''}{category},{role},{rule}"
            for group_id, category, role, rule in path
        )
        for ref, path in paths.items()
    }


if __name__ == "__main__":
    source, output = sys.argv[1:]
    with open(output, "w", encoding="utf-8") as file:
        json.dump(extract(source), file, ensure_ascii=False, separators=(",", ":"))
