package net.minecraft.client.gui;

/**
 * Compile-only stub of the 1.8.9 class. Never packaged into the agent jar.
 *
 * Only drawRect is declared: the static drawString/drawCenteredString helpers
 * do not exist in Lunar's runtime (calling them produced
 * IncompatibleClassChangeError), so text goes through FontRenderer directly.
 */
public class Gui {

    public static void drawRect(int left, int top, int right, int bottom, int color) {
    }
}
