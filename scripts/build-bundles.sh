#!/usr/bin/env bash
#
# CLI stand-in for Eclipse PDE's "Export > Deployable plug-ins and fragments".
# All logic lives in scripts/BundleBuilder.java (single-file source-launch): it
# auto-discovers PDE bundle projects and builds each from its own metadata.
# Args: optional project dirs; with none, every bundle project is built.

set -euo pipefail
cd "$(dirname "$0")/.."
exec java scripts/BundleBuilder.java "$@"
