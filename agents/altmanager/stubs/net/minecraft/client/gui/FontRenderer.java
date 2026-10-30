package net.minecraft.client.gui;

/**
 * Compile-only stub of the 1.8.9 class. Never packaged into the agent jar.
 */
public class FontRenderer {

    public static final int FONT_HEIGHT = 9;

    public int drawString(String text, int x, int y, int color) {
        return 0;
    }

    public int getStringWidth(String text) {
        return text == null ? 0 : text.length() * 6;
    }

    public String trimStringToWidth(String text, int width) {
        return text;
    }
}
