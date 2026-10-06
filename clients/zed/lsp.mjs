import { execFile } from 'node:child_process';
import { realpath } from 'node:fs/promises';
import { dirname, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { createConnection, MarkupKind, TextDocumentSyncKind } from 'vscode-languageserver/node';
import { httpFailure, safeFailure } from './errors.mjs';
import { version } from './intent-trace.mjs';

const run = promisify(execFile);
const FILE_TTL = 5 * 60_000;
const LINE_TTL = 60_000;
const FAILURE_SECONDS = 30;
const MAX_RECORDS = 3;
const statusLabels = { PUBLISHED: '팀 공개', SUPERSEDED: '대체됨' };

/** IntelliJ의 GitHubRemoteParser와 같은 규칙으로 원격 주소에서 소문자 owner/repository를 구한다. */
export function repositoryKey(remote) {
  const value = remote.trim();
  const scp = /^(?:[^@/]+@)?github\.com[:/]([^/]+)\/([^/]+?)(?:\.git)?\/?$/i.exec(value);
  let parts = scp?.slice(1);
  if (!parts) {
    let url;
    try { url = new URL(value); } catch { return null; }
    if (url.hostname.toLowerCase() !== 'github.com') return null;
    parts = url.pathname.split('/').filter(Boolean);
    if (parts.length !== 2) return null;
  }
  const [owner, repository] = [parts[0].toLowerCase(), parts[1].toLowerCase().replace(/\.git$/, '')];
  return [owner, repository].every(part => /^[a-z0-9_.-]+$/.test(part)) ? `${owner}/${repository}` : null;
}

/** 커밋된 파일의 저장소·HEAD·상대 경로다. Git 저장소가 아니거나 커밋되지 않은 변경이 있으면 null이다. */
export async function lineContext(file) {
  const git = (cwd, ...args) => run('git', ['--literal-pathspecs', ...args], { cwd, timeout: 5_000 }).then(result => result.stdout.trim());
  try {
    const path = await realpath(file);
    const [root, revision] = (await git(dirname(path), 'rev-parse', '--show-toplevel', 'HEAD')).split('\n');
    const relativePath = relative(root, path).split(sep).join('/');
    if (await git(root, 'status', '--porcelain', '--', relativePath)) return null;
    // IntelliJ와 같이 origin을 먼저 보고, 없으면 다른 원격의 GitHub 주소를 사용한다.
    const remotes = (await git(root, 'config', '--get-regexp', '^remote\\..*\\.(push)?url$')).split('\n')
      .map(line => line.split(/\s+/))
      .sort(([left], [right]) => Number(!left.startsWith('remote.origin.')) - Number(!right.startsWith('remote.origin.')));
    const key = remotes.map(([, url]) => url && repositoryKey(url)).find(Boolean);
    return key && /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/.test(revision) ? { repositoryKey: key, revision, relativePath } : null;
  } catch {
    return null;
  }
}

/** 기존 REST 조회를 `its_` 세션으로 호출한다. 오류 본문과 토큰은 결과에 넣지 않는다. */
function createApi(url, token) {
  async function get(path, parameters) {
    const target = new URL(path, url);
    target.search = new URLSearchParams(parameters);
    const response = await fetch(target, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw await httpFailure(response);
    return response.json();
  }
  return {
    // 파일 단위로 공개 기록이 있는지 먼저 확인해 기록이 없는 파일에서는 줄마다 조회하지 않는다.
    fileHasRecords: async ({ repositoryKey, relativePath }) =>
      (await get('/api/v1/change-records', { repositoryKey, scope: 'TEAM', path: relativePath, limit: 1 })).items.length > 0,
    lookup: ({ repositoryKey, revision, relativePath }, line) =>
      get('/api/v1/change-records/lookup', { repositoryKey, revision, path: relativePath, line }),
  };
}

// 기록 본문은 사용자가 쓴 값이므로 Markdown 문법·링크로 해석되지 않게 한다.
const plain = (value, limit = 160) => {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return (text.length > limit ? `${text.slice(0, limit - 1)}…` : text).replace(/[\\`*_{}[\]()<>#+\-.!|~]/g, '\\$&');
};

export function renderHover(found, context, line, url) {
  if (!found.items?.length) return null;
  const items = found.items.slice(0, MAX_RECORDS).map(record => [
    `- **${plain(record.title, 200)}** · ${statusLabels[record.status] ?? plain(record.status)} · @${plain(record.createdBy?.login)}  `,
    ...(record.decisions?.[0] ? [`  구현 결정: ${plain(record.decisions[0].summary)}  `] : []),
    `  [기록 보기](${new URL(`/records/${encodeURIComponent(record.id)}`, url).href})`,
  ].join('\n'));
  const lines = [`**IntentTrace 변경 의도** · ${plain(context.relativePath, 300)}:${line}`, '', ...items];
  if (found.truncated || found.items.length > MAX_RECORDS) {
    const query = new URLSearchParams({ repositoryKey: context.repositoryKey, revision: context.revision, path: context.relativePath, line });
    const history = new URL(`/records/history?${query}`, url);
    lines.push('', `최근 공개 기록 ${MAX_RECORDS}건만 표시합니다. [웹에서 모두 보기](${history.href})`);
  }
  return lines.join('\n');
}

function failureText(failure) {
  const { code, retryAfterSeconds } = failure.details;
  const text = code === 'AUTHENTICATION_REQUIRED'
    ? '세션이 만료됐거나 유효하지 않습니다. 다시 로그인한 뒤 `intent-trace-zed launch`로 Zed를 다시 실행하세요.'
    : code === 'RATE_LIMITED'
      ? `호출 제한에 도달했습니다. ${Math.max(retryAfterSeconds ?? 0, FAILURE_SECONDS)}초 동안 다시 조회하지 않습니다.`
      : `IntentTrace 서버 응답을 받지 못했습니다. ${FAILURE_SECONDS}초 뒤 다시 조회합니다.`;
  return `**IntentTrace** · ${text}`;
}

/** Zed hover에 현재 줄의 공개 기록 요약을 표시하는 언어 서버다. 표준 입출력은 LSP 통신에만 쓴다. */
export function serveLanguageServer(url, token) {
  const connection = createConnection(process.stdin, process.stdout);
  const api = createApi(url, token);
  const edited = new Set();
  const files = new Map();
  const lines = new Map();
  const denied = new Map();
  let blocked;
  const cached = (cache, key, ttl, load) => {
    const entry = cache.get(key);
    if (entry && entry.until > Date.now()) return entry.value;
    if (cache.size > 1_000) cache.clear();
    return load().then(value => {
      cache.set(key, { until: Date.now() + ttl, value });
      return value;
    });
  };
  const hover = value => ({ contents: { kind: MarkupKind.Markdown, value } });

  connection.onInitialize(() => ({
    capabilities: { hoverProvider: true, textDocumentSync: { openClose: true, change: TextDocumentSyncKind.Incremental, save: { includeText: false } } },
    serverInfo: { name: 'intent-trace', version },
  }));
  connection.onDidOpenTextDocument(({ textDocument }) => edited.delete(textDocument.uri));
  connection.onDidChangeTextDocument(({ textDocument }) => edited.add(textDocument.uri));
  connection.onDidSaveTextDocument(({ textDocument }) => edited.delete(textDocument.uri));
  connection.onDidCloseTextDocument(({ textDocument }) => edited.delete(textDocument.uri));
  connection.onHover(async ({ textDocument, position }) => {
    // 저장하지 않았거나 커밋되지 않은 변경이 있으면 HEAD 기준 줄과 달라 조회하지 않는다.
    if (edited.has(textDocument.uri) || !textDocument.uri.startsWith('file:')) return null;
    const target = await lineContext(fileURLToPath(textDocument.uri));
    if (!target || (denied.get(target.repositoryKey) ?? 0) > Date.now()) return null;
    if (blocked && blocked.until > Date.now()) return hover(blocked.message);
    const line = position.line + 1;
    try {
      if (!await cached(files, `${target.repositoryKey}\0${target.relativePath}`, FILE_TTL, () => api.fileHasRecords(target))) return null;
      const found = await cached(lines, [target.repositoryKey, target.revision, target.relativePath, line].join('\0'), LINE_TTL, () => api.lookup(target, line));
      const markdown = renderHover(found, target, line, url);
      return markdown && hover(markdown);
    } catch (error) {
      const failure = safeFailure(error);
      // 권한이 없는 저장소는 hover마다 안내하지 않고 잠시 조회를 멈춘다.
      if (failure.details.code === 'ACCESS_DENIED') {
        denied.set(target.repositoryKey, Date.now() + FILE_TTL);
        return null;
      }
      blocked = { until: Date.now() + Math.max(failure.details.retryAfterSeconds ?? 0, FAILURE_SECONDS) * 1000, message: failureText(failure) };
      return hover(blocked.message);
    }
  });
  connection.listen();
}
