# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A minimal **OSGi Declarative Services (DS)** demo built as an **Eclipse PDE** (Plug-in Development Environment) workspace. It demonstrates service publishing and dependency injection across bundle boundaries. There is no Maven/Gradle build — bundles are compiled and launched from inside Eclipse.

Target runtime: **Equinox OSGi** with **Apache Felix SCR** (Service Component Runtime). Compiler/runtime target is **JavaSE-1.8** (`Bundle-RequiredExecutionEnvironment`).

## Architecture

Three bundles plus a deployment definition. They communicate **only** through the `IGreet` API contract — `app` and `imp` never reference each other's classes directly.

- `com.kk.greet.api` — the contract. Exports package `com.kk.greet.api` containing the `IGreet` interface. This is the only package shared between bundles (`Export-Package` here, `Import-Package` in the others).
- `com.kk.greet.imp` — the service **provider**. `Greet` implements `IGreet` and is registered as a DS component that `provide`s the `IGreet` service.
- `com.kk.greet.app` — the service **consumer**. `App` is a DS component with a mandatory (`1..1`) `@Reference` to `IGreet`; the runtime injects the `Greet` instance.
- `com.kk.greet.ui` — a Swing window as a DS component (`GreetFrame`), laid out with **MigLayout from a `Bundle-ClassPath` nested jar** (`lib/miglayout-3.7.4-swing.jar`) to prove bundle-embedded third-party libraries survive the build + obfuscation pipeline. All visible text comes from **external resource bundles** in `configs/lang/messages[_tr|_en].properties` (UTF-8, loaded via a custom `ResourceBundle.Control` because Java 8 reads .properties as ISO-8859-1 by default). Language is chosen at launch via `-Dgreet.lang` or the `GREET_LANG` env var (default `en`; unknown values fall back to the base/English bundle — the platform default locale is deliberately ignored). In `GREET_MODE=check` runs the window is skipped but the localized title is printed for scripted assertions.
- `Deployment/` — PDE target platform (`greet.target`) and the bundle JARs (`Deployment/target/`) needed to run: Equinox, Felix Gogo shell/runtime/command, Felix SCR, and OSGi component/util bundles.

### How wiring works (read this before editing components)

Each DS component is declared in **two places that must agree**:

1. Java annotations — `@Component`, `@Activate` (the `start()` method), and `@Reference` (the injected field).
2. The generated SCR descriptor in `OSGI-INF/*.xml`, listed via `Service-Component:` in that bundle's `META-INF/MANIFEST.MF`.

Eclipse PDE regenerates the `OSGI-INF/*.xml` from the annotations on build. If you change a component's annotations (e.g. add a `@Reference`, rename the `activate` method), the corresponding XML **and** the `Service-Component:`/`Import-Package:` lines in the MANIFEST must end up consistent — otherwise the component will not bind at runtime. Note the descriptors currently use different SCR namespace versions (`imp` → v1.1.0, `app` → v1.3.0); preserve whatever PDE generates rather than hand-editing.

Startup flow: SCR activates `Greet` (prints `Greet.start()`), registers it as `IGreet`; SCR then satisfies `App`'s reference, activates `App` (prints `App.start()`), and `App.start()` calls `greetService.greet()` (prints `Greet.greet()`).

## Building and running

This is an Eclipse PDE project — there is no command-line build. Open the workspace in Eclipse with PDE installed.

- **Build**: Eclipse builds automatically; `build.properties` in each bundle maps `src/ → bin/`.
- **Set the target platform**: open `Deployment/greet.target` and click *Set as Active Target Platform* (it points at `Deployment/target/` plus the `org.eclipse.rcp` feature).
- **Run**: create an *OSGi Framework* run configuration including the three `com.kk.greet.*` bundles plus the Felix SCR / Gogo / Equinox bundles from the target. Success looks like the three print lines above in the console.

## Obfuscation (name obfuscation via ProGuard + Maven)

The `obfuscation/` module obfuscates the **already-built** bundle JARs — PDE (or
`scripts/build-bundles.sh`) stays the compiler; Maven only obfuscates. There is **no
per-bundle ProGuard configuration**: `ObfuscationRunner`
(`obfuscation/src/main/java/com/kk/greet/obfuscation/ObfuscationRunner.java`) scans every
jar in `Deployment/build/`, derives the keep rules from each bundle's own metadata, and
runs ProGuard per bundle in-process. Adding a fourth bundle needs zero config here.

How the keep rules are derived (metadata → rule):

| bundle metadata | generated rule |
|---|---|
| `Export-Package: p` (MANIFEST.MF) | `-keep class p.* { *; }` — exported API stays intact |
| `Bundle-Activator: C` | `-keep class C { *; }` |
| `Bundle-ClassPath: lib/x.jar` | `-keep class <pkg>.* { *; }` for every package in the nested jar — third-party libraries are never renamed (they may use reflection internally); the jar itself is carried through as a resource |
| DS `implementation@class = C` (OSGI-INF XML) | `-keep class C { <init>(...); }` |
| `@activate/@deactivate/@modified` + the DS default names | `-keepclassmembers` on those methods |
| `reference@bind/@unbind/@updated` | `-keepclassmembers` on those methods |
| `reference@field` (SCR ≥1.3) / `@activation-fields` (1.4) | `-keepclassmembers` on those fields |

A jar with no DS components, no activator, and only exported packages (i.e.
`com.kk.greet.api`) is classified pure-API and **copied through unchanged**. The generated
rules land in `obfuscation/target/keep/<symbolic-name>.pro` (auditable; regenerated every
build). Flags common to all bundles (`-target 1.8`, `-dontoptimize`, `-dontshrink`,
`-keepattributes`) live in `obfuscation/proguard-common.conf`. Outputs per obfuscated
bundle: `obfuscation/target/<symbolic-name>-obf.jar` + `<symbolic-name>-mapping.txt`
(there is no single `mapping.txt` anymore). SCR namespace parsing is version-agnostic
(v1.0–v1.5); `Service-Component` wildcards (`OSGI-INF/*.xml`) and quoted version ranges in
`Export-Package` are handled.

Pipeline (any current JDK works for every step — see the JDK note below).
Each step has a `.sh` (macOS/Linux/Git-Bash/WSL) and a `.bat` (Windows cmd) form.

**macOS / Linux:**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home   # any current JDK
./scripts/build-bundles.sh                 # PDE-export stand-in: auto-discovers bundle projects; emits Java 8 bytecode
mvn -f obfuscation/pom.xml package          # -> obfuscation/target/*-obf.jar, keep/*.pro, *-mapping.txt
                                            #    honors $GREET_TARGET_DIR like build-bundles (-Ddeployment.target.dir= overrides)
./scripts/run-osgi.sh                       # boots Equinox + Felix SCR with the OBFUSCATED imp + app bundles
USE_PLAIN=1 ./scripts/run-osgi.sh           # same, with the un-obfuscated bundles (A/B baseline)
```

**Windows (cmd.exe):**
```
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21   :: any current JDK
scripts\build-bundles.bat                   :: same as above (both wrappers run scripts\BundleBuilder.java)
mvn -f obfuscation\pom.xml package          :: -> obfuscation\target\*-obf.jar, keep\*.pro, *-mapping.txt
scripts\run-osgi.bat                        :: boots Equinox + Felix SCR with the OBFUSCATED imp + app bundles
set USE_PLAIN=1 && scripts\run-osgi.bat     :: un-obfuscated bundles (A/B baseline); `set USE_PLAIN=` to clear
```

On startup all four print `Greet.start()` / `App.start()` / `Greet.greet()` on either OS.

`build-bundles.{sh,bat}` are thin wrappers around `scripts/BundleBuilder.java` (single-file
source-launch, so the build logic exists once, not per shell). It **auto-discovers** every
bundle project at the repo root (dir with `META-INF/MANIFEST.MF` + `build.properties`) and
builds each from its own metadata: `Import/Export-Package` + `Require-Bundle` → compile order (topological),
`Bundle-RequiredExecutionEnvironment` → `--release` level, `Bundle-ClassPath` → nested
library jars (`lib/*.jar`) on that project's compile classpath, `build.properties`
`source.*` → sources, `bin.includes` → shipped resources (e.g. `OSGI-INF/`, `lib/`). Compile classpath = dependency
projects' classes + all target-platform jars from `$GREET_TARGET_DIR` (scanned recursively,
so a `plugins/` subfolder layout works; missing/empty dir fails the build) or, unset,
`Deployment/target/` (missing/empty only warns). Optional args restrict the build to the
named project dirs. The `run-osgi.{sh,bat}` scripts resolve target-platform jars by
symbolic-name prefix (not pinned version). Bundle locations are passed to the framework via
`File.toURI()` (`scripts/Launcher.java`) so they form valid `file:` URLs on Windows paths
too. `.gitattributes` pins `*.sh` to LF and `*.bat` to CRLF so both work after a Windows
checkout.

### Interactive console (observing bundle + DS status)

`run-osgi.{sh,bat}` mirror `Deployment/launch/greet.launch`: they install the Gogo shell +
`org.eclipse.equinox.console` alongside Felix SCR, then drop to a live console (the framework
stays up via `eclipse.ignoreApp=true`, like the Eclipse launch's `-console -consoleLog`).
After the three DS lines, you get a `g!` prompt:

| command | shows |
|---------|-------|
| `ss` / `lb` | bundle states (ACTIVE / RESOLVED / STARTING) |
| `scr:list` | DS components and whether they're enabled/satisfied |
| `scr:info <id>` | one component's references, properties, activation |
| `close` | stop the framework and exit (answer `y` to confirm) |

`scr:list` registers a moment after startup (Felix SCR finishes activating), so if it reports
"Command not found", wait a second and retry. `scr:list` shows the component as
`com.kk.greet.imp.Greet` even on the obfuscated bundle — proof the DS entry point was kept.

For scripted/CI checks where you don't want an interactive prompt, set `GREET_MODE=check`:
the launcher activates DS, prints the three lines, and exits (no console).

What is and isn't obfuscated, and why (all derived automatically — see the table above):
- **Kept**: `com.kk.greet.imp.Greet` + `start` (from its DS descriptor) and
  `com.kk.greet.app.App` + `start` + injected field `greetService` (from its v1.3
  descriptor). Rename any of these and SCR can't load/activate/bind the component.
  `Greet.greet()` survives without a rule because `IGreet` is a `-libraryjars` type and
  ProGuard never renames methods overriding library types. `com.kk.greet.api` is
  copied through unchanged (pure API bundle).
- **Renamed**: `MessageFormatter` (a deliberately-added package-private helper) → `a`, proving
  internals are obfuscated while the DS surface stays intact. See
  `obfuscation/target/com.kk.greet.imp-mapping.txt` after a run. Likewise `com.kk.greet.ui`'s
  `Messages` → `a` while MigLayout inside `lib/` stays byte-identical (`Bundle-ClassPath`
  keep rules + carried through as a resource).
- `proguard-common.conf` uses `-target 1.8`, `-dontoptimize`, `-dontshrink` so the *only*
  transformation is renaming.

**Reality check on protection**: this is a low-cost deterrent, not a security boundary — bytecode
stays fully decompilable, there is no string/control-flow protection (those need a commercial tool
like Allatori/Zelix/DashO), and the DS class names you're forced to keep are exactly an attacker's
entry points.

**JDK note**: the project targets **Java 1.8** (`--release 8`, ProGuard `-target 1.8`; output is
class-file v52). The obfuscation runs under any reasonably current JDK: ProGuard is at **7.9.1**
(7.5.0 rejected `java.base` newer than JDK 21; the whole pipeline is verified under both
JDK 21 and JDK 25). JDKs built
without a `jmods/` directory (JEP 493 linkable run-time images, e.g. some Temurin 24+ builds)
are handled automatically — ObfuscationRunner dumps the `java.*` modules from the `jrt:` image
into `target/jdk-runtime-classes.jar` and uses that as `-libraryjars`.

## Encoding (UTF-8 everywhere)

Source may contain Turkish characters. Encoding is pinned at four layers, and all four must stay in place — on Turkish Windows the platform default is Cp1254, which produces `unmappable character (0xE7) for encoding UTF-8` errors when any layer falls back to it:

1. `.settings/org.eclipse.core.resources.prefs` in every project: `encoding/<project>=UTF-8` (governs the Eclipse editor and JDT builds; committed, so it survives workspace re-import).
2. `javacDefaultEncoding.. = UTF-8` in every `build.properties` (governs headless PDE/Ant export builds, which do **not** read the `.settings` prefs).
3. `.editorconfig` at the repo root: `charset = utf-8` (governs non-Eclipse editors).
4. Each bundle's main source file carries an "Encoding canary" comment with Turkish characters — if any tool regresses to Cp1254, compilation fails loudly at the canary instead of silently corrupting real strings. Do not remove these comments.

When adding a bundle, replicate layers 1, 2, and 4. Full details, verification steps, and the migration procedure for existing repos are in `ENCODING.md`.

## Conventions

- Bundle Java packages mirror the bundle symbolic name (`com.kk.greet.<api|imp|app>`); keep that mapping when adding bundles.
- A new bundle project at the repo root is picked up **automatically** by both `build-bundles` (BundleBuilder discovery) and the obfuscation pipeline (ObfuscationRunner) — no script or config edits. Only `run-osgi.{sh,bat}` list bundles explicitly (which bundles to *boot* is runtime config).
- New cross-bundle types belong in `com.kk.greet.api` and must be added to its `Export-Package`; consumers add the package to their `Import-Package`. Never import an implementation class across bundles.
- `bin/` holds compiled `.class` output and is regenerated by Eclipse — do not edit.
