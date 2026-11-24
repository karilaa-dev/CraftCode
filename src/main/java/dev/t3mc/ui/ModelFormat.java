package dev.t3mc.ui;

import dev.t3mc.L10n;
import dev.t3mc.ui.model.ModelChoice;
import dev.t3mc.ui.model.ModelOption;
import dev.t3mc.ui.model.ModelProvider;
import dev.t3mc.ui.model.ModelSelection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Labels and lookups for the model picker. Pure functions over catalog data; never touches the network.
 * <p>
 * Option values are only ever <em>displayed</em> differently: the raw IDs and typed values (including an
 * explicit {@code false}) are what the selection keeps and sends to T3 Code.
 */
final class ModelFormat {
    private ModelFormat() {}

    /** How an option is presented: effort and speed get labeled controls, everything else is generic. */
    enum Kind { EFFORT, SPEED, OTHER }

    /** Driver of T3 Code's native Claude provider, whose {@code ultrathink} effort is typed, not clicked. */
    static final String CLAUDE_DRIVER = "claudeAgent";
    static final String ULTRATHINK = "ultrathink";
    private static final Pattern MODEL_SLUG = Pattern.compile("[a-z0-9.]+(?:-[a-z0-9.]+)+");

    /** Option IDs that name an effort control only when their values are effort levels. */
    private static final Set<String> EFFORT_ALIASES = Set.of(
            "variant", "thinkinglevel", "thoughtlevel", "thinking", "reasoning", "reasoninglevel", "level", "thinkingmode");
    private static final Set<String> EFFORT_WORDS = Set.of(
            "none", "minimal", "low", "medium", "med", "high", "xhigh", "extrahigh", "max", "maximum",
            "ultra", "ultrathink", "auto", "default");
    private static final Set<String> EFFORT_CORE = Set.of("minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> SPEED_IDS = Set.of("servicetier", "speed", "speedtier", "fastmode", "fast");

    static @Nullable ModelProvider provider(List<ModelProvider> catalog, String providerId) {
        for (var provider : catalog) if (provider.id().equals(providerId)) return provider;
        return null;
    }

    /**
     * The provider's icon when the icon alone tells it apart: a recognized brand that no other listed provider
     * shares. Null for unrecognized providers and for several instances of one brand (e.g. two Codex accounts),
     * whose human labels must stay visible to disambiguate them.
     */
    static @Nullable ProviderIcon distinctIcon(List<ModelProvider> catalog, String providerId) {
        var icon = ProviderIcon.resolve(provider(catalog, providerId), providerId);
        if (icon == null) return null;
        for (var other : catalog) {
            if (!other.id().equals(providerId) && ProviderIcon.resolve(other, other.id()) == icon) return null;
        }
        return icon;
    }

    static @Nullable ModelChoice model(List<ModelProvider> catalog, String providerId, String modelId) {
        var provider = provider(catalog, providerId);
        if (provider == null) return null;
        for (var model : provider.models()) if (model.id().equals(modelId)) return model;
        return null;
    }

    /** The provider's driver (e.g. {@code claudeAgent}), or empty when the provider is unknown. */
    static String driverKind(List<ModelProvider> catalog, String providerId) {
        var provider = provider(catalog, providerId);
        return provider == null ? "" : Objects.requireNonNullElse(provider.driverKind(), "");
    }

    static boolean isSet(@Nullable ModelSelection selection) {
        return selection != null && !selection.providerId().isEmpty() && !selection.modelId().isEmpty();
    }

    static boolean sameModel(@Nullable ModelSelection selection, String providerId, String modelId) {
        return selection != null && selection.providerId().equals(providerId) && selection.modelId().equals(modelId);
    }

    static String providerLabel(List<ModelProvider> catalog, String providerId) {
        var provider = provider(catalog, providerId);
        return provider == null ? humanize(providerId) : provider.label();
    }

    static String modelLabel(List<ModelProvider> catalog, String providerId, String modelId) {
        var provider = provider(catalog, providerId);
        var model = model(catalog, providerId, modelId);
        return modelLabel(provider == null ? providerId : provider.driverKind(), modelId, model == null ? modelId : model.label());
    }

    static String modelLabel(String driver, String modelId, String label) {
        if (!"codex".equals(driver) || !modelId.equals(label) || !MODEL_SLUG.matcher(modelId).matches()) return label;
        var words = modelId.split("-");
        var name = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0) name.append(i == 1 && words[0].equals("gpt") && Character.isDigit(word.charAt(0)) ? '-' : ' ');
            if (word.equals("gpt")) name.append("GPT");
            else if (i == 0 && word.length() > 1 && word.charAt(0) == 'o' && Character.isDigit(word.charAt(1))) name.append(word);
            else name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    // ---------------------------------------------------------------- option kinds

    private static String norm(@Nullable Object id) {
        return id == null ? "" : id.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }

    /** The values the server lists, or its default alone when it lists none. Never invents choices. */
    static List<Object> declared(ModelOption option) {
        if (!option.values().isEmpty()) return option.values();
        return option.defaultValue() == null ? List.of() : List.of(option.defaultValue());
    }

    static Kind kind(ModelOption option) {
        String id = norm(option.id());
        var values = declared(option);
        boolean strings = values.stream().allMatch(v -> v instanceof String);
        boolean booleans = !values.isEmpty() && values.stream().allMatch(v -> v instanceof Boolean);
        if (id.contains("effort")) return strings ? Kind.EFFORT : Kind.OTHER;
        if (EFFORT_ALIASES.contains(id) && looksLikeEffort(values)) return Kind.EFFORT;
        if (SPEED_IDS.contains(id) && (strings || booleans)) return Kind.SPEED;
        return Kind.OTHER;
    }

    /** Kind from the ID alone, for saved values whose model the catalog does not describe. */
    static Kind kindOfId(String optionId) {
        String id = norm(optionId);
        if (id.contains("effort")) return Kind.EFFORT;
        if (SPEED_IDS.contains(id)) return Kind.SPEED;
        return Kind.OTHER;
    }

    private static boolean looksLikeEffort(List<Object> values) {
        if (values.size() < 2) return false;
        boolean core = false;
        for (var v : values) {
            if (!(v instanceof String s) || !EFFORT_WORDS.contains(norm(s))) return false;
            core |= EFFORT_CORE.contains(norm(s));
        }
        return core;
    }

    static boolean isEffort(ModelOption option) {
        return kind(option) == Kind.EFFORT;
    }

    static boolean isSpeed(ModelOption option) {
        return kind(option) == Kind.SPEED;
    }

    /** Effort options first, then speed, then the rest in catalog order. */
    static List<ModelOption> ordered(List<ModelOption> options) {
        return Stream.of(Kind.EFFORT, Kind.SPEED, Kind.OTHER)
                .flatMap(k -> options.stream().filter(o -> kind(o) == k)).toList();
    }

    static @Nullable ModelOption firstOfKind(@Nullable ModelChoice model, Kind kind) {
        if (model == null) return null;
        for (var option : model.options()) if (kind(option) == kind) return option;
        return null;
    }

    // ---------------------------------------------------------------- choices

    /**
     * Effort values that native Claude takes from the message text instead of a click: {@code ultrathink},
     * when the model declares it. Empty for every other provider.
     */
    static List<Object> promptOnly(ModelOption option, @Nullable String driverKind) {
        if (!CLAUDE_DRIVER.equals(driverKind) || kind(option) != Kind.EFFORT) return List.of();
        return option.values().stream().filter(v -> ULTRATHINK.equals(norm(v))).toList();
    }

    /** Clickable values in server order: exactly what the model declares, minus prompt-only ones. */
    static List<Object> choices(ModelOption option, @Nullable String driverKind) {
        var hidden = promptOnly(option, driverKind);
        if (hidden.isEmpty()) return declared(option);
        return declared(option).stream().filter(v -> !hidden.contains(v)).toList();
    }

    /** Hint shown under a Claude effort row that declares {@code ultrathink}; empty otherwise. */
    static String promptOnlyHint(ModelOption option, @Nullable String driverKind) {
        return promptOnly(option, driverKind).isEmpty() ? ""
                : L10n.tr("model.hint.ultrathink", "Type ultrathink in your message for deeper reasoning.");
    }

    // ---------------------------------------------------------------- labels

    /** "Effort", "Speed", else the humanized ID ("contextWindow" → "Context window"). */
    static String optionName(ModelOption option) {
        return optionName(kind(option), option.id());
    }

    static String optionName(String optionId) {
        return optionName(kindOfId(optionId), optionId);
    }

    private static String optionName(Kind kind, String optionId) {
        return switch (kind) {
            case EFFORT -> L10n.tr("model.option.effort", "Effort");
            case SPEED -> L10n.tr("model.option.speed", "Speed");
            case OTHER -> otherName(optionId);
        };
    }

    /**
     * Well-known generic option IDs get a translated name; the English fallback is always the humanized ID,
     * so names read the same as before when no translation is loaded. Unknown IDs are only humanized.
     */
    private static final Map<String, String> KNOWN_OPTIONS = Map.of(
            "variant", "variant", "contextwindow", "context_window", "context", "context",
            "thinking", "thinking", "reasoning", "reasoning", "verbosity", "verbosity",
            "temperature", "temperature", "mode", "mode", "cyberaccessprogram", "cyber_access_program");

    private static String otherName(String optionId) {
        String human = humanize(optionId);
        String key = KNOWN_OPTIONS.get(norm(optionId));
        return key == null || human.isEmpty() ? human : L10n.tr("model.option." + key, human);
    }

    static String humanize(String id) {
        if (id == null || id.isBlank()) return "";
        String spaced = id.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ').strip();
        if (spaced.isEmpty()) return id;
        String lower = spaced.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Label for {@code value} of {@code option}, disambiguated by its raw ID when two values read alike. */
    static String valueLabel(ModelOption option, @Nullable Object value) {
        Kind kind = kind(option);
        String label = valueLabel(kind, option.id(), value);
        if (value == null) return label;
        for (var other : option.values()) {
            if (!valueEquals(other, value) && valueLabel(kind, option.id(), other).equals(label)) {
                return label + " (" + value + ")";
            }
        }
        return label;
    }

    /** Label for a value whose option the catalog does not describe. */
    static String valueLabel(String optionId, @Nullable Object value) {
        return valueLabel(kindOfId(optionId), optionId, value);
    }

    static String valueLabel(Kind kind, String optionId, @Nullable Object value) {
        if (value == null) return L10n.tr("model.value.default", "Default");
        if (value instanceof Boolean b) {
            if (kind == Kind.SPEED) return b ? speedFast() : speedStandard();
            return b ? L10n.tr("model.value.on", "On") : L10n.tr("model.value.off", "Off");
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : n.toString();
        }
        String text = value.toString();
        if (text.isBlank()) return L10n.tr("model.value.empty", "(empty)");
        String n = norm(text);
        String special = switch (kind) {
            case EFFORT -> switch (n) {
                case "none" -> L10n.tr("model.effort.none", "None");
                case "minimal" -> L10n.tr("model.effort.minimal", "Minimal");
                case "low" -> L10n.tr("model.effort.low", "Low");
                case "medium", "med" -> L10n.tr("model.effort.medium", "Medium");
                case "high" -> L10n.tr("model.effort.high", "High");
                case "xhigh", "extrahigh" -> L10n.tr("model.effort.xhigh", "Extra High");
                case "max", "maximum" -> L10n.tr("model.effort.max", "Max");
                case "ultra" -> L10n.tr("model.effort.ultra", "Ultra");
                case "ultrathink" -> L10n.tr("model.effort.ultrathink", "Ultrathink");
                case "auto" -> L10n.tr("model.effort.auto", "Auto");
                case "default" -> L10n.tr("model.effort.default", "Default");
                default -> null;
            };
            case SPEED -> switch (n) {
                case "priority", "fast" -> speedFast();
                case "default", "standard", "normal" -> speedStandard();
                case "ultrafast" -> L10n.tr("model.speed.ultrafast", "Ultrafast");
                case "flex" -> L10n.tr("model.speed.flex", "Flex");
                default -> null;
            };
            case OTHER -> norm(optionId).contains("context") && text.matches("\\d+(\\.\\d+)?[kKmM]")
                    ? text.toUpperCase(Locale.ROOT) : null;
        };
        if (special != null) return special;
        if ("cyberaccessprogram".equals(norm(optionId)) && n.equals("standard")) {
            return L10n.tr("model.value.access_standard", "Standard");
        }
        String human = humanize(text);
        return human.isEmpty() ? text : titleCase(human);
    }

    private static String speedFast() {
        return L10n.tr("model.speed.fast", "Fast");
    }

    private static String speedStandard() {
        return L10n.tr("model.speed.standard", "Standard");
    }

    private static String titleCase(String text) {
        var out = new StringBuilder(text.length());
        boolean start = true;
        for (char c : text.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- state

    /** Equality that treats 1 and 1.0 the same, since numbers may cross JSON with different types. */
    static boolean valueEquals(@Nullable Object a, @Nullable Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    static boolean containsValue(ModelOption option, @Nullable Object value) {
        for (var v : option.values()) if (valueEquals(v, value)) return true;
        return false;
    }

    /** True when the selection sets {@code option} explicitly (even to its default or to {@code false}). */
    static boolean overridden(ModelOption option, Map<String, Object> chosen) {
        return chosen.containsKey(option.id());
    }

    /** An explicit value the model does not list; it is still sent to T3 Code unchanged. */
    static boolean unsupported(ModelOption option, Map<String, Object> chosen) {
        Object value = chosen.get(option.id());
        return value != null && !option.values().isEmpty() && !containsValue(option, value);
    }

    /** The value in effect: the explicit one if the model lists it, else the advertised default (may be null). */
    static @Nullable Object effective(ModelOption option, Map<String, Object> chosen) {
        Object value = chosen.get(option.id());
        if (value != null && !unsupported(option, chosen)) return value;
        return option.defaultValue();
    }

    /** Row hint next to an option's name in the settings panel, describing what applies and why. */
    static String stateHint(ModelOption option, Map<String, Object> chosen) {
        Object def = option.defaultValue();
        if (unsupported(option, chosen)) return L10n.tr("model.state.custom", "custom value %s", String.valueOf(chosen.get(option.id())));
        if (!overridden(option, chosen)) {
            return def == null ? providerDefault() : L10n.tr("model.state.using_default", "using default %s", valueLabel(option, def));
        }
        if (def == null) return L10n.tr("model.state.set", "set");
        return valueEquals(def, chosen.get(option.id())) ? L10n.tr("model.state.set_same", "set (same as default)")
                : L10n.tr("model.state.default", "default %s", valueLabel(option, def));
    }

    /** "High", "High (default)" or null when neither set nor advertised. */
    private static @Nullable String shown(ModelOption option, Map<String, Object> chosen, boolean markDefault) {
        Object explicit = chosen.get(option.id());
        if (explicit != null) return valueLabel(option, explicit);
        Object def = option.defaultValue();
        if (def == null) return null;
        return markDefault ? L10n.tr("model.value.marked_default", "%s (default)", valueLabel(option, def)) : valueLabel(option, def);
    }

    private static String summaryOf(@Nullable ModelChoice model, Map<String, Object> chosen, Kind kind) {
        var option = firstOfKind(model, kind);
        if (option != null) return Objects.requireNonNullElse(shown(option, chosen, false), "");
        if (model == null || model.options().isEmpty()) {
            for (var entry : chosen.entrySet()) {
                if (kindOfId(entry.getKey()) == kind) return valueLabel(entry.getKey(), entry.getValue());
            }
        }
        return "";
    }

    /** Effort in effect for the composer chip, e.g. "High". Empty when unknown. */
    static String effortSummary(@Nullable ModelChoice model, Map<String, Object> chosen) {
        return summaryOf(model, chosen, Kind.EFFORT);
    }

    /** Speed in effect, e.g. "Fast" or "Standard". Empty when the model has no speed or it is unknown. */
    static String speedSummary(@Nullable ModelChoice model, Map<String, Object> chosen) {
        return summaryOf(model, chosen, Kind.SPEED);
    }

    /** Effort and speed together, e.g. "High · Fast". */
    static String chipSummary(@Nullable ModelChoice model, Map<String, Object> chosen) {
        return Stream.of(effortSummary(model, chosen), speedSummary(model, chosen))
                .filter(s -> !s.isEmpty()).reduce((a, b) -> a + " · " + b).orElse("");
    }

    /** One line, e.g. "Effort: High  ·  Speed: Fast (default)". Options with no known value are left out. */
    static String optionsSummary(@Nullable ModelChoice model, Map<String, Object> chosen) {
        var parts = new ArrayList<String>();
        var seen = new HashSet<String>();
        if (model != null) {
            for (var option : ordered(model.options())) {
                seen.add(option.id());
                String value = shown(option, chosen, true);
                if (value != null) parts.add(optionName(option) + ": " + value);
            }
        }
        for (var entry : chosen.entrySet()) {
            if (seen.add(entry.getKey())) parts.add(optionName(entry.getKey()) + ": " + valueLabel(entry.getKey(), entry.getValue()));
        }
        return String.join("  ·  ", parts);
    }

    /** One line per option for tooltips, including options left to the provider's default. */
    static String optionsDetail(@Nullable ModelChoice model, Map<String, Object> chosen) {
        var lines = new ArrayList<String>();
        var seen = new HashSet<String>();
        if (model != null) {
            for (var option : ordered(model.options())) {
                seen.add(option.id());
                String value = shown(option, chosen, true);
                lines.add(optionName(option) + ": " + (value == null ? providerDefault() : value));
            }
        }
        for (var entry : chosen.entrySet()) {
            if (seen.add(entry.getKey())) lines.add(optionName(entry.getKey()) + ": " + valueLabel(entry.getKey(), entry.getValue()));
        }
        return String.join("\n", lines);
    }

    private static String providerDefault() {
        return L10n.tr("model.state.provider_default", "provider default");
    }

    /**
     * Settings to keep when switching to {@code target}: values with the same ID the target offers as a
     * clickable choice, then an effort or speed value whose raw ID the target's control of the same kind offers.
     * Prompt-only values (Claude's ultrathink) and options with no advertised values never carry over.
     */
    static Map<String, Object> carryOver(@Nullable ModelChoice from, Map<String, Object> chosen, @Nullable ModelChoice target,
                                         @Nullable String targetDriver) {
        var out = new java.util.HashMap<String, Object>();
        if (target == null) return out;
        for (var option : target.options()) {
            Object v = chosen.get(option.id());
            if (selectable(option, targetDriver, v)) out.put(option.id(), v);
        }
        if (from == null) return out;
        for (Kind kind : List.of(Kind.EFFORT, Kind.SPEED)) {
            var source = firstOfKind(from, kind);
            var dest = firstOfKind(target, kind);
            if (source == null || dest == null || out.containsKey(dest.id())) continue;
            Object v = chosen.get(source.id());
            if (selectable(dest, targetDriver, v)) out.put(dest.id(), v);
        }
        return out;
    }

    /** A value the target model advertises and the picker lets the player click (not prompt-only). */
    private static boolean selectable(ModelOption option, @Nullable String driver, @Nullable Object value) {
        return value != null && !option.values().isEmpty()
                && choices(option, driver).stream().anyMatch(c -> valueEquals(c, value));
    }

    // ---------------------------------------------------------------- search

    /** A model ID the player typed: one token, no spaces. */
    static boolean isTypedModelId(String text) {
        String t = text.strip();
        return !t.isEmpty() && t.length() <= 200 && t.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * Every query token must appear in the haystack. A token typed in Cyrillic also matches when it is
     * the same keys on a QWERTY layout ("сдфгву" → "claude") or a common Russian spelling of a model or
     * provider name ("опус" → "opus"), so the search works without switching keyboard layouts.
     */
    static boolean matches(String query, String... haystack) {
        if (query.isBlank()) return true;
        String all = String.join(" ", haystack).toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (!all.contains(token) && !cyrillicMatch(token, all)) return false;
        }
        return true;
    }

    private static final String RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюё";
    private static final String EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";

    /** Russian spellings of names that show up in model and provider labels, matched by prefix while typing. */
    private static final Map<String, String> RU_NAMES = Map.ofEntries(
            Map.entry("клод", "claude"), Map.entry("клауд", "claude"), Map.entry("опус", "opus"),
            Map.entry("сонет", "sonnet"), Map.entry("соннет", "sonnet"), Map.entry("хайку", "haiku"),
            Map.entry("гпт", "gpt"), Map.entry("кодекс", "codex"), Map.entry("джемини", "gemini"),
            Map.entry("гемини", "gemini"), Map.entry("курсор", "cursor"), Map.entry("грок", "grok"),
            Map.entry("мини", "mini"), Map.entry("нано", "nano"), Map.entry("флеш", "flash"),
            Map.entry("флэш", "flash"), Map.entry("про", "pro"), Map.entry("квен", "qwen"),
            Map.entry("дипсик", "deepseek"), Map.entry("мистраль", "mistral"), Map.entry("лама", "llama"),
            Map.entry("ллама", "llama"), Map.entry("опенкод", "opencode"), Map.entry("макс", "max"));

    private static boolean cyrillicMatch(String token, String all) {
        if (token.chars().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC)) return false;
        var layout = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            int i = RU_KEYS.indexOf(c);
            layout.append(i < 0 ? c : EN_KEYS.charAt(i));
        }
        if (all.contains(layout)) return true;
        // Split a leading name from trailing digits/punctuation, e.g. "опус4.5" → "опус" + "4.5".
        int end = 0;
        while (end < token.length() && Character.UnicodeBlock.of(token.charAt(end)) == Character.UnicodeBlock.CYRILLIC) end++;
        String word = token.substring(0, end);
        String rest = token.substring(end);
        if (word.length() < 2) return false;
        for (var e : RU_NAMES.entrySet()) {
            boolean named = rest.isEmpty() ? e.getKey().startsWith(word) : e.getKey().equals(word);
            if (named && all.contains(e.getValue()) && all.contains(rest)) return true;
        }
        return false;
    }
}
