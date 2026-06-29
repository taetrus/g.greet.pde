#!/usr/bin/env bash
#
# Boots the three greet bundles inside Equinox + Felix SCR to prove the
# OBFUSCATED imp bundle still wires up via Declarative Services.
# Expects:
#   - scripts/build-bundles.sh has produced Deployment/build/*.jar
#   - mvn -f obfuscation/pom.xml package has produced the obfuscated imp jar
#
# Pass USE_PLAIN=1 to run the un-obfuscated imp bundle instead (for A/B comparison).

set -euo pipefail
cd "$(dirname "$0")/.."

T="Deployment/target"
B="Deployment/build"
# Resolve a target-platform jar by symbolic-name prefix, so a version bump in
# Deployment/target/ doesn't silently break this script. The trailing '_' keeps
# e.g. 'org.osgi.service.component_' from matching 'org.osgi.service.component.annotations_'.
pick() { ls "$T/$1"*.jar | head -1; }

EQUINOX="$(pick org.eclipse.osgi_)"
RUN="$B/run"
rm -rf "$RUN"; mkdir -p "$RUN/storage"

if [ "${USE_PLAIN:-0}" = "1" ]; then
  IMP="$B/com.kk.greet.imp.jar"
  echo ">> using PLAIN imp bundle: $IMP"
else
  IMP="obfuscation/target/com.kk.greet.imp-obf.jar"
  echo ">> using OBFUSCATED imp bundle: $IMP"
fi

echo ">> compiling launcher (release 8)"
javac --release 8 -cp "$EQUINOX" -d "$RUN" scripts/Launcher.java

# Bundle resolve order: OSGi util -> DS API -> SCR -> api -> imp -> app
echo ">> launching Equinox + Felix SCR"
java -cp "$EQUINOX:$RUN" Launcher "$RUN/storage" \
  "$(pick org.osgi.util.function_)" \
  "$(pick org.osgi.util.promise_)" \
  "$(pick org.osgi.service.component_)" \
  "$(pick org.apache.felix.scr_)" \
  "$B/com.kk.greet.api.jar" \
  "$IMP" \
  "$B/com.kk.greet.app.jar"
