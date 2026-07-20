package com.kk.greet.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * Loads UI-only settings (window geometry etc.) from the external file
 * {@code configs/com.kk.greet.ui/conf/ui.properties}
 * (override the directory with {@code -Dgreet.conf.dir}).
 *
 * Purely presentational knobs live here; user-visible texts stay in the
 * {@code lang/} resource bundles ({@link Messages}). A missing file or a
 * missing key falls back to the built-in default, so the bundle also runs
 * with no external config at all.
 */
final class UiConfig {

	private static final Properties PROPS = load();

	private UiConfig() {
	}

	/** Window width in pixels; 0 means "pack to the content's preferred size". */
	static int windowWidth() {
		return intValue("window.width", 0);
	}

	/** Window height in pixels; 0 means "pack to the content's preferred size". */
	static int windowHeight() {
		return intValue("window.height", 0);
	}

	static boolean windowResizable() {
		return !"false".equalsIgnoreCase(PROPS.getProperty("window.resizable", "true").trim());
	}

	private static int intValue(String key, int fallback) {
		String value = PROPS.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			System.err.println("UiConfig: ignoring non-numeric " + key + "=" + value.trim());
			return fallback;
		}
	}

	private static Properties load() {
		String dir = System.getProperty("greet.conf.dir", "configs/com.kk.greet.ui/conf");
		File file = new File(dir, "ui.properties");
		Properties props = new Properties();
		if (!file.isFile()) {
			// External config is optional; defaults keep the window usable.
			return props;
		}
		try {
			InputStream in = new FileInputStream(file);
			// UTF-8, not the Properties default ISO-8859-1 (see ENCODING.md).
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
			try {
				props.load(reader);
			} finally {
				reader.close();
			}
		} catch (IOException e) {
			System.err.println("UiConfig: cannot read " + file + " (" + e + "), using defaults");
		}
		return props;
	}

}
