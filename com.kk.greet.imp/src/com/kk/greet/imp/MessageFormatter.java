package com.kk.greet.imp;

/**
 * Package-private helper. Nothing in the OSGi runtime, the DS descriptor, or any
 * other bundle references this class by name, so it is a legitimate obfuscation
 * target: ProGuard is free to rename the class and its method. It exists so the
 * demo can show that internals get obfuscated while the kept DS entry points
 * ({@code Greet}, {@code start}, {@code greet}) stay intact.
 *
 * Java 1.8 compatible.
 */
class MessageFormatter {

	private MessageFormatter() {
	}

	static String format(String action) {
		return "Greet." + action + "()";
	}
}
