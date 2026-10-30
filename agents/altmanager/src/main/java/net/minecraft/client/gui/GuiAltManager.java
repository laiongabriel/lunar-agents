package net.minecraft.client.gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Alt manager screen.
 *
 * Compiled against 1.8.9 signatures and defined inside the game's class loader
 * at runtime by the agent, because GenesisClassLoader cannot see the agent jar.
 *
 * Only vanilla primitives are used: Gui.drawRect, FontRenderer.drawString and
 * GuiButton, all of which are already exercised by the server list screen.
 */
public class GuiAltManager extends GuiScreen {

    private static final int ROW_HEIGHT = 20;
    private static final int MAX_ROWS = 7;

    private static final int ID_DONE = 0;
    private static final int ID_ADD = 1;
    private static final int ID_DELETE = 2;
    private static final int ID_RENAME = 3;
    private static final int ID_ACTIVATE = 4;
    private static final int ID_UP = 5;
    private static final int ID_DOWN = 6;

    private static final int COLOR_BORDER = 0x60FFFFFF;
    /** Panel is 31% black so the game background still shows through. */
    private static final int COLOR_PANEL = 0x50000000;
    private static final int COLOR_LINE = 0x30FFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_OK = 0xFF55FF55;
    private static final int COLOR_BAD = 0xFFFF5555;
    private static final int COLOR_ROW = 0x10FFFFFF;
    private static final int COLOR_ROW_SELECTED = 0x38FFFFFF;

    private final List<String> accounts = new ArrayList<>();
    private final GuiScreen parent;
    private String active = "";
    private int selected = -1;
    private int scroll;
    private int visibleRows = MAX_ROWS;
    private GuiButton scrollUp;
    private GuiButton scrollDown;

    private String input;
    private boolean renaming;
    private String status = "";
    private boolean statusIsError;
    private long statusUntil;

    private int listX;
    private int listY;
    private int listWidth;
    private int detailX;
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int panelBottom;

    private boolean loggedFrame;
    private boolean loggedError;

    public GuiAltManager(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        load();
        log("initGui " + this.width + "x" + this.height
                + " fontRendererObj=" + (this.fontRendererObj != null));

        int center = this.width / 2;
        int panelWidth = Math.min(380, this.width - 40);

        this.panelLeft = center - panelWidth / 2;
        this.panelRight = center + panelWidth / 2;
        this.panelTop = 52;
        // Leaves room for the status line between the panel and the buttons.
        this.panelBottom = this.height - 56;

        this.listX = this.panelLeft + 10;
        this.listWidth = 186;
        this.listY = this.panelTop + 26;
        this.detailX = this.listX + this.listWidth + 16;

        int available = this.panelBottom - this.listY - 8;
        this.visibleRows = Math.max(1, Math.min(MAX_ROWS, available / ROW_HEIGHT));

        int bar = this.height - 38;
        this.buttonList.add(new GuiButton(ID_ADD, this.panelLeft + 10, bar, 60, 20, "Add"));
        this.buttonList.add(new GuiButton(ID_RENAME, this.panelLeft + 76, bar, 68, 20, "Rename"));
        this.buttonList.add(new GuiButton(ID_DELETE, this.panelLeft + 150, bar, 60, 20, "Delete"));
        this.buttonList.add(new GuiButton(ID_ACTIVATE, this.panelLeft + 216, bar, 86, 20, "Set Active"));
        this.buttonList.add(new GuiButton(ID_DONE, this.panelRight - 70, bar, 60, 20, "Done"));

        this.scrollUp = new GuiButton(ID_UP, this.listX + this.listWidth - 38, this.panelTop + 4, 16, 16, "^");
        this.scrollDown = new GuiButton(ID_DOWN, this.listX + this.listWidth - 20, this.panelTop + 4, 16, 16, "v");
        this.buttonList.add(this.scrollUp);
        this.buttonList.add(this.scrollDown);

        clampScroll();
        updateScrollButtons();
    }

    /**
     * Set Active doubles as Set Inactive: when the selected account is already
     * the active one, the button clears it (the game falls back to the session
     * from the launcher). Rename/Delete are disabled without a selection.
     */
    private void updateActionButton() {
        boolean hasSelection = this.selected >= 0 && this.selected < this.accounts.size();

        GuiButton activate = button(ID_ACTIVATE);
        if (activate != null) {
            boolean isActive = hasSelection && this.accounts.get(this.selected).equalsIgnoreCase(this.active);
            activate.enabled = hasSelection;
            activate.displayString = isActive ? "Set Inactive" : "Set Active";
        }

        GuiButton rename = button(ID_RENAME);
        if (rename != null) {
            rename.enabled = hasSelection;
        }

        GuiButton delete = button(ID_DELETE);
        if (delete != null) {
            delete.enabled = hasSelection;
        }
    }

    private GuiButton button(int id) {
        for (GuiButton candidate : this.buttonList) {
            if (candidate.id == id) {
                return candidate;
            }
        }
        return null;
    }

    /** The arrows only make sense when the list does not fit on screen. */
    private void updateScrollButtons() {
        boolean needed = this.accounts.size() > this.visibleRows;
        if (this.scrollUp != null) {
            this.scrollUp.visible = needed;
            this.scrollUp.enabled = needed && this.scroll > 0;
        }
        if (this.scrollDown != null) {
            this.scrollDown.visible = needed;
            this.scrollDown.enabled = needed && this.scroll < this.accounts.size() - this.visibleRows;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        try {
            drawFrame(mouseX, mouseY, partialTicks);
        } catch (Throwable t) {
            logOnce(t);
            try {
                drawRect(0, 0, Math.max(this.width, 1), 13, COLOR_BAD);
            } catch (Throwable ignored) {
                // Nothing else we can do while rendering.
            }
        }
    }

    private void drawFrame(int mouseX, int mouseY, float partialTicks) {
        if (!this.loggedFrame) {
            this.loggedFrame = true;
            log("drawScreen " + this.width + "x" + this.height
                    + " fontRendererObj=" + (this.fontRendererObj != null)
                    + " buttons=" + (this.buttonList == null ? -1 : this.buttonList.size()));
        }

        drawDefaultBackground();

        updateActionButton();

        centered("Alt Manager", this.width / 2, 22, COLOR_TEXT);
        centered("offline accounts - they only work on offline-mode servers",
                this.width / 2, 34, COLOR_DIM);

        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, COLOR_PANEL);

        // 1px frame, drawn as four thin rects so it does not fill the panel.
        drawRect(this.panelLeft - 1, this.panelTop - 1, this.panelRight + 1, this.panelTop, COLOR_BORDER);
        drawRect(this.panelLeft - 1, this.panelBottom, this.panelRight + 1, this.panelBottom + 1, COLOR_BORDER);
        drawRect(this.panelLeft - 1, this.panelTop, this.panelLeft, this.panelBottom, COLOR_BORDER);
        drawRect(this.panelRight, this.panelTop, this.panelRight + 1, this.panelBottom, COLOR_BORDER);

        drawRect(this.detailX - 10, this.panelTop + 8, this.detailX - 9, this.panelBottom - 8, COLOR_LINE);

        font().drawString("Accounts", this.listX + 2, this.panelTop + 10, COLOR_DIM);

        drawList();
        drawDetails();

        if (this.input != null) {
            drawInput();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (this.input == null && System.currentTimeMillis() < this.statusUntil) {
            font().drawString(this.status, this.panelLeft + 10, this.height - 52,
                    this.statusIsError ? COLOR_BAD : COLOR_OK);
        }
    }

    private void drawList() {
        if (this.accounts.isEmpty()) {
            centered("No accounts yet", this.listX + this.listWidth / 2, this.listY + 24, COLOR_DIM);
            font().drawString("Press \"Add\" to create one",
                    this.listX + 12, this.listY + 40, COLOR_DIM);
            return;
        }

        for (int row = 0; row < this.visibleRows; row++) {
            int index = this.scroll + row;
            if (index >= this.accounts.size()) {
                break;
            }

            int y = this.listY + row * ROW_HEIGHT;
            String name = this.accounts.get(index);
            boolean isSelected = index == this.selected;
            boolean isActive = name.equalsIgnoreCase(this.active);

            drawRect(this.listX, y, this.listX + this.listWidth, y + ROW_HEIGHT - 2,
                    isSelected ? COLOR_ROW_SELECTED : COLOR_ROW);

            if (isActive) {
                drawRect(this.listX, y, this.listX + 2, y + ROW_HEIGHT - 2, COLOR_OK);
            }

            font().drawString(name, this.listX + 8, y + 6, isSelected ? COLOR_TEXT : COLOR_DIM);

            if (isActive) {
                String tag = "active";
                font().drawString(tag,
                        this.listX + this.listWidth - 8 - font().getStringWidth(tag),
                        y + 6, COLOR_OK);
            }
        }

        if (this.accounts.size() > this.visibleRows) {
            String counter = (this.scroll + 1) + "-"
                    + Math.min(this.scroll + this.visibleRows, this.accounts.size())
                    + " / " + this.accounts.size();
            font().drawString(counter, this.listX + 2, this.panelBottom - 14, COLOR_DIM);
        }
    }

    private void drawDetails() {
        if (this.selected < 0 || this.selected >= this.accounts.size()) {
            font().drawString("Select an account", this.detailX, this.panelTop + 30, COLOR_DIM);
            return;
        }

        String name = this.accounts.get(this.selected);
        boolean isActive = name.equalsIgnoreCase(this.active);

        font().drawString("Selected", this.detailX, this.panelTop + 26, COLOR_DIM);
        font().drawString(name, this.detailX, this.panelTop + 38, COLOR_TEXT);

        font().drawString("Status", this.detailX, this.panelTop + 58, COLOR_DIM);
        font().drawString(isActive ? "Active" : "Inactive",
                this.detailX, this.panelTop + 70, isActive ? COLOR_OK : COLOR_DIM);

        int hintY = this.panelBottom - 52;
        font().drawString("Offline sessions have no", this.detailX, hintY, COLOR_DIM);
        font().drawString("skin and a zero token, so", this.detailX, hintY + 11, COLOR_DIM);
        font().drawString("premium servers reject them.", this.detailX, hintY + 22, COLOR_DIM);
    }

    private void drawInput() {
        int boxWidth = 220;
        int boxHeight = 66;
        int x = this.width / 2 - boxWidth / 2;
        int y = this.height / 2 - boxHeight / 2;

        drawRect(x, y, x + boxWidth, y + boxHeight, COLOR_PANEL);
        drawRect(x - 1, y - 1, x + boxWidth + 1, y, COLOR_BORDER);
        drawRect(x - 1, y + boxHeight, x + boxWidth + 1, y + boxHeight + 1, COLOR_BORDER);
        drawRect(x - 1, y, x, y + boxHeight, COLOR_BORDER);
        drawRect(x + boxWidth, y, x + boxWidth + 1, y + boxHeight, COLOR_BORDER);

        font().drawString(this.renaming ? "New name" : "Account name",
                x + 10, y + 8, COLOR_DIM);

        drawRect(x + 10, y + 22, x + boxWidth - 10, y + 42, COLOR_ROW);

        String cursor = (System.currentTimeMillis() / 500L) % 2L == 0L ? "_" : "";
        font().drawString(fit(this.input + cursor, boxWidth - 26), x + 15, y + 28, COLOR_TEXT);

        font().drawString("Enter to confirm, Esc to cancel", x + 10, y + 48, COLOR_DIM);
    }

    /** fontRendererObj can be missing when the game never called setWorldAndResolution. */
    private FontRenderer font() {
        return this.fontRendererObj != null ? this.fontRendererObj : this.mc.fontRendererObj;
    }

    private void centered(String text, int centerX, int y, int color) {
        font().drawString(text, centerX - font().getStringWidth(text) / 2, y, color);
    }

    /** Truncates with "..." using only getStringWidth, which is known to work here. */
    private String fit(String text, int maxWidth) {
        if (font().getStringWidth(text) <= maxWidth) {
            return text;
        }

        String result = text;
        while (!result.isEmpty() && font().getStringWidth(result + "...") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "...";
    }

    private void logOnce(Throwable t) {
        if (this.loggedError) {
            return;
        }
        this.loggedError = true;
        log("drawScreen failed: " + t + " (size " + this.width + "x" + this.height
                + ", fontRendererObj=" + (this.fontRendererObj != null) + ")");
    }

    private static void log(String message) {
        try {
            java.nio.file.Path file = java.nio.file.Paths.get(
                    System.getProperty("java.io.tmpdir", "."), "altmanager-status.log");
            java.nio.file.Files.write(file,
                    (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (Throwable ignored) {
            // Logging must never break the screen.
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (this.input != null) {
            return;
        }

        if (mouseButton == 0) {
            for (int row = 0; row < this.visibleRows; row++) {
                int index = this.scroll + row;
                if (index >= this.accounts.size()) {
                    break;
                }

                int y = this.listY + row * ROW_HEIGHT;
                if (mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                        && mouseY >= y && mouseY <= y + ROW_HEIGHT - 2) {
                    this.selected = index;
                    return;
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_DONE:
                close();
                return;
            case ID_ADD:
                startInput("", false);
                return;
            case ID_RENAME:
                if (this.selected >= 0) {
                    startInput(this.accounts.get(this.selected), true);
                }
                return;
            case ID_DELETE:
                deleteSelected();
                return;
            case ID_ACTIVATE:
                activateSelected();
                return;
            case ID_UP:
                this.scroll--;
                clampScroll();
                updateScrollButtons();
                return;
            case ID_DOWN:
                this.scroll++;
                clampScroll();
                updateScrollButtons();
                return;
            default:
                super.actionPerformed(button);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.input == null) {
            if (keyCode == 1) {
                close();
                return;
            }
            super.keyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == 1) {
            this.input = null;
            return;
        }

        if (keyCode == 28) {
            confirmInput();
            return;
        }

        if (keyCode == 14) {
            if (!this.input.isEmpty()) {
                this.input = this.input.substring(0, this.input.length() - 1);
            }
            return;
        }

        if (this.input.length() < 16 && isAllowed(typedChar)) {
            this.input += typedChar;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        save();
    }

    private void close() {
        this.mc.displayGuiScreen(this.parent);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void startInput(String value, boolean rename) {
        this.input = value == null ? "" : value;
        this.renaming = rename;
    }

    private void confirmInput() {
        String name = this.input == null ? "" : this.input.trim();
        this.input = null;

        if (name.length() < 3) {
            report("A name needs at least 3 characters", true);
            return;
        }

        int existing = indexOfName(name);

        if (this.renaming) {
            if (this.selected < 0) {
                return;
            }
            if (existing >= 0 && existing != this.selected) {
                report("There is already an account named " + name, true);
                return;
            }

            String old = this.accounts.get(this.selected);
            this.accounts.set(this.selected, name);
            if (old.equalsIgnoreCase(this.active)) {
                this.active = name;
            }
            report("Renamed " + old + " to " + name, false);
        } else {
            if (existing >= 0) {
                report("There is already an account named " + name, true);
                return;
            }

            this.accounts.add(name);
            this.selected = this.accounts.size() - 1;

            if (this.active.isEmpty()) {
                this.active = name;
                report("Added " + name + " and set it as active", false);
            } else {
                report("Added " + name, false);
            }
        }

        this.renaming = false;
        scrollToSelection();
        save();
    }

    private void deleteSelected() {
        if (this.selected < 0 || this.selected >= this.accounts.size()) {
            report("Select an account first", true);
            return;
        }

        String removed = this.accounts.remove(this.selected);
        if (removed.equalsIgnoreCase(this.active)) {
            this.active = "";
        }

        if (this.accounts.isEmpty()) {
            this.selected = -1;
        } else {
            this.selected = Math.min(this.selected, this.accounts.size() - 1);
        }

        clampScroll();
        updateScrollButtons();
        report("Removed " + removed, false);
        save();
    }

    private void activateSelected() {
        if (this.selected < 0 || this.selected >= this.accounts.size()) {
            report("Select an account first", true);
            return;
        }

        String name = this.accounts.get(this.selected);

        if (name.equalsIgnoreCase(this.active)) {
            this.active = "";
            report("No active account - using the launcher session", false);
        } else {
            this.active = name;
            report("Active account: " + name, false);
        }

        save();
    }

    private void report(String message, boolean error) {
        this.status = message;
        this.statusIsError = error;
        this.statusUntil = System.currentTimeMillis() + 4000L;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private Path configPath() {
        String path = System.getProperty("altmanager.config");
        return path == null ? null : Paths.get(path);
    }

    private void load() {
        this.accounts.clear();
        this.active = "";
        this.selected = -1;
        this.scroll = 0;

        Path file = configPath();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (Throwable t) {
            report("Could not read " + file.getFileName(), true);
            return;
        }

        this.active = properties.getProperty("active", "").trim();

        for (String name : properties.getProperty("accounts", "").split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && indexOfName(trimmed) < 0) {
                this.accounts.add(trimmed);
            }
        }

        this.selected = indexOfName(this.active);
        if (this.selected < 0 && !this.accounts.isEmpty()) {
            this.selected = 0;
        }
    }

    private void save() {
        Path file = configPath();
        if (file == null) {
            report("No config file configured", true);
            return;
        }

        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }

            StringBuilder joined = new StringBuilder();
            for (String account : this.accounts) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(account);
            }

            properties.remove("hotkey"); // removed feature: only the button opens the screen
            properties.setProperty("accounts", joined.toString());
            properties.setProperty("active", this.active);

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, "managed by the alt manager agent");
            }

            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            report("Could not save " + file.getFileName(), true);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int indexOfName(String name) {
        for (int i = 0; i < this.accounts.size(); i++) {
            if (this.accounts.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private void clampScroll() {
        int max = Math.max(0, this.accounts.size() - this.visibleRows);
        if (this.scroll > max) {
            this.scroll = max;
        }
        if (this.scroll < 0) {
            this.scroll = 0;
        }
    }

    private void scrollToSelection() {
        if (this.selected < 0) {
            return;
        }
        if (this.selected < this.scroll) {
            this.scroll = this.selected;
        }
        if (this.selected >= this.scroll + this.visibleRows) {
            this.scroll = this.selected - this.visibleRows + 1;
        }
        clampScroll();
        updateScrollButtons();
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

}

