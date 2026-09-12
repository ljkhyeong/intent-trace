import test from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, existsSync, statSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parse } from 'jsonc-parser';
import { prepareSettings } from '../settings.mjs';

const script = fileURLToPath(new URL('../intent-trace.mjs', import.meta.url));

test('JSONC 주석과 다른 연결을 보존하고 미리보기·반복 등록·실행 경로 갱신을 처리한다', () => {
  const directory = mkdtempSync(join(tmpdir(), 'intent-trace-settings-'));
  const path = join(directory, 'settings.json');
  const original = '{\n  // 화면 설정 유지\n  "theme": "One Dark",\n  "context_servers": {\n    // 다른 서버 유지\n    "other": { "env": { "TOKEN": "other-secret" } },\n    "intent-trace": { "command": "old-node", "env": { "TOKEN": "old-secret" } },\n  },\n}\n';
  const run = (...args) => spawnSync(process.execPath, [script, 'configure', '--settings', path, ...args], { encoding: 'utf8' });
  try {
    writeFileSync(path, original);
    if (process.platform !== 'win32') chmodSync(path, 0o640);
    const preview = run();
    assert.equal(preview.status, 0, preview.stderr);
    assert.equal(readFileSync(path, 'utf8'), original);
    assert.ok(!preview.stdout.includes('secret'));
    const applied = run('--apply');
    assert.equal(applied.status, 0, applied.stderr);
    const content = readFileSync(path, 'utf8');
    if (process.platform !== 'win32') assert.equal(statSync(path).mode & 0o777, 0o640);
    assert.ok(content.includes('// 화면 설정 유지'));
    assert.ok(content.includes('// 다른 서버 유지'));
    assert.equal(parse(content).context_servers.other.env.TOKEN, 'other-secret');
    assert.equal(parse(content).context_servers['intent-trace'].command, process.execPath);
    assert.deepEqual(parse(content).context_servers['intent-trace'].env, {});
    assert.equal(run('--apply').status, 0);
    assert.equal(readFileSync(path, 'utf8'), content);
    assert.equal(run('https://intent.example/mcp', '--apply').status, 0);
    assert.equal(parse(readFileSync(path, 'utf8')).context_servers['intent-trace'].args.at(-1), 'https://intent.example/mcp');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('설정을 처음 생성하고 잘못된 JSONC와 중복 연결은 덮어쓰지 않는다', () => {
  const entry = { command: 'node', args: [], env: {} };
  assert.deepEqual(parse(prepareSettings('// 첫 설정\n', entry).text).context_servers['intent-trace'], entry);
  for (const content of ['{broken', '[]', '{"context_servers":[]}', '{"context_servers":{},"context_servers":{}}',
    '{"context_servers":{"intent-trace":{},"intent-trace":{}}}']) {
    for (const replacement of [entry, undefined]) assert.throws(() => prepareSettings(content, replacement));
  }
});

test('연결 위치와 끝 쉼표에 관계없이 다른 설정과 인접 주석을 보존하며 제거한다', () => {
  const target = '"intent-trace": { "command": "old-node", "env": { "TOKEN": "old-secret" } }';
  const other = '// 다른 연결 주석\r\n\t"other": { "env": { "TOKEN": "other-secret" } }';
  const another = '/* 추가 연결 주석 */ "another": {}';
  for (const entries of [[target], [target, other], [other, target], [other, target, another]]) {
    for (const trailing of ['', ',']) {
      const original = `// 파일 주석\r\n{\r\n\t"theme": "One Dark",\r\n\t"context_servers": { /* 연결 목록 */\r\n\t${entries.join(',\r\n\t')}${trailing}\r\n\t}\r\n}\r\n`;
      const removed = prepareSettings(original, undefined);
      const errors = [];
      const parsed = parse(removed.text, errors, { allowTrailingComma: true });
      assert.deepEqual(errors, []);
      assert.equal(removed.operation, '연결 제거');
      const expected = parse(original);
      delete expected.context_servers['intent-trace'];
      assert.deepEqual(parsed, expected);
      for (const comment of ['// 파일 주석', '/* 연결 목록 */', ...entries.filter(entry => entry !== target)]) {
        assert.ok(removed.text.includes(comment), comment);
      }
      assert.ok(!removed.text.includes('old-secret'));
      assert.deepEqual(prepareSettings(removed.text, undefined), { text: removed.text, operation: '변경 없음' });
    }
  }
  for (const text of ['', '// 아직 등록하지 않음\n', '{"theme":"One Dark"}']) {
    assert.deepEqual(prepareSettings(text, undefined), { text, operation: '변경 없음' });
  }
});

test('제거 미리보기는 파일과 비밀값을 보존하고 적용은 해당 연결만 지운다', () => {
  const directory = mkdtempSync(join(tmpdir(), 'intent-trace-unconfigure-'));
  const path = join(directory, 'settings.json');
  const original = '{"context_servers":{"other":{"env":{"TOKEN":"other-secret"}},"intent-trace":{"env":{"TOKEN":"old-secret"}}}}';
  const env = { ...process.env, INTENT_TRACE_MCP_URL: 'invalid-address', INTENT_TRACE_SESSION_TOKEN: '' };
  const run = (...args) => spawnSync(process.execPath, [script, 'unconfigure', '--settings', path, ...args], { env, encoding: 'utf8' });
  try {
    writeFileSync(path, original);
    if (process.platform !== 'win32') chmodSync(path, 0o640);
    const preview = run();
    assert.equal(preview.status, 0, preview.stderr);
    assert.match(preview.stdout, /연결 제거 미리보기/);
    assert.equal(readFileSync(path, 'utf8'), original);
    for (const args of [['https://unexpected.example/mcp', '--apply'], ['--unknown', '--apply']]) {
      const failed = run(...args);
      assert.equal(failed.status, 1);
      assert.match(failed.stderr, /unconfigure/);
      assert.equal(readFileSync(path, 'utf8'), original);
    }
    const applied = run('--apply');
    assert.equal(applied.status, 0, applied.stderr);
    assert.deepEqual(parse(readFileSync(path, 'utf8')), { context_servers: { other: { env: { TOKEN: 'other-secret' } } } });
    if (process.platform !== 'win32') assert.equal(statSync(path).mode & 0o777, 0o640);
    const unchanged = run('--apply');
    assert.equal(unchanged.status, 0, unchanged.stderr);
    assert.match(unchanged.stdout, /변경 없음/);
    for (const result of [preview, applied, unchanged]) assert.ok(!`${result.stdout}${result.stderr}`.includes('secret'));
    const missing = join(directory, 'missing', 'settings.json');
    const absent = spawnSync(process.execPath, [script, 'unconfigure', '--settings', missing, '--apply'], { env, encoding: 'utf8' });
    assert.equal(absent.status, 0, absent.stderr);
    assert.match(absent.stdout, /변경 없음/);
    assert.equal(existsSync(join(directory, 'missing')), false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
