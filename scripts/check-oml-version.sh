#!/usr/bin/env bash
# The three Gradle repositories consume each other by Maven coordinate at one shared version, and
# only this repo's oml_version is a *request*: the loader and the plugin publish whatever their own
# gradle.properties says. A mismatch surfaces as "Could not find org.ohmyloader:…" after minutes of
# building, which reads like a broken repository instead of the one file that has to be bumped.
#
# Sibling checkouts are the CI layout — every job checks OhMyLoader and OhMyLoaderGradle out next to
# this repo. Locally, a sibling that is not there is reported and skipped, not failed.
set -uo pipefail
cd "$(dirname "$0")/.."

# Java properties accept spaces around the `=`; whitespace is stripped before matching so the read
# here agrees with what Gradle actually resolves.
version_in() { sed -n 's/[[:space:]]//g; s/^oml_version=//p' "$1/gradle.properties" | head -1; }

ours="$(version_in .)"
if [[ -z "$ours" ]]; then
  echo "gradle.properties declares no oml_version" >&2
  exit 1
fi
echo "this repo resolves org.ohmyloader:* at $ours"

status=0
for sibling in OhMyLoader OhMyLoaderGradle; do
  if [[ ! -f "$sibling/gradle.properties" ]]; then
    echo "$sibling: no checkout beside this repo, not compared"
    continue
  fi
  theirs="$(version_in "$sibling")"
  if [[ "$theirs" != "$ours" ]]; then
    echo "$sibling publishes $theirs, this repo resolves $ours — align oml_version in gradle.properties" >&2
    status=1
  else
    echo "$sibling publishes $theirs (matches)"
  fi
done
exit "$status"
