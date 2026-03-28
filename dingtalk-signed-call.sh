#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  ./dingtalk-signed-call.sh --base-url https://bot.2756online.com --secret '<dingtalk-sign-secret>' --text 'echo hi'

Options:
  --base-url         MindOS public base URL, e.g. https://bot.2756online.com
  --secret           DingTalk signing secret (same as mindos.im.dingtalk.secret)
  --text             Message text content (default: echo hi)
  --sender-id        senderId in payload (default: local-ding-user)
  --conversation-id  conversationId in payload (default: local-conv)
  --timestamp        Override unix ms timestamp (default: current time)
  --timeout          Curl timeout seconds (default: 10)
  --dry-run          Print request URL/body/sign and exit without curl
  --help             Show this help

Environment fallbacks:
  MINDOS_IM_BASE_URL
  MINDOS_IM_DINGTALK_SECRET
USAGE
}

BASE_URL="${MINDOS_IM_BASE_URL:-}"
SECRET="${MINDOS_IM_DINGTALK_SECRET:-}"
TEXT="echo hi"
SENDER_ID="local-ding-user"
CONVERSATION_ID="local-conv"
TIMESTAMP=""
TIMEOUT_SECONDS="10"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --secret)
      SECRET="${2:-}"
      shift 2
      ;;
    --text)
      TEXT="${2:-}"
      shift 2
      ;;
    --sender-id)
      SENDER_ID="${2:-}"
      shift 2
      ;;
    --conversation-id)
      CONVERSATION_ID="${2:-}"
      shift 2
      ;;
    --timestamp)
      TIMESTAMP="${2:-}"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$BASE_URL" || -z "$SECRET" ]]; then
  echo "[ERROR] --base-url and --secret are required (or set env vars)."
  usage
  exit 1
fi

if [[ -z "$TIMESTAMP" ]]; then
  TIMESTAMP="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
fi

SIGN="$(python3 - <<'PY' "$TIMESTAMP" "$SECRET"
import base64
import hashlib
import hmac
import sys
import urllib.parse

timestamp = sys.argv[1]
secret = sys.argv[2]
string_to_sign = f"{timestamp}\n{secret}"
digest = hmac.new(secret.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha256).digest()
print(urllib.parse.quote(base64.b64encode(digest).decode("utf-8"), safe=""))
PY
)"

PAYLOAD="$(python3 - <<'PY' "$SENDER_ID" "$CONVERSATION_ID" "$TEXT"
import json
import sys
payload = {
  "senderId": sys.argv[1],
  "conversationId": sys.argv[2],
  "text": {"content": sys.argv[3]}
}
print(json.dumps(payload, ensure_ascii=False))
PY
)"

BASE_URL="${BASE_URL%/}"
REQUEST_URL="${BASE_URL}/api/im/dingtalk/events?timestamp=${TIMESTAMP}&sign=${SIGN}"

echo "[INFO] request-url: $REQUEST_URL"
echo "[INFO] payload: $PAYLOAD"

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[PASS] dry-run only"
  exit 0
fi

curl --silent --show-error --max-time "$TIMEOUT_SECONDS" \
  -X POST \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD" \
  "$REQUEST_URL"

echo

