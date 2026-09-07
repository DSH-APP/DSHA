// 关键兼容范围是业务断言；再与官方 npm 包进行覆盖更多组合的差分检查。
const assert = require('node:assert/strict');
const semver = require('../app/src/main/assets/plugin-semver.cjs');
const reference = require(process.argv[2]);
const cases = [
  ['0.5.0', '^0', true], ['1.8.0', '~1', true], ['1.2.9', '1.2', true],
  ['0.1.2-rc.1', '^0.1.1-alpha', false], ['0.1.2-rc.1', '^0.1.2-rc.0', true],
  ['0.1.2-rc.1', '*', false], ['1.2.3', '1.2 - 1.3', true],
  ['1.3.0', '1.2.x', false], ['2.0.0', '^1 || >=2 <3', true],
  ['0.0.4', '^0.0.3', false], ['1.2.3+build.4', '=1.2.3', true],
];
for (const [version, range, expected] of cases) assert.equal(semver.satisfies(version, range), expected, `${version} ${range}`);
let checked = 0;
for (const major of [0, 1, 2, 10]) for (const minor of [0, 1, 2, 9]) for (const patch of [0, 1, 3])
for (const suffix of ['', '-alpha.0', '-rc.1', '+build.2'])
for (const range of ['*', '', '^0', '^0.0', '~1', '1.2', '1.x', '>= 1.0.0 < 2', '1.2 - 2.0', '^0.1.1-alpha', '^0.1.2-rc.0', 'bad', '^1 || >=2 <3']) {
  const version = `${major}.${minor}.${patch}${suffix}`;
  assert.equal(semver.validRange(range), reference.validRange(range));
  assert.equal(semver.satisfies(version, range), reference.satisfies(version, range), `${version} ${range}`);
  checked++;
}
console.log(`SemVer: ${cases.length} assertions and ${checked} npm comparisons passed`);
