package dev.t3mc.ui;

import dev.t3mc.ui.ModelFormat.Kind;
import dev.t3mc.ui.model.ModelChoice;
import dev.t3mc.ui.model.ModelOption;
import dev.t3mc.ui.model.ModelProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog shapes below mirror what the installed T3 Code MCP advertises. */
class ModelFormatTest {
    @Test void driverIdentifiesTheIconEvenWhenInstancesAreRenamed() {
        var drivers = Map.of("codex", ProviderIcon.CODEX, "claudeAgent", ProviderIcon.CLAUDE,
                "opencode", ProviderIcon.OPENCODE, "antigravity", ProviderIcon.ANTIGRAVITY, "cursor", ProviderIcon.CURSOR);
        drivers.forEach((driver, icon) -> {
            var custom = new ModelProvider("office", "Office", List.of(), driver);
            assertEquals(icon, ProviderIcon.resolve(custom, custom.id()));
            assertEquals(icon, ProviderIcon.resolve(null, driver));
            assertEquals(icon, ModelFormat.distinctIcon(List.of(custom), custom.id()));
        });
        var unknown = new ModelProvider("codex", "Private gateway", List.of(), "other");
        assertNull(ProviderIcon.resolve(unknown, unknown.id()));
        assertNull(ProviderIcon.resolve(null, "future-driver"));
    }

    @Test void sharedProviderBrandsKeepDistinctInstanceLabels() {
        var first = new ModelProvider("first", "Personal", List.of(), "codex");
        var second = new ModelProvider("second", "Team", List.of(), "codex");
        var cursor = new ModelProvider("third", "Cursor", List.of(), "cursor");
        var catalog = List.of(first, second, cursor);
        assertNull(ModelFormat.distinctIcon(catalog, first.id()));
        assertNull(ModelFormat.distinctIcon(catalog, second.id()));
        assertEquals(ProviderIcon.CURSOR, ModelFormat.distinctIcon(catalog, cursor.id()));
    }

    @Test void russianQueriesMatchUnchangedModelNames() {
        assertTrue(ModelFormat.matches("опус 5", "Claude Opus 5.5"));
        assertTrue(ModelFormat.matches("сдфгву", "Claude"));
        assertFalse(ModelFormat.matches("гпт", "Claude"));
    }

    @Test void rawCodexIdentifiersHaveReadableNamesWithoutChangingIdentity() {
        var raw = new ModelChoice("gpt-6.2-astra", "", List.of());
        var friendly = new ModelChoice("gpt-6-sol", "Sol (team preview)", List.of());
        var providers = List.of(new ModelProvider("my-codex", "Personal", List.of(raw, friendly), "codex"),
                new ModelProvider("other", "Other", List.of(raw), "cursor"));
        String name = ModelFormat.modelLabel(providers, "my-codex", raw.id());
        assertEquals("GPT-6.2 Astra", name);
        assertEquals("gpt-6.2-astra", raw.id());
        assertTrue(ModelFormat.matches("GPT-6.2 Astra", name, raw.id()));
        assertTrue(ModelFormat.matches("gpt-6.2-astra", name, raw.id()));
        assertEquals("Sol (team preview)", ModelFormat.modelLabel(providers, "my-codex", friendly.id()));
        assertEquals("gpt-6.2-astra", ModelFormat.modelLabel(providers, "other", raw.id()));
        assertEquals("GPT-6 Sol Preview", ModelFormat.modelLabel(providers, "my-codex", "gpt-6-sol-preview"));
        assertEquals("o4 Mini", ModelFormat.modelLabel(List.of(), "codex", "o4-mini"));
        assertEquals("gpt-6-sol", ModelFormat.modelLabel(List.of(), "unrecognized", "gpt-6-sol"));
    }

    private static final List<Object> CODEX_EFFORTS = List.of("low", "medium", "high", "xhigh", "max", "ultra");
    private static final ModelOption TIER = new ModelOption("serviceTier", List.of("default", "priority"), "priority");
    private static final ModelChoice ASTRA = new ModelChoice("gpt-6.2-astra", "", List.of(
            new ModelOption("reasoningEffort", CODEX_EFFORTS, "low"), TIER,
            new ModelOption("cyberAccessProgram", List.of("standard"), "standard")));
    private static final ModelChoice LUNA = new ModelChoice("gpt-6-luna", "", List.of(
            new ModelOption("reasoningEffort", List.of("low", "medium", "high", "xhigh", "max"), "medium"), TIER,
            new ModelOption("cyberAccessProgram", List.of("standard", "daybreakBlue"), "standard")));
    private static final ModelOption CLAUDE_EFFORT =
            new ModelOption("effort", List.of("low", "medium", "high", "xhigh", "max", "ultrathink"), "medium");
    private static final ModelChoice OPUS = new ModelChoice("claude-opus-5-5", "Claude Opus 5.5", List.of(CLAUDE_EFFORT,
            new ModelOption("ultracode", List.of(false, true), null),
            new ModelOption("fastMode", List.of(false, true), null)));
    private static final ModelChoice SONNET = new ModelChoice("claude-sonnet-5-5", "Claude Sonnet 5.5", List.of(
            new ModelOption("effort", CLAUDE_EFFORT.values(), "high"), new ModelOption("ultracode", List.of(false, true), null)));
    private static final ModelChoice CURSOR_OPUS = new ModelChoice("claude-opus-5-5", "Claude Opus 5.5", List.of(
            new ModelOption("effort", List.of("low", "medium", "high", "xhigh", "max"), "medium"),
            new ModelOption("contextWindow", List.of("300k", "1m"), "1m"),
            new ModelOption("fastMode", List.of(false, true), false)));

    @Test void codexEffortAndSpeedFollowServerOrderAndLabels() {
        var effort = ASTRA.options().getFirst();
        assertEquals(Kind.EFFORT, ModelFormat.kind(effort));
        assertEquals(Kind.SPEED, ModelFormat.kind(TIER));
        assertEquals(Kind.OTHER, ModelFormat.kind(ASTRA.options().getLast()));
        assertEquals(CODEX_EFFORTS, ModelFormat.choices(effort, "codex"));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), ModelFormat.choices(LUNA.options().getFirst(), "codex"));
        assertEquals("Extra High", ModelFormat.valueLabel(effort, "xhigh"));
        assertEquals("Ultra", ModelFormat.valueLabel(effort, "ultra"));
        assertEquals("Speed", ModelFormat.optionName(TIER));
        assertEquals("Standard", ModelFormat.valueLabel(TIER, "default"));
        assertEquals("Fast", ModelFormat.valueLabel(TIER, "priority"));
        assertEquals("Daybreak Blue", ModelFormat.valueLabel(LUNA.options().getLast(), "daybreakBlue"));
        assertEquals("Low · Fast", ModelFormat.chipSummary(ASTRA, Map.of()));
        assertEquals("Max · Standard", ModelFormat.chipSummary(ASTRA, Map.of("reasoningEffort", "max", "serviceTier", "default")));
        assertEquals("using default Fast", ModelFormat.stateHint(TIER, Map.of()));
        assertEquals("default Fast", ModelFormat.stateHint(TIER, Map.of("serviceTier", "default")));
    }

    @Test void otherSpeedTiersReadNaturallyAndNeverCollide() {
        var tier = new ModelOption("service_tier", List.of("flex", "default", "fast", "ultrafast", "priority", "scale"), null);
        assertEquals(Kind.SPEED, ModelFormat.kind(tier));
        assertEquals("Flex", ModelFormat.valueLabel(tier, "flex"));
        assertEquals("Ultrafast", ModelFormat.valueLabel(tier, "ultrafast"));
        assertEquals("Scale", ModelFormat.valueLabel(tier, "scale"));
        assertEquals("Fast (fast)", ModelFormat.valueLabel(tier, "fast"));
        assertEquals("Fast (priority)", ModelFormat.valueLabel(tier, "priority"));
        assertEquals("provider default", ModelFormat.stateHint(tier, Map.of()));
    }

    @Test void claudeUltrathinkIsPromptOnlyAndFlagsStayIndependent() {
        var choices = ModelFormat.choices(CLAUDE_EFFORT, ModelFormat.CLAUDE_DRIVER);
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), choices);
        assertFalse(ModelFormat.promptOnlyHint(CLAUDE_EFFORT, ModelFormat.CLAUDE_DRIVER).isEmpty());
        assertTrue(ModelFormat.promptOnlyHint(CLAUDE_EFFORT, "cursor").isEmpty());
        assertEquals(CLAUDE_EFFORT.values(), ModelFormat.choices(CLAUDE_EFFORT, "opencode"));
        // A saved ultrathink is still shown as what the thread uses.
        assertEquals("Ultrathink", ModelFormat.effortSummary(OPUS, Map.of("effort", "ultrathink")));

        var ultracode = OPUS.options().get(1);
        var fast = OPUS.options().get(2);
        assertEquals(Kind.OTHER, ModelFormat.kind(ultracode));
        assertEquals(Kind.SPEED, ModelFormat.kind(fast));
        assertEquals(List.of(false, true), ModelFormat.choices(fast, ModelFormat.CLAUDE_DRIVER));
        assertEquals("Standard", ModelFormat.valueLabel(fast, false));
        assertEquals("Fast", ModelFormat.valueLabel(fast, true));
        // No default advertised: no synthetic speed, but an explicit false is kept and shown.
        assertEquals("Medium", ModelFormat.chipSummary(OPUS, Map.of()));
        Map<String, Object> chosen = Map.of("fastMode", false, "ultracode", true);
        assertEquals("Medium · Standard", ModelFormat.chipSummary(OPUS, chosen));
        assertEquals("Effort: Medium (default)  ·  Speed: Standard  ·  Ultracode: On", ModelFormat.optionsSummary(OPUS, chosen));
        assertEquals("Effort: Medium (default)\nSpeed: provider default\nUltracode: provider default",
                ModelFormat.optionsDetail(OPUS, Map.of()));
        assertEquals(List.of("effort", "fastMode", "ultracode"), ModelFormat.ordered(OPUS.options()).stream().map(ModelOption::id).toList());
    }

    @Test void cursorOptionsKeepTheirAdvertisedLevels() {
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), ModelFormat.choices(CURSOR_OPUS.options().getFirst(), "cursor"));
        assertEquals("1M", ModelFormat.valueLabel(CURSOR_OPUS.options().get(1), "1m"));
        assertEquals("Medium · Standard", ModelFormat.chipSummary(CURSOR_OPUS, Map.of()));
        assertEquals("set (same as default)", ModelFormat.stateHint(CURSOR_OPUS.options().getLast(), Map.of("fastMode", false)));
    }

    @Test void effortAliasesNeedEffortValues() {
        assertEquals(Kind.EFFORT, ModelFormat.kind(new ModelOption("variant", List.of("low", "high", "max"), null)));
        assertEquals(Kind.EFFORT, ModelFormat.kind(new ModelOption("thought_level", List.of("minimal", "low", "high"), "low")));
        assertEquals(Kind.EFFORT, ModelFormat.kind(new ModelOption("reasoning_effort", List.of("low", "medium", "high", "xhigh"), "high")));
        assertEquals(Kind.OTHER, ModelFormat.kind(new ModelOption("variant", List.of("thinking", "plain"), null)));
        assertEquals(Kind.OTHER, ModelFormat.kind(new ModelOption("thinkingLevel", List.of(false, true), false)));
        assertEquals(Kind.OTHER, ModelFormat.kind(new ModelOption("reasoning", List.of(false, true), null)));
        assertEquals("Variant", ModelFormat.optionName(new ModelOption("variant", List.of("thinking", "plain"), null)));
    }

    @Test void neverInventsChoicesOrDefaults() {
        assertEquals(List.of(), ModelFormat.choices(new ModelOption("flag", List.of(), null), "x"));
        assertEquals(List.of(true), ModelFormat.choices(new ModelOption("flag", List.of(), true), "x"));
        var option = new ModelOption("effort", List.of("low", "high"), null);
        assertNull(ModelFormat.effective(option, Map.of()));
        assertNull(ModelFormat.effective(option, Map.of("effort", "turbo")));
        assertTrue(ModelFormat.unsupported(option, Map.of("effort", "turbo")));
        // Options the catalog does not describe are still summarized from their raw values.
        assertEquals("Effort: Low  ·  Custom thing: Yes", ModelFormat.optionsSummary(
                new ModelChoice("m", "", List.of(option)), new java.util.LinkedHashMap<>(Map.of("effort", "low", "custom_thing", "yes"))));
    }

    @Test void carryOverOnlyKeepsSelectableValues() {
        assertEquals(Map.of(), ModelFormat.carryOver(OPUS, Map.of("effort", "ultrathink"), SONNET, ModelFormat.CLAUDE_DRIVER));
        assertEquals(Map.of("effort", "high", "ultracode", true),
                ModelFormat.carryOver(OPUS, Map.of("effort", "high", "ultracode", true, "fastMode", true), SONNET, ModelFormat.CLAUDE_DRIVER));
        // Same raw effort level across different option IDs.
        assertEquals(Map.of("effort", "xhigh"), ModelFormat.carryOver(ASTRA, Map.of("reasoningEffort", "xhigh"), SONNET, ModelFormat.CLAUDE_DRIVER));
        assertEquals(Map.of(), ModelFormat.carryOver(ASTRA, Map.of("reasoningEffort", "ultra"), LUNA, "codex"));
        var open = new ModelChoice("m", "", List.of(new ModelOption("anything", List.of(), "x")));
        assertEquals(Map.of(), ModelFormat.carryOver(null, Map.of("anything", "y"), open, "x"));
    }
}
