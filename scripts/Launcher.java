import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Embedded OSGi launcher for the greet demo. Boots Equinox, installs the given
 * bundle jars (in argument order), starts them, and then either:
 *
 *  - console mode (default): enables the Equinox/Gogo console on stdin and keeps
 *    the framework running so you can observe bundle + DS status interactively
 *    (lb, ss, scr:list, scr:info <id>, ...). Type `close` to shut down. This
 *    mirrors the Eclipse "Equinox Launcher" config (-console -consoleLog,
 *    eclipse.ignoreApp=true).
 *  - check mode (env GREET_MODE=check): non-interactive; waits briefly for DS to
 *    activate, then stops. Used for scripted verification.
 *
 * args[0]      = framework storage directory
 * args[1..n]   = bundle jar paths, in resolve order
 *
 * Java 1.8 compatible.
 */
public class Launcher {
    public static void main(String[] args) throws Exception {
        boolean checkMode = "check".equalsIgnoreCase(System.getenv("GREET_MODE"));

        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        Map<String, String> config = new HashMap<String, String>();
        config.put(Constants.FRAMEWORK_STORAGE, args[0]);
        config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        config.put("eclipse.ignoreApp", "true");
        if (!checkMode) {
            // Enable the Equinox console (hosted by org.eclipse.equinox.console + Gogo) on stdin.
            config.put("osgi.console", "");
        }

        Framework framework = factory.newFramework(config);
        framework.init();
        BundleContext context = framework.getBundleContext();

        List<Bundle> installed = new ArrayList<Bundle>();
        for (int i = 1; i < args.length; i++) {
            // File.toURI() yields a valid file: URL on every platform
            // (e.g. file:/C:/... on Windows), unlike "file:" + a raw path.
            installed.add(context.installBundle(new File(args[i]).toURI().toString()));
        }
        framework.start();

        for (Bundle b : installed) {
            // Fragments (e.g. none here) cannot be started; skip them defensively.
            if (b.getHeaders().get(Constants.FRAGMENT_HOST) != null) {
                continue;
            }
            try {
                b.start();
            } catch (BundleException e) {
                System.out.println("[launcher] could not start " + b.getSymbolicName() + ": " + e.getMessage());
            }
        }

        if (checkMode) {
            // Give Declarative Services time to bind/activate the delayed Greet component.
            Thread.sleep(2000);
            framework.stop();
            framework.waitForStop(5000);
        } else {
            System.out.println("[launcher] OSGi console ready — try: lb | ss | scr:list | scr:info <id> | close");
            // Block until the console (or `close`) stops the framework.
            framework.waitForStop(0);
        }
    }
}
