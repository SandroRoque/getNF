#!/usr/bin/env bash
# Creates the Play upload key (D105) outside the repo, and the file Gradle reads to sign release builds:
#   ~/.config/getnf/upload-key.jks          the key (PKCS12, RSA 4096, alias "upload", valid ~27 years)
#   ~/.config/getnf/upload-key.properties   where it is and its password (readable only by you)
# Run it yourself in a terminal: it asks for the password, which nobody else should see. Afterwards keep a copy of
# both files and the password somewhere safe (a password manager). Losing the upload key means asking Google for a
# reset; with Play App Signing, Google keeps the key that actually signs the app for users.
set -euo pipefail

dir="$HOME/.config/getnf"
ks="$dir/upload-key.jks"
props="$dir/upload-key.properties"
if [[ -e "$ks" || -e "$props" ]]; then
  echo "Already there: $ks / $props. Not overwriting a key; move them away first if you really mean to." >&2
  exit 1
fi

default_name="$(git config user.name 2>/dev/null || true)"
read -rp "Name on the certificate [${default_name:-}]: " name
name="${name:-$default_name}"
[[ -n "$name" ]] || { echo "A name is needed." >&2; exit 1; }

read -rsp "Password for the upload key (at least 8 characters): " pw; echo
read -rsp "Again: " pw2; echo
[[ "$pw" == "$pw2" ]] || { echo "The passwords differ." >&2; exit 1; }
(( ${#pw} >= 8 )) || { echo "Too short." >&2; exit 1; }

umask 077
mkdir -p "$dir"
chmod 700 "$dir"
# Passed by environment, not on the command line, so it doesn't show in the process list.
GETNF_KEY_PASSWORD="$pw" keytool -genkeypair -keystore "$ks" -storetype PKCS12 -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=$name, C=BR" \
  -storepass:env GETNF_KEY_PASSWORD -keypass:env GETNF_KEY_PASSWORD

# Java properties: a backslash in the password must be doubled.
escaped="${pw//\\/\\\\}"
{
  echo "storeFile=$ks"
  echo "storePassword=$escaped"
  echo "keyAlias=upload"
  echo "keyPassword=$escaped"
} > "$props"
chmod 600 "$ks" "$props"

echo
echo "Done. Release builds now sign with this key:  cd android && ./gradlew :app:bundleRelease"
echo "Back up $ks, $props and the password now."
GETNF_KEY_PASSWORD="$pw" keytool -list -v -keystore "$ks" -storepass:env GETNF_KEY_PASSWORD -alias upload 2>/dev/null | grep -i "SHA-256" || true
