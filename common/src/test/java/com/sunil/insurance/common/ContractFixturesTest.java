package com.sunil.insurance.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractFixturesTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Test
    void contractFixturesMatchEventSchemas() throws IOException {
        Path repoRoot = findRepoRoot();
        Path contractsRoot = repoRoot.resolve("contracts");
        Path fixturesRoot = contractsRoot.resolve("fixtures/events");

        Map<String, Integer> validCounts = new HashMap<>();
        Map<String, Integer> invalidCounts = new HashMap<>();

        try (Stream<Path> eventTypes = Files.list(fixturesRoot)) {
            for (Path eventTypeDir : eventTypes.sorted().collect(Collectors.toList())) {
                if (!Files.isDirectory(eventTypeDir)) {
                    continue;
                }
                String eventType = eventTypeDir.getFileName().toString();
                Path versionDir = eventTypeDir.resolve("v1");
                if (!Files.isDirectory(versionDir)) {
                    continue;
                }

                JsonSchema schema = loadSchema(contractsRoot.resolve("events").resolve(eventType).resolve("v1.schema.json"));

                try (Stream<Path> fixtures = Files.list(versionDir)) {
                    for (Path fixture : fixtures.sorted().collect(Collectors.toList())) {
                        String fileName = fixture.getFileName().toString();
                        JsonNode document = OBJECT_MAPPER.readTree(fixture.toFile());
                        Set<ValidationMessage> errors = schema.validate(document);

                        if (fileName.startsWith("valid-")) {
                            validCounts.merge(eventType, 1, Integer::sum);
                            assertTrue(errors.isEmpty(), () -> describeErrors(eventType, fixture, errors));
                        } else if (fileName.startsWith("invalid-")) {
                            invalidCounts.merge(eventType, 1, Integer::sum);
                            assertFalse(errors.isEmpty(), () -> "Expected validation failure for " + fixture);
                        }
                    }
                }
            }
        }

        for (String eventType : List.of(
                "claim.submitted",
                "claim.analysis.completed",
                "claim.decided",
                "settlement.simulated")) {
            assertTrue(validCounts.getOrDefault(eventType, 0) >= 1,
                    () -> "Missing valid fixture for " + eventType);
            assertTrue(invalidCounts.getOrDefault(eventType, 0) >= 1,
                    () -> "Missing invalid fixture for " + eventType);
        }
    }

    @Test
    void e2eScenarioFixturesValidateAndExerciseIdempotencyAndOrdering() throws IOException {
        Path repoRoot = findRepoRoot();
        Path contractsRoot = repoRoot.resolve("contracts");
        Path e2eRoot = repoRoot.resolve("e2e/fixtures");

        Path duplicateDir = e2eRoot.resolve("duplicate-event-id");
        List<JsonNode> duplicateEvents = loadScenarioEvents(duplicateDir);
        assertEquals(2, duplicateEvents.size(), "duplicate-event-id scenario expects two events");
        String sharedEventId = duplicateEvents.get(0).get("eventId").asText();
        assertEquals(sharedEventId, duplicateEvents.get(1).get("eventId").asText());

        Path outOfOrderDir = e2eRoot.resolve("out-of-order-occurred-at");
        List<JsonNode> outOfOrderEvents = loadScenarioEvents(outOfOrderDir);
        assertEquals(2, outOfOrderEvents.size(), "out-of-order-occurred-at scenario expects two events");
        String sharedAggregateId = outOfOrderEvents.get(0).get("aggregateId").asText();
        assertEquals(sharedAggregateId, outOfOrderEvents.get(1).get("aggregateId").asText());

        List<Instant> occurredAt = outOfOrderEvents.stream()
                .map(node -> Instant.parse(node.get("occurredAt").asText()))
                .sorted()
                .toList();
        assertTrue(occurredAt.get(0).isBefore(occurredAt.get(1)),
                "Scenario should include two distinct occurredAt timestamps for the same aggregate");

        for (JsonNode event : concat(duplicateEvents, outOfOrderEvents)) {
            String eventType = event.get("eventType").asText();
            JsonSchema schema = loadSchema(contractsRoot.resolve("events").resolve(eventType).resolve("v1.schema.json"));
            Set<ValidationMessage> errors = schema.validate(event);
            assertTrue(errors.isEmpty(), () -> describeErrors(eventType, null, errors));
        }
    }

    private static JsonSchema loadSchema(Path schemaPath) {
        if (!Files.isRegularFile(schemaPath)) {
            throw new IllegalStateException("Missing schema: " + schemaPath);
        }
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        return SCHEMA_FACTORY.getSchema(SchemaLocation.of(schemaPath.toUri().toString()), config);
    }

    private static List<JsonNode> loadScenarioEvents(Path directory) throws IOException {
        List<JsonNode> events = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            events.add(OBJECT_MAPPER.readTree(path.toFile()));
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        }
        return events;
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

    private static String describeErrors(String eventType, Path fixture, Set<ValidationMessage> errors) {
        String target = fixture == null ? eventType : fixture.toString();
        String details = errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
        return "Validation failed for " + target + ": " + details;
    }

    private static List<JsonNode> concat(List<JsonNode> first, List<JsonNode> second) {
        List<JsonNode> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }
}
