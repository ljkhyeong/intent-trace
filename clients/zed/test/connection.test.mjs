import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { endpoint } from '../intent-trace.mjs';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { retryAfterSeconds, safeFailure } from '../errors.mjs';

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
  const invalid = spawnSync(process.execPath, [script, 'serve'], {
    env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: 'invalid-session-for-test' }, encoding: 'utf8',
  });
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /INTENT_TRACE_SESSION_TOKEN 환경 변수/);
  assert.ok(!invalid.stderr.includes('invalid-session-for-test'));
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
    assert.match(output, /다시 로그인해 받은 세션 토큰/);
    assert.match(output, /INTENT_TRACE_ERROR AUTHENTICATION_REQUIRED/);
  } finally {
    child.kill();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('연결 후 인증과 호출 제한 및 서버 장애를 구분하고 원문을 버린다', { timeout: 15_000 }, async () => {
  let status = 401;
  const server = createServer(async (request, response) => {
    if (request.method !== 'POST') { response.writeHead(405).end(); return; }
    let body = ''; for await (const chunk of request) body += chunk;
    const message = JSON.parse(body);
    if (message.id === undefined) { response.writeHead(202).end(); return; }
    if (message.method === 'tools/call') {
      response.writeHead(status, { 'Content-Type': 'text/plain', 'Retry-After': '120' });
      response.end(token); return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result: {
      protocolVersion: message.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: '오류 검증', version: '1' },
    } }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const client = new Client({ name: '검증', version: '1' });
  const transport = new StdioClientTransport({ command: process.execPath, args: [script, 'serve', `http://127.0.0.1:${server.address().port}/mcp`], env: { INTENT_TRACE_SESSION_TOKEN: token }, stderr: 'pipe' });
  transport.stderr?.resume();
  try {
    await client.connect(transport);
    for (const [httpStatus, code] of [[401, 'AUTHENTICATION_REQUIRED'], [403, 'ACCESS_DENIED'], [429, 'RATE_LIMITED'], [502, 'UPSTREAM_UNAVAILABLE']]) {
      status = httpStatus;
      await assert.rejects(client.callTool({ name: 'probe', arguments: {} }), error => {
        assert.equal(error.data.code, code);
        assert.equal(error.data.retryAfterSeconds, status === 429 ? 120 : undefined);
        assert.ok(!JSON.stringify(error).includes(token));
        assert.ok(!error.message.includes(token));
        if (status === 502) assert.match(error.message, /IntentTrace 서버 오류가 발생했습니다/);
        return true;
      });
    }
  } finally {
    await client.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});

test('연결 점검 초기화 실패도 대기 시간과 안전한 분류를 남긴다', { timeout: 15_000 }, async () => {
  const server = createServer((request, response) => {
    response.writeHead(429, { 'Retry-After': '120' }); response.end(token);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const child = spawn(process.execPath, [script, 'check', `http://127.0.0.1:${server.address().port}/mcp`], { env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  try {
    assert.equal(await new Promise(resolve => child.once('close', resolve)), 1);
    assert.match(output, /120초 후/); assert.match(output, /RATE_LIMITED/); assert.ok(!output.includes(token));
  } finally {
    child.kill(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});

test('대기 시간 형식을 제한하고 시간 초과는 변경 상태 확인을 안내한다', () => {
  assert.equal(retryAfterSeconds('120'), 120);
  assert.equal(retryAfterSeconds('Sat, 05 Sep 2026 00:02:00 GMT', Date.parse('2026-09-05T00:00:00Z')), 120);
  for (const input of ['-1', '1e2', 'Infinity', '99999999', token]) assert.equal(retryAfterSeconds(input), undefined);
  assert.match(safeFailure({ code: -32001 }).message, /다시 보내기 전에 기록·게시 상태/);
});
