package net.minecraft.client.gui;

import net.minecraft.client.Minecraft;

/**
 * Compile-only stub of the 1.8.9 class. Never packaged into the agent jar.
 */
public class GuiButton {

    protected int width;
    protected int height;
    public int xPosition;
    public int yPosition;
    public String displayString;
    public int id;
    public boolean enabled = true;
    public boolean visible = true;

    public GuiButton(int buttonId, int x, int y, int widthIn, int heightIn, String buttonText) {
        this.id = buttonId;
        this.xPosition = x;
        this.yPosition = y;
        this.width = widthIn;
        this.height = heightIn;
        this.displayString = buttonText;
    }

    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
    }

    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        return false;
    }
}
