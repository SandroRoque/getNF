#!/usr/bin/env bash
# Check that the dev environment is ready. Prints OK/MISSING per item.
. "$(dirname "$0")/env.sh"
ok()   { printf '  \e[32mOK\e[0m      %s\n' "$1"; }
miss() { printf '  \e[31mMISSING\e[0m %s  ->  %s\n' "$1" "$2"; fail=1; }
fail=0

echo "getNF dev environment"
command -v java >/dev/null           && ok "JDK ($(java -version 2>&1 | head -1))" || miss "JDK" "pacman -S jdk21-openjdk"
[[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]] && ok "Android cmdline-tools" || miss "Android cmdline-tools" "docs/dev-environment.md"
command -v adb >/dev/null            && ok "adb" || miss "adb" "sdkmanager platform-tools"
command -v emulator >/dev/null       && ok "emulator" || miss "emulator" "sdkmanager emulator"
avdmanager list avd -c 2>/dev/null | grep -qx "$GETNF_AVD" && ok "AVD '$GETNF_AVD'" || miss "AVD '$GETNF_AVD'" "docs/dev-environment.md"
[[ -r /dev/kvm ]]                    && ok "/dev/kvm (hardware virtualization)" || miss "/dev/kvm" "enable SVM/AMD-V in BIOS, reboot"
command -v magick >/dev/null         && ok "imagemagick (still-image camera input)" || miss "imagemagick" "pacman -S imagemagick"
docker info >/dev/null 2>&1          && ok "docker" || miss "docker" "systemctl start docker"
curl -sf http://127.0.0.1:8090/api/health >/dev/null && ok "PocketBase on :8090" || echo "  --      PocketBase not running (docker compose -f server/compose.yaml up -d)"
exit $fail
