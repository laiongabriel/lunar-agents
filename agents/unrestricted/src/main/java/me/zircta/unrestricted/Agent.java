package me.zircta.unrestricted;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Unrestricted premain agent.
 *
 * Port of the Weave mod "unrestricted" (marioparaschiv/weave-unrestricted,
 * itself a port of Youded-byte/unrestricted). The original registered a
 * {@link java.lang.instrument.ClassFileTransformer} through Weave's
 * {@code preInit(Instrumentation)}; here the same transformer is registered
 * through the standard {@code -javaagent} premain hook.
 */
public final class Agent {

    private static final Path LOG_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "unrestricted-status.log");

    private static final Object LOG_LOCK = new Object();

    public static void premain(String args, Instrumentation inst) {
        resetLog();
        log("agent starting");
        inst.addTransformer(new UnrestrictedTransformer(), true);
    }

    /** Used when the jar is attached to an already running client. */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);

        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            if (!name.startsWith("com.moonsworth.lunar.") && !name.startsWith("com.lunarclient.")) {
                continue;
            }
            if (!inst.isModifiableClass(type)) {
                continue;
            }
            try {
                if (!mightMatch(type)) {
                    continue;
                }
                inst.retransformClasses(type);
                log("retransformed " + name);
            } catch (Throwable t) {
                log("retransform failed " + name + ": " + t);
            }
        }
    }

    /**
     * Cheap pre-filter for the attach path: read the class resource and look for
     * one of the transformer markers before paying for a retransform.
     */
    private static boolean mightMatch(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        String resource = (dot >= 0 ? name.substring(dot + 1) : name) + ".class";

        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                return true;
            }
            return UnrestrictedTransformer.mayMatch(in.readAllBytes());
        } catch (Throwable t) {
            return true;
        }
    }

    static void resetLog() {
        synchronized (LOG_LOCK) {
            try {
                Files.writeString(LOG_FILE, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Throwable ignored) {
                // Logging must never break class loading.
            }
        }
    }

    static void log(String message) {
        synchronized (LOG_LOCK) {
            try {
                Files.writeString(LOG_FILE, message + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            } catch (Throwable ignored) {
                // Logging must never break class loading.
            }
        }
    }
}
