package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecidedByTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesUserIdAndRole() throws Exception {
        DecidedBy decidedBy = new DecidedBy("u-test-001", "AGENT");
        JsonNode json = MAPPER.valueToTree(decidedBy);
        Set<String> keys = fieldNames(json);
        assertEquals(Set.of("userId", "role"), keys);
        assertEquals("u-test-001", json.get("userId").asText());
        assertEquals("AGENT", json.get("role").asText());
    }

    @Test
    void roundTrips() throws Exception {
        DecidedBy original = new DecidedBy("u-test-001", "AGENT");
        DecidedBy restored = MAPPER.readValue(MAPPER.writeValueAsString(original), DecidedBy.class);
        assertEquals(original, restored);
    }

    @Test
    void autoApprovalConstant() {
        assertEquals("system:auto-approval", DecidedBy.AUTO_APPROVAL.userId());
        assertEquals("SYSTEM", DecidedBy.AUTO_APPROVAL.role());
    }

    private static Set<String> fieldNames(JsonNode json) {
        Set<String> keys = new LinkedHashSet<>();
        Iterator<String> iterator = json.fieldNames();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        return keys;
    }
}
