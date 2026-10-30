package net.minecraft.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

/**
 * Compile-only stub of the 1.8.9 class. Never packaged into the agent jar.
 */
public class GuiScreen extends Gui {

    public Minecraft mc;
    public int width;
    public int height;
    protected FontRenderer fontRendererObj;
    protected List<GuiButton> buttonList = new ArrayList<GuiButton>();

    public void initGui() {
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
    }

    protected void keyTyped(char typedChar, int keyCode) throws IOException {
    }

    protected void actionPerformed(GuiButton button) throws IOException {
    }

    public void drawDefaultBackground() {
    }

    public boolean doesGuiPauseGame() {
        return true;
    }

    public void onGuiClosed() {
    }
}
