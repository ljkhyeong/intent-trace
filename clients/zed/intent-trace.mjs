#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { realpathSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { BridgeFailure } from './errors.mjs';

const script = fileURLToPath(import.meta.url);
const defaultUrl = 'http://127.0.0.1:8080/mcp';
const commandUsage = {
  config: 'config [MCP 주소]\n  Zed에 등록할 연결 설정을 출력합니다. 파일은 변경하지 않습니다.',
  configure: 'configure [MCP 주소] [--settings 설정파일] [--apply]\n  연결 설정을 미리 봅니다. --apply를 지정하면 설정 파일에 저장합니다.',
  check: 'check [MCP 주소] [owner/repo] [--revision 커밋] [--pr 번호]\n  MCP 연결과 저장소 권한을 점검합니다. PR·커밋을 지정하려면 저장소도 필요합니다.',
  serve: 'serve [MCP 주소]\n  Zed의 stdio 요청을 IntentTrace MCP 서버에 전달합니다.',
  launch: 'launch [Zed 인자]\n  세션을 전달해 Zed를 실행합니다. 뒤의 인자는 Zed에 그대로 전달합니다.',
};

function printHelp(mode) {
  console.log(mode ? `사용법: intent-trace-zed ${commandUsage[mode]}`
    : `사용법: intent-trace-zed <명령> [옵션]\n\n${Object.values(commandUsage).join('\n\n')}`);
  console.log('\nMCP 주소는 INTENT_TRACE_MCP_URL 환경 변수, 없으면 http://127.0.0.1:8080/mcp를 사용합니다.');
  console.log('check·serve에는 INTENT_TRACE_SESSION_TOKEN 환경 변수가 필요합니다. 토큰을 명령 인자에 넣지 마세요.');
}

export function endpoint(value = process.env.INTENT_TRACE_MCP_URL || defaultUrl) {
  let url;
  try { url = new URL(value); } catch { throw new Error('IntentTrace MCP 주소 형식을 확인하세요.'); }
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) ||
      url.username || url.password || url.search || url.hash || url.pathname !== '/mcp') {
    throw new Error('MCP 주소는 HTTPS 또는 로컬 HTTP의 /mcp 경로여야 하며 인증 정보와 쿼리를 넣을 수 없습니다.');
  }
  return url;
}

export function sessionToken() {
  const value = process.env.INTENT_TRACE_SESSION_TOKEN;
  if (!value || !/^its_[A-Za-z0-9_-]{32,128}$/.test(value)) {
    throw new Error('INTENT_TRACE_SESSION_TOKEN 환경 변수에 로그인 화면의 its_ 세션 토큰을 설정하세요.');
  }
  return value;
}

function checkOptions(args) {
  let parsed;
  try {
    parsed = parseArgs({ args, allowPositionals: true, options: {
      revision: { type: 'string' }, pr: { type: 'string' },
    } });
  } catch {
    throw new Error('IntentTrace 연결 점검: check [MCP 주소] [owner/repo] [--revision 커밋] [--pr 번호] 형식을 확인하세요.');
  }
  const { positionals, values } = parsed;
  const pullNumber = values.pr === undefined ? undefined : Number(values.pr);
  if (pullNumber !== undefined && (!Number.isSafeInteger(pullNumber) || pullNumber <= 0)) {
    throw new Error('IntentTrace 연결 점검: PR 번호는 양수인 정수여야 합니다.');
  }
  if ((values.revision !== undefined || pullNumber !== undefined) && !positionals[1]) {
    throw new Error('IntentTrace 연결 점검: PR 또는 커밋을 확인하려면 MCP 주소 뒤에 owner/repo를 지정하세요.');
  }
  return { positionals, diagnostic: { revision: values.revision, pullNumber } };
}

async function main() {
  const [mode, ...arguments_] = process.argv.slice(2);
  if (!mode || ['--help', '-h'].includes(mode)) return printHelp();
  if (!Object.hasOwn(commandUsage, mode)) {
    console.error('알 수 없는 명령입니다. intent-trace-zed --help로 사용법을 확인하세요.');
    process.exitCode = 1;
    return;
  }
  if (mode !== 'launch' && arguments_.some(value => ['--help', '-h'].includes(value))) return printHelp(mode);
  if (mode === 'launch') {
    const bundled = new URL('./zed-with-intent-trace.py', import.meta.url);
    const launcher = existsSync(bundled) ? bundled : new URL('../../scripts/zed-with-intent-trace.py', import.meta.url);
    const child = spawn(process.platform === 'win32' ? 'python' : 'python3', [fileURLToPath(launcher), ...arguments_], { stdio: 'inherit' });
    process.exitCode = await new Promise((resolve, reject) => {
      child.once('error', () => reject(new Error('Zed 설정: Python 3와 Zed CLI 설치를 확인하세요.')));
      child.once('close', code => resolve(code ?? 1));
    });
    return;
  }
  if (mode === 'configure') {
    const { configure, defaultSettingsPath } = await import('./settings.mjs');
    let path = defaultSettingsPath();
    let address;
    let apply = false;
    for (let i = 0; i < arguments_.length; i++) {
      const value = arguments_[i];
      if (value === '--apply' && !apply) apply = true;
      else if (value === '--settings' && arguments_[i + 1] && !arguments_[i + 1].startsWith('--')) path = arguments_[++i];
      else if (!value.startsWith('--') && !address) address = value;
      else throw new Error('Zed 설정: configure [MCP 주소] [--settings 설정파일] [--apply] 형식을 확인하세요.');
    }
    return configure(path, { command: process.execPath, args: [script, 'serve', endpoint(address).href], env: {} }, apply);
  }
  const { positionals, diagnostic } = mode === 'check' ? checkOptions(arguments_) : { positionals: arguments_ };
  const [address, repositoryKey] = positionals;
  if (positionals.length > (mode === 'check' ? 2 : 1)) throw new Error('IntentTrace MCP 주소와 명령 인자 수를 확인하세요.');
  const url = endpoint(address);
  if (mode === 'config') {
    console.log(JSON.stringify({ context_servers: { 'intent-trace': {
      command: process.execPath, args: [script, 'serve', url.href], env: {},
    } } }, null, 2));
    return;
  }
  sessionToken();
  const bridge = await import('./bridge.mjs');
  if (mode === 'serve') await bridge.serve(url);
  else await bridge.check(script, url, repositoryKey, diagnostic);
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  main().catch(error => {
    if (error instanceof BridgeFailure) {
      console.error(error.message);
      console.error(`INTENT_TRACE_ERROR ${error.details.code}${error.details.retryAfterSeconds === undefined ? '' : ` ${error.details.retryAfterSeconds}`}`);
      process.exitCode = 1;
      return;
    }
    // 외부 HTTP 오류·설정 값·토큰을 콘솔에 전달하지 않는다.
    const message = error?.code === 'ERR_MODULE_NOT_FOUND'
      ? '연결 도구를 다시 설치하세요. 소스 실행 시 clients/zed에서 npm ci를 실행하세요.'
      : error?.message?.startsWith('Zed 설정:') || error?.message?.startsWith('INTENT_TRACE_SESSION_TOKEN') || error?.message?.startsWith('MCP 주소') || error?.message?.startsWith('IntentTrace MCP 주소') || error?.message?.startsWith('IntentTrace 연결 점검:')
        ? error.message : 'IntentTrace 연결을 완료하지 못했습니다. 서버 주소·세션 만료·저장소 권한을 확인하세요.';
    console.error(message);
    process.exitCode = 1;
  });
}
