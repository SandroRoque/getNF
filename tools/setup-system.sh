#!/usr/bin/env bash
# One-time, root-requiring host setup (Arch Linux). Run in a normal terminal:
#   sudo bash tools/setup-system.sh
# Idempotent. Does NOT touch BIOS: SVM/AMD-V must be enabled there separately.
# If pacman 404s on a package, the sync DB is stale: run `sudo pacman -Syu` first.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }

# android-udev: lets adb talk to the real phone over USB without root.
pacman -S --needed --noconfirm android-udev

user="${SUDO_USER:-}"
if [[ -n "$user" ]]; then
  getent group adbusers >/dev/null && gpasswd -a "$user" adbusers >/dev/null
  getent group kvm >/dev/null && gpasswd -a "$user" kvm >/dev/null
fi

if [[ -e /dev/kvm ]]; then echo "KVM: OK"; else echo "KVM: /dev/kvm missing -> enable SVM Mode in BIOS and reboot"; fi
echo "Done. Log out/in (or reboot) for group changes to apply."
