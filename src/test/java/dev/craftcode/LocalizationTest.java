package dev.craftcode;

import net.minecraft.locale.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    @Test void bothLanguagesProvideTheSameMessagesAndFormattingArguments() throws IOException {
        var english = catalog("en_us");
        var russian = catalog("ru_ru");
        assertTrue(russian.containsKey("key.t3mc.open"));
        assertEquals(english.keySet(), russian.keySet());
        english.forEach((key, value) -> {
            assertFalse(russian.get(key).isBlank(), key);
            assertEquals(arguments(value), arguments(russian.get(key)), key);
        });
    }

    private static Map<String, String> catalog(String locale) throws IOException {
        var result = new HashMap<String, String>();
        try (var file = LocalizationTest.class.getResourceAsStream("/assets/craftcode/lang/" + locale + ".json")) {
            assertNotNull(file, locale);
            Language.loadFromJson(file, result::put);
        }
        return result;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%%|%(?:(\\d+)\\$)?s");

    private static TreeSet<Integer> arguments(String value) {
        var ids = new TreeSet<Integer>();
        var matcher = PLACEHOLDER.matcher(value);
        int implicit = 1;
        while (matcher.find()) {
            if (!matcher.group().equals("%%")) {
                ids.add(matcher.group(1) == null ? implicit++ : Integer.parseInt(matcher.group(1)));
            }
        }
        return ids;
    }
}
