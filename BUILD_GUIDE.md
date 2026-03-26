# Building MobileClaw

## Prerequisites

- **Xcode 15+** (iOS 17 Live Activities support)
- **XcodeGen** — installed via `brew install xcodegen`
- An **API key** from Anthropic, OpenAI, or OpenRouter

## Quick Start (3 commands)

```bash
# 1. Generate the Xcode project
cd ~/Projects/chai/mobileclaw
xcodegen generate

# 2. Build for Simulator (no code signing needed)
xcodebuild build \
  -project MobileClaw.xcodeproj \
  -scheme MobileClaw \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY=- \
  CODE_SIGNING_ALLOWED=NO

# 3. Or open in Xcode for device builds
open MobileClaw.xcodeproj
```

## Building for a Physical Device

1. Open `MobileClaw.xcodeproj` in Xcode
2. Select your **Team** in Signing & Capabilities
3. Connect your iPhone, select it as the destination
4. **⌘R** to build and run

## Project Structure

The `project.yml` (XcodeGen spec) defines:
- **MobileClaw** target — main iOS app
- **MobileClawWidgetExtension** target — Live Activity widget
- AppResources and Skills use **folder references** to preserve directory structure

## QEMU VM Engine

MobileClaw runs a full Alpine Linux VM using QEMU with the TCG threaded interpreter
(same approach as UTM SE on the App Store). This allows real Linux command execution
on iOS without jailbreaking.

### Required Resources

Place these in the app bundle (see setup instructions below):

1. **QEMU dylib** — `qemu-system-aarch64.dylib` or `qemu-system-x86_64.dylib`
   - Obtained from UTM's prebuilt sysroot artifacts on GitHub Actions
   - Download `Sysroot-ios-tci-arm64` from the latest UTM CI build

2. **Alpine Linux disk image** — `alpine.qcow2`
   - A minimal Alpine rootfs configured for serial console login
   - Should auto-login as root and have shell ready on boot

### Setting Up QEMU (First Time)

```bash
# 1. Clone UTM to get build scripts (shallow clone)
git clone --depth 1 https://github.com/utmapp/UTM.git /tmp/utm

# 2. Download prebuilt sysroot from GitHub Actions
# Visit: https://github.com/utmapp/UTM/actions
# Download: Sysroot-ios-tci-arm64 (requires GitHub login)
# Extract to /tmp/utm/

# 3. Build UTM SE to get the QEMU dylib
cd /tmp/utm
xcodebuild build -scheme "UTM SE" -destination 'generic/platform=iOS'

# 4. Copy the QEMU dylib to your project
# The dylib will be in the build products directory
```

> **Note:** Without the QEMU dylib and disk image, the chat UI, settings,
> API key validation, and LLM text responses still work — only `run_command`
> execution requires the QEMU VM.

## After Building

On first launch, the **Onboarding flow** starts:
1. Allow notifications
2. Pick your LLM provider (Anthropic / OpenAI / OpenRouter)
3. Enter your API key (validated live)

## Clean Build

```bash
xcodebuild clean -project MobileClaw.xcodeproj -scheme MobileClaw
xcodebuild build -project MobileClaw.xcodeproj -scheme MobileClaw \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=NO
```

## Troubleshooting

| Issue | Fix |
|-------|-----|
| `xcodegen: not found` | `brew install xcodegen` |
| No .xcodeproj | Run `xcodegen generate` in project root |
| Code signing errors | Use `CODE_SIGNING_ALLOWED=NO` for simulator, or set team in Xcode for device |
| QEMU "Booting…" forever | Missing QEMU dylib or alpine.qcow2 — see QEMU setup above |
| Bridging header error | Ensure `MobileClaw/MobileClaw-Bridging-Header.h` exists and is set in project.yml |
