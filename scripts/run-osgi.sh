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
EQUINOX="$(ls $T/org.eclipse.osgi_*.jar | head -1)"
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
  "$T/org.osgi.util.function_1.2.0.202109301733.jar" \
  "$T/org.osgi.util.promise_1.3.0.202212101352.jar" \
  "$T/org.osgi.service.component_1.5.1.202212101352.jar" \
  "$T/org.apache.felix.scr_2.2.12.jar" \
  "$B/com.kk.greet.api.jar" \
  "$IMP" \
  "$B/com.kk.greet.app.jar"
