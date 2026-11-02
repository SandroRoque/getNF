#!/usr/bin/env bash
# The Google Play Developer API's credentials for tools/play.py (D159). Run by the user, once, with gcloud signed in:
#
#   tools/setup-play-api.sh [project-id]     default project id: caderneta-play
#
# Makes (or reuses) a Google Cloud project, turns the Play Developer API on in it, makes a service account
# play-publisher and a JSON key for it at ~/.config/getnf/play-api.json (0600, beside the upload key). Doesn't touch
# gcloud's default project or anyone's application-default credentials. No billing is needed.
#
# Then, in Play Console › Users and permissions › Invite new users: the service account's e-mail (printed at the end),
# app Caderneta, permissions "View app information", "Manage testing tracks", "Release to testing tracks" (and later
# "Release to production" if wanted). Invites take effect at once for service accounts.
#
# Back up the key with the upload key (~/.config/getnf/). To revoke: delete the user in Play Console, then
#   gcloud iam service-accounts keys list --iam-account=<email> --project=<project>   and keys delete.
set -euo pipefail
project="${1:-caderneta-play}"
sa="play-publisher"
email="$sa@$project.iam.gserviceaccount.com"
key="$HOME/.config/getnf/play-api.json"

if ! gcloud projects describe "$project" >/dev/null 2>&1; then
  echo "creating project $project"
  gcloud projects create "$project" --name="Caderneta Play API"
fi
echo "turning on androidpublisher.googleapis.com"
gcloud services enable androidpublisher.googleapis.com --project="$project"
if ! gcloud iam service-accounts describe "$email" --project="$project" >/dev/null 2>&1; then
  echo "creating service account $email"
  gcloud iam service-accounts create "$sa" --project="$project" --display-name="Play publisher (tools/play.py)"
fi
if [ -e "$key" ]; then
  echo "$key exists already; delete it first to make a new key"
else
  mkdir -p "$(dirname "$key")"
  (umask 077; gcloud iam service-accounts keys create "$key" --iam-account="$email" --project="$project")
  chmod 600 "$key"
fi
echo
echo "Done. Now invite this e-mail in Play Console › Users and permissions (app Caderneta):"
echo "  $email"
echo "Then: tools/play.py status"
