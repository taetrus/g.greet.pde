#!/usr/bin/env bash
#
# CLI stand-in for Eclipse PDE's "Export > Deployable plug-ins and fragments".
# Compiles the api + imp bundles at Java 1.8 bytecode and assembles proper OSGi
# bundle JARs (MANIFEST.MF + OSGI-INF) into Deployment/build/.
#
# In normal development you would instead export from Eclipse; this script exists
# so the obfuscation pipeline can be built and verified without the IDE.
#
# Java 1.8 compatibility is enforced via `javac --release 8`.

set -euo pipefail
cd "$(dirname "$0")/.."

OUT="Deployment/build"
ANNOTATIONS="$(ls Deployment/target/org.osgi.service.component.annotations_*.jar | head -1)"

rm -rf "$OUT"
mkdir -p "$OUT/api-classes" "$OUT/imp-classes" "$OUT/app-classes"

echo ">> compiling com.kk.greet.api (release 8)"
javac --release 8 -d "$OUT/api-classes" \
  com.kk.greet.api/src/com/kk/greet/api/IGreet.java

echo ">> compiling com.kk.greet.imp (release 8)"
javac --release 8 -cp "$OUT/api-classes:$ANNOTATIONS" -d "$OUT/imp-classes" \
  com.kk.greet.imp/src/com/kk/greet/imp/*.java

echo ">> compiling com.kk.greet.app (release 8)"
javac --release 8 -cp "$OUT/api-classes:$ANNOTATIONS" -d "$OUT/app-classes" \
  com.kk.greet.app/src/com/kk/greet/app/App.java

echo ">> assembling bundle jars"
jar cfm "$OUT/com.kk.greet.api.jar" com.kk.greet.api/META-INF/MANIFEST.MF \
  -C "$OUT/api-classes" .

mkdir -p "$OUT/app-classes/OSGI-INF"
cp com.kk.greet.app/OSGI-INF/com.kk.greet.app.App.xml "$OUT/app-classes/OSGI-INF/"
jar cfm "$OUT/com.kk.greet.app.jar" com.kk.greet.app/META-INF/MANIFEST.MF \
  -C "$OUT/app-classes" .

# imp bundle must carry its DS descriptor under OSGI-INF/, exactly as PDE ships it.
mkdir -p "$OUT/imp-classes/OSGI-INF"
cp com.kk.greet.imp/OSGI-INF/com.kk.greet.imp.Greet.xml "$OUT/imp-classes/OSGI-INF/"
jar cfm "$OUT/com.kk.greet.imp.jar" com.kk.greet.imp/META-INF/MANIFEST.MF \
  -C "$OUT/imp-classes" .

echo ">> done:"
ls -1 "$OUT"/*.jar
