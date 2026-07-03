# FOR_Kerem — the greet project, explained like a human

## What this project is

A tiny OSGi demo with a big point. Three bundles say hello to each other through
a service, and then we run ProGuard over them to answer a practical question:
**how do you obfuscate OSGi/DS bundles without breaking them — at scale, without
hand-writing config for every bundle?**

## The cast (three bundles)

Think of it as a restaurant:

- **`com.kk.greet.api`** — the *menu*. One interface, `IGreet`, in one exported
  package. Everyone reads the menu; nobody is allowed in the kitchen.
- **`com.kk.greet.imp`** — the *kitchen*. `Greet` implements `IGreet` and is
  published as a Declarative Services (DS) component. It also has a private
  helper, `MessageFormatter`, that nobody outside ever sees.
- **`com.kk.greet.app`** — the *customer*. `App` declares "I need an `IGreet`"
  (a DS reference) and the runtime hands it one. `app` and `imp` never touch
  each other's classes — only the menu.

At startup, Felix SCR (the DS runtime) wires it all up and you see:

```
Greet.start()
App.start()
Greet.greet()
```

## How DS wiring actually works (the key to everything below)

DS components are declared in XML files under `OSGI-INF/` inside each bundle
(Eclipse PDE generates them from `@Component` annotations). The XML says, *by
fully-qualified name in plain text*:

- which class is the component (`implementation class="com.kk.greet.imp.Greet"`)
- which method to call on activation (`activate="start"`)
- which fields/methods receive injected services (`field="greetService"`)

SCR reads those strings at runtime and uses reflection. That's the crux:
**the XML is a list of names that must survive obfuscation.** Rename `Greet`
to `a` and SCR throws `ClassNotFoundException`; rename the `greetService`
field and injection silently can't happen.

## The obfuscation pipeline

PDE (or `scripts/build-bundles.sh` / `.bat`, its CLI stand-in) compiles the
bundles. The scripts are thin wrappers around `scripts/BundleBuilder.java`,
which *auto-discovers* every bundle project in the repo root and derives the
whole build from each project's own metadata — compile order from
`Import/Export-Package`, `--release` level from
`Bundle-RequiredExecutionEnvironment`, sources from `build.properties`
`source.*`, shipped resources (like the DS XMLs) from `bin.includes`. Same
zero-config idea as the obfuscation step, applied to the build. Then:

```
mvn -f obfuscation/pom.xml package        (run under JDK 21)
./scripts/run-osgi.sh                     (boots Equinox with the obfuscated jars)
USE_PLAIN=1 ./scripts/run-osgi.sh         (same with plain jars — A/B proof)
```

Both variants print the same three lines. That's the whole demo: obfuscated
internals, intact behavior.

## The clever part: nobody writes keep rules

Here's the trick that scales to your real projects with dozens of bundles and
hundreds of classes.

Everything ProGuard must NOT rename is **already written down inside each
bundle** — machine-readably:

| where | what it names |
|---|---|
| `MANIFEST.MF` → `Export-Package` | the API packages other bundles compile against |
| `MANIFEST.MF` → `Service-Component` | which `OSGI-INF/*.xml` files exist |
| `MANIFEST.MF` → `Bundle-Activator` | a class the framework instantiates by name |
| `OSGI-INF/*.xml` | component class, lifecycle methods, bind methods, injected fields |

So `ObfuscationRunner` (one Java file in `obfuscation/src/main/java/`) simply
*reads the bundle's own metadata* and generates the keep rules:

1. Scan `Deployment/build/*.jar`.
2. For each jar, parse the manifest and every DS descriptor (any SCR version,
   v1.0–v1.5 — it matches XML local names, not namespace URIs).
3. Emit `obfuscation/target/keep/<bundle>.pro` — a keep rule per discovered
   name, each with a comment saying which metadata it came from. Open one; it's
   the audit trail.
4. Run ProGuard on that jar, in-process, with the generated rules + the shared
   flags from `obfuscation/proguard-common.conf`.

Two nice consequences:

- **A bundle that is pure API** (everything exported, no DS, no activator) is
  detected and *copied through unchanged* — you never obfuscate the contract.
- **Adding bundle number 4 (or 40) requires zero configuration.** Drop the jar
  in the input folder; its own metadata drives everything.

Subtle bonus: `Greet.greet()` needs no keep rule at all. `IGreet` comes in as a
ProGuard *library jar*, and ProGuard never renames a method that overrides a
library type's method. The API bundle protects its implementors for free.

## Porting this to your real projects

- Point the runner's input dir (property `bundles.input.dir` in
  `obfuscation/pom.xml`) at your PDE export folder.
- Point `deployment.target.dir` at your real target platform folder so
  framework types (`BundleActivator`, `ComponentContext`, …) resolve.
- That's it for DS-based bundles. Things the runner does NOT yet parse (add
  rules in the same pattern if you use them): Eclipse extension points
  (`plugin.xml` also names classes by FQN!), `Meta-Persistence`/JPA,
  serialization, and any `Class.forName` on your own internals.

## Lessons learned (the bugs teach the best)

1. **`pom`-packaged Maven modules create no `target/` dir** — nothing in the
   lifecycle does — and ProGuard won't `mkdir` for its outputs. First run on a
   fresh Windows clone died with `FileNotFoundException: ...mapping.txt`. The
   runner now `Files.createDirectories` everything it writes.
2. **OSGi manifest headers are not `split(",")`-able.** A version range like
   `version="[1.0,2.0)"` has a comma *inside quotes*. The header parser is
   quote-aware for exactly this reason.
3. **DS has defaults.** If the XML has no `activate=` attribute, SCR still
   calls a method literally named `activate`. The generator keeps the default
   names too, or those components would break only at runtime.
4. **ProGuard 7.5.0 can't read JDK 26's `java.base`** (class file version too
   new). Run the obfuscation step under JDK 21. The *output* stays Java 8
   bytecode (`-target 1.8`) either way.
5. **Name obfuscation is a deterrent, not a boundary.** The kept DS classes are
   precisely an attacker's entry points, and everything is still decompilable.
   For string encryption / control-flow obfuscation you need a commercial tool
   (Zelix, Allatori, DashO). Know what you're buying.

## Map of the repo

```
com.kk.greet.api|imp|app/    the three PDE bundles (src/, META-INF/, OSGI-INF/)
Deployment/                  target platform jars + greet.target + greet.launch
Deployment/build/            build-bundles output: the plain bundle jars
scripts/                     build-bundles + run-osgi wrappers (.sh + .bat),
                             BundleBuilder.java (metadata-driven build), Launcher.java
obfuscation/                 the Maven module: ObfuscationRunner + proguard-common.conf
obfuscation/target/          *-obf.jar, *-mapping.txt, keep/*.pro   (all generated)
```
