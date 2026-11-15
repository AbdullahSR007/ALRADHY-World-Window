#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
REPO="AbdullahSR007/ALRADHY-World-Window"
KEY_DIR="$HOME/.alradhy-private-keys"
KEY="$KEY_DIR/infinity-release.p12"
ALIAS="infinity"
mkdir -p "$KEY_DIR"; chmod 700 "$KEY_DIR"
pkg install -y gh openjdk-17 coreutils >/dev/null
gh auth status >/dev/null 2>&1 || gh auth login
echo "ALRADHY INFINITY: one-time signing setup"
echo "IMPORTANT: NEVER delete the private signing key; updates depend on it."
read -rsp "Enter a strong signing password: " PASS
echo
if [ "${#PASS}" -lt 12 ]; then echo "Use at least 12 characters.";exit 1;fi
if [ ! -s "$KEY" ]; then
  keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -validity 10000 -storetype PKCS12 -keystore "$KEY" \
    -dname "CN=ALRADHY Infinity, OU=Personal TV, O=ALRADHY, C=SA" \
    -storepass "$PASS" -keypass "$PASS" -noprompt
fi
keytool -list -keystore "$KEY" -storepass "$PASS" -alias "$ALIAS" >/dev/null
base64 -w0 "$KEY" | gh secret set INFINITY_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$PASS" | gh secret set INFINITY_STORE_PASS --repo "$REPO"
printf '%s' "$PASS" | gh secret set INFINITY_KEY_PASS --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set INFINITY_KEY_ALIAS --repo "$REPO"
unset PASS
chmod 600 "$KEY"
echo "Release signing secrets configured for $REPO."
echo "Keep a secure offline backup of $KEY. Do not put it in GitHub code."
echo "Run the GitHub Actions workflow on feature/infinity-v1 to create a release-signed APK."
