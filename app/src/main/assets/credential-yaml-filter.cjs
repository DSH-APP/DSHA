// Data-only YAML AST transform. No credential values are written to diagnostics.
const fs = require('node:fs');
const yaml = require(process.argv[2]);
try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  if (typeof input.text !== 'string' || input.text.length > 1024 * 1024 || !Array.isArray(input.prefixes)) throw Error();
  const doc = yaml.parseDocument(input.text, { uniqueKeys: true });
  if (doc.errors.length || doc.warnings.length || !yaml.isMap(doc.contents)) throw Error();
  let alias = false;
  yaml.visit(doc, (_key, node) => { if (yaml.isAlias(node)) alias = true; });
  if (alias) throw Error();
  const records = doc.get('records', true), removed = [];
  if (records !== undefined && !(yaml.isScalar(records) && records.value === null)) {
    if (!yaml.isMap(records)) throw Error();
    for (const pair of [...records.items]) {
      if (!yaml.isScalar(pair.key) || typeof pair.key.value !== 'string') throw Error();
      const name = pair.key.value;
      if (input.prefixes.some(prefix => typeof prefix === 'string' && name.startsWith(prefix))) {
        records.delete(name); removed.push(name);
      }
    }
  }
  process.stdout.write(JSON.stringify({ text: removed.length ? doc.toString() : input.text, removed }));
} catch { process.stderr.write('CREDENTIAL_TRIM_FAILED\n'); process.exitCode = 65; }
