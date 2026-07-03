# Metadata-driven build-bundles (auto-discover PDE projects)

- [x] 1. Baseline capture from current scripts
- [x] 2. scripts/BundleBuilder.java (discover → topo sort → compile → assemble)
- [x] 3. build-bundles.sh/.bat → thin wrappers
- [x] 4. Parity + pipeline + fake-4th-bundle verification
- [x] 5. Docs (CLAUDE.md, FOR_Kerem.md)

Review: new jars vs old-script baseline — entry sets identical, all .class/.xml
members byte-identical (manifest no longer gains a jar-tool Created-By line;
more faithful to PDE). Shuffled explicit args reordered by topo sort; bogus arg
exits 1 with a clear message. Fake com.kk.greet.imp2 project: discovered, built,
obfuscated (Greet2 kept, its MessageFormatter → a) with zero config edits, then
removed. Final 3-bundle pipeline A/B (GREET_MODE=check, obf vs USE_PLAIN=1)
prints identical DS lines. .bat wrapper is a 5-line mirror, reviewed by
inspection (no Windows box).

---

# Automated OSGi/DS-aware ProGuard keep-rule generation

Plan: ~/.claude/plans/serene-drifting-leaf.md (approved)

- [x] 1. proguard-common.conf (flags only, extracted from proguard.conf)
- [x] 2. ObfuscationRunner.java (manifest+DS parsing → keep rules → programmatic ProGuard)
- [x] 3. pom.xml rewrite (packaging jar, compiler release 8, exec runs runner); delete proguard.conf
- [x] 4. Build + verify keep files and mappings against expected table
- [x] 5. run-osgi.sh/.bat: per-bundle obf/plain selection (imp + app)
- [x] 6. End-to-end A/B verification (GREET_MODE=check, obf vs USE_PLAIN=1)
- [x] 7. Docs: CLAUDE.md obfuscation section, FOR_Kerem.md

## Review

Verified from a fully clean state (rm -rf Deployment/build obfuscation/target):
- build-bundles.sh → mvn package: api copied through (pure-API detection), app+imp
  obfuscated with generated keep/*.pro; keep files match the metadata→rule table
  (Greet+start; App+start+greetService; MessageFormatter in no rule → renamed to `a`).
- GREET_MODE=check run-osgi.sh prints identical Greet.start()/App.start()/Greet.greet()
  for obfuscated AND USE_PLAIN=1 baseline.
- Obf jars retain MANIFEST.MF (Service-Component header) + OSGI-INF XML byte-identical.
- .bat mirrors .sh (pickbundle subroutine); not executed here (no Windows box) — diff
  kept mechanical against the .sh logic.

Design notes: paths passed to ProGuard as args (never inside config files) → Windows-safe;
DS default lifecycle names (activate/deactivate/modified) always kept; SCR namespace
parsing version-agnostic; ProGuard driven in-process via Configuration/ConfigurationParser
(pinned 7.5.0, isolated in one method).
