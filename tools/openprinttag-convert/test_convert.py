import json
import os
import unittest

import convert

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.result = convert.convert(FIXTURES, commit="abc1234", date="2026-06-10")

    def test_header(self):
        self.assertEqual(self.result["schema"], 1)
        self.assertEqual(self.result["source"], "OpenPrintTag/openprinttag-database")
        self.assertEqual(self.result["commit"], "abc1234")
        self.assertEqual(self.result["date"], "2026-06-10")
        self.assertEqual(self.result["count"], 2)  # SLA filtered out
        self.assertEqual(len(self.result["entries"]), 2)

    def test_fff_filter_drops_sla(self):
        slugs = [e["s"] for e in self.result["entries"]]
        self.assertNotIn("testbrand-resin-grey", slugs)

    def test_full_entry_fields(self):
        e = next(x for x in self.result["entries"] if x["s"] == "testbrand-pla-azure")
        self.assertEqual(e["b"], "Test Brand")       # display name from brands/
        self.assertEqual(e["n"], "PLA Azure")
        self.assertEqual(e["m"], "PLA")
        self.assertEqual(e["h"], "#008FBE")           # alpha stripped, uppercased
        self.assertEqual(e["td"], 5.5)
        self.assertEqual(e["ri"], 1.46)
        self.assertEqual(e["d"], 1.24)
        self.assertEqual(e["nl"], 205)
        self.assertEqual(e["nh"], 225)
        self.assertEqual(e["bl"], 40)
        self.assertEqual(e["bh"], 60)
        self.assertNotIn("mr", e)                     # canonical == raw → omitted

    def test_no_colour_entry_kept_and_pa6_mapped(self):
        e = next(x for x in self.result["entries"] if x["s"] == "testbrand-pa6-natural")
        self.assertNotIn("h", e)                      # nulls omitted entirely
        self.assertEqual(e["m"], "PA")                # canonical
        self.assertEqual(e["mr"], "PA6")              # raw kept when it differs
        self.assertNotIn("td", e)
        self.assertNotIn("nl", e)
        self.assertEqual(e["d"], 1.14)

    def test_entries_sorted_by_brand_then_name(self):
        entries = self.result["entries"]
        keys = [(x["b"].lower(), x["n"].lower()) for x in entries]
        self.assertEqual(keys, sorted(keys))


if __name__ == "__main__":
    unittest.main()
