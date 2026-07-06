# UTF-8 Encoding Immunity

This repo is hardened against `error: unmappable character (0xE7) for encoding UTF-8`
— the failure you get when a source file containing Turkish characters (e.g. `ç` = byte
`0xE7` in Cp1254) is saved in the Windows Turkish codepage but compiled as UTF-8. On
Turkish-locale Windows, Cp1254 is the platform default, so any tool that falls back to
the platform default will corrupt or reject Turkish text.

Encoding is pinned at four independent layers. All four must stay in place; each one
covers a tool the others don't reach.

## The four layers

| # | Artifact | Governs |
|---|----------|---------|
| 1 | `.settings/org.eclipse.core.resources.prefs` → `encoding/<project>=UTF-8` (every project, committed) | Eclipse editor + JDT workspace builds; survives workspace re-import |
| 2 | `build.properties` → `javacDefaultEncoding.. = UTF-8` (every bundle) | Headless PDE/Ant export builds — these read `build.properties`, **not** the `.settings` prefs |
| 3 | `.editorconfig` (repo root) → `charset = utf-8` | Non-Eclipse editors: VS Code, IntelliJ, Notepad++, Eclipse 2021-03+ |
| 4 | "Encoding canary" comment in each bundle's main source file: `çğıöşü ÇĞİÖŞÜ` | Detection: if any tool regresses to Cp1254, compilation fails loudly at the canary instead of silently corrupting real strings. Do not remove. |

When adding a bundle, replicate layers 1, 2, and 4.

## Verifying

Compile with the strict configuration; it must pass:

```powershell
javac -encoding UTF-8 -d <out> -cp <component-annotations-jar> `
  com.kk.greet.api\src\com\kk\greet\api\IGreet.java `
  com.kk.greet.imp\src\com\kk\greet\imp\Greet.java `
  com.kk.greet.app\src\com\kk\greet\app\App.java `
  -sourcepath "com.kk.greet.api\src;com.kk.greet.imp\src;com.kk.greet.app\src"
```

## Applying this scheme to an existing repo

The four layers only prevent *new* damage. A repo that already has Cp1254-saved files
needs them converted first.

1. **Find non-UTF-8 files** (capture the hits — step 2 iterates over them):

   ```powershell
   $hits = Get-ChildItem -Recurse -Include *.java,*.properties,MANIFEST.MF | ForEach-Object {
     $b = [IO.File]::ReadAllBytes($_.FullName)
     try {
       [Text.Encoding]::GetEncoding('utf-8',
         [Text.EncoderFallback]::ExceptionFallback,
         [Text.DecoderFallback]::ExceptionFallback).GetString($b) | Out-Null
     } catch { $_.FullName }
   }
   $hits
   ```

   If `$hits` prints nothing, the repo is already clean — skip step 2.

2. **Convert each hit** (read as Cp1254, rewrite as UTF-8 without BOM), then review the
   diff to confirm the characters survived:

   ```powershell
   foreach ($f in $hits) {
     $t = [IO.File]::ReadAllText($f, [Text.Encoding]::GetEncoding(1254))
     [IO.File]::WriteAllText($f, $t, (New-Object Text.UTF8Encoding $false))
   }
   ```

3. **Replicate the four layers** listed above.

4. **Set the Eclipse workspace default once per machine:** *Window → Preferences →
   General → Workspace → Text file encoding → Other: UTF-8*. Committed project prefs
   override it, but the workspace default still governs files outside projects and
   newly created projects.

## Caveats

- `java.util.Properties.load(InputStream)` reads ISO-8859-1 by definition on Java 8.
  If a `.properties` file with Turkish characters goes through that API, use `\uXXXX`
  escapes (or load with an explicit UTF-8 `Reader`) instead of raw UTF-8 bytes.
- A fully encoding-proof fallback for any string literal is the Unicode escape form:
  `"\u00E7"` for `ç` — plain ASCII, compiles under any encoding.
