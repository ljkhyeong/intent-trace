import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema, McpError, ErrorCode } from '@modelcontextprotocol/sdk/types.js';
import { sessionToken } from './intent-trace.mjs';

const version = '0.10.0';
const timeout = 60_000;

export async function serve(url) {
  const remote = new Client({ name: 'intent-trace-zed-bridge', version });
  const transport = new StreamableHTTPClientTransport(url, {
    requestInit: { headers: { Authorization: `Bearer ${sessionToken()}` }, redirect: 'error' },
    fetch: (input, init) => {
      const target = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
      if (target.href !== url.href) throw new Error('허용한 MCP 주소가 아닙니다.');
      return fetch(input, { ...init, redirect: 'error' });
    },
  });
  const local = new Server({ name: 'intent-trace', version }, { capabilities: { tools: {} } });
  let closed = false;
  const close = async () => {
    if (closed) return;
    closed = true;
    await Promise.allSettled([remote.close(), local.close()]);
  };
  const forward = async action => {
    try { return await action(); } catch {
      throw new McpError(ErrorCode.InternalError, 'IntentTrace 요청을 완료하지 못했습니다. 연결·세션을 확인하고 변경 요청은 기록 또는 게시 상태를 먼저 조회하세요.');
    }
  };
  local.setRequestHandler(ListToolsRequestSchema, (request, extra) =>
    forward(() => remote.listTools(request.params, { timeout, signal: extra.signal })));
  local.setRequestHandler(CallToolRequestSchema, (request, extra) =>
    forward(() => remote.callTool(request.params, undefined, { timeout, signal: extra.signal })));
  local.onclose = close;
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
  process.stdin.once('end', close);
  try {
    await remote.connect(transport, { timeout });
    await local.connect(new StdioServerTransport());
  } catch (error) {
    await close();
    throw error;
  }
}

export async function check(script, url, repositoryKey) {
  const client = new Client({ name: 'intent-trace-connection-check', version });
  const transport = new StdioClientTransport({
    command: process.execPath, args: [script, 'serve', url.href],
    env: { INTENT_TRACE_SESSION_TOKEN: sessionToken() }, stderr: 'pipe',
  });
  // 자식 프로세스 오류 원문도 외부로 출력하지 않는다.
  transport.stderr?.resume();
  try {
    await client.connect(transport, { timeout });
    const result = await client.listTools();
    if (!result.tools.some(tool => tool.name === 'diagnose_connection')) throw new Error('진단 도구가 없습니다.');
    console.log(`MCP 연결 성공: ${result.tools.length}개 도구를 확인했습니다.`);
    if (repositoryKey) {
      const result = await client.callTool({ name: 'diagnose_connection', arguments: { repositoryKey } });
      if (result.isError) throw new Error('저장소 진단에 실패했습니다.');
      const diagnosis = result.structuredContent ?? JSON.parse(result.content.find(item => item.type === 'text').text);
      for (const item of diagnosis.checks) console.log(`${item.name}: ${item.status}`);
      if (diagnosis.checks.some(item => item.status === 'FAILED')) process.exitCode = 1;
    }
  } finally {
    await client.close();
  }
}
