package com.hvmanker.insurance.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static com.hvmanker.insurance.common.MoneyTestSupport.assertMoneyEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimAnalysisRequestedEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Test
    void roundTrips() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID claimId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        ClaimAnalysisRequestedEvent original =
                new ClaimAnalysisRequestedEvent(eventId, claimId, new BigDecimal("4000.00"));
        ClaimAnalysisRequestedEvent restored =
                MAPPER.readValue(MAPPER.writeValueAsString(original), ClaimAnalysisRequestedEvent.class);
        assertEquals(eventId, restored.eventId());
        assertEquals(claimId, restored.claimId());
        assertMoneyEqual(new BigDecimal("4000.00"), restored.claimedAmount());
    }

    @Test
    void serializedEnvelopeValidatesAgainstContractSchema() throws Exception {
        UUID eventId = UUID.fromString("e1000000-0000-4000-8000-000000000001");
        UUID claimId = UUID.fromString("c2000000-0000-4000-8000-000000000001");
        UUID correlationId = UUID.fromString("d3000000-0000-4000-8000-000000000001");
        ClaimAnalysisRequestedEvent event =
                new ClaimAnalysisRequestedEvent(eventId, claimId, new BigDecimal("4000.00"));

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "claim.analysis.requested");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateId", claimId.toString());
        envelope.put("occurredAt", "2026-10-01T12:00:00Z");
        envelope.put("correlationId", correlationId.toString());
        envelope.putNull("causationId");

        JsonNode eventFields = MAPPER.readTree(MAPPER.writeValueAsString(event));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("claimId", claimId.toString());
        payload.set("claimedAmount", eventFields.get("claimedAmount"));
        envelope.set("payload", payload);

        Path schemaPath = findRepoRoot().resolve("contracts/events/claim.analysis.requested/v1.schema.json");
        JsonSchema schema = loadSchema(schemaPath);
        Set<ValidationMessage> errors = schema.validate(envelope);
        assertTrue(errors.isEmpty(), () -> errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("unknown validation error"));
        assertEquals("4000.00", payload.get("claimedAmount").asText());
    }

    private static JsonSchema loadSchema(Path schemaPath) {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        return SCHEMA_FACTORY.getSchema(SchemaLocation.of(schemaPath.toUri().toString()), config);
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts"))
                    && Files.isDirectory(current.resolve("common"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from user.dir="
                + System.getProperty("user.dir"));
    }
}
