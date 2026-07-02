#!/usr/bin/env bash
#
# Boots the three greet bundles inside Equinox + Felix SCR, with the Gogo shell
# and Equinox console, so you can observe bundle + DS status interactively. This
# matches Deployment/launch/greet.launch (the Eclipse Equinox launch config).
# On startup it prints the three DS lines, then drops to a console prompt:
#   lb                list bundles            ss          short bundle status
#   scr:list          list DS components      scr:info N  component detail
#   close             stop the framework and exit
# Expects:
#   - scripts/build-bundles.sh has produced Deployment/build/*.jar
#   - mvn -f obfuscation/pom.xml package has produced the obfuscated bundle jars
#
# Env:
#   USE_PLAIN=1     run the un-obfuscated bundles (A/B comparison)
#   GREET_MODE=check  non-interactive: activate DS, print, exit (for scripted checks)

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

# Pick the obfuscated or plain jar for one greet bundle. Falls back to the
# plain jar (with a note) if the obfuscated one hasn't been built yet.
bundle_for() {
  local sn="$1" obf="obfuscation/target/$1-obf.jar"
  if [ "${USE_PLAIN:-0}" = "1" ]; then
    echo ">> using PLAIN $sn: $B/$sn.jar" >&2
    echo "$B/$sn.jar"
  elif [ -f "$obf" ]; then
    echo ">> using OBFUSCATED $sn: $obf" >&2
    echo "$obf"
  else
    echo ">> NOTE: $obf not found, falling back to PLAIN $sn" >&2
    echo "$B/$sn.jar"
  fi
}

IMP="$(bundle_for com.kk.greet.imp)"
APP="$(bundle_for com.kk.greet.app)"

echo ">> compiling launcher (release 8)"
javac --release 8 -cp "$EQUINOX" -d "$RUN" scripts/Launcher.java

# Bundle resolve order: OSGi util -> DS API -> SCR -> api -> imp -> app
echo ">> launching Equinox + Felix SCR + Gogo console"
# Bundle set mirrors Deployment/launch/greet.launch: Gogo + console for
# observability, OSGi util + DS API + SCR, then the three greet bundles.
java -cp "$EQUINOX:$RUN" Launcher "$RUN/storage" \
  "$(pick org.apache.felix.gogo.runtime_)" \
  "$(pick org.apache.felix.gogo.command_)" \
  "$(pick org.apache.felix.gogo.shell_)" \
  "$(pick org.eclipse.equinox.console_)" \
  "$(pick org.osgi.util.function_)" \
  "$(pick org.osgi.util.promise_)" \
  "$(pick org.osgi.service.component_)" \
  "$(pick org.osgi.service.component.annotations_)" \
  "$(pick org.apache.felix.scr_)" \
  "$B/com.kk.greet.api.jar" \
  "$IMP" \
  "$APP"
