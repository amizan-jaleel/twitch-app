#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-ios-ipa.sh <path-to-App.ipa|App.app|App.xcarchive>

Verifies a TestFlight/App Store iOS build artifact contains:
  - aps-environment = production in the signed app entitlements
  - GoogleService-Info.plist in the app bundle
EOF
}

fail() {
  echo "error: $*" >&2
  exit 1
}

artifact="${1:-}"
if [[ -z "$artifact" || "$artifact" == "-h" || "$artifact" == "--help" ]]; then
  usage
  exit 0
fi

[[ -e "$artifact" ]] || fail "artifact not found: $artifact"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/verify-ios-ipa.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

app_path=""
case "$artifact" in
  *.ipa)
    unzip -q "$artifact" -d "$tmpdir"
    app_path="$(find "$tmpdir/Payload" -maxdepth 1 -type d -name '*.app' -print -quit 2>/dev/null || true)"
    ;;
  *.app)
    app_path="$artifact"
    ;;
  *.xcarchive)
    app_path="$(find "$artifact/Products/Applications" -maxdepth 1 -type d -name '*.app' -print -quit 2>/dev/null || true)"
    ;;
  *)
    fail "expected an .ipa, .app, or .xcarchive artifact"
    ;;
esac

[[ -n "$app_path" && -d "$app_path" ]] || fail "could not find App.app in artifact"

bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_path/Info.plist" 2>/dev/null || true)"
[[ -n "$bundle_id" ]] || fail "could not read CFBundleIdentifier from $app_path/Info.plist"

[[ -f "$app_path/GoogleService-Info.plist" ]] ||
  fail "missing GoogleService-Info.plist in $app_path"

entitlements="$tmpdir/entitlements.plist"
codesign -d --entitlements :- "$app_path" >"$entitlements" 2>/dev/null ||
  fail "could not read signed entitlements from $app_path"

aps_environment="$(/usr/libexec/PlistBuddy -c 'Print :aps-environment' "$entitlements" 2>/dev/null || true)"
[[ "$aps_environment" == "production" ]] ||
  fail "expected aps-environment=production for $bundle_id, got '${aps_environment:-missing}'"

echo "Verified $bundle_id"
echo "  aps-environment: production"
echo "  GoogleService-Info.plist: present"
