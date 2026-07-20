# greet — OSGi DS bundles + zero-config ProGuard obfuscation

Four Eclipse PDE bundles wired through OSGi Declarative Services, plus a
pipeline that builds them from the command line, obfuscates them with ProGuard
(keep rules derived from each bundle's own metadata — no per-bundle config),
and boots them on Equinox + Felix SCR to prove behavior is unchanged.

Deeper docs: [CLAUDE.md](CLAUDE.md) (architecture + how rules are derived),
[FOR_Kerem.md](FOR_Kerem.md) (plain-language walkthrough),
[ENCODING.md](ENCODING.md) (UTF-8 hardening).

## Prerequisites

- **JDK** — any current one (verified on 21 and 25). Output bytecode is always
  Java 8, taken from each bundle's `Bundle-RequiredExecutionEnvironment`.
  `JAVA_HOME` must point at a JDK (not a JRE): the build compiles in-process.
- **Maven** — only for the obfuscation step.

## The pipeline

Every step has a `.sh` (macOS/Linux/Git-Bash/WSL) and a `.bat` (Windows cmd)
form with identical behavior.

### 1. Build the bundles

```bash
./scripts/build-bundles.sh                # macOS/Linux
scripts\build-bundles.bat                 # Windows
```

Auto-discovers every bundle project at the repo root (a directory holding
`META-INF/MANIFEST.MF` + `build.properties`) and derives the whole build from
bundle metadata: compile order from `Import-Package`/`Export-Package` **and**
`Require-Bundle`, `--release` level from `Bundle-RequiredExecutionEnvironment`,
nested library jars from `Bundle-ClassPath`, shipped resources from
`bin.includes`. Output: `Deployment/build/<symbolic-name>.jar`.

| knob | effect |
|---|---|
| *(args)* `build-bundles.sh proj1 proj2` | build only the named project dirs |
| `GREET_TARGET_DIR` | where the target-platform jars live (compile classpath). Scanned **recursively**, so an Eclipse `plugins/` layout works. Unset → `Deployment/target/`. Set-but-wrong → the build **fails loudly** instead of drowning you in `cannot find symbol`. |
| `JDK_JAVA_OPTIONS` | extra JVM flags for the in-process javac — needed for Lombok, see [Troubleshooting](#troubleshooting) |

### 2. Obfuscate

```bash
mvn -f obfuscation/pom.xml package        # macOS/Linux
mvn -f obfuscation\pom.xml package        # Windows
```

`ObfuscationRunner` scans every jar in the input dir, derives ProGuard keep
rules from each bundle's manifest + DS descriptors, and runs ProGuard per
bundle in-process. Pure-API bundles (everything exported, no DS components, no
activator) are copied through unchanged. `Bundle-ClassPath` nested jars are
never renamed and pass through byte-identical.

| knob | effect |
|---|---|
| `GREET_TARGET_DIR` (env) | target-platform jars used as ProGuard `-libraryjars` — **same variable as the build step**, so set it once. Every run prints `>> library pool: N target-platform jars from <dir>`; check that line first when a rule doesn't match. |
| `-Ddeployment.target.dir=<dir>` | same thing, explicit; wins over the env var |
| `-Dbundles.input.dir=<dir>` | which jars to obfuscate (default `Deployment/build/`). Everything here gets obfuscated unless pure-API — jars that must stay untouched belong in the *target* dir instead. |
| `obfuscation/proguard-common.conf` | flags shared by every bundle + the place for **manual keep rules** (see below) |

Outputs, per bundle, under `obfuscation/target/`:

- `<symbolic-name>-obf.jar` — the obfuscated bundle
- `<symbolic-name>-mapping.txt` — rename mapping (retrace input; check here to see what was renamed)
- `keep/<symbolic-name>.pro` — the generated keep rules, one comment per rule saying which metadata produced it (the audit trail)

**Manual keep rules** go in `proguard-common.conf` (never edit `target/keep/*.pro`
— regenerated every build). Common shapes:

```proguard
-keep class com.acme.config.ServerConfig { *; }          # one class
-keep class com.acme.config.** { *; }                    # package + subpackages
-keep class * extends com.acme.config.AbsConfig { *; }   # whole hierarchy (extends/implements are equivalent)
-keep @com.acme.config.ConfigClass class * { *; }        # annotated classes
```

A rule that references a class ProGuard can't see matches **nothing** — and
`-dontnote`/`-dontwarn` in the common conf hide the evidence. When debugging,
comment those two out and/or add `-printseeds seeds.txt` to see what the rules
actually captured. Full syntax: the [ProGuard manual](https://www.guardsquare.com/manual/home)
(Configuration → Usage / Examples).

### 3. Run

```bash
./scripts/run-osgi.sh                     # macOS/Linux
scripts\run-osgi.bat                      # Windows
```

Boots Equinox + Felix SCR + Gogo console with the **obfuscated** `imp`, `app`
and `ui` bundles (plus the untouched `api`). Success looks like:

```
Greet.start()
App.start()
Greet.greet()
GreetFrame.start() lang=en title=Greeting Window miglayout=3.7.4 window=480x260 token=s3..(15)
```

(`token=s3..(15)` is a mask — first two chars + length — proving the encrypted
`api.token` decrypted; the secret itself is never printed. See *Encrypted
config values* below.)

| knob | effect |
|---|---|
| `USE_PLAIN=1` | boot the un-obfuscated jars from `Deployment/build/` instead — the A/B baseline; output must be identical |
| `GREET_MODE=check` | no interactive console: activate DS, print the lines, exit. For scripts/CI. |
| `GREET_LANG=tr\|en` (or `-Dgreet.lang`) | UI language. Unknown values fall back to the base (English) bundle; the platform default locale is deliberately ignored. |
| `-Dgreet.lang.dir=<dir>` | where `messages[_xx].properties` live (default `configs/com.kk.greet.ui/lang` **relative to the working directory** — set this when launching from Eclipse) |
| `-Dgreet.conf.dir=<dir>` | where `ui.properties` lives (default `configs/com.kk.greet.ui/conf`, same working-directory caveat). Window geometry etc.; a missing file just means built-in defaults (`window=pack`). |

At the `g!` prompt: `ss` / `lb` (bundle states), `scr:list` (DS components;
registers a moment after startup — retry if "command not found"),
`scr:info <id>` (one component's references), `close` (shut down).

### Encrypted config values

Critical values in `configs/<bundle>/conf/*.properties` can be stored encrypted
as `key=ENC(<base64>)`; the app decrypts them transparently at load time, so
every getter sees plaintext. Encrypt a value with:

```
./scripts/encrypt-config.sh 's3cr3t-T0k3n-42'        # prints ENC(...) to paste in
./scripts/encrypt-config.sh --decrypt 'ENC(...)'     # round-trip check
scripts\encrypt-config.bat 's3cr3t-T0k3n-42'         # Windows
```

AES-128/GCM, fresh random IV per run (so the same plaintext yields a different
`ENC(...)` each time — both decrypt fine). Only `ENC(...)`-wrapped values are
touched; plain values are read as-is. A value that fails to decrypt (tampered,
wrong key) logs a warning and is left untouched — the app still starts.

**Reality check**: the key is embedded (XOR-split) in the obfuscated `ui`
bundle, so this deters casual inspection of the config files — it is **not** a
security boundary. Anyone with the jar can recover the key. For real protection,
move the key out of the app (env var / key file / a KMS) and keep only the
ciphertext on disk; the `ENC(...)` scheme and loader stay the same.

Windows note: `set VAR=value` — no quotes, spaces are fine; clear with
`set VAR=`.

## Troubleshooting

| symptom | cause / fix |
|---|---|
| build: `cannot find symbol` for framework/platform types | target platform not found — set `GREET_TARGET_DIR`; the build prints the dir and jar count it used |
| build: `IllegalAccessError: ... LombokProcessor` | a Lombok jar in the platform runs as annotation processor inside the in-process javac. Set `JDK_JAVA_OPTIONS` to the Lombok `--add-opens jdk.compiler/...=ALL-UNNAMED` set (see Lombok docs), or add `-proc:none` if nothing actually uses Lombok. |
| build: `NoSuchFieldError: JCTree$JCImport ... qualid` | Lombok jar too old for the JDK — upgrade Lombok to ≥ 1.18.30 (JDK 21), newer for JDK 24+ |
| obfuscation: `Note: ... refers to unknown class X` | your keep rule names a class ProGuard can't see — wrong FQN, or X's jar isn't in the library pool. Check the `>> library pool:` line; the note disappearing is the success signal. |
| a bundle that must stay untouched got obfuscated | it was in `bundles.input.dir` (= "obfuscate these"). Move its jar to the target-platform dir (= "libraries, never touched"). |
| UI: `MissingResourceException ... base name messages` | working directory ≠ repo root (typical for Eclipse launches) — pass `-Dgreet.lang.dir=<abs path to configs/com.kk.greet.ui/lang>` |
| UI: `ConfCrypto: cannot decrypt ...` | the `ENC(...)` value was tampered, or its key constants don't match `scripts/EncryptConfig.java` — re-encrypt the value with the current tool. App still starts (value left as-is). |
| Turkish characters corrupt / `unmappable character` | see [ENCODING.md](ENCODING.md) |

## Reality check on protection

Name obfuscation is a deterrent, not a security boundary: the bytecode stays
fully decompilable, there is no string/control-flow protection, and the DS
class names the runtime forces you to keep are exactly an attacker's entry
points. For more, commercial tools (Zelix, Allatori, DashO) exist.
