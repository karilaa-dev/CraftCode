package dev.craftcode.api;

import dev.craftcode.L10n;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.craftcode.ui.model.ModelChoice;
import dev.craftcode.ui.model.ModelOption;
import dev.craftcode.ui.model.ModelProvider;
import dev.craftcode.ui.model.ModelSelection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.craftcode.api.T3Api.*;

/** Converts the T3 model catalog and selection wire formats into immutable picker values. */
final class T3Models {
    private T3Models() {}

    static List<ModelProvider> providers(JsonObject reply) throws IOException {
        List<ModelProvider> providers = new ArrayList<>();
        for (JsonElement item : array(reply, "providers")) {
            JsonObject provider = object(item, "provider");
            if (!flag(provider, "canRunChildTask", false)) continue;
            String id = required(provider, "providerInstanceId");
            String label = string(provider, "displayName");
            List<ModelChoice> choices = new ArrayList<>();
            for (JsonElement model : array(provider, "models")) choices.add(choice(object(model, "model")));
            providers.add(new ModelProvider(id, label, choices, string(provider, "driverKind")));
        }
        return List.copyOf(providers);
    }

    private static ModelChoice choice(JsonObject model) throws IOException {
        String id = required(model, "id");
        String label = string(model, "label");
        List<ModelOption> options = new ArrayList<>();
        if (present(model, "options")) {
            for (JsonElement entry : array(model, "options")) {
                JsonObject option = object(entry, "option");
                List<Object> values = new ArrayList<>();
                for (JsonElement value : array(option, "values")) values.add(value(value));
                options.add(new ModelOption(required(option, "id"), List.copyOf(values),
                        present(option, "default") ? value(option.get("default")) : null));
            }
        }
        return new ModelChoice(id, label.isBlank() ? id : label, List.copyOf(options));
    }

    static ModelSelection selection(JsonObject row) throws IOException {
        String instance = required(row, present(row, "instanceId") ? "instanceId" : "provider");
        Map<String,Object> options = new LinkedHashMap<>();
        if (present(row, "options")) {
            if (row.get("options") instanceof JsonObject legacy) {
                for (var entry : legacy.entrySet()) options.put(entry.getKey(), value(entry.getValue()));
            } else {
                for (JsonElement entry : array(row, "options")) {
                    JsonObject pair = object(entry, "option");
                    options.put(required(pair, "id"), value(pair.get("value")));
                }
            }
        }
        return new ModelSelection(instance, required(row, "model"), Map.copyOf(options));
    }

    static JsonObject encode(ModelSelection selection) throws IOException {
        if (selection == null || selection.providerId().isBlank() || selection.modelId().isBlank()) {
            throw new IOException(L10n.tr("api.model.choose", "Choose a provider and model."));
        }
        JsonObject out = new JsonObject();
        out.addProperty("instanceId", selection.providerId());
        out.addProperty("model", selection.modelId());
        JsonArray options = new JsonArray();
        for (var entry : selection.options().entrySet()) {
            JsonObject pair = new JsonObject();
            pair.addProperty("id", entry.getKey());
            if (entry.getValue() instanceof String text) pair.addProperty("value", text);
            else if (entry.getValue() instanceof Boolean flag) pair.addProperty("value", flag);
            else throw malformed("options[].value");
            options.add(pair);
        }
        // The empty array is an explicit reset when the previous model had saved overrides.
        out.add("options", options);
        return out;
    }

    private static Object value(JsonElement value) throws IOException {
        if (value instanceof JsonPrimitive scalar) {
            if (scalar.isString()) return scalar.getAsString();
            if (scalar.isBoolean()) return scalar.getAsBoolean();
        }
        throw malformed("options[].value");
    }
}
