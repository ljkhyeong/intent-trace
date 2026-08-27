#!/bin/sh

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    printf '%s\n' '{"continue":true}'
    exit 0
fi

printf '%s\n' '{"continue":true,"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"코드를 변경했다면 완료 전에 IntentTrace 기록을 제안하세요. 원문 대화나 숨은 추론을 저장하지 말고, 사용자가 명시한 요청·확인 가능한 판단·최소 코드 범위·실제로 실행한 검증·미검증 항목만 구조화하세요. 초안은 작성자 확인 전 공개하지 마세요."}}'
