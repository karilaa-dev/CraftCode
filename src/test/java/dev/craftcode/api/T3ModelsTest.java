package dev.craftcode.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class T3ModelsTest {
    @Test void keepsRunnableProvidersAndTypedOptions() throws Exception {
        JsonObject catalog = object("""
                {"providers":[
                  {"providerInstanceId":"local","driverKind":"claudeAgent","displayName":"My provider","canRunChildTask":true,
                   "models":[{"id":"swift","options":[{"id":"fastMode","values":[false,true],"default":false}]}]},
                  {"providerInstanceId":"custom","canRunChildTask":true,"models":[]},
                  {"providerInstanceId":"signed-out","canRunChildTask":false,"models":[{"id":"unavailable"}]}
                ]}
                """);
        var providers = T3Models.providers(catalog);
        assertEquals(2, providers.size());
        assertEquals("claudeAgent", providers.getFirst().driverKind());
        var model = providers.getFirst().models().getFirst();
        assertEquals("swift", model.label());
        assertEquals(List.of(false, true), model.options().getFirst().values());
        assertEquals(false, model.options().getFirst().defaultValue());
        assertEquals("custom", providers.getLast().id());
        assertTrue(providers.getLast().models().isEmpty());
    }

    @Test void sendsCanonicalOptionsForExistingAndLegacySelections() throws Exception {
        var legacy = object("""
                {"provider":"claudeAgent","model":"m","options":{"effort":"high","fastMode":false}}
                """);
        var selection = T3Models.selection(legacy);
        assertEquals(Map.of("effort", "high", "fastMode", false), selection.options());
        var encoded = T3Models.encode(selection);
        assertEquals("claudeAgent", encoded.get("instanceId").getAsString());
        assertTrue(encoded.get("options").isJsonArray());
        assertEquals(selection, T3Models.selection(encoded));
        var clean = T3Models.selection(object("{\"instanceId\":\"a\",\"model\":\"m\"}"));
        assertTrue(T3Models.encode(clean).getAsJsonArray("options").isEmpty());
    }

    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
}
