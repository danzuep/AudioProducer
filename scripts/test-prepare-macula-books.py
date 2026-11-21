import csv
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class PrepareBooksTest(unittest.TestCase):
    def test_preserves_legacy_forms_and_rejects_missing_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "words.tsv"
            xml_dir = root / "xml"
            output = root / "books"
            xml_dir.mkdir()
            output.mkdir()
            with source.open("w", encoding="utf-8", newline="") as file:
                writer = csv.writer(file, delimiter="\t")
                writer.writerows([["ref", "text"], ["JHN 1:1!1", "old"], ["JHN 1:1!2", "same"]])
            book = xml_dir / "john.xml"
            book.write_text(
                '<book id="JHN"><wg class="np"><w ref="JHN 1:1!1">new</w>'
                '<w ref="JHN 1:1!2">same</w></wg></book>', encoding="utf-8"
            )
            command = [sys.executable, str(Path(__file__).with_name("prepare-macula-books.py")),
                       str(source), str(xml_dir), str(output)]
            subprocess.run(command, check=True)
            words = ET.parse(output / "JHN.xml").getroot().findall(".//w")
            self.assertEqual("old", words[0].get("legacy-text"))
            self.assertEqual("new", words[0].text)
            self.assertIsNone(words[1].get("legacy-text"))
            book.write_text('<book id="JHN"><w ref="JHN 1:1!1">new</w></book>', encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Missing XML references", result.stderr)


if __name__ == "__main__":
    unittest.main()
