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
 * Minimal embedded OSGi launcher for verification. Boots Equinox, installs the
 * given bundle jars (in argument order), starts them, waits for Declarative
 * Services to activate components, then shuts the framework down.
 *
 * args[0]      = framework storage directory
 * args[1..n]   = bundle jar paths, in resolve order
 *
 * Java 1.8 compatible.
 */
public class Launcher {
    public static void main(String[] args) throws Exception {
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        Map<String, String> config = new HashMap<String, String>();
        config.put(Constants.FRAMEWORK_STORAGE, args[0]);
        config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);

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
            try {
                b.start();
            } catch (BundleException e) {
                System.out.println("[launcher] could not start " + b.getSymbolicName() + ": " + e.getMessage());
            }
        }

        // Give Declarative Services time to bind/activate the delayed Greet component.
        Thread.sleep(2000);

        framework.stop();
        framework.waitForStop(5000);
    }
}
