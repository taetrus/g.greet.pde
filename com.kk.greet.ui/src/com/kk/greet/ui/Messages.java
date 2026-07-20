package com.kk.greet.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * Loads UI texts from external resource bundles in
 * {@code configs/com.kk.greet.ui/lang/}
 * (override the directory with {@code -Dgreet.lang.dir}).
 *
 * Language selection at launch: {@code -Dgreet.lang=tr|en}, or the
 * {@code GREET_LANG} environment variable. Default: en. An unknown language
 * falls back to the base bundle {@code messages.properties} (English) — the
 * platform default locale is deliberately NOT consulted, so behaviour is the
 * same on a Turkish and an English machine.
 *
 * The .properties files are read as UTF-8 via {@link Utf8Control}; plain
 * {@code Properties.load(InputStream)} would read ISO-8859-1 on Java 8 and
 * corrupt the Turkish texts (see ENCODING.md).
 */
final class Messages {

	private static final ResourceBundle BUNDLE = load();

	private Messages() {
	}

	static String get(String key) {
		return BUNDLE.getString(key);
	}

	static String language() {
		String lang = System.getProperty("greet.lang");
		if (lang == null || lang.trim().isEmpty()) {
			lang = System.getenv("GREET_LANG");
		}
		if (lang == null || lang.trim().isEmpty()) {
			lang = "en";
		}
		return lang.trim().toLowerCase(Locale.ROOT);
	}

	private static ResourceBundle load() {
		String dir = System.getProperty("greet.lang.dir", "configs/com.kk.greet.ui/lang");
		try {
			URL url = new File(dir).toURI().toURL();
			// Parent loader null: bundles come from the directory only, never
			// from this bundle's own classpath.
			URLClassLoader loader = new URLClassLoader(new URL[] { url }, null);
			return ResourceBundle.getBundle("messages", new Locale(language()), loader, new Utf8Control());
		} catch (MalformedURLException e) {
			throw new IllegalStateException("Cannot resolve language directory: " + dir, e);
		}
	}

	/** Reads .properties bundles as UTF-8 and disables default-locale fallback. */
	private static final class Utf8Control extends ResourceBundle.Control {

		@Override
		public List<String> getFormats(String baseName) {
			return FORMAT_PROPERTIES;
		}

		@Override
		public Locale getFallbackLocale(String baseName, Locale locale) {
			// Skip the platform default locale; fall through to messages.properties.
			return null;
		}

		@Override
		public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
				boolean reload) throws IOException {
			String resource = toResourceName(toBundleName(baseName, locale), "properties");
			InputStream in = loader.getResourceAsStream(resource);
			if (in == null) {
				return null;
			}
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
			try {
				return new PropertyResourceBundle(reader);
			} finally {
				reader.close();
			}
		}
	}

}
