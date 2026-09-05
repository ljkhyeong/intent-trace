import { applyEdits, findNodeAtLocation, getNodeValue, modify, parseTree } from 'jsonc-parser';
import { isDeepStrictEqual } from 'node:util';
import { chmod, lstat, mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID } from 'node:crypto';

export function defaultSettingsPath() {
  if (process.platform === 'win32') return join(process.env.APPDATA || join(homedir(), 'AppData', 'Roaming'), 'Zed', 'settings.json');
  const config = process.platform === 'darwin' ? join(homedir(), '.config') : process.env.XDG_CONFIG_HOME || join(homedir(), '.config');
  return join(config, 'zed', 'settings.json');
}

export function prepareSettings(text, entry) {
  const errors = [];
  const root = parseTree(text, errors, { allowTrailingComma: true, allowEmptyContent: true });
  if (errors.length || (root && root.type !== 'object')) throw new Error('Zed 설정: JSONC 문법과 최상위 객체를 확인하세요.');
  const servers = root && findNodeAtLocation(root, ['context_servers']);
  if (servers && servers.type !== 'object') throw new Error('Zed 설정: context_servers는 객체여야 합니다.');
  for (const [node, key] of [[root, 'context_servers'], [servers, 'intent-trace']]) {
    if (node?.children?.filter(child => child.children?.[0]?.value === key).length > 1) {
      throw new Error('Zed 설정: context_servers 또는 intent-trace 중복 항목을 먼저 정리하세요.');
    }
  }
  const current = root && findNodeAtLocation(root, ['context_servers', 'intent-trace']);
  if (current && isDeepStrictEqual(getNodeValue(current), entry)) return { text, operation: '변경 없음' };
  const indent = text.match(/\n([\t ]+)"/)?.[1];
  const edits = modify(text, ['context_servers', 'intent-trace'], entry, {
    formattingOptions: { insertSpaces: !indent?.includes('\t'), tabSize: indent?.includes('\t') ? 1 : indent?.length || 2, eol: text.includes('\r\n') ? '\r\n' : '\n' },
  });
  return { text: applyEdits(text, edits), operation: current ? '연결 업데이트' : '연결 추가' };
}

async function readSettings(path) {
  try {
    const stat = await lstat(path);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Zed 설정: 일반 설정 파일만 수정할 수 있습니다.');
    return { text: await readFile(path, 'utf8'), mode: stat.mode & 0o777 };
  } catch (error) {
    if (error.code === 'ENOENT') return { text: '', mode: 0o600 };
    throw error;
  }
}

export async function configure(path, entry, apply) {
  const target = resolve(path);
  const original = await readSettings(target);
  const prepared = prepareSettings(original.text, entry);
  // 다른 서버 설정에는 비밀값이 있을 수 있어 새 IntentTrace 연결만 미리 보여준다.
  console.log(`Zed 설정: ${prepared.operation}${apply ? '' : ' 미리보기'}`);
  console.log(JSON.stringify({ context_servers: { 'intent-trace': entry } }, null, 2));
  if (!apply) {
    console.log('저장하려면 같은 명령에 --apply를 추가하세요. intent-trace 항목만 교체하며 다른 서버와 주석은 보존합니다.');
    return;
  }
  if (prepared.text === original.text) return;
  await mkdir(dirname(target), { recursive: true });
  const temporary = join(dirname(target), `.intent-trace-${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, prepared.text, { flag: 'wx', mode: original.mode });
    await chmod(temporary, original.mode);
    if ((await readSettings(target)).text !== original.text) throw new Error('Zed 설정: 다른 프로그램이 설정을 변경했습니다. 다시 미리보기를 실행하세요.');
    await rename(temporary, target);
  } finally {
    await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; });
  }
  console.log('Zed 설정 저장 완료. Zed에서 IntentTrace 연결을 다시 시작하세요.');
}
