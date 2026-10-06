package io.github.azureland;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;

final class MarkdownRenderer {
    record Rendered(List<Component> lines, boolean truncated) {}

    private static final Pattern CITATION = Pattern.compile("\\[([0-9]{1,6})\\]");
    private static final Pattern COLOR_TAG = Pattern.compile(
            "<(/)?(color|#[0-9a-f]{6}|[a-z_]+)(?::(#[0-9a-f]{6}|[a-z_]+))?>",
            Pattern.CASE_INSENSITIVE);
    private final List<AiClient.Source> sources;
    private final List<TextComponent> spans = new ArrayList<>();
    private final Deque<ColorTag> colors = new ArrayDeque<>();

    private static final class ColorTag extends CustomNode {
        final String name;
        final TextColor color;
        final boolean closing;

        ColorTag(String name, TextColor color, boolean closing) {
            this.name = name;
            this.color = color;
            this.closing = closing;
        }
    }

    private static final class ColorParserFactory implements InlineContentParserFactory {
        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('<');
        }

        @Override
        public InlineContentParser create() {
            return state -> {
                Scanner scanner = state.scanner();
                Position start = scanner.position();
                StringBuilder token = new StringBuilder();
                while (scanner.hasNext() && scanner.peek() != '\n') {
                    char character = scanner.peek();
                    token.append(character);
                    scanner.next();
                    if (character == '>') {
                        break;
                    }
                }
                Matcher match = COLOR_TAG.matcher(token);
                if (match.matches()) {
                    String name = match.group(2).toLowerCase(Locale.ROOT);
                    String value = match.group(3);
                    boolean closing = match.group(1) != null;
                    if (name.equals("reset") && !closing && value == null) {
                        return ParsedInline.of(new ColorTag(name, null, false), scanner.position());
                    }
                    String colorName = name.equals("color") ? value : name;
                    TextColor color = colorName == null ? null : colorName.startsWith("#")
                            ? TextColor.fromHexString(colorName)
                            : NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
                    boolean valid = closing
                            ? value == null && (name.equals("color") || color != null)
                            : color != null && (name.equals("color") || value == null);
                    if (valid) {
                        return ParsedInline.of(new ColorTag(name, color, closing), scanner.position());
                    }
                }
                scanner.setPosition(start);
                return ParsedInline.none();
            };
        }
    }

    private MarkdownRenderer(List<AiClient.Source> sources) {
        this.sources = sources;
    }

    static Rendered render(String markdown, NamedTextColor color, List<AiClient.Source> sources) {
        return parse(markdown, color, sources).splitLines();
    }

    static Component renderDialog(String markdown, NamedTextColor color) {
        MarkdownRenderer renderer = parse(markdown, color, List.of());
        if (renderer.spans.isEmpty()) {
            return DialogTextLayout.leftAligned(List.of(Component.text("（空消息）", NamedTextColor.GRAY)));
        }
        return DialogTextLayout.leftAligned(renderer.spans);
    }

    private static MarkdownRenderer parse(String markdown, NamedTextColor color,
                                           List<AiClient.Source> sources) {
        String clean = ChatColor.stripColor(markdown).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        Parser parser = Parser.builder()
                // Keep color tags on their own lines in the Markdown inline parser.
                .enabledBlockTypes(new LinkedHashSet<>(List.of(BlockQuote.class, Heading.class,
                        FencedCodeBlock.class, ThematicBreak.class, ListBlock.class, IndentedCodeBlock.class)))
                .customInlineContentParserFactory(new ColorParserFactory()).extensions(List.of(
                StrikethroughExtension.create(), TablesExtension.create())).build();
        MarkdownRenderer renderer = new MarkdownRenderer(sources);
        renderer.visit(parser.parse(clean), Style.style(color), "");
        return renderer;
    }

    private void children(Node node, Style style, String prefix) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            visit(child, style, prefix);
        }
    }

    private void visit(Node node, Style style, String prefix) {
        if (node instanceof ColorTag tag) {
            if (tag.name.equals("reset")) {
                colors.clear();
            } else if (tag.closing) {
                if (!colors.isEmpty() && colors.peek().name.equals(tag.name)) {
                    colors.pop();
                }
            } else {
                colors.push(tag);
            }
        } else if (node instanceof Text text) {
            text(text.getLiteral(), style);
        } else if (node instanceof StrongEmphasis) {
            children(node, style.decorate(TextDecoration.BOLD), prefix);
        } else if (node instanceof Emphasis) {
            children(node, style.decorate(TextDecoration.ITALIC), prefix);
        } else if (node instanceof Strikethrough) {
            children(node, style.decorate(TextDecoration.STRIKETHROUGH), prefix);
        } else if (node instanceof Code code) {
            literal(code.getLiteral(), style.color(NamedTextColor.YELLOW));
        } else if (node instanceof Link link) {
            children(node, linkStyle(link.getDestination(), style), prefix);
        } else if (node instanceof Image image) {
            literal("[图片] ", style);
            children(node, linkStyle(image.getDestination(), style), prefix);
        } else if (node instanceof Heading) {
            literal(prefix, style);
            children(node, style.color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD), prefix);
            newline();
        } else if (node instanceof Paragraph) {
            literal(prefix, style);
            children(node, style, prefix);
            newline();
        } else if (node instanceof BlockQuote) {
            children(node, style.color(NamedTextColor.GRAY), prefix + "> ");
        } else if (node instanceof BulletList) {
            list(node, style, prefix, false, 0);
        } else if (node instanceof OrderedList list) {
            list(node, style, prefix, true, list.getMarkerStartNumber());
        } else if (node instanceof FencedCodeBlock code) {
            codeBlock(code.getLiteral(), style, prefix);
        } else if (node instanceof IndentedCodeBlock code) {
            codeBlock(code.getLiteral(), style, prefix);
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            newline();
            literal(prefix, style);
        } else if (node instanceof ThematicBreak) {
            literal(prefix + "--------------------", style.color(NamedTextColor.GRAY));
            newline();
        } else if (node instanceof TableRow) {
            literal(prefix + "| ", style);
            children(node, style, prefix);
            newline();
        } else if (node instanceof TableCell cell) {
            children(node, cell.isHeader() ? style.decorate(TextDecoration.BOLD) : style, prefix);
            literal(" | ", style);
        } else if (node instanceof HtmlInline html) {
            literal(html.getLiteral(), style);
        } else if (node instanceof HtmlBlock html) {
            codeBlock(html.getLiteral(), style, prefix);
        } else {
            children(node, style, prefix);
        }
    }

    private void list(Node node, Style style, String prefix, boolean ordered, int start) {
        int number = start;
        for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
            String marker = ordered ? number++ + ". " : "- ";
            boolean first = true;
            for (Node block = item.getFirstChild(); block != null; block = block.getNext()) {
                String itemPrefix = prefix + (first ? marker : " ".repeat(marker.length()));
                if (first && !(block instanceof Paragraph)) {
                    literal(prefix + marker, style);
                    newline();
                    itemPrefix = prefix + " ".repeat(marker.length());
                }
                if (block instanceof Paragraph) {
                    literal(itemPrefix, style);
                    children(block, style, prefix + " ".repeat(marker.length()));
                    newline();
                } else {
                    visit(block, style, itemPrefix);
                }
                first = false;
            }
        }
    }

    private void codeBlock(String code, Style style, String prefix) {
        for (String line : code.split("\n")) {
            literal(prefix + line, style.color(NamedTextColor.YELLOW));
            newline();
        }
    }

    private Style linkStyle(String destination, Style style) {
        try {
            URI uri = URI.create(destination);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                return style.color(NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(destination))
                        .hoverEvent(Component.text(destination));
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid or non-web links are displayed as ordinary text.
        }
        return style;
    }

    private void text(String text, Style style) {
        Matcher matcher = CITATION.matcher(text);
        int previous = 0;
        while (matcher.find()) {
            literal(text.substring(previous, matcher.start()), style);
            int number = Integer.parseInt(matcher.group(1));
            Style citationStyle = style;
            if (number > 0 && number <= sources.size()) {
                AiClient.Source source = sources.get(number - 1);
                citationStyle = style.color(NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(source.url()))
                        .hoverEvent(Component.text(source.title() + "\n" + source.url()));
            }
            literal(matcher.group(), citationStyle);
            previous = matcher.end();
        }
        literal(text.substring(previous), style);
    }

    private void literal(String text, Style style) {
        if (!text.isEmpty()) {
            if (!colors.isEmpty()) {
                style = style.color(colors.peek().color);
            }
            spans.add(Component.text(text, style));
        }
    }

    private void newline() {
        literal("\n", Style.empty());
    }

    // Split styled spans instead of raw Markdown so wrapping cannot break markup or links.
    private Rendered splitLines() {
        List<Component> lines = new ArrayList<>();
        Component line = Component.empty();
        int length = 0;
        for (TextComponent span : spans) {
            String content = span.content();
            for (int offset = 0; offset < content.length();) {
                if (content.charAt(offset) == '\n') {
                    if (length > 0) {
                        if (lines.size() == 80) {
                            return new Rendered(lines, true);
                        }
                        lines.add(line);
                        line = Component.empty();
                        length = 0;
                    }
                    offset++;
                    continue;
                }
                if (length == 200) {
                    if (lines.size() == 80) {
                        return new Rendered(lines, true);
                    }
                    lines.add(line);
                    line = Component.empty();
                    length = 0;
                }
                int newline = content.indexOf('\n', offset);
                int limit = newline < 0 ? content.length() : newline;
                int count = Math.min(200 - length, content.codePointCount(offset, limit));
                int end = content.offsetByCodePoints(offset, count);
                line = line.append(Component.text(content.substring(offset, end), span.style()));
                length += count;
                offset = end;
            }
        }
        if (length > 0) {
            if (lines.size() == 80) {
                return new Rendered(lines, true);
            }
            lines.add(line);
        }
        return new Rendered(lines, false);
    }
}
