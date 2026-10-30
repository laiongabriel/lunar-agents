package me.zircta.altmanager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Code injected into net.minecraft.client.Minecraft by the alt manager agent.
 *
 * This class is copied into Minecraft (the agent classes are not visible to the
 * game's class loader), so it must only touch JDK types, LWJGL and the two game
 * classes below, and it must keep working inside a Java 8 class file.
 *
 * The screen is defined here, from inside the game, with
 * MethodHandles.privateLookupIn(GuiScreen).defineClass(...). That way nothing
 * depends on the game's class loader being able to resolve classes by name.
 */
public final class AltRuntime {

    private static final Path LOG_FILE =
            Paths.get(System.getProperty("java.io.tmpdir", "."), "altmanager-status.log");

    private static Object alt$cachedSession;
    private static String alt$cachedName;
    private static long alt$cachedModified = Long.MIN_VALUE;
    private static Class<?> alt$guiClass;
    private static boolean alt$guiFailed;

    private AltRuntime() {
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    /**
     * True when an offline account is configured. Used by getSession() to decide
     * whether to fall back to the original body.
     */
    public static boolean alt$ready() {
        return alt$peek() != null;
    }

    /**
     * Returns the active offline account as a Session instance, or null when no
     * account is configured (the game then keeps its own session).
     */
    public static Object alt$session() {
        return alt$peek();
    }

    private static Object alt$peek() {
        String path = System.getProperty("altmanager.config");
        if (path == null) {
            return null;
        }

        try {
            Path file = Paths.get(path);
            if (!Files.isRegularFile(file)) {
                return null;
            }

            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == alt$cachedModified) {
                return alt$cachedSession;
            }
            alt$cachedModified = modified;

            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }

            String name = properties.getProperty("active", "").trim();
            if (name.equals(alt$cachedName)) {
                return alt$cachedSession;
            }

            alt$cachedName = name;
            alt$cachedSession = name.isEmpty() ? null : alt$create(name);
            return alt$cachedSession;
        } catch (Throwable t) {
            return alt$cachedSession;
        }
    }

    private static Object alt$create(String name) throws Exception {
        String uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();

        Class<?> session = Class.forName("net.minecraft.util.Session");

        try {
            // Vanilla 1.8.9: Session(String, String, String, String) with
            // "legacy" / "mojang" as the session type.
            return session
                    .getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance(name, uuid, "0", "legacy");
        } catch (NoSuchMethodException ignored) {
            // Newer Lunar builds take Session.Type instead.
        }

        Class<?> type = Class.forName("net.minecraft.util.Session$Type");
        return session
                .getConstructor(String.class, String.class, String.class, type)
                .newInstance(name, uuid, "0", alt$legacy(type));
    }

    private static Object alt$legacy(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalStateException("Session$Type has no constants");
        }

        for (Object constant : constants) {
            if (String.valueOf(constant).equalsIgnoreCase("legacy")) {
                return constant;
            }
        }

        return constants[0];
    }

    // ------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------

    /**
     * Defines (once) and opens the alt manager screen. Every failure is caught
     * and logged, so a broken screen never crashes the client.
     */
    public static void alt$open(GuiScreen parent) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                alt$log("open: Minecraft.getMinecraft() returned null");
                return;
            }

            Class<?> gui = alt$gui();
            if (gui == null) {
                return;
            }

            Object screen = gui.getDeclaredConstructor(GuiScreen.class).newInstance(parent);
            mc.displayGuiScreen((GuiScreen) screen);
        } catch (Throwable t) {
            alt$log("open failed: " + t);
        }
    }

    private static Class<?> alt$gui() {
        Class<?> gui = alt$guiClass;
        if (gui != null) {
            return gui;
        }
        if (alt$guiFailed) {
            return null;
        }

        try {
            String path = System.getProperty("altmanager.gui");
            if (path == null) {
                alt$guiFailed = true;
                alt$log("gui: altmanager.gui is not set");
                return null;
            }

            byte[] bytes = Files.readAllBytes(Paths.get(path));
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles
                    .privateLookupIn(GuiScreen.class, java.lang.invoke.MethodHandles.lookup());

            gui = lookup.defineClass(bytes);
            alt$guiClass = gui;
            alt$log("defined " + gui.getName());
            return gui;
        } catch (Throwable t) {
            alt$guiFailed = true;
            alt$log("gui define failed: " + t);
            return null;
        }
    }

    private static void alt$log(String message) {
        try {
            Files.write(LOG_FILE, (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (Throwable ignored) {
            // Logging must never break the game.
        }
    }
}
