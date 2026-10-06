package io.github.azureland;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

final class DialogTextLayout {
    static final int BODY_WIDTH = 360;
    // 26.3's FocusableTextWidget includes 4px padding per side in the declared body width.
    private static final int TEXT_WIDTH = BODY_WIDTH - 8;
    private static final byte[] METRICS = loadMetrics();
    private record Glyph(int codePoint, Style style, int advance, int endOffset) { }

    private DialogTextLayout() { }

    static Component leftAligned(List<TextComponent> spans) {
        List<Glyph> glyphs = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (TextComponent span : spans) {
            Style style = span.style();
            boolean bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            span.content().replace("\t", "    ").codePoints().forEach(codePoint -> {
                int metric = codePoint * 2;
                int advance = METRICS[metric] & 0xff;
                if (bold) {
                    advance += METRICS[metric + 1] & 0xff;
                }
                plain.appendCodePoint(codePoint);
                glyphs.add(new Glyph(codePoint, style, advance, plain.length()));
            });
        }
        // Unicode line boundaries handle Chinese text and punctuation as well as English words.
        BreakIterator iterator = BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(plain.toString());
        BitSet breaks = new BitSet(plain.length() + 1);
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE; boundary = iterator.next()) {
            breaks.set(boundary);
        }
        TextComponent.Builder output = Component.text();
        int start = 0;
        int cursor = 0;
        int width = 0;
        int lastBreak = -1;
        boolean hasText = false;
        while (cursor < glyphs.size()) {
            Glyph glyph = glyphs.get(cursor);
            if (glyph.codePoint() == '\n') {
                appendLine(output, glyphs, start, cursor);
                output.append(Component.newline());
                cursor++;
                start = cursor;
                width = 0;
                lastBreak = -1;
                hasText = false;
                continue;
            }
            if (width + glyph.advance() > TEXT_WIDTH * 2) {
                int end = lastBreak > start ? lastBreak : cursor;
                if (isListMarker(glyphs, start, end)) {
                    end = cursor;
                }
                // Long unbroken words still need a character split; keep closing punctuation with text.
                while (end > start + 1 && (isClosing(glyphs.get(end).codePoint())
                        || isOpening(glyphs.get(end - 1).codePoint()))) {
                    end--;
                }
                appendLine(output, glyphs, start, end);
                output.append(Component.newline());
                cursor = end;
                start = cursor;
                width = 0;
                lastBreak = -1;
                hasText = false;
                continue;
            }
            width += glyph.advance();
            cursor++;
            if (!Character.isSpaceChar(glyph.codePoint()) && glyph.advance() > 0) {
                hasText = true;
            }
            if (hasText && breaks.get(glyph.endOffset())) {
                lastBreak = cursor;
            }
        }
        if (start < glyphs.size()) {
            appendLine(output, glyphs, start, glyphs.size());
        }
        return output.build();
    }

    private static void appendLine(TextComponent.Builder output, List<Glyph> glyphs, int start, int end) {
        StringBuilder text = new StringBuilder();
        Style style = Style.empty();
        int width = 0;
        for (int index = start; index < end; index++) {
            Glyph glyph = glyphs.get(index);
            if (!glyph.style().equals(style)) {
                if (!text.isEmpty()) {
                    output.append(Component.text(text.toString(), style));
                    text.setLength(0);
                }
                style = glyph.style();
            }
            text.appendCodePoint(glyph.codePoint());
            width += glyph.advance();
        }
        if (!text.isEmpty()) {
            output.append(Component.text(text.toString(), style));
        }
        // Centering uses a whole-pixel rounded width. Ordinary spaces give 4px; bold U+200C gives 1px.
        int padding = TEXT_WIDTH - (width + 1) / 2;
        output.append(Component.text(" ".repeat(padding / 4)).decoration(TextDecoration.BOLD, false));
        output.append(Component.text("\u200c".repeat(padding % 4)).decoration(TextDecoration.BOLD, true));
        // An unbolded zero-width character keeps the padding before the explicit line break.
        output.append(Component.text("\u200c").decoration(TextDecoration.BOLD, false));
    }

    private static boolean isClosing(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || ",.:;!?%‰°、。，．：；！？…".indexOf(codePoint) >= 0;
    }

    private static boolean isListMarker(List<Glyph> glyphs, int start, int end) {
        StringBuilder marker = new StringBuilder();
        for (int index = start; index < end; index++) {
            marker.appendCodePoint(glyphs.get(index).codePoint());
        }
        return marker.toString().strip().matches("(?:[-+*]|[0-9]+[.)])");
    }

    private static boolean isOpening(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.START_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION;
    }

    private static byte[] loadMetrics() {
        try (GZIPInputStream input = new GZIPInputStream(
                DialogTextLayout.class.getResourceAsStream("/dialog-font-metrics.bin.gz"))) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
