#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
REPO="AbdullahSR007/ALRADHY-World-Window"
OUT="${ALRADHY_APK_DIR:-/sdcard/Download/ALRADHY-APKs/INFINITY}"
pkg install -y gh jq unzip >/dev/null
gh auth status >/dev/null 2>&1 || gh auth login
[ -w /sdcard/Download ] || termux-setup-storage
mkdir -p "$OUT"
RUN="$(gh run list --repo "$REPO" --workflow build-apk.yml --branch feature/infinity-v1 \
  --limit 30 --json databaseId,conclusion \
  --jq '[.[] | select(.conclusion=="success")][0].databaseId // empty')"
if [ -z "$RUN" ]; then
  echo "No successful Android build is available yet."
  echo "Check: https://github.com/$REPO/actions"
  exit 2
fi
gh run download "$RUN" --repo "$REPO" --dir "$OUT"
echo "Available APKs:"
find "$OUT" -type f -name '*.apk' -print
echo "Workflow: https://github.com/$REPO/actions/runs/$RUN"
