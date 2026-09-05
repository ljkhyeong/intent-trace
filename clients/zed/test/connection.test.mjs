import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { endpoint } from '../intent-trace.mjs';

const script = fileURLToPath(new URL('../intent-trace.mjs', import.meta.url));
const token = `its_${'x'.repeat(43)}`;

test('설정에 토큰을 넣지 않고 절대 실행 경로를 생성한다', () => {
  const result = spawnSync(process.execPath, [script, 'config'], {
    env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: token }, encoding: 'utf8',
  });
  assert.equal(result.status, 0);
  const config = JSON.parse(result.stdout).context_servers['intent-trace'];
  assert.equal(config.command, process.execPath);
  assert.equal(config.args[0], script);
  assert.deepEqual(config.env, {});
  assert.ok(!result.stdout.includes(token));
});

test('원격 HTTP와 인증 정보가 포함된 주소를 거부한다', () => {
  for (const value of ['http://example.com/mcp', 'https://user:secret@example.com/mcp',
    'https://example.com/mcp?token=secret', 'https://example.com/mcp#secret', 'https://example.com/other']) {
    assert.throws(() => endpoint(value));
  }
  assert.equal(endpoint('https://example.com/mcp').protocol, 'https:');
  assert.equal(endpoint('http://[::1]:8080/mcp').hostname, '[::1]');
});

test('인증 실패 응답의 원문과 토큰을 로그로 내보내지 않는다', { timeout: 15_000 }, async () => {
  const server = createServer((request, response) => {
    response.writeHead(401, { 'Content-Type': 'text/plain' });
    response.end(request.headers.authorization);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const child = spawn(process.execPath, [script, 'serve', `http://127.0.0.1:${server.address().port}/mcp`], {
    env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: token }, stdio: ['pipe', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', bytes => { output += bytes; });
  child.stderr.on('data', bytes => { output += bytes; });
  try {
    const code = await new Promise(resolve => child.once('close', resolve));
    assert.equal(code, 1);
    assert.ok(!output.includes(token));
    assert.match(output, /연결을 완료하지 못했습니다/);
  } finally {
    child.kill();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
