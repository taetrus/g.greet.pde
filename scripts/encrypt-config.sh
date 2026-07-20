#!/usr/bin/env bash
#
# Encrypts a config value for configs/<bundle>/conf/*.properties.
# All logic lives in scripts/EncryptConfig.java (single-file source-launch).
#   encrypt-config.sh <plaintext>            print ENC(...) to paste into a .properties file
#   encrypt-config.sh --decrypt 'ENC(...)'   round-trip check: print the plaintext

set -euo pipefail
cd "$(dirname "$0")/.."
exec java scripts/EncryptConfig.java "$@"
