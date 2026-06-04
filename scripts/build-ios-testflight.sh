#!/usr/bin/env bash
#
# Build the iOS app end-to-end and (optionally) upload it to TestFlight.
#
# This encodes the full working recipe so it's one command instead of a dozen
# fragile manual steps. It exists because the naive "Archive in Xcode" path does
# not work on this project/machine — see the THREE gotchas it handles:
#
#   1. Homebrew rsync shadows Apple's openrsync and breaks exportArchive's IPA
#      copy step ("Copy failed"). We force /usr/bin first on PATH.
#   2. A *signed* archive is impossible here (automatic signing wants an iOS
#      Development profile, which needs a registered device the team doesn't
#      have; ad-hoc is blocked on the current SDK; there's no local Distribution
#      private key because signing is cloud-managed). So we archive UNSIGNED.
#   3. An unsigned archive carries no entitlements, so exportArchive re-derives
#      them from the provisioning profile — which omits aps-environment, silently
#      shipping a build with no push. We codesign-embed ios/appstore-entitlements.plist
#      into the archive first; the export then preserves aps-environment=production.
#
# Usage:
#   scripts/build-ios-testflight.sh                 # build + verify (no upload)
#   scripts/build-ios-testflight.sh --upload        # build + verify + upload to TestFlight
#   scripts/build-ios-testflight.sh --build 7       # force a specific build number
#   scripts/build-ios-testflight.sh --skip-frontend # skip ./build-mobile.sh (native-only iteration)
#
# Build numbers: ios/build-number.txt records the last uploaded build. By default
# this script uses (last + 1). On a successful --upload it writes the new number back.

set -euo pipefail

# --- config -----------------------------------------------------------------
TEAM_ID="3757U37Z55"
SCHEME="App"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ARCHIVE="build/ios-archive/App.xcarchive"
APP="$ARCHIVE/Products/Applications/App.app"
EXPORT_DIR="build/export"
DERIVED="$REPO_ROOT/.derivedData/ios-release"
ENTITLEMENTS="ios/appstore-entitlements.plist"
BUILD_NUMBER_FILE="ios/build-number.txt"
GOOGLE_PLIST="ios/App/App/GoogleService-Info.plist"
# Optional, gitignored: exports ASC_KEY_ID / ASC_ISSUER_ID / ASC_KEY_PATH for
# headless App Store Connect auth. See scripts/.asc-api-key.env.example.
ASC_KEY_ENV="scripts/.asc-api-key.env"

# --- args -------------------------------------------------------------------
UPLOAD=false
SKIP_FRONTEND=false
BUILD=""

# Print the header comment block (from line 3 until the first non-comment line).
usage() { awk 'NR>=3 && /^#/ {sub(/^# ?/,""); print; next} NR>=3 {exit}' "$0"; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload)        UPLOAD=true; shift ;;
    --skip-frontend) SKIP_FRONTEND=true; shift ;;
    --build)         BUILD="${2:-}"; shift 2 ;;
    -h|--help)       usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 1 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

# --- preflight --------------------------------------------------------------
command -v xcodebuild >/dev/null || fail "xcodebuild not found (install Xcode + xcode-select)"
[[ -f "$GOOGLE_PLIST" ]] || fail "missing $GOOGLE_PLIST (required for push; see README)"
[[ -f "$ENTITLEMENTS" ]] || fail "missing $ENTITLEMENTS"

DEV_IDENTITY="$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/{print $2; exit}')"
[[ -n "$DEV_IDENTITY" ]] || fail "no 'Apple Development' signing identity in the keychain"

# App Store Connect API key (export + upload). Headless and durable — unlike the
# Xcode-signed-in Apple ID session, which expires. Optional; falls back to that
# session if not configured. (The key does NOT help the archive step: that still
# wants a Development profile + registered device, hence the unsigned-archive +
# embed approach above.)
AUTH_ARGS=()
AUTH_DESC="Xcode Apple ID session"
# shellcheck disable=SC1090
[[ -f "$ASC_KEY_ENV" ]] && source "$ASC_KEY_ENV"
if [[ -n "${ASC_KEY_ID:-}" && -n "${ASC_ISSUER_ID:-}" && -f "${ASC_KEY_PATH:-}" ]]; then
  AUTH_ARGS=(-authenticationKeyPath "$ASC_KEY_PATH"
             -authenticationKeyID "$ASC_KEY_ID"
             -authenticationKeyIssuerID "$ASC_ISSUER_ID")
  AUTH_DESC="API key $ASC_KEY_ID"
fi

# --upload hands the verified IPA to altool, which needs the API key (it can't
# use the Xcode Apple ID session). Fail early rather than deep in the upload.
if $UPLOAD && [[ -z "${ASC_KEY_ID:-}" || -z "${ASC_ISSUER_ID:-}" || ! -f "${ASC_KEY_PATH:-}" ]]; then
  fail "--upload requires an App Store Connect API key. Configure $ASC_KEY_ENV (see $ASC_KEY_ENV.example)."
fi

if [[ -z "$BUILD" ]]; then
  last="$(cat "$BUILD_NUMBER_FILE" 2>/dev/null || echo 0)"
  BUILD=$((last + 1))
fi
[[ "$BUILD" =~ ^[0-9]+$ ]] || fail "build number must be an integer, got '$BUILD'"

# Use Apple's /usr/bin/rsync (openrsync), not Homebrew's, for exportArchive (gotcha #1).
export PATH="/usr/bin:$PATH"

echo "==> iOS TestFlight build"
echo "    scheme=$SCHEME  build=$BUILD  upload=$UPLOAD"
echo "    embed-identity=$DEV_IDENTITY"
echo "    asc-auth=$AUTH_DESC"

# --- 1. frontend + cap sync -------------------------------------------------
if ! $SKIP_FRONTEND; then
  echo "==> [1/5] Building frontend + cap sync (./build-mobile.sh)"
  ./build-mobile.sh
else
  echo "==> [1/5] Skipping frontend build (--skip-frontend)"
fi

# --- 2. unsigned archive (gotcha #2) ----------------------------------------
echo "==> [2/5] Archiving (unsigned, build $BUILD)"
rm -rf "$ARCHIVE"
xcodebuild -project ios/App/App.xcodeproj -scheme "$SCHEME" -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" -derivedDataPath "$DERIVED" \
  -skipPackagePluginValidation \
  CODE_SIGNING_ALLOWED=NO CURRENT_PROJECT_VERSION="$BUILD" archive

# --- 3. embed entitlements (gotcha #3) --------------------------------------
echo "==> [3/5] Embedding $ENTITLEMENTS into the archive app"
codesign -f -s "$DEV_IDENTITY" \
  --entitlements "$ENTITLEMENTS" --generate-entitlement-der "$APP"

# --- 4. export signed IPA + verify ------------------------------------------
# Export to a local IPA (destination=export), NOT destination=upload. This lets
# us verify the exact bytes we later hand to altool — so the entitlement gate
# below actually covers the uploaded artifact rather than a second re-export.
echo "==> [4/5] Exporting signed IPA and verifying entitlements"
mkdir -p build
cat > build/exportOptions-export.plist <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>destination</key><string>export</string>
	<key>method</key><string>app-store-connect</string>
	<key>signingStyle</key><string>automatic</string>
	<key>manageAppVersionAndBuildNumber</key><false/>
	<key>stripSwiftSymbols</key><true/>
	<key>uploadSymbols</key><true/>
	<key>teamID</key><string>$TEAM_ID</string>
</dict>
</plist>
PLIST

rm -rf "$EXPORT_DIR"
xcodebuild -exportArchive -archivePath "$ARCHIVE" \
  -exportOptionsPlist build/exportOptions-export.plist \
  -exportPath "$EXPORT_DIR" -allowProvisioningUpdates \
  ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"}

# Hard gate: never upload a build that fails entitlement verification.
scripts/verify-ios-ipa.sh "$EXPORT_DIR/App.ipa"

# --- 5. upload (opt-in) -----------------------------------------------------
if ! $UPLOAD; then
  echo "==> [5/5] Done. Verified IPA: $EXPORT_DIR/App.ipa (build $BUILD)"
  echo "    Re-run with --upload to push this build to TestFlight."
  exit 0
fi

# Upload the SAME IPA that was just verified (altool uploads a pre-built file),
# rather than re-exporting — so the gate above covers exactly what ships.
echo "==> [5/5] Uploading the verified IPA (build $BUILD) to App Store Connect"
xcrun altool --upload-app -f "$EXPORT_DIR/App.ipa" -t ios \
  --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

# Record the build number we just uploaded so the next run auto-increments.
echo "$BUILD" > "$BUILD_NUMBER_FILE"
echo "==> Uploaded build $BUILD. Recorded in $BUILD_NUMBER_FILE."
echo "    It will appear in TestFlight after App Store Connect finishes processing."
