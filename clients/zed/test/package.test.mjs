import test from 'node:test';
import assert from 'node:assert/strict';
import { cp, mkdtemp, readFile, readdir, realpath, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';

const builder = fileURLToPath(new URL('../../../scripts/package-zed.mjs', import.meta.url));

test('배포 패키지는 잠금 파일로 의존성을 준비하고 빈 캐시로 오프라인 설치한다', { timeout: 120_000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'intent-trace-install-'));
  const output = join(directory, 'release');
  const install = join(directory, 'install');
  const token = `its_${'a'.repeat(43)}`;
  function run(command, args, options = {}) {
    const result = spawnSync(command, args, { cwd: directory, encoding: 'utf8', ...options });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout;
  }
  let server;
  try {
    const fixture = join(directory, 'source');
    const source = join(fixture, 'clients/zed');
    await mkdir(source, { recursive: true });
    await mkdir(join(fixture, 'scripts'));
    await cp(builder, join(fixture, 'scripts/package-zed.mjs'));
    await cp(join(dirname(builder), 'zed-with-intent-trace.py'), join(fixture, 'scripts/zed-with-intent-trace.py'));
    for (const name of ['package.json', 'package-lock.json', 'intent-trace.mjs', 'bridge.mjs', 'errors.mjs', 'settings.mjs', 'README.md']) {
      await cp(new URL(`../${name}`, import.meta.url), join(source, name));
    }
    const isolatedBuilder = join(fixture, 'scripts/package-zed.mjs');
    // 직접·하위 의존성이 잘못 설치돼 있어도 배포에는 잠금 파일의 버전만 들어가야 한다.
    for (const name of ['@modelcontextprotocol/sdk', 'zod']) {
      await mkdir(join(source, 'node_modules', name), { recursive: true });
      await writeFile(join(source, 'node_modules', name, 'package.json'), JSON.stringify({ name, version: '99.0.0' }));
    }
    run(process.execPath, [isolatedBuilder, '--output', output]);
    const filename = (await readdir(output)).find(name => name.endsWith('.tgz'));
    const tarball = join(output, filename);
    const digest = createHash('sha256').update(await readFile(tarball)).digest('hex');
    assert.equal(await readFile(`${tarball}.sha256`, 'utf8'), `${digest}  ${filename}\n`);
    run('npm', ['install', '--prefix', install, '--cache', join(directory, 'empty-cache'), '--offline', '--ignore-scripts', '--no-audit', '--no-fund', tarball]);
    const bin = join(install, 'node_modules/.bin/intent-trace-zed');
    const configured = JSON.parse(run(bin, ['config']));
    const entry = configured.context_servers['intent-trace'];
    assert.ok(entry.args[0].startsWith(await realpath(install)));
    assert.deepEqual(entry.env, {});
    const settings = join(directory, 'settings.json');
    run(bin, ['configure', '--settings', settings, '--apply']);
    assert.ok((await readFile(settings, 'utf8')).includes(await realpath(install)));
    assert.ok(!(await readFile(settings, 'utf8')).includes(token));
    const packageDirectory = join(install, 'node_modules/intent-trace-zed');
    assert.ok((await readdir(packageDirectory)).includes('zed-with-intent-trace.py'));
    assert.ok(!(await readdir(packageDirectory)).includes('test'));
    const lockfile = await readFile(join(source, 'package-lock.json'));
    const buildInfo = JSON.parse(await readFile(join(packageDirectory, 'build-info.json'), 'utf8'));
    assert.equal(buildInfo.lockfileSha256, createHash('sha256').update(lockfile).digest('hex'));
    assert.deepEqual(JSON.parse(await readFile(`${tarball}.build.json`, 'utf8')), { ...buildInfo, tarballSha256: digest });
    for (const [path, dependency] of Object.entries(JSON.parse(lockfile).packages)) {
      if (!path || dependency.dev) continue;
      assert.equal(JSON.parse(await readFile(join(packageDirectory, path, 'package.json'), 'utf8')).version, dependency.version, path);
    }
    assert.equal(JSON.parse(await readFile(join(source, 'node_modules/zod/package.json'), 'utf8')).version, '99.0.0');

    server = createServer(async (request, response) => {
      if (request.method !== 'POST') { response.writeHead(405).end(); return; }
      assert.equal(request.headers.authorization, `Bearer ${token}`);
      let body = ''; for await (const chunk of request) body += chunk;
      const message = JSON.parse(body);
      if (message.id === undefined) { response.writeHead(202).end(); return; }
      const result = message.method === 'initialize'
        ? { protocolVersion: message.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: '설치 검증', version: '1' } }
        : { tools: [{ name: 'diagnose_connection', inputSchema: { type: 'object', properties: {} } }] };
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }));
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const child = spawn(bin, ['check'], { cwd: directory, env: { ...process.env, INTENT_TRACE_MCP_URL: `http://127.0.0.1:${server.address().port}/mcp`, INTENT_TRACE_SESSION_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe'] });
    let text = '';
    child.stdout.on('data', bytes => { text += bytes; }); child.stderr.on('data', bytes => { text += bytes; });
    const code = await new Promise((resolve, reject) => { child.once('close', resolve); child.once('error', reject); });
    assert.equal(code, 0, text);
    assert.ok(!text.includes(token));

    const fakeBin = join(directory, 'fake-bin');
    await mkdir(fakeBin);
    await writeFile(join(fakeBin, 'zed'), '#!/usr/bin/env python3\nimport os,sys\nassert os.environ["INTENT_TRACE_SESSION_TOKEN"].startswith("its_")\nassert sys.argv[1:] == ["project with spaces"]\nprint("Zed 실행 환경 확인")\n', { mode: 0o700 });
    assert.match(run(bin, ['launch', 'project with spaces'], { env: { ...process.env, PATH: `${fakeBin}:${process.env.PATH}`, INTENT_TRACE_SESSION_TOKEN: token } }), /Zed 실행 환경 확인/);

    const publication = join(directory, 'publication');
    run(process.execPath, [isolatedBuilder, '--output', publication, '--npm-name', '@example/intent-trace-zed', '--mcp-name', 'io.github.example/intent-trace', '--repository', 'https://github.com/example/intent-trace']);
    const metadata = JSON.parse(await readFile(join(publication, 'server.json'), 'utf8'));
    assert.equal(metadata.name, 'io.github.example/intent-trace');
    assert.equal(metadata.packages[0].identifier, '@example/intent-trace-zed');
    assert.equal(metadata.packages[0].environmentVariables.find(value => value.name === 'INTENT_TRACE_SESSION_TOKEN').isSecret, true);
    assert.ok(!JSON.stringify(metadata).includes(token));
    const manifest = JSON.parse(await readFile(join(source, 'package.json'), 'utf8'));
    manifest.dependencies['jsonc-parser'] = '99.0.0';
    await writeFile(join(source, 'package.json'), JSON.stringify(manifest));
    const rejected = spawnSync(process.execPath, [isolatedBuilder, '--output', join(directory, 'invalid')], { encoding: 'utf8', env: { ...process.env, npm_config_offline: 'true' } });
    assert.notEqual(rejected.status, 0);
    assert.match(rejected.stderr, /잠금 파일로 배포 의존성을 설치하지 못했습니다/);
    await assert.rejects(readdir(join(directory, 'invalid')), { code: 'ENOENT' });
  } finally {
    if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
    await rm(directory, { recursive: true, force: true });
  }
});
