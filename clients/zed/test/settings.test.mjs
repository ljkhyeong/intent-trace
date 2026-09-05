import test from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, statSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
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
    '{"context_servers":{"intent-trace":{},"intent-trace":{}}}']) assert.throws(() => prepareSettings(content, entry));
});
