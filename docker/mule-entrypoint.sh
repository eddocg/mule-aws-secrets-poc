#!/usr/bin/env bash
#
# Container entrypoint for mule-aws-secrets-poc.
#
# Responsibilities, in order:
#   1. verify the mounted Mule EE license exists
#   2. verify no license is already installed in the image
#   3. install the license into $MULE_HOME/conf
#   4. fail startup immediately if installation fails
#   5. confirm $MULE_HOME/conf/muleLicenseKey.lic was created
#   6. exec Mule in the foreground so it becomes PID 1 and receives container signals
#
# The license is mounted at runtime and is never baked into the image. License file
# contents are never printed, echoed, or logged by this script.
#
set -euo pipefail

MULE_HOME="${MULE_HOME:-/opt/mule}"
MULE_LICENSE_PATH="${MULE_LICENSE_PATH:-/run/secrets/mule/license.lic}"
INSTALLED_LICENSE="${MULE_HOME}/conf/muleLicenseKey.lic"

log() {
    printf '%s [mule-entrypoint] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fatal() {
    log "FATAL: $*"
    exit 1
}

# --- 1. the mounted license must be present and non-empty -------------------
if [[ ! -f "${MULE_LICENSE_PATH}" ]]; then
    fatal "Mule EE license not found at ${MULE_LICENSE_PATH}. Expected the Kubernetes Secret 'mule-runtime-license' (key 'license.lic') to be mounted read-only at that path."
fi

if [[ ! -s "${MULE_LICENSE_PATH}" ]]; then
    fatal "Mule EE license at ${MULE_LICENSE_PATH} is empty. Check how the Kubernetes Secret was created."
fi

if [[ ! -r "${MULE_LICENSE_PATH}" ]]; then
    fatal "Mule EE license at ${MULE_LICENSE_PATH} is not readable by uid $(id -u). Check the Secret volume defaultMode and the pod runAsUser/fsGroup."
fi

# --- 2. refuse to run if a license was baked into the image -----------------
# A license present before installation would mean the image itself shipped
# credentials, which this project explicitly forbids.
if [[ -e "${INSTALLED_LICENSE}" ]]; then
    fatal "${INSTALLED_LICENSE} already exists before installation. A license must never be baked into the image; rebuild it without one."
fi

# --- 3./4. install the license, failing fast ---------------------------------
log "Installing Mule EE license from ${MULE_LICENSE_PATH} (contents are never logged)"

install_log="$(mktemp)"
# shellcheck disable=SC2064
trap "rm -f '${install_log}'" EXIT

if ! "${MULE_HOME}/bin/mule" -installLicense "${MULE_LICENSE_PATH}" >"${install_log}" 2>&1; then
    log "License installation command failed. Installer output follows (it reports status only, not license contents):"
    cat "${install_log}" >&2
    fatal "Mule license installation failed."
fi

# --- 5. confirm the license actually landed in conf/ -------------------------
# Authoritative check: the installer's exit code alone is not sufficient evidence.
if [[ ! -s "${INSTALLED_LICENSE}" ]]; then
    log "License installation reported success but produced no license file. Installer output follows:"
    cat "${install_log}" >&2
    fatal "${INSTALLED_LICENSE} was not created."
fi

log "Mule EE license installed successfully at ${INSTALLED_LICENSE}"

# --- 6. hand over to Mule in the foreground ---------------------------------
# Running bin/mule with no start/stop argument keeps Mule in the foreground.
# exec replaces this shell so Mule is PID 1 and receives SIGTERM directly on
# pod termination, instead of being killed after the grace period.
log "Starting Mule Runtime in foreground (MULE_HOME=${MULE_HOME})"

trap - EXIT
rm -f "${install_log}"

exec "${MULE_HOME}/bin/mule" "$@"
