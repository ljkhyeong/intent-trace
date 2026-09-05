#!/usr/bin/env node
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

if (process.platform === 'win32') throw new Error('배포 파일 생성은 macOS 또는 Linux에서 실행해 주세요.');

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'clients/zed');
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i];
  if (!['--output', '--npm-name', '--mcp-name', '--repository'].includes(key) || !process.argv[i + 1] || options[key]) {
    throw new Error('배포 파일 생성: --output 폴더와 선택 --npm-name 이름 --mcp-name 이름 --repository GitHub주소를 확인하세요.');
  }
  options[key] = process.argv[i + 1];
}
const publication = ['--npm-name', '--mcp-name', '--repository'].map(key => options[key]);
if (publication.some(Boolean) && !publication.every(Boolean)) throw new Error('공개 제출 자료에는 패키지 이름·MCP 이름·저장소 주소가 모두 필요합니다.');
if (publication.every(Boolean)) {
  if (!/^@[a-z0-9][a-z0-9-]*\/[a-z0-9][a-z0-9-]*$/.test(publication[0]) ||
      !/^io\.github\.[a-z0-9][a-z0-9-]*\/[a-z0-9][a-z0-9-]*$/.test(publication[1]) ||
      !/^https:\/\/github\.com\/[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/.test(publication[2])) {
    throw new Error('공개 제출 자료의 이름과 GitHub 저장소 주소 형식을 확인하세요.');
  }
  if (publication[1].split('/')[0].slice('io.github.'.length) !== new URL(publication[2]).pathname.split('/')[1].toLowerCase()) {
    throw new Error('MCP 이름의 GitHub 소유자와 저장소 소유자를 일치시켜 주세요.');
  }
}
const output = resolve(options['--output'] || join(root, 'build/zed-release'));
const staging = await mkdtemp(join(tmpdir(), 'intent-trace-package-'));
try {
  const pkg = JSON.parse(await readFile(join(source, 'package.json'), 'utf8'));
  // 실행에 필요한 파일만 복사한다. 사용자 설정과 프로젝트 문서는 패키지에 넣지 않는다.
  for (const name of ['intent-trace.mjs', 'bridge.mjs', 'errors.mjs', 'settings.mjs', 'README.md']) {
    await cp(join(source, name), join(staging, name));
  }
  await cp(join(root, 'scripts/zed-with-intent-trace.py'), join(staging, 'zed-with-intent-trace.py'));
  await cp(join(source, 'node_modules'), join(staging, 'node_modules'), { recursive: true });
  pkg.scripts = {};
  pkg.bin = { 'intent-trace-zed': 'intent-trace.mjs' };
  pkg.files = ['*.mjs', 'zed-with-intent-trace.py', 'README.md'];
  pkg.bundleDependencies = Object.keys(pkg.dependencies);
  pkg.license = 'UNLICENSED';
  let descriptor;
  if (publication.every(Boolean)) {
    pkg.name = publication[0];
    pkg.mcpName = publication[1];
    pkg.repository = { type: 'git', url: publication[2], directory: 'clients/zed' };
    pkg.private = false;
    descriptor = {
      $schema: 'https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json',
      name: pkg.mcpName,
      description: '작성자가 확인한 변경 의도와 코드 근거를 조회하고 관리하는 IntentTrace MCP 연결 도구',
      repository: { url: publication[2], source: 'github', subfolder: 'clients/zed' },
      version: pkg.version,
      packages: [{
        registryType: 'npm', identifier: pkg.name, version: pkg.version, transport: { type: 'stdio' },
        packageArguments: [{ type: 'positional', value: 'serve' }],
        environmentVariables: [
          { name: 'INTENT_TRACE_MCP_URL', description: 'IntentTrace 서버의 HTTPS /mcp 주소. 생략하면 로컬 서버에 연결합니다.', format: 'string', isRequired: false, default: 'http://127.0.0.1:8080/mcp' },
          { name: 'INTENT_TRACE_SESSION_TOKEN', description: 'IntentTrace 로그인 후 받은 its_ 세션. 파일에 저장하지 않고 실행 환경으로 전달합니다.', format: 'string', isRequired: true, isSecret: true },
        ],
      }],
    };
  }
  await writeFile(join(staging, 'package.json'), JSON.stringify(pkg, null, 2) + '\n');
  await mkdir(output, { recursive: true });
  const npm = 'npm';
  const packed = spawnSync(npm, ['pack', '--ignore-scripts', '--json', '--pack-destination', output], { cwd: staging, encoding: 'utf8' });
  if (packed.status !== 0) throw new Error('패키지를 만들지 못했습니다. 고정 의존성을 먼저 설치해 주세요.');
  const { filename } = JSON.parse(packed.stdout)[0];
  const digest = createHash('sha256').update(await readFile(join(output, filename))).digest('hex');
  await writeFile(join(output, `${filename}.sha256`), `${digest}  ${filename}\n`);
  if (descriptor) await writeFile(join(output, 'server.json'), JSON.stringify(descriptor, null, 2) + '\n');
  console.log(`배포 파일 생성 완료: ${filename}${descriptor ? ' · server.json' : ' · 로컬 설치용'}`);
} finally {
  await rm(staging, { recursive: true, force: true });
}
