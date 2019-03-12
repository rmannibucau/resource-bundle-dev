package com.github.rmannibucau.resourcebundle.dev;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.stream.Stream;

public class ResourceBundleDevAgent {
    private ResourceBundleDevAgent() {
        // no-op
    }

    public static void premain(final String agentArgs,
                               final Instrumentation instrumentation) {
        if (!Boolean.parseBoolean(extractConfig(agentArgs, "active="))) {
            Log.info(ResourceBundleDevAgent.class.getSimpleName() + " not active");
            return;
        }
        Log.info(ResourceBundleDevAgent.class.getSimpleName() + " activated");
        instrumentation.addTransformer(new ResourceBundleTransformer(
                extractConfig(agentArgs, "pattern="),
                extractListConfig(agentArgs, "includes="),
                ofNullable(extractListConfig(agentArgs, "excludes=")).orElseGet(() -> asList("java.", "sun.", "jdk.", "oracle."))));
    }

    private static Collection<String> extractListConfig(final String agentArgs, final String name) {
        return ofNullable(extractConfig(agentArgs, name))
                .map(it -> Stream.of(it).map(String::trim).filter(v -> !v.isEmpty()).collect(toList()))
                .orElse(null);
    }

    private static String extractConfig(final String agentArgs, final String startStr) {
        if (agentArgs != null && agentArgs.contains(startStr)) {
            final int start = agentArgs.indexOf(startStr) + startStr.length();
            final int separator = agentArgs.indexOf('|', start);
            final int endIdx;
            if (separator > 0) {
                endIdx = separator;
            } else {
                endIdx = agentArgs.length();
            }
            return agentArgs.substring(start, endIdx);
        }
        return null;
    }
}
