#!/usr/bin/env bash
# Installs the nightly LLM worker as a systemd user timer (no sudo). Check it with:
#   systemctl --user list-timers getnf-llm-worker; journalctl --user -u getnf-llm-worker
set -euo pipefail
dir=~/.config/systemd/user
mkdir -p "$dir"
cp "$(dirname "$0")"/getnf-llm-worker.{service,timer} "$dir"/
systemctl --user daemon-reload
systemctl --user enable --now getnf-llm-worker.timer
systemctl --user list-timers getnf-llm-worker.timer
