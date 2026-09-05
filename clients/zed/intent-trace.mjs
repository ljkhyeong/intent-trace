#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';

const script = fileURLToPath(import.meta.url);
const defaultUrl = 'http://127.0.0.1:8080/mcp';

export function endpoint(value = defaultUrl) {
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
    throw new Error('INTENT_TRACE_SESSION_TOKEN에 로그인 화면에서 받은 its_ 세션을 환경 변수로 전달하세요.');
  }
  return value;
}

async function main() {
  const [mode, ...arguments_] = process.argv.slice(2);
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
  const [address, repositoryKey] = arguments_;
  if (arguments_.length > (mode === 'check' ? 2 : 1)) throw new Error('IntentTrace MCP 주소와 명령 인자 수를 확인하세요.');
  if (!['config', 'serve', 'check'].includes(mode)) {
    console.log('사용법: node clients/zed/intent-trace.mjs config|check|serve [MCP 주소] [check 시 저장소 owner/repo] 또는 configure [MCP 주소] [--settings 설정파일] [--apply]');
    return;
  }
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
  else await bridge.check(script, url, repositoryKey);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    // 외부 HTTP 오류·설정 값·토큰을 콘솔에 전달하지 않는다.
    const message = error?.code === 'ERR_MODULE_NOT_FOUND'
      ? '먼저 npm ci --prefix clients/zed --ignore-scripts를 실행하세요.'
      : error?.message?.startsWith('Zed 설정:') || error?.message?.startsWith('INTENT_TRACE_SESSION_TOKEN') || error?.message?.startsWith('MCP 주소') || error?.message?.startsWith('IntentTrace MCP 주소')
        ? error.message : 'IntentTrace 연결을 완료하지 못했습니다. 서버 주소·세션 만료·저장소 권한을 확인하세요.';
    console.error(message);
    process.exitCode = 1;
  });
}
