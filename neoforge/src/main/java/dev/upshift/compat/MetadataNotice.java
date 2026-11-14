package dev.upshift.compat;

import dev.upshift.Upshift;
import dev.upshift.compat.MetadataAudit.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Draws a short, self-dismissing note on the title screen when another mod
 * ships outdated icon metadata.
 *
 * <p>NeoForge only warns about {@code logoFile} in development, so the note
 * is development-only as well: players would otherwise get a message about a
 * problem that never reaches them. The note appears once per changed set of
 * mods (the signature is stored in the Upshift config) and fades out after
 * half a minute; after the next restart the same set produces no note at
 * all.</p>
 */
public final class MetadataNotice {
    private static final long VISIBLE_NS = 30_000_000_000L;
    private static final long FADE_NS = 2_500_000_000L;
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int GAP = 2;
    private static final int MAX_TEXT_WIDTH = 440;

    // Armed on the mod-loading worker during client setup and read on the
    // render thread once the title screen appears.
    private static volatile List<Finding> findings = List.of();
    private static long firstRenderNs;
    private static boolean loggedShown;
    private static boolean loggedHidden;

    private MetadataNotice() {
    }

    public static void arm(List<Finding> detected) {
        findings = List.copyOf(detected);
        firstRenderNs = 0L;
        loggedShown = false;
        loggedHidden = false;
    }

    public static void clear() {
        findings = List.of();
        firstRenderNs = 0L;
    }

    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (findings.isEmpty()) {
            return;
        }
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen)) {
            return;
        }
        long now = System.nanoTime();
        if (firstRenderNs == 0L) {
            firstRenderNs = now;
        }
        long elapsed = now - firstRenderNs;
        if (elapsed >= VISIBLE_NS) {
            if (!loggedHidden) {
                loggedHidden = true;
                Upshift.LOG.info("metadata notice: hidden until the affected mod list changes");
            }
            return;
        }
        if (!loggedShown) {
            loggedShown = true;
            Upshift.LOG.info("metadata notice: shown on the title screen");
        }
        float fade = elapsed > VISIBLE_NS - FADE_NS ? (float)(VISIBLE_NS - elapsed) / (float)FADE_NS : 1.0f;
        MetadataNotice.draw(event.getGuiGraphics(), fade);
    }

    private static void draw(GuiGraphicsExtractor graphics, float fade) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        int maxTextWidth = Math.min(MAX_TEXT_WIDTH, Math.max(160, guiWidth - 24 - PADDING * 2 - 3));

        String mods = findings.stream()
                .map(Finding::displayName)
                .distinct()
                .collect(Collectors.joining(", "));
        List<String> first = MetadataNotice.wrap(font, Component.translatable("upshift.metadata.mods", mods).getString(), maxTextWidth);
        List<String> second = MetadataNotice.wrap(font, Component.translatable("upshift.metadata.restart").getString(), maxTextWidth);
        List<String> lines = new ArrayList<>(first.size() + second.size());
        lines.addAll(first);
        lines.addAll(second);

        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, font.width(line));
        }
        contentWidth = Math.min(contentWidth, maxTextWidth);
        int width = contentWidth + PADDING * 2 + 3;
        int height = PADDING * 2 + first.size() * LINE_HEIGHT + GAP + second.size() * LINE_HEIGHT;
        // Sit just above the version/copyright line, which is 10 px tall.
        int x = Math.max(2, (guiWidth - width) / 2);
        int y = Math.max(2, guiHeight - 14 - height);

        graphics.fill(x, y, x + width, y + height, MetadataNotice.withAlpha(0xD0101010, fade));
        graphics.fill(x, y, x + 2, y + height, MetadataNotice.withAlpha(0xFF4FC3F7, fade));

        int textX = x + PADDING + 3;
        int cursorY = y + PADDING;
        int firstColor = MetadataNotice.withAlpha(0xFFE8F6FF, fade);
        int secondColor = MetadataNotice.withAlpha(0xFF9E9E9E, fade);
        for (int i = 0; i < first.size(); ++i) {
            graphics.text(font, first.get(i), textX, cursorY, firstColor, true);
            cursorY += LINE_HEIGHT;
        }
        cursorY += GAP;
        for (String line : second) {
            graphics.text(font, line, textX, cursorY, secondColor, true);
            cursorY += LINE_HEIGHT;
        }
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static int withAlpha(int color, float alpha) {
        int base = color >>> 24;
        int scaled = Math.max(0, Math.min(255, Math.round(base * alpha)));
        return scaled << 24 | (color & 0xFFFFFF);
    }
}
