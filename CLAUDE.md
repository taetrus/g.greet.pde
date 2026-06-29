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

The `obfuscation/` module runs ProGuard over the **already-built** `com.kk.greet.imp`
bundle JAR — PDE (or `scripts/build-bundles.sh`) stays the compiler; Maven only obfuscates.
This demonstrates what name obfuscation can and cannot do in a DS runtime.

Pipeline (run the *obfuscation* step under JDK 21 — see the JDK note below).
Each step has a `.sh` (macOS/Linux/Git-Bash/WSL) and a `.bat` (Windows cmd) form.

**macOS / Linux:**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./scripts/build-bundles.sh                 # CLI stand-in for PDE "Export deployable plug-ins"; emits Java 8 bytecode
mvn -f obfuscation/pom.xml package          # -> obfuscation/target/com.kk.greet.imp-obf.jar + mapping.txt
./scripts/run-osgi.sh                       # boots Equinox + Felix SCR with the OBFUSCATED bundle
USE_PLAIN=1 ./scripts/run-osgi.sh           # same, with the un-obfuscated bundle (A/B baseline)
```

**Windows (cmd.exe):**
```
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21
scripts\build-bundles.bat                   :: same as above, emits Java 8 bytecode
mvn -f obfuscation\pom.xml package          :: -> obfuscation\target\com.kk.greet.imp-obf.jar + mapping.txt
scripts\run-osgi.bat                        :: boots Equinox + Felix SCR with the OBFUSCATED bundle
set USE_PLAIN=1 && scripts\run-osgi.bat     :: un-obfuscated bundle (A/B baseline); `set USE_PLAIN=` to clear
```

All four should print `Greet.start()` / `App.start()` / `Greet.greet()` on either OS. The
`.sh` and `.bat` scripts resolve target-platform jars by symbolic-name prefix (not pinned
version) and differ only in shell syntax and the classpath separator (`:` vs `;`). Bundle
locations are passed to the framework via `File.toURI()` (`scripts/Launcher.java`) so they
form valid `file:` URLs on Windows paths too. `.gitattributes` pins `*.sh` to LF and `*.bat`
to CRLF so both work after a Windows checkout.

What is and isn't obfuscated, and why (`obfuscation/proguard.conf`):
- **Kept**: `com.kk.greet.imp.Greet` + its `start`/`greet` methods, because
  `OSGI-INF/com.kk.greet.imp.Greet.xml` references the class by FQN and activates `start`.
  Rename any of these and SCR can't load/activate/bind the component. The exported
  `com.kk.greet.api` package is a library reference and is never renamed (it's the contract).
- **Renamed**: `MessageFormatter` (a deliberately-added package-private helper) → `a`, proving
  internals are obfuscated while the DS surface stays intact. See `mapping.txt` after a run.
- Config uses `-target 1.8`, `-dontoptimize`, `-dontshrink` so the *only* transformation is renaming.

**Reality check on protection**: this is a low-cost deterrent, not a security boundary — bytecode
stays fully decompilable, there is no string/control-flow protection (those need a commercial tool
like Allatori/Zelix/DashO), and the DS class names you're forced to keep are exactly an attacker's
entry points.

**JDK note**: the project targets **Java 1.8** (`--release 8`, ProGuard `-target 1.8`; output is
class-file v52). Run the obfuscation under **JDK 21** — ProGuard 7.5.0 rejects the JDK 26
`java.base` (class major 70 > supported 66). JDK 21's `java.base.jmod` (major 65) works.

## Conventions

- Bundle Java packages mirror the bundle symbolic name (`com.kk.greet.<api|imp|app>`); keep that mapping when adding bundles.
- New cross-bundle types belong in `com.kk.greet.api` and must be added to its `Export-Package`; consumers add the package to their `Import-Package`. Never import an implementation class across bundles.
- `bin/` holds compiled `.class` output and is regenerated by Eclipse — do not edit.
