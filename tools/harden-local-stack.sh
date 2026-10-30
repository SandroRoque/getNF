#!/usr/bin/env bash
# Keep the local Supabase dev stack (ports 54320-54329) off the LAN and the internet (D109). Needs root; run:
#   sudo bash tools/harden-local-stack.sh            # apply now and persist across `ufw reload` / reboot
#   sudo bash tools/harden-local-stack.sh --status   # show whether the rule is live / persisted
#   sudo bash tools/harden-local-stack.sh --remove   # roll back (live rule + the persisted block)
# No container, volume or Docker setting is touched, and nothing restarts.
#
# Why: the Supabase CLI publishes these ports with no bind address (all IPv4+IPv6 interfaces) and has no setting to
# change that. The ufw-docker block in /etc/ufw/after.rules returns every RFC1918 source (10/8, 172.16/12,
# 192.168/16) before anything can drop it, so any LAN device could reach Kong, Postgres, Studio and Mailpit.
# This adds a jump at the head of DOCKER-USER into a small GETNF-LOCAL chain: new connections to those original destination ports are dropped
# unless they arrive from loopback or a Docker bridge. Host-local clients (psql, curl, the emulator via 10.0.2.2)
# reach the ports through docker-proxy on the host, not through this chain, so they are unaffected.
# IPv6 needs no rule here: Docker has IPv6 off (no ip6tables), so [::] connections are accepted by docker-proxy
# in INPUT, which ufw's default-deny already covers. Verify that from another device (docs/dev-environment.md).
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }

PORTS=54320:54329
CHAIN=GETNF-LOCAL
JUMP=(DOCKER-USER -p tcp -m conntrack --ctstate NEW --ctorigdstport "$PORTS" -j "$CHAIN")
FILE=/etc/ufw/after.rules
BEGIN='# BEGIN GETNF LOCAL STACK'
END='# END GETNF LOCAL STACK'
persisted() { grep -qF "$BEGIN" "$FILE"; }
live()      { iptables -C "${JUMP[@]}" 2>/dev/null; }

case "${1:-apply}" in
  --status)
    live && echo "live:      yes" || echo "live:      NO"
    persisted && echo "persisted: yes ($FILE)" || echo "persisted: NO"
    iptables -S DOCKER-USER | head -5; iptables -S $CHAIN 2>/dev/null || true
    ;;
  --remove)
    while live; do iptables -D "${JUMP[@]}"; done
    iptables -F $CHAIN 2>/dev/null && iptables -X $CHAIN 2>/dev/null || true
    if persisted; then
      cp -a "$FILE" "$FILE.getnf-bak-$(date +%Y%m%d-%H%M%S)"
      sed -i "/^$BEGIN\$/,/^$END\$/d" "$FILE"
    fi
    echo "removed. DOCKER-USER now:"; iptables -S DOCKER-USER | head -5
    ;;
  apply)
    if ! live; then
      iptables -N $CHAIN 2>/dev/null || iptables -F $CHAIN
      iptables -A $CHAIN -i lo -j RETURN
      iptables -A $CHAIN -i 'br-+' -j RETURN
      iptables -A $CHAIN -i docker0 -j RETURN
      iptables -A $CHAIN -j DROP
      iptables -I "${JUMP[@]}"
    fi
    live || { echo "rule did not apply; nothing persisted" >&2; exit 1; }
    if ! persisted; then
      cp -a "$FILE" "$FILE.getnf-bak-$(date +%Y%m%d-%H%M%S)"
      # right after the IPv4 ':DOCKER-USER - [0:0]' declaration: declare the chain, fill it, jump to it first
      awk -v b="$BEGIN" -v e="$END" -v ports="$PORTS" '
        {print} !done && /^:DOCKER-USER - \[0:0\]/ {
          print b; print ":GETNF-LOCAL - [0:0]"
          print "-A GETNF-LOCAL -i lo -j RETURN"; print "-A GETNF-LOCAL -i br-+ -j RETURN"
          print "-A GETNF-LOCAL -i docker0 -j RETURN"; print "-A GETNF-LOCAL -j DROP"
          print "-A DOCKER-USER -p tcp -m conntrack --ctstate NEW --ctorigdstport " ports " -j GETNF-LOCAL"
          print e; done=1}' "$FILE" > "$FILE.new"
      grep -qF "$BEGIN" "$FILE.new" || { rm -f "$FILE.new"; echo "could not find the DOCKER-USER chain in $FILE" >&2; exit 1; }
      mv "$FILE.new" "$FILE"; chmod 644 "$FILE"
    fi
    echo "applied. DOCKER-USER now:"; iptables -S DOCKER-USER | head -5; iptables -S $CHAIN
    echo "Check from ANOTHER device: nc -zv -w3 192.168.4.4 54321 (and the IPv6 address); all four ports should time out."
    ;;
  *) echo "usage: $0 [--status|--remove]" >&2; exit 2 ;;
esac
