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
#   - mvn -f obfuscation/pom.xml package has produced the obfuscated imp jar
#
# Env:
#   USE_PLAIN=1     run the un-obfuscated imp bundle (A/B comparison)
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
  "$B/com.kk.greet.app.jar"
