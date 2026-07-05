import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * CLI stand-in for Eclipse PDE's "Export > Deployable plug-ins and fragments".
 *
 * Auto-discovers PDE bundle projects and builds each one purely from its own
 * metadata - adding a new bundle project requires zero changes here (the same
 * promise ObfuscationRunner makes for the obfuscation step):
 *
 *   .project dir with META-INF/MANIFEST.MF + build.properties  -> it's a bundle project
 *   MANIFEST.MF  Bundle-SymbolicName                 -> jar name Deployment/build/<sn>.jar
 *   MANIFEST.MF  Import-Package / Export-Package    -> inter-project compile order (topo sort)
 *   MANIFEST.MF  Bundle-RequiredExecutionEnvironment -> javac --release level
 *   MANIFEST.MF  Bundle-ClassPath                   -> nested library jars (lib/*.jar) added to
 *                                                      that project's compile classpath
 *   build.properties  source.*                      -> source roots to compile
 *   build.properties  bin.includes                  -> resources shipped in the jar (e.g. OSGI-INF, lib/)
 *
 * Compile classpath per project = its dependency projects' class dirs + every
 * target-platform jar in Deployment/target/.
 *
 * Usage:  java scripts/BundleBuilder.java [projectDir...]
 * With no args, all bundle projects in the repo root are discovered and built.
 *
 * Run via JDK single-file source-launch from build-bundles.{sh,bat}. Unlike
 * scripts/Launcher.java this never runs inside the Java-8-targeted OSGi runtime,
 * so it does not need a --release 8 precompile; only its OUTPUT bytecode level
 * (from Bundle-RequiredExecutionEnvironment) is pinned.
 */
public final class BundleBuilder {

    private static final Path OUT = Paths.get("Deployment", "build");
    private static final Path TARGET_PLATFORM = Paths.get("Deployment", "target");

    public static void main(String[] args) throws Exception {
        List<Project> projects = new ArrayList<Project>();
        if (args.length > 0) {
            for (String arg : args) {
                Project p = Project.load(Paths.get(arg));
                if (p == null) {
                    fail(arg + " is not a bundle project (needs META-INF/MANIFEST.MF with "
                            + "Bundle-SymbolicName, plus build.properties)");
                }
                projects.add(p);
            }
        } else {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(Paths.get("."))) {
                List<Path> sorted = new ArrayList<Path>();
                for (Path d : dirs) {
                    if (Files.isDirectory(d) && !d.getFileName().toString().startsWith(".")) {
                        sorted.add(d);
                    }
                }
                sorted.sort(null);
                for (Path d : sorted) {
                    Project p = Project.load(d);
                    if (p != null) {
                        projects.add(p);
                    }
                }
            }
            if (projects.isEmpty()) {
                fail("no bundle projects found in " + Paths.get(".").toAbsolutePath().normalize());
            }
        }

        List<Path> targetJars = new ArrayList<Path>();
        if (Files.isDirectory(TARGET_PLATFORM)) {
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(TARGET_PLATFORM, "*.jar")) {
                for (Path j : jars) {
                    targetJars.add(j);
                }
            }
            targetJars.sort(null);
        }

        deleteRecursive(OUT);
        Files.createDirectories(OUT);

        Map<Project, Path> classDirs = new LinkedHashMap<Project, Path>();
        for (Project p : topoSort(projects)) {
            Path classesDir = OUT.resolve(p.symbolicName + "-classes");
            Files.createDirectories(classesDir);
            compile(p, classesDir, classDirs, targetJars);
            assemble(p, classesDir, OUT.resolve(p.symbolicName + ".jar"));
            classDirs.put(p, classesDir);
        }

        System.out.println(">> done:");
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(OUT, "*.jar")) {
            List<Path> sorted = new ArrayList<Path>();
            for (Path j : jars) {
                sorted.add(j);
            }
            sorted.sort(null);
            for (Path j : sorted) {
                System.out.println(j);
            }
        }
    }

    /** One PDE bundle project, read entirely from its own metadata files. */
    private static final class Project {
        final Path dir;
        String symbolicName;
        int release = 8;
        final Set<String> importedPackages = new LinkedHashSet<String>();
        final Set<String> exportedPackages = new LinkedHashSet<String>();
        final List<Path> sourceRoots = new ArrayList<Path>();
        final List<String> binIncludes = new ArrayList<String>();
        final List<Path> classpathJars = new ArrayList<Path>();
        Manifest manifest;

        private Project(Path dir) {
            this.dir = dir;
        }

        /** Returns null if the directory is not a bundle project. */
        static Project load(Path dir) throws IOException {
            Path manifestFile = dir.resolve("META-INF").resolve("MANIFEST.MF");
            Path buildProps = dir.resolve("build.properties");
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(buildProps)) {
                return null;
            }
            Project p = new Project(dir);
            try (InputStream in = Files.newInputStream(manifestFile)) {
                // java.util.jar.Manifest handles the 72-byte line folding - never hand-parse.
                p.manifest = new Manifest(in);
            }
            String sn = p.manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            if (sn == null) {
                return null;
            }
            p.symbolicName = parseHeader(sn).get(0);
            p.importedPackages.addAll(parseHeader(p.manifest.getMainAttributes().getValue("Import-Package")));
            p.exportedPackages.addAll(parseHeader(p.manifest.getMainAttributes().getValue("Export-Package")));
            p.release = releaseFromEE(p.symbolicName,
                    p.manifest.getMainAttributes().getValue("Bundle-RequiredExecutionEnvironment"));
            // Nested library jars (Bundle-ClassPath: ., lib/foo.jar) are part of
            // the bundle's own classpath; "." is the bundle itself.
            for (String entry : parseHeader(p.manifest.getMainAttributes().getValue("Bundle-ClassPath"))) {
                if (!entry.equals(".")) {
                    Path nested = dir.resolve(entry);
                    if (!Files.isRegularFile(nested)) {
                        fail(p.symbolicName + ": Bundle-ClassPath entry not found: " + entry);
                    }
                    p.classpathJars.add(nested);
                }
            }

            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(buildProps)) {
                props.load(in); // natively handles the trailing-backslash continuations
            }
            for (String key : new TreeSet<String>(props.stringPropertyNames())) {
                if (key.equals("source.") || key.startsWith("source.")) {
                    for (String root : props.getProperty(key).split(",")) {
                        root = root.trim();
                        if (!root.isEmpty()) {
                            p.sourceRoots.add(dir.resolve(root));
                        }
                    }
                }
            }
            for (String inc : props.getProperty("bin.includes", "").split(",")) {
                inc = inc.trim();
                if (!inc.isEmpty()) {
                    p.binIncludes.add(inc);
                }
            }
            return p;
        }
    }

    /** Maps JavaSE-1.8 -> 8, JavaSE-11 -> 11, ...; defaults to 8 with a warning. */
    private static int releaseFromEE(String symbolicName, String ee) {
        if (ee != null) {
            String v = ee.trim();
            if (v.startsWith("JavaSE-")) {
                v = v.substring("JavaSE-".length());
                if (v.startsWith("1.")) {
                    v = v.substring(2);
                }
                try {
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    // falls through to the warning below
                }
            }
        }
        System.out.println(">> WARNING: " + symbolicName
                + ": unrecognized/missing Bundle-RequiredExecutionEnvironment (" + ee + "), using --release 8");
        return 8;
    }

    /**
     * Orders projects so every project is compiled after the projects whose
     * exported packages it imports. Imports satisfied by no discovered project
     * are assumed to come from the target platform.
     */
    private static List<Project> topoSort(List<Project> projects) {
        Map<String, Project> exporterOf = new TreeMap<String, Project>();
        for (Project p : projects) {
            for (String pkg : p.exportedPackages) {
                Project clash = exporterOf.put(pkg, p);
                if (clash != null) {
                    fail("package " + pkg + " is exported by both " + clash.symbolicName
                            + " and " + p.symbolicName + " - ambiguous compile dependency");
                }
            }
        }
        List<Project> ordered = new ArrayList<Project>();
        Set<Project> done = new LinkedHashSet<Project>();
        Set<Project> visiting = new LinkedHashSet<Project>();
        for (Project p : projects) {
            visit(p, exporterOf, done, visiting, ordered);
        }
        return ordered;
    }

    private static void visit(Project p, Map<String, Project> exporterOf,
                              Set<Project> done, Set<Project> visiting, List<Project> ordered) {
        if (done.contains(p)) {
            return;
        }
        if (!visiting.add(p)) {
            StringBuilder cycle = new StringBuilder();
            for (Project v : visiting) {
                cycle.append(v.symbolicName).append(" -> ");
            }
            fail("Import-Package cycle among projects: " + cycle + p.symbolicName);
        }
        for (String pkg : p.importedPackages) {
            Project dep = exporterOf.get(pkg);
            if (dep != null && dep != p) {
                visit(dep, exporterOf, done, visiting, ordered);
            }
        }
        visiting.remove(p);
        done.add(p);
        ordered.add(p);
    }

    private static void compile(Project p, Path classesDir,
                                Map<Project, Path> builtClassDirs, List<Path> targetJars) throws IOException {
        List<Path> sources = new ArrayList<Path>();
        for (Path root : p.sourceRoots) {
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(".java")) {
                            sources.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        sources.sort(null);
        if (sources.isEmpty()) {
            System.out.println(">> " + p.symbolicName + ": no Java sources, assembling only");
            return;
        }

        StringBuilder cp = new StringBuilder();
        for (Path jar : p.classpathJars) {
            cp.append(jar).append(java.io.File.pathSeparatorChar);
        }
        for (Path dir : builtClassDirs.values()) {
            cp.append(dir).append(java.io.File.pathSeparatorChar);
        }
        for (Path jar : targetJars) {
            cp.append(jar).append(java.io.File.pathSeparatorChar);
        }

        System.out.println(">> compiling " + p.symbolicName + " (release " + p.release + ")");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("no system Java compiler - JAVA_HOME must point at a JDK, not a JRE");
        }
        List<String> options = new ArrayList<String>();
        options.add("--release");
        options.add(Integer.toString(p.release));
        options.add("-cp");
        options.add(cp.toString());
        options.add("-d");
        options.add(classesDir.toString());
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            boolean ok = compiler.getTask(null, fm, null, options, null,
                    fm.getJavaFileObjectsFromPaths(sources)).call();
            if (!ok) {
                fail("compilation failed for " + p.symbolicName);
            }
        }
    }

    /**
     * Copies every bin.includes resource (except '.', the class output, and
     * META-INF/, represented by the Manifest object) into the classes dir, then
     * jars it with the project's MANIFEST.MF as the first entry.
     */
    private static void assemble(Project p, Path classesDir, Path outJar) throws IOException {
        for (String inc : p.binIncludes) {
            if (inc.equals(".") || inc.equals("META-INF/")) {
                continue;
            }
            Path src = p.dir.resolve(inc);
            Path dest = classesDir.resolve(inc);
            if (Files.isDirectory(src)) {
                copyRecursive(src, dest);
            } else if (Files.isRegularFile(src)) {
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            } else {
                fail(p.symbolicName + ": bin.includes entry not found: " + inc);
            }
        }

        System.out.println(">> assembling " + outJar.getFileName());
        Set<String> entries = new TreeSet<String>();
        Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = classesDir.relativize(file).toString().replace('\\', '/');
                entries.add(rel);
                // parent directory entries, as the jar tool emits them
                for (int i = rel.indexOf('/'); i >= 0; i = rel.indexOf('/', i + 1)) {
                    entries.add(rel.substring(0, i + 1));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outJar), p.manifest)) {
            // the jar tool also emits a META-INF/ directory entry; match it
            jar.putNextEntry(new JarEntry("META-INF/"));
            jar.closeEntry();
            for (String name : entries) {
                jar.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) {
                    Files.copy(classesDir.resolve(name), jar);
                }
                jar.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Duplicated from obfuscation/.../ObfuscationRunner.parseHeader: this tool
    // must stay a standalone single-file program (java source-launch, no Maven).
    // Keep the two implementations in sync.
    // ---------------------------------------------------------------------

    /**
     * Splits an OSGi manifest header into its clause names: comma-separated, but
     * commas inside quotes (version="[1.0,2.0)") do not split; per clause, any
     * ;attributes / ;directives after the name are dropped.
     */
    static List<String> parseHeader(String header) {
        List<String> clauses = new ArrayList<String>();
        if (header == null) {
            return clauses;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char ch = header.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            }
            if (ch == ',' && !inQuotes) {
                addClause(clauses, current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        addClause(clauses, current.toString());
        return clauses;
    }

    private static void addClause(List<String> clauses, String clause) {
        int semi = clause.indexOf(';');
        String name = (semi < 0 ? clause : clause.substring(0, semi)).trim();
        if (!name.isEmpty()) {
            clauses.add(name);
        }
    }

    private static void copyRecursive(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(src.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void fail(String message) {
        System.err.println("BUILD FAILED: " + message);
        System.exit(1);
    }

    private BundleBuilder() {
    }
}
