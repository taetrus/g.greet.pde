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
