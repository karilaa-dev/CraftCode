package dev.craftcode.ui;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;

/**
 * Turns {@link Markdown} into styled Minecraft text. {@link #inline} styles one block's spans (links carry an
 * {@link ClickEvent.OpenUrl} so hit tests can find them); {@link #format} flattens a whole message into one component
 * for places without block layout (question prompts).
 */
final class MessageFormatter {
    private MessageFormatter() {}

    /** Styled spans of one block. {@code baseColor} is the normal text color. */
    static MutableComponent inline(List<Markdown.Span> spans, int baseColor, boolean baseBold) {
        MutableComponent out = Component.empty();
        for (var span : spans) out.append(Component.literal(span.text()).withStyle(style(span, baseColor, baseBold)));
        return out;
    }

    private static Style style(Markdown.Span span, int baseColor, boolean baseBold) {
        int color = span.url() != null ? Theme.LINK
                : span.has(Markdown.CODE) ? Theme.CODE
                : span.has(Markdown.BOLD) && !baseBold ? Theme.TEXT
                : baseColor;
        Style style = Style.EMPTY.withColor(color & 0xFFFFFF)
                .withBold(baseBold || (span.has(Markdown.BOLD) && !span.has(Markdown.CODE)))
                .withItalic(span.has(Markdown.ITALIC) && !span.has(Markdown.CODE))
                .withStrikethrough(span.has(Markdown.STRIKE));
        URI uri = uri(span.url());
        if (uri != null) {
            style = style.withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(uri))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(span.url())));
        }
        return style;
    }

    static @Nullable URI uri(@Nullable String url) {
        if (url == null) return null;
        try {
            return new URI(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** The http(s) link carried by {@code style}, if any. */
    static @Nullable URI link(@Nullable Style style) {
        if (style == null) return null;
        if (style.getClickEvent() instanceof ClickEvent.OpenUrl(URI uri)) {
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return uri;
        }
        return null;
    }

    /** Whole message as one component with newlines between blocks (no hanging indents). */
    static MutableComponent format(String raw, int baseColor) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (var block : Markdown.parse(raw)) {
            if (!first) out.append(Component.literal("\n"));
            first = false;
            switch (block.kind()) {
                case CODE -> out.append(colored(String.join("\n", block.code()), Theme.CODE));
                case RULE -> out.append(colored("────────", Theme.FAINT));
                case HEADING -> out.append(inline(block.spans(), Theme.TEXT, true));
                case BULLET, ORDERED -> {
                    out.append(colored("  ".repeat(block.level()) + block.marker() + " ", Theme.MUTED));
                    out.append(inline(block.spans(), baseColor, false));
                }
                case QUOTE -> {
                    out.append(colored("│ ", Theme.FAINT));
                    out.append(inline(block.spans(), Theme.MUTED, false));
                }
                case PARAGRAPH, TABLE_ROW -> out.append(inline(block.spans(), baseColor, false));
            }
        }
        return out;
    }

    static MutableComponent colored(String text, int color) {
        return Component.literal(text).withStyle(s -> s.withColor(color & 0xFFFFFF));
    }
}
