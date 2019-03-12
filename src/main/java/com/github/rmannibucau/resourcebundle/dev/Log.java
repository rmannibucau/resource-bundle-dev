package com.github.rmannibucau.resourcebundle.dev;

final class Log {
    private Log() {
        // no-op
    }

    // don't use loggers here
    static void info(final String value) {
        System.out.println(value);
    }
}
