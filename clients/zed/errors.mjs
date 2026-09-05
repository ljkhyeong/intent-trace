const recovery = '변경 요청을 다시 보내기 전에 기록·게시 상태를 확인하세요.';
const messages = {
  AUTHENTICATION_REQUIRED: 'IntentTrace 인증에 실패했습니다. 다시 로그인해 받은 세션 토큰으로 연결하세요.',
  ACCESS_DENIED: 'IntentTrace 접근이 거부됐습니다. 저장소 권한을 확인하세요.',
  RATE_LIMITED: 'IntentTrace 호출 제한에 도달했습니다.',
  UPSTREAM_UNAVAILABLE: 'IntentTrace 서버 오류가 발생했습니다.',
  REQUEST_TIMEOUT: 'IntentTrace 응답 대기 시간이 지났습니다.',
  CONNECTION_FAILED: 'IntentTrace 연결을 완료하지 못했습니다. 서버 주소와 연결 상태를 확인하세요.',
};

export function retryAfterSeconds(value, now = Date.now()) {
  if (typeof value !== 'string' || value.length > 64) return undefined;
  let seconds;
  if (/^\d{1,8}$/.test(value)) seconds = Number(value);
  else if (/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(value)) {
    seconds = Math.ceil((Date.parse(value) - now) / 1000);
  }
  return Number.isSafeInteger(seconds) && seconds >= 0 && seconds <= 604800 ? seconds : undefined;
}

export class BridgeFailure extends Error {
  constructor(code, delay) {
    const kind = Object.hasOwn(messages, code) ? code : 'CONNECTION_FAILED';
    const seconds = kind === 'RATE_LIMITED' ? retryAfterSeconds(String(delay)) : undefined;
    const wait = kind === 'RATE_LIMITED'
      ? seconds === undefined ? ' 대기 시간을 확인할 수 없습니다. 즉시 반복하지 말고 잠시 후 다시 시도하세요.' : ` ${seconds}초 후 다시 시도하세요.`
      : '';
    super(`${messages[kind]}${wait}${kind === 'AUTHENTICATION_REQUIRED' || kind === 'ACCESS_DENIED' ? '' : ` ${recovery}`}`);
    this.details = { code: kind, ...(seconds === undefined ? {} : { retryAfterSeconds: seconds }) };
  }
}

export function httpFailure(status, retryAfter) {
  const code = status === 401 ? 'AUTHENTICATION_REQUIRED' : status === 403 ? 'ACCESS_DENIED'
    : status === 429 ? 'RATE_LIMITED' : status >= 500 ? 'UPSTREAM_UNAVAILABLE' : 'CONNECTION_FAILED';
  return new BridgeFailure(code, retryAfterSeconds(retryAfter));
}

export function safeFailure(error) {
  if (error instanceof BridgeFailure) return error;
  if (error?.data && Object.hasOwn(messages, error.data.code)) return new BridgeFailure(error.data.code, error.data.retryAfterSeconds);
  return new BridgeFailure(error?.code === -32001 || error?.name === 'TimeoutError' ? 'REQUEST_TIMEOUT' : 'CONNECTION_FAILED');
}

export function parseFailureLine(line) {
  const match = /^INTENT_TRACE_ERROR ([A-Z_]+)(?: (\d{1,6}))?$/.exec(line);
  return match && Object.hasOwn(messages, match[1]) ? new BridgeFailure(match[1], match[2]) : undefined;
}
