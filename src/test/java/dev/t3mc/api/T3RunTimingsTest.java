package dev.t3mc.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t3mc.ui.model.ChatMessage.RunTiming;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T3RunTimingsTest {
    @Test void readsAvailableSummariesWithRequestedTimeAsTheFallback() {
        var result = JsonParser.parseString("""
                {"recentRuns":[
                  {"runId":"completed","requestedAt":"1970-01-01T00:00:02Z","startedAt":"1970-01-01T00:00:05Z","completedAt":"1970-01-01T00:00:08Z"},
                  {"runId":"queued","requestedAt":"1970-01-01T00:00:10Z","startedAt":null,"completedAt":null},
                  {"runId":"invalid","startedAt":"1970-01-01T00:00:09Z","completedAt":"1970-01-01T00:00:01Z"},
                  {"runId":"unknown","startedAt":"unknown"}]}
                """).getAsJsonObject();
        assertEquals(Map.of("completed", new RunTiming(5_000, 8_000), "queued", new RunTiming(10_000, 0)), T3Api.runTimings(result));
        assertTrue(T3Api.runTimings(new JsonObject()).isEmpty());
    }
}
