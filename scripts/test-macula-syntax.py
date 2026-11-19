import importlib.util
import io
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("macula_syntax", Path(__file__).with_name("macula-syntax.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SyntaxExtractionTest(unittest.TestCase):
    def test_nested_groups_keep_roles_and_xml_order_does_not_replace_references(self):
        xml = """<book><wg class="cl" Rule="ClCl"><w ref="A!2"/>
        <wg class="np" role="s" rule="DetNP"><w ref="A!1"/></wg></wg></book>"""
        paths = module.extract(io.StringIO(xml))
        self.assertEqual("1,cl,,ClCl", paths["A!2"])
        self.assertEqual("1,cl,,ClCl/2,np,s,DetNP", paths["A!1"])

    def test_duplicate_reference_is_rejected(self):
        with self.assertRaises(ValueError):
            module.extract(io.StringIO('<book><w ref="A!1"/><w ref="A!1"/></book>'))

    def test_cross_verse_group_is_marked_incomplete(self):
        xml = '<book><wg class="cl"><w ref="A!1"/><w ref="B!1"/></wg></book>'
        paths = module.extract(io.StringIO(xml))
        self.assertEqual("1,!cl,,", paths["A!1"])
        self.assertEqual("1,!cl,,", paths["B!1"])


if __name__ == "__main__":
    unittest.main()
