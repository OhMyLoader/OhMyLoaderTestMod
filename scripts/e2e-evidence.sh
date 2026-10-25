#!/usr/bin/env bash
# Reproducibility context for one E2E side. Sourced by e2e-server.sh / e2e-client.sh:
#
#   e2e_evidence_start <side> <log>
#   e2e_evidence_finish <exit-code>
#
# The context is written before the game starts, so a killed run still leaves its inputs behind.
# The result is written from the caller's EXIT trap and carries the verdict line parsed from the log.
# OML_E2E_CONTEXT / OML_E2E_RESULT override the default build/e2e-{context,result}-<side>.json paths;
# the client uses that to keep its nested world-generation server run separate.

e2e_evidence_json() {
  printf '%s' "${1:-}" | tr '\r\n' '  ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

e2e_evidence_sha() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | sed 's/^.*= //'
  else
    printf 'unavailable'
  fi
}

e2e_evidence_repo_dir() {
  local name="$1" explicit=""
  case "$name" in
    loader) explicit="${OML_LOADER_DIR:-}" ;;
    gradle) explicit="${OML_GRADLE_DIR:-}" ;;
    testmod) explicit="${OML_TESTMOD_DIR:-}" ;;
  esac
  if [ -n "$explicit" ]; then printf '%s' "$explicit"; return; fi
  case "$name" in
    loader)
      if [ -e "OhMyLoader/.git" ]; then printf 'OhMyLoader'
      elif [ -e "../OhMyLoader/.git" ]; then printf '../OhMyLoader'
      fi
      ;;
    gradle)
      if [ -e "OhMyLoaderGradle/.git" ]; then printf 'OhMyLoaderGradle'
      elif [ -e "../OhMyLoaderGradle/.git" ]; then printf '../OhMyLoaderGradle'
      fi
      ;;
    testmod)
      if [ -e ".git" ]; then printf '.'
      fi
      ;;
  esac
}

e2e_evidence_repo_sha() {
  local name="$1" override="" dir
  case "$name" in
    loader) override="${OML_LOADER_SHA:-}" ;;
    gradle) override="${OML_GRADLE_SHA:-}" ;;
    testmod) override="${OML_TESTMOD_SHA:-}" ;;
  esac
  if [ -n "$override" ]; then printf '%s' "$override"; return; fi
  dir="$(e2e_evidence_repo_dir "$name")"
  if [ -n "$dir" ] && [ -e "$dir/.git" ]; then
    git -C "$dir" rev-parse HEAD 2>/dev/null || printf 'unknown'
  else
    printf 'unknown'
  fi
}

e2e_evidence_repo_dirty() {
  local name="$1" dir status
  dir="$(e2e_evidence_repo_dir "$name")"
  if [ -z "$dir" ] || [ ! -e "$dir/.git" ]; then printf 'null'; return; fi
  status="$(git -C "$dir" status --porcelain 2>/dev/null || true)"
  if [ "$name" = "testmod" ] && [ -n "$status" ]; then
    # The CI layout checks the loader and plugin out inside this repo; they are inputs, not dirt.
    status="$(printf '%s\n' "$status" | grep -v -E '^(\?\?| M|A |D ) (OhMyLoader|OhMyLoaderGradle)/' || true)"
  fi
  if [ -n "$status" ]; then printf 'true'; else printf 'false'; fi
}

e2e_evidence_property() {
  local file="$1" key="$2"
  if [ -f "$file" ]; then
    sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$file" | head -1
  fi
}

e2e_evidence_start() {
  local side="${1:-unknown}" log="${2:-}" context game seed seed_source
  context="${OML_E2E_CONTEXT:-build/e2e-context-${side}.json}"
  game="${OML_E2E_GAME_VERSION:-${OML_GAME_VERSION:-26.3}}"
  seed="${OML_E2E_SEED:-oml-e2e}"
  seed_source="OML_E2E_SEED/default"
  if [ -f run/server/server.properties ]; then
    local file_seed
    file_seed="$(sed -n 's/^level-seed=//p' run/server/server.properties | head -1)"
    if [ -n "$file_seed" ]; then
      seed="$file_seed"
      seed_source="run/server/server.properties"
    fi
  fi

  local loader_dir gradle_dir testmod_dir
  loader_dir="$(e2e_evidence_repo_dir loader)"
  gradle_dir="$(e2e_evidence_repo_dir gradle)"
  testmod_dir="$(e2e_evidence_repo_dir testmod)"

  mkdir -p "$(dirname "$context")"
  local artifacts_tmp file
  artifacts_tmp="$(mktemp)"
  : > "$artifacts_tmp"
  local first=1
  e2e_evidence_add_artifact() {
    local file="$1"
    [ -f "$file" ] || return 0
    local hash
    hash="$(e2e_evidence_sha "$file")"
    if [ "$first" -eq 0 ]; then printf ',\n' >> "$artifacts_tmp"; fi
    first=0
    printf '    {"path": "%s", "sha256": "%s"}' \
      "$(e2e_evidence_json "$file")" "$hash" >> "$artifacts_tmp"
  }

  if [ -d "$HOME/.m2/repository/org/ohmyloader" ]; then
    while IFS= read -r file; do
      e2e_evidence_add_artifact "$file"
    done < <(find "$HOME/.m2/repository/org/ohmyloader" -type f -name '*.jar' 2>/dev/null | sort)
  fi
  for file in build/libs/*.jar; do
    [ -e "$file" ] && e2e_evidence_add_artifact "$file"
  done
  for file in "run/$game/minecraft"/*.jar "run/$game/minecraft"/*.json; do
    [ -e "$file" ] && e2e_evidence_add_artifact "$file"
  done

  local uname_line java_line glx_line mesa_version xvfb_version cpus
  uname_line="$(uname -a 2>/dev/null || printf 'unknown')"
  java_line="$(java -version 2>&1 | head -1 || printf 'unknown')"
  glx_line="$(glxinfo -B 2>/dev/null | head -8 || true)"
  mesa_version="$(dpkg-query -W -f='${Version}' mesa-vulkan-drivers 2>/dev/null || printf 'unknown')"
  xvfb_version="$(dpkg-query -W -f='${Version}' xvfb 2>/dev/null || printf 'unknown')"
  cpus="$(nproc 2>/dev/null || printf 'unknown')"

  # 26.x ships unobfuscated jars, so there is no mappings artifact to pin.
  cat > "$context" <<EOF
{
  "schema": 1,
  "side": "$(e2e_evidence_json "$side")",
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "ci": {
    "workflow": "$(e2e_evidence_json "${GITHUB_WORKFLOW:-local}")",
    "run_id": "$(e2e_evidence_json "${GITHUB_RUN_ID:-local}")",
    "job": "$(e2e_evidence_json "${GITHUB_JOB:-local}")",
    "ref": "$(e2e_evidence_json "${GITHUB_REF:-local}")",
    "sha": "$(e2e_evidence_json "${GITHUB_SHA:-local}")",
    "runner_os": "$(e2e_evidence_json "${RUNNER_OS:-local}")",
    "runner_image": "$(e2e_evidence_json "${ImageOS:-local}")"
  },
  "repos": {
    "loader": {
      "path": "$(e2e_evidence_json "$loader_dir")",
      "sha": "$(e2e_evidence_json "$(e2e_evidence_repo_sha loader)")",
      "dirty": $(e2e_evidence_repo_dirty loader)
    },
    "gradle": {
      "path": "$(e2e_evidence_json "$gradle_dir")",
      "sha": "$(e2e_evidence_json "$(e2e_evidence_repo_sha gradle)")",
      "dirty": $(e2e_evidence_repo_dirty gradle)
    },
    "testmod": {
      "path": "$(e2e_evidence_json "$testmod_dir")",
      "sha": "$(e2e_evidence_json "$(e2e_evidence_repo_sha testmod)")",
      "dirty": $(e2e_evidence_repo_dirty testmod)
    }
  },
  "versions": {
    "loader_gradle_properties": "$(e2e_evidence_json "$(e2e_evidence_property "${loader_dir:+$loader_dir/gradle.properties}" oml_version)")",
    "gradle_gradle_properties": "$(e2e_evidence_json "$(e2e_evidence_property "${gradle_dir:+$gradle_dir/gradle.properties}" oml_version)")",
    "testmod_gradle_properties": "$(e2e_evidence_json "$(e2e_evidence_property gradle.properties oml_version)")",
    "oml_version_override": "$(e2e_evidence_json "${OML_E2E_OML_VERSION:-}")",
    "minecraft": "$(e2e_evidence_json "$game")",
    "mappings": "none"
  },
  "artifacts": [
$(cat "$artifacts_tmp")
  ],
  "environment": {
    "uname": "$(e2e_evidence_json "$uname_line")",
    "java": "$(e2e_evidence_json "$java_line")",
    "cpus": "$(e2e_evidence_json "$cpus")",
    "glxinfo": "$(e2e_evidence_json "$glx_line")",
    "mesa_vulkan_drivers": "$(e2e_evidence_json "$mesa_version")",
    "xvfb": "$(e2e_evidence_json "$xvfb_version")",
    "display": "$(e2e_evidence_json "${DISPLAY:-}")",
    "libgl_always_software": "$(e2e_evidence_json "${LIBGL_ALWAYS_SOFTWARE:-}")",
    "mesa_loader_driver_override": "$(e2e_evidence_json "${MESA_LOADER_DRIVER_OVERRIDE:-}")"
  },
  "run": {
    "side": "$(e2e_evidence_json "$side")",
    "seed": "$(e2e_evidence_json "$seed")",
    "seed_source": "$(e2e_evidence_json "$seed_source")",
    "ticks": "$(e2e_evidence_json "${OML_E2E_TICKS:-}")",
    "grace_seconds": "$(e2e_evidence_json "${OML_E2E_GRACE_SECONDS:-}")",
    "watchdog_seconds": "$(e2e_evidence_json "${OML_E2E_WATCHDOG:-}")",
    "timeout_seconds": "$(e2e_evidence_json "${OML_E2E_TIMEOUT:-}")",
    "ready_timeout_seconds": "$(e2e_evidence_json "${OML_E2E_READY_TIMEOUT:-}")",
    "stop_timeout_seconds": "$(e2e_evidence_json "${OML_E2E_STOP_TIMEOUT:-}")",
    "no_join": "$(e2e_evidence_json "${OML_E2E_NO_JOIN:-}")",
    "quickplay": "$(e2e_evidence_json "${OML_E2E_QUICKPLAY:-}")",
    "expect_extra": "$(e2e_evidence_json "${OML_E2E_EXPECT_EXTRA:-}")",
    "multiplayer": "$(e2e_evidence_json "${OML_E2E_MULTIPLAYER:-}")",
    "multiplayer_address": "$(e2e_evidence_json "${OML_E2E_MULTIPLAYER_ADDRESS:-}")",
    "console_mirror": "$(e2e_evidence_json "${OML_LOG_FILE:-}")",
    "run_dir": "run"
  },
  "log": "$(e2e_evidence_json "$log")"
}
EOF
  rm -f "$artifacts_tmp"
  export OML_E2E_SIDE="$side"
  export OML_E2E_LOG_PATH="$log"
  printf 'e2e-evidence: context %s\n' "$context"
}

e2e_evidence_finish() {
  local rc="${1:-1}"
  local side="${OML_E2E_SIDE:-unknown}"
  local context="${OML_E2E_CONTEXT:-build/e2e-context-${side}.json}"
  local result="${OML_E2E_RESULT:-build/e2e-result-${side}.json}"
  local log="${OML_E2E_LOG_PATH:-}"
  local verdict="UNKNOWN" checks="0" failures="0" line="" tail=""
  if [ -n "$log" ] && [ -f "$log" ]; then
    line="$(grep -E '\[OML-E2E\] RESULT (PASS|FAIL)' "$log" | tail -1 || true)"
    if [ -n "$line" ]; then
      verdict="$(printf '%s' "$line" | sed -n 's/.*RESULT \(PASS\|FAIL\).*/\1/p')"
      checks="$(printf '%s' "$line" | sed -n 's/.*checks=\([0-9][0-9]*\).*/\1/p')"
      failures="$(printf '%s' "$line" | sed -n 's/.*failures=\([0-9][0-9]*\).*/\1/p')"
    fi
    tail="$(tail -20 "$log" 2>/dev/null || true)"
  fi
  [ -n "$checks" ] || checks=0
  [ -n "$failures" ] || failures=0
  mkdir -p "$(dirname "$result")"
  cat > "$result" <<EOF
{
  "schema": 1,
  "side": "$(e2e_evidence_json "$side")",
  "context": "$(e2e_evidence_json "$context")",
  "finished_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "exit_code": $rc,
  "result": "$(e2e_evidence_json "$verdict")",
  "checks": $checks,
  "failures": $failures,
  "log": "$(e2e_evidence_json "$log")",
  "log_tail": "$(e2e_evidence_json "$tail")"
}
EOF
  printf 'e2e-evidence: result %s (exit=%s result=%s checks=%s failures=%s)\n' \
    "$result" "$rc" "$verdict" "$checks" "$failures"
  return 0
}
