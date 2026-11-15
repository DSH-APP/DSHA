"""Budget boundary tests; no archive generation, guest runtime or device access."""
import json
import unittest
import recovery_descriptor_limits as budget


class RecoveryDescriptorBudgetTests(unittest.TestCase):
    def descriptor(self, **extra):
        return dict(files=[dict(path='file-' + str(i), sha256='a'*64, bytes=0) for i in range(6)], links=[], **extra)

    def serialized(self, value):
        return json.dumps(value, separators=(',', ':')).encode()

    def test_serialized_budget_is_exact_and_applies_before_json_parse(self):
        value = self.descriptor(padding='')
        value['padding'] = 'x' * (budget.MAX_BYTES - len(self.serialized(value)))
        self.assertEqual(len(self.serialized(value)), budget.MAX_BYTES)
        budget.validate(self.serialized(value))
        with self.assertRaisesRegex(ValueError, 'RECOVERY_DESCRIPTOR_BYTE_LIMIT'):
            budget.validate(self.serialized(value) + b' ')

    def test_proof_rows_include_links_in_the_shared_budget(self):
        value = self.descriptor()
        value['links'] = [dict(path='x', target='y')] * (budget.MAX_ENTRIES - 6)
        budget.validate(self.serialized(value))
        value['links'].append(dict(path='x', target='y'))
        with self.assertRaisesRegex(ValueError, 'RECOVERY_DESCRIPTOR_ENTRY_LIMIT'):
            budget.validate(self.serialized(value))

    def test_path_byte_depth_and_escape_budgets(self):
        budget.path('a' * 4096)
        for bad in ['中' * 1366, 'a/' * 128 + 'a', '../x', '/x', 'a//b', 'a/./b', 'a\\b', 'a\0b']:
            with self.subTest(path_shape=len(bad)), self.assertRaises(ValueError):
                budget.path(bad)

    def test_constant_source_ignores_comments_and_rejects_expressions_or_duplicate_literals(self):
        source = budget.SOURCE.read_text(encoding='utf8')
        self.assertEqual(budget.read_limits('/*\npublic static final int MAX_BYTES = 1;\n*/\n' + source)['MAX_BYTES'], 8388608)
        for changed in [source.replace('MAX_BYTES = 8388608;', 'MAX_BYTES = 8 * 1024 * 1024;'), source + '\npublic static final int MAX_BYTES = 1;\n']:
            with self.assertRaisesRegex(ValueError, 'RECOVERY_DESCRIPTOR_BUDGET_SOURCE'):
                budget.read_limits(changed)


if __name__ == '__main__':
    unittest.main()
