import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, rm, writeFile, appendFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from 'vscode-languageserver/node';
import { lineContext, renderHover, repositoryKey } from '../lsp.mjs';

const script = fileURLToPath(new URL('../intent-trace.mjs', import.meta.url));
const token = `its_${'x'.repeat(43)}`;

async function repository(remote = 'git@github.com:Acme/Intent-Trace.git') {
  const root = await mkdtemp(join(tmpdir(), 'intent-trace-lsp-'));
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'user.name=test', '-c', 'user.email=test@example.com', ...args], { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-q');
  if (remote) git('remote', 'add', 'origin', remote);
  await writeFile(join(root, 'App.kt'), 'fun main() {}\nval answer = 42\n');
  git('add', '.');
  git('commit', '-q', '-m', 'init');
  return { root, file: join(root, 'App.kt'), revision: git('rev-parse', 'HEAD') };
}

test('GitHub 원격 주소는 IntelliJ와 같은 규칙으로 소문자 owner/repository로 바꾼다', () => {
  for (const [remote, expected] of [
    ['git@github.com:Acme/Intent-Trace.git', 'acme/intent-trace'],
    ['https://github.com/acme/intent-trace', 'acme/intent-trace'],
    ['ssh://git@github.com/acme/intent-trace.git', 'acme/intent-trace'],
    ['https://gitlab.com/acme/intent-trace.git', null],
    ['https://github.com/acme/intent-trace/tree/main', null],
    ['git@github.com:acme/bad%name.git', null],
  ]) assert.equal(repositoryKey(remote), expected, remote);
});

test('커밋된 파일만 저장소·HEAD·상대 경로를 계산하고 수정·미추적·GitHub 외 저장소는 조회하지 않는다', async () => {
  const { root, file, revision } = await repository();
  try {
    assert.deepEqual(await lineContext(file), { repositoryKey: 'acme/intent-trace', revision, relativePath: 'App.kt' });
    await writeFile(join(root, 'New.kt'), 'class New\n');
    assert.equal(await lineContext(join(root, 'New.kt')), null);
    await appendFile(file, '// 수정\n');
    assert.equal(await lineContext(file), null);
    assert.equal(await lineContext(join(tmpdir(), 'not-in-repository.kt')), null);
  } finally { await rm(root, { recursive: true, force: true }); }
  const other = await repository('https://gitlab.com/acme/intent-trace.git');
  try { assert.equal(await lineContext(other.file), null); } finally { await rm(other.root, { recursive: true, force: true }); }
});

test('hover는 기록 문구를 Markdown으로 해석하지 않고 3건과 웹 이력 링크만 표시한다', () => {
  const record = id => ({ id, title: `[클릭](https://evil.example) **${id}**`, status: 'PUBLISHED', createdBy: { login: 'lim' },
    decisions: [{ summary: '줄\n바꿈과 <b>태그</b>' }] });
  const context = { repositoryKey: 'acme/intent-trace', revision: 'a'.repeat(40), relativePath: 'src/App.kt' };
  const markdown = renderHover({ items: ['r1', 'r2', 'r3', 'r4'].map(record), truncated: true }, context, 7, new URL('https://trace.example/mcp'));
  assert.ok(!markdown.includes('[클릭](https://evil.example)'));
  assert.ok(markdown.includes('\\[클릭\\]\\(https://evil\\.example\\)'));
  assert.ok(!markdown.includes('<b>'));
  assert.equal((markdown.match(/\[기록 보기\]/g) ?? []).length, 3);
  assert.ok(markdown.includes('(https://trace.example/records/r1)'));
  assert.ok(markdown.includes('https://trace.example/records/history?repositoryKey=acme%2Fintent-trace&revision=' + 'a'.repeat(40) + '&path=src%2FApp.kt&line=7'));
  assert.equal(renderHover({ items: [], truncated: false }, context, 7, new URL('https://trace.example/mcp')), null);
});

test('언어 서버는 파일 단위 확인 뒤 줄을 조회해 캐시하고 편집 중 파일과 호출 제한에서는 서버를 다시 부르지 않는다', { timeout: 30_000 }, async () => {
  const { root, file, revision } = await repository();
  const requests = [];
  let status = 200;
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://localhost');
    requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), authorization: request.headers.authorization });
    if (status !== 200) {
      response.writeHead(status, { 'Retry-After': '120' });
      return response.end(`private ${request.headers.authorization}`);
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(url.pathname.endsWith('/lookup')
      ? { items: [{ id: 'record-1', title: '세션 저장', status: 'PUBLISHED', createdBy: { login: 'lim' }, decisions: [{ summary: 'PasswordSafe 사용' }] }], truncated: false }
      : { items: [{ id: 'record-1' }], nextCursor: null }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const child = spawn(process.execPath, [script, 'lsp', `http://127.0.0.1:${server.address().port}/mcp`], {
    env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: token }, stdio: ['pipe', 'pipe', 'pipe'],
  });
  let errors = '';
  child.stderr.on('data', bytes => { errors += bytes; });
  const client = createMessageConnection(new StreamMessageReader(child.stdout), new StreamMessageWriter(child.stdin));
  client.listen();
  const uri = pathToFileURL(file).href;
  const hover = line => client.sendRequest('textDocument/hover', { textDocument: { uri }, position: { line, character: 0 } });
  try {
    const initialized = await client.sendRequest('initialize', { processId: null, rootUri: pathToFileURL(root).href, capabilities: {} });
    assert.equal(initialized.capabilities.hoverProvider, true);
    client.sendNotification('initialized', {});
    client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'kotlin', version: 1, text: 'fun main() {}\n' } });

    const first = await hover(1);
    assert.match(first.contents.value, /세션 저장/);
    assert.match(first.contents.value, /App\\\.kt:2/);
    assert.deepEqual(requests.map(request => request.path), ['/api/v1/change-records', '/api/v1/change-records/lookup']);
    assert.deepEqual(requests[0].query, { repositoryKey: 'acme/intent-trace', scope: 'TEAM', path: 'App.kt', limit: '1' });
    assert.deepEqual(requests[1].query, { repositoryKey: 'acme/intent-trace', revision, path: 'App.kt', line: '2' });
    assert.ok(requests.every(request => request.authorization === `Bearer ${token}`));

    await hover(1);
    assert.equal(requests.length, 2);
    await hover(0);
    assert.deepEqual(requests.slice(2).map(request => request.path), ['/api/v1/change-records/lookup']);

    client.sendNotification('textDocument/didChange', { textDocument: { uri, version: 2 }, contentChanges: [{ text: 'changed\n' }] });
    assert.equal(await hover(5), null);
    client.sendNotification('textDocument/didSave', { textDocument: { uri } });

    status = 429;
    const limited = await hover(5);
    assert.match(limited.contents.value, /120초 동안 다시 조회하지 않습니다/);
    assert.ok(!limited.contents.value.includes(token));
    const calls = requests.length;
    await hover(6);
    assert.equal(requests.length, calls);
    assert.equal(errors, '');
  } finally {
    await client.sendRequest('shutdown').catch(() => {});
    client.sendNotification('exit');
    client.dispose();
    child.kill();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(root, { recursive: true, force: true });
  }
});

test('세션이 없으면 언어 서버를 시작하지 않고 표준 출력에 아무것도 쓰지 않는다', () => {
  const result = spawnSync(process.execPath, [script, 'lsp'], { env: { ...process.env, INTENT_TRACE_SESSION_TOKEN: '' }, encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /INTENT_TRACE_SESSION_TOKEN 환경 변수/);
});
