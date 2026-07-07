package com.kk.greet.obfuscation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * Obfuscates every OSGi bundle jar in an input directory, deriving the ProGuard
 * keep rules from each bundle's own metadata instead of hand-written config:
 *
 *   MANIFEST.MF  Export-Package      -> exported API packages are kept wholesale
 *   MANIFEST.MF  Bundle-Activator    -> activator class kept
 *   MANIFEST.MF  Bundle-ClassPath    -> nested library jars (lib/*.jar): third-party
 *                                       classes are kept wholesale, never renamed
 *   MANIFEST.MF  Service-Component   -> locates the DS descriptors, and each
 *   OSGI-INF/*.xml (SCR v1.0..v1.5)  -> component class, lifecycle methods
 *                                       (activate/deactivate/modified), bind/
 *                                       unbind/updated methods, injected fields
 *
 * A bundle with no DS components, no activator, and only exported packages is a
 * pure API bundle: it is copied through unchanged (APIs are never obfuscated).
 * Everything else is renamed by ProGuard except the derived keep surface.
 *
 * Per bundle it writes (under the output dir):
 *   keep/<symbolic-name>.pro   the generated keep rules (auditable artifact)
 *   <symbolic-name>-obf.jar    the obfuscated bundle
 *   <symbolic-name>-mapping.txt  the rename mapping (retrace input)
 *
 * Adding a new bundle to the input directory requires zero configuration here.
 */
public final class ObfuscationRunner {

    public static void main(String[] args) throws Exception {
        Path inputDir = Paths.get(args.length > 0 ? args[0] : "../Deployment/build");
        Path outDir = Paths.get(args.length > 1 ? args[1] : "target");
        Path commonConf = Paths.get(args.length > 2 ? args[2] : "proguard-common.conf");
        Path frameworkDir = Paths.get(args.length > 3 ? args[3] : "../Deployment/target");

        List<Path> jars = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> s = Files.list(inputDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no bundle jars in " + inputDir.toAbsolutePath()
                    + " - run scripts/build-bundles first");
        }

        // The library pool resolves every cross-bundle/framework type; a wrong
        // dir here doesn't fail the run, it silently un-matches keep rules
        // (-keep class * extends X finds no X) - so report it loudly up front.
        long frameworkJars = 0;
        if (Files.isDirectory(frameworkDir)) {
            try (java.util.stream.Stream<Path> s = Files.walk(frameworkDir)) {
                frameworkJars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).count();
            }
        }
        if (frameworkJars == 0) {
            System.out.println(">> WARNING: no target-platform jars under "
                    + frameworkDir.toAbsolutePath().normalize()
                    + " - framework/cross-bundle types will be unresolved and keep rules"
                    + " referring to them will silently match nothing"
                    + " (set GREET_TARGET_DIR or -Ddeployment.target.dir=)");
        } else {
            System.out.println(">> library pool: " + frameworkJars + " target-platform jars from "
                    + frameworkDir.toAbsolutePath().normalize());
        }

        Files.createDirectories(outDir.resolve("keep"));

        List<BundleInfo> bundles = new ArrayList<BundleInfo>();
        for (Path jar : jars) {
            BundleInfo b = BundleInfo.read(jar);
            if (b == null) {
                System.out.println(">> skipping (not an OSGi bundle): " + jar.getFileName());
            } else {
                bundles.add(b);
            }
        }

        for (BundleInfo bundle : bundles) {
            if (bundle.isPureApi()) {
                Path dest = outDir.resolve(bundle.jar.getFileName());
                Files.copy(bundle.jar, dest, StandardCopyOption.REPLACE_EXISTING);
                System.out.println(">> " + bundle.symbolicName + ": pure API bundle, copied through unchanged");
                continue;
            }
            Path keepFile = outDir.resolve("keep").resolve(bundle.symbolicName + ".pro");
            bundle.writeKeepRules(keepFile);
            System.out.println(">> " + bundle.symbolicName + ": generated " + keepFile);
            runProGuard(bundle, bundles, outDir, commonConf, frameworkDir, keepFile);
            System.out.println(">> " + bundle.symbolicName + ": obfuscated -> "
                    + outDir.resolve(bundle.symbolicName + "-obf.jar"));
        }
    }

    /** Invokes ProGuard in-process; paths are passed as args, never written into config files. */
    private static void runProGuard(BundleInfo bundle, List<BundleInfo> all, Path outDir,
                                    Path commonConf, Path frameworkDir, Path keepFile) throws Exception {
        List<String> pg = new ArrayList<String>();
        pg.add("-injars");
        pg.add(bundle.jar.toString());
        pg.add("-outjars");
        pg.add(outDir.resolve(bundle.symbolicName + "-obf.jar").toString());
        // All JDK modules, not just java.base: bundles using e.g. Swing need
        // java.desktop in the library pool so ProGuard sees the full class
        // hierarchy when deciding what it may rename.
        pg.add("-libraryjars");
        pg.add(jdkLibraryPath(outDir));
        // Other bundles' plain jars: inter-bundle references only go through
        // exported packages, which are kept wholesale, so plain jars are sound.
        for (BundleInfo other : all) {
            if (other != bundle) {
                pg.add("-libraryjars");
                pg.add(other.jar.toString());
            }
        }
        // Target-platform jars (OSGi framework + DS APIs) for bundles that
        // reference framework types (BundleActivator, ComponentContext, ...).
        // Each jar is its own entry: a single dir(**.jar) entry reads the jars
        // as opaque files without expanding their classes, leaving every
        // platform type unresolved.
        if (Files.isDirectory(frameworkDir)) {
            try (java.util.stream.Stream<Path> s = Files.walk(frameworkDir)) {
                for (Path jar : s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted().collect(java.util.stream.Collectors.toList())) {
                    pg.add("-libraryjars");
                    pg.add(jar.toString());
                }
            }
        }
        pg.add("-printmapping");
        pg.add(outDir.resolve(bundle.symbolicName + "-mapping.txt").toString());
        pg.add("@" + commonConf);
        pg.add("@" + keepFile);

        Configuration configuration = new Configuration();
        new ConfigurationParser(pg.toArray(new String[0]), new Properties()).parse(configuration);
        new ProGuard(configuration).execute();
    }

    /**
     * ProGuard -libraryjars entry for the JDK API classes. JDKs that ship a
     * jmods/ directory are used directly; JDKs built without one (JEP 493
     * linkable run-time images, e.g. some Temurin 24+ builds) get the java.*
     * modules dumped from the jrt: run-time image into one jar, once per build.
     */
    private static String jdkLibraryPath(Path outDir) throws IOException {
        Path jmods = Paths.get(System.getProperty("java.home"), "jmods");
        if (Files.isDirectory(jmods)) {
            return jmods + "(!**.jar;!module-info.class)";
        }
        Path libJar = outDir.resolve("jdk-runtime-classes.jar");
        if (!Files.exists(libJar)) {
            System.out.println(">> no jmods/ in " + System.getProperty("java.home")
                    + " - dumping java.* modules from the jrt: image to " + libJar);
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(libJar))) {
                try (DirectoryStream<Path> modules = Files.newDirectoryStream(jrt.getPath("/modules"))) {
                    for (Path module : modules) {
                        if (!module.getFileName().toString().startsWith("java.")) {
                            continue;
                        }
                        Files.walkFileTree(module, new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                    throws IOException {
                                String name = module.relativize(file).toString().replace('\\', '/');
                                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                                    out.putNextEntry(new JarEntry(name));
                                    Files.copy(file, out);
                                    out.closeEntry();
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
                }
            }
        }
        return libJar.toString();
    }

    /** Everything the keep-rule derivation needs, read from one bundle jar. */
    private static final class BundleInfo {
        final Path jar;
        final String symbolicName;
        final Set<String> exportedPackages = new LinkedHashSet<String>();
        final Set<String> classPackages = new TreeSet<String>();
        final Set<String> nestedLibPackages = new TreeSet<String>();
        final List<DsComponent> components = new ArrayList<DsComponent>();
        String activator;

        private BundleInfo(Path jar, String symbolicName) {
            this.jar = jar;
            this.symbolicName = symbolicName;
        }

        /** Returns null if the jar is not an OSGi bundle (no Bundle-SymbolicName). */
        static BundleInfo read(Path jarPath) throws Exception {
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Manifest mf = jarFile.getManifest();
                String sn = mf == null ? null : mf.getMainAttributes().getValue("Bundle-SymbolicName");
                if (sn == null) {
                    return null;
                }
                BundleInfo b = new BundleInfo(jarPath, parseHeader(sn).get(0));
                b.activator = trimToNull(mf.getMainAttributes().getValue("Bundle-Activator"));
                for (String pkg : parseHeader(mf.getMainAttributes().getValue("Export-Package"))) {
                    b.exportedPackages.add(pkg);
                }
                for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {
                    String name = e.nextElement().getName();
                    if (name.endsWith(".class")) {
                        int slash = name.lastIndexOf('/');
                        b.classPackages.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
                    }
                }
                // Bundle-ClassPath nested library jars (".": the bundle itself).
                // Their classes are program classes for ProGuard (it descends into
                // nested archives), so without keep rules it would rename them.
                for (String entry : parseHeader(mf.getMainAttributes().getValue("Bundle-ClassPath"))) {
                    if (entry.equals(".")) {
                        continue;
                    }
                    JarEntry nested = jarFile.getJarEntry(entry);
                    if (nested == null) {
                        System.out.println(">> WARNING: " + jarPath.getFileName()
                                + " Bundle-ClassPath entry not found: " + entry);
                        continue;
                    }
                    try (java.util.jar.JarInputStream jin =
                            new java.util.jar.JarInputStream(jarFile.getInputStream(nested))) {
                        for (JarEntry je; (je = jin.getNextJarEntry()) != null;) {
                            String name = je.getName();
                            if (!name.endsWith(".class")) {
                                continue;
                            }
                            int slash = name.lastIndexOf('/');
                            if (slash < 0) {
                                System.out.println(">> WARNING: " + jarPath.getFileName() + " " + entry
                                        + " has a default-package class (" + name + ") - not covered by keep rules");
                            } else {
                                b.nestedLibPackages.add(name.substring(0, slash).replace('/', '.'));
                            }
                        }
                    }
                }
                for (String clause : parseHeader(mf.getMainAttributes().getValue("Service-Component"))) {
                    for (String entry : resolveDescriptorEntries(jarFile, clause)) {
                        try (InputStream in = jarFile.getInputStream(jarFile.getEntry(entry))) {
                            b.components.addAll(DsComponent.parse(in));
                        }
                    }
                }
                return b;
            }
        }

        boolean isPureApi() {
            return components.isEmpty() && activator == null && exportedPackages.containsAll(classPackages);
        }

        void writeKeepRules(Path keepFile) throws IOException {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(keepFile, StandardCharsets.UTF_8))) {
                w.println("# GENERATED by ObfuscationRunner from " + jar.getFileName() + " metadata - do not edit.");
                for (String pkg : exportedPackages) {
                    w.println();
                    w.println("# MANIFEST.MF Export-Package: exported API stays fully intact");
                    w.println("-keep class " + pkg + ".* { *; }");
                }
                if (activator != null) {
                    w.println();
                    w.println("# MANIFEST.MF Bundle-Activator: framework instantiates it by name");
                    w.println("-keep class " + activator + " { *; }");
                }
                if (!nestedLibPackages.isEmpty()) {
                    w.println();
                    w.println("# MANIFEST.MF Bundle-ClassPath nested library jars: third-party code is");
                    w.println("# never renamed - it may use reflection internally, and only the bundle's");
                    w.println("# own calls into it are ours to obfuscate.");
                    for (String pkg : nestedLibPackages) {
                        w.println("-keep class " + pkg + ".* { *; }");
                    }
                }
                for (DsComponent c : components) {
                    w.println();
                    w.println("# OSGI-INF DS descriptor: SCR loads the component class by FQN");
                    w.println("-keep class " + c.implementationClass + " { <init>(...); }");
                    for (String m : c.methods) {
                        w.println("# DS lifecycle/bind method referenced (or defaulted) by the descriptor");
                        w.println("-keepclassmembers class " + c.implementationClass + " { *** " + m + "(...); }");
                    }
                    for (String f : c.fields) {
                        w.println("# DS field injection (SCR >= 1.3): field is set by name via reflection");
                        w.println("-keepclassmembers class " + c.implementationClass + " { *** " + f + "; }");
                    }
                }
            }
        }
    }

    /** One parsed <component> from a DS descriptor: the names SCR resolves reflectively. */
    private static final class DsComponent {
        String implementationClass;
        final Set<String> methods = new LinkedHashSet<String>();
        final Set<String> fields = new LinkedHashSet<String>();

        /** Namespace-agnostic parse: SCR v1.0..v1.5 differ only in namespace URI. */
        static List<DsComponent> parse(InputStream in) throws Exception {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().parse(in);
            List<DsComponent> result = new ArrayList<DsComponent>();
            NodeList nodes = doc.getElementsByTagNameNS("*", "component");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element component = (Element) nodes.item(i);
                Element impl = firstChild(component, "implementation");
                if (impl == null) {
                    continue;
                }
                DsComponent c = new DsComponent();
                c.implementationClass = impl.getAttribute("class");
                // Explicit names win; the defaults are also kept because DS falls
                // back to methods literally named activate/deactivate/modified
                // when the attribute is absent.
                addIfSet(c.methods, component, "activate");
                addIfSet(c.methods, component, "deactivate");
                addIfSet(c.methods, component, "modified");
                c.methods.add("activate");
                c.methods.add("deactivate");
                c.methods.add("modified");
                for (String f : component.getAttribute("activation-fields").trim().split("\\s+")) {
                    if (!f.isEmpty()) {
                        c.fields.add(f);
                    }
                }
                // SCR 1.4 constructor injection (component@init) needs no extra
                // rule: <init>(...) is already kept on the implementation class.
                NodeList refs = component.getElementsByTagNameNS("*", "reference");
                for (int r = 0; r < refs.getLength(); r++) {
                    Element ref = (Element) refs.item(r);
                    addIfSet(c.methods, ref, "bind");
                    addIfSet(c.methods, ref, "unbind");
                    addIfSet(c.methods, ref, "updated");
                    addIfSet(c.fields, ref, "field");
                }
                result.add(c);
            }
            return result;
        }

        private static void addIfSet(Set<String> into, Element e, String attr) {
            String v = e.getAttribute(attr).trim();
            if (!v.isEmpty()) {
                into.add(v);
            }
        }

        private static Element firstChild(Element parent, String localName) {
            NodeList children = parent.getElementsByTagNameNS("*", localName);
            return children.getLength() == 0 ? null : (Element) children.item(0);
        }
    }

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

    /** Resolves one Service-Component clause (literal path or OSGI-INF/*.xml glob) to jar entries. */
    private static List<String> resolveDescriptorEntries(JarFile jarFile, String clause) {
        List<String> entries = new ArrayList<String>();
        if (clause.indexOf('*') < 0) {
            if (jarFile.getEntry(clause) != null) {
                entries.add(clause);
            } else {
                System.out.println(">> WARNING: " + jarFile.getName()
                        + " Service-Component entry not found: " + clause);
            }
            return entries;
        }
        StringBuilder regex = new StringBuilder();
        for (String part : clause.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append("[^/]*");
            }
            regex.append(Pattern.quote(part));
        }
        Pattern pattern = Pattern.compile(regex.toString());
        for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {
            String name = e.nextElement().getName();
            if (pattern.matcher(name).matches()) {
                entries.add(name);
            }
        }
        if (entries.isEmpty()) {
            System.out.println(">> WARNING: " + jarFile.getName()
                    + " Service-Component glob matched nothing: " + clause);
        }
        return entries;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private ObfuscationRunner() {
    }
}
