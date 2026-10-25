#!/usr/bin/env bash
# Installs the nightly backup as a systemd user timer (no sudo). Check it with: systemctl --user list-timers getnf-backup
set -euo pipefail
dir=~/.config/systemd/user
mkdir -p "$dir"
cp "$(dirname "$0")"/getnf-backup.{service,timer} "$dir"/
systemctl --user daemon-reload
systemctl --user enable --now getnf-backup.timer
systemctl --user list-timers getnf-backup.timer
