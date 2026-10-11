package com.hvmanker.insurance.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractFixturesTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static final List<String> REQUIRED_EVENT_TYPES = List.of(
            "claim.submitted",
            "claim.analysis.completed",
            "claim.decided",
            "settlement.simulated");

    record ContractFixtureCase(Path fixturePath, String eventType, boolean expectValid, String expectedErrorKeyword) {
        @Override
        public String toString() {
            return fixturePath.toString();
        }
    }

    record E2eFixtureCase(Path fixturePath, String scenario) {
        @Override
        public String toString() {
            return fixturePath.toString();
        }
    }

    static Stream<ContractFixtureCase> contractFixtureCases() throws IOException {
        Path repoRoot = findRepoRoot();
        Path fixturesRoot = repoRoot.resolve("contracts/fixtures/events");
        List<ContractFixtureCase> cases = new ArrayList<>();

        try (Stream<Path> eventTypes = Files.list(fixturesRoot)) {
            eventTypes.filter(Files::isDirectory)
                    .sorted()
                    .forEach(eventTypeDir -> {
                        String eventType = eventTypeDir.getFileName().toString();
                        Path versionDir = eventTypeDir.resolve("v1");
                        if (!Files.isDirectory(versionDir)) {
                            return;
                        }
                        try (Stream<Path> fixtures = Files.list(versionDir)) {
                            fixtures.filter(path -> path.getFileName().toString().endsWith(".json"))
                                    .sorted()
                                    .forEach(fixture -> {
                                        String fileName = fixture.getFileName().toString();
                                        if (fileName.startsWith("valid-")) {
                                            cases.add(new ContractFixtureCase(fixture, eventType, true, null));
                                        } else if (fileName.startsWith("invalid-")) {
                                            cases.add(new ContractFixtureCase(
                                                    fixture,
                                                    eventType,
                                                    false,
                                                    expectedInvalidFixtureKeyword(fileName)));
                                        }
                                    });
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        }
        return cases.stream();
    }

    static Stream<E2eFixtureCase> e2eFixtureCases() throws IOException {
        Path e2eRoot = findRepoRoot().resolve("e2e/fixtures");
        List<E2eFixtureCase> cases = new ArrayList<>();
        try (Stream<Path> scenarios = Files.walk(e2eRoot)) {
            scenarios.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        Path scenario = e2eRoot.relativize(path.getParent());
                        cases.add(new E2eFixtureCase(path, scenario.toString()));
                    });
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("contractFixtureCases")
    void contractFixtureMatchesSchema(ContractFixtureCase fixtureCase) throws IOException {
        Path repoRoot = findRepoRoot();
        Path schemaPath = repoRoot.resolve("contracts/events")
                .resolve(fixtureCase.eventType())
                .resolve("v1.schema.json");
        JsonSchema schema = loadSchema(schemaPath);
        JsonNode document = OBJECT_MAPPER.readTree(fixtureCase.fixturePath().toFile());
        Set<ValidationMessage> errors = schema.validate(document);

        if (fixtureCase.expectValid()) {
            assertTrue(errors.isEmpty(), () -> describeErrors(fixtureCase.fixturePath(), errors));
            return;
        }

        assertTrue(!errors.isEmpty(), () -> "Expected validation failure for " + fixtureCase.fixturePath());
        assertTrue(
                errorsContainKeyword(errors, fixtureCase.expectedErrorKeyword()),
                () -> "Expected keyword '" + fixtureCase.expectedErrorKeyword() + "' in errors for "
                        + fixtureCase.fixturePath() + ": " + formatErrors(errors));
    }

    @Test
    void eachEventSchemaHasAtLeastOneValidAndInvalidFixture() throws IOException {
        Map<String, Integer> validCounts = new HashMap<>();
        Map<String, Integer> invalidCounts = new HashMap<>();

        contractFixtureCases().forEach(caseEntry -> {
            if (caseEntry.expectValid()) {
                validCounts.merge(caseEntry.eventType(), 1, Integer::sum);
            } else {
                invalidCounts.merge(caseEntry.eventType(), 1, Integer::sum);
            }
        });

        for (String eventType : REQUIRED_EVENT_TYPES) {
            assertTrue(validCounts.getOrDefault(eventType, 0) >= 1,
                    () -> "Missing valid fixture for " + eventType);
            assertTrue(invalidCounts.getOrDefault(eventType, 0) >= 1,
                    () -> "Missing invalid fixture for " + eventType);
        }
    }

    @ParameterizedTest
    @MethodSource("e2eFixtureCases")
    void e2eFixtureMatchesEventSchema(E2eFixtureCase fixtureCase) throws IOException {
        Path repoRoot = findRepoRoot();
        JsonNode document = OBJECT_MAPPER.readTree(fixtureCase.fixturePath().toFile());
        String eventType = document.get("eventType").asText();
        Path schemaPath = repoRoot.resolve("contracts/events").resolve(eventType).resolve("v1.schema.json");
        JsonSchema schema = loadSchema(schemaPath);
        Set<ValidationMessage> errors = schema.validate(document);
        assertTrue(errors.isEmpty(), () -> describeErrors(fixtureCase.fixturePath(), errors));
    }

    @Test
    void duplicateEventIdScenarioUsesSameEventId() throws IOException {
        List<JsonNode> events = loadScenarioEvents(findRepoRoot().resolve("e2e/fixtures/duplicate-event-id"));
        assertEquals(2, events.size());
        assertEquals(events.get(0).get("eventId").asText(), events.get(1).get("eventId").asText());
    }

    @Test
    void duplicateSubmissionScenarioUsesSameAggregateId() throws IOException {
        List<JsonNode> events = loadScenarioEvents(findRepoRoot().resolve("e2e/fixtures/duplicate-submission"));
        assertEquals(2, events.size());
        assertEquals(events.get(0).get("aggregateId").asText(), events.get(1).get("aggregateId").asText());
        assertEquals("claim.submitted", events.get(0).get("eventType").asText());
        assertEquals("claim.submitted", events.get(1).get("eventType").asText());
    }

    @Test
    void outOfOrderScenarioLateDeliveryDiffersFromTimeline() throws IOException {
        Path scenarioDir = findRepoRoot().resolve("e2e/fixtures/out-of-order");
        List<JsonNode> deliveryOrder = loadScenarioEvents(scenarioDir);
        assertEquals(2, deliveryOrder.size());

        JsonNode deliveredFirst = deliveryOrder.get(0);
        JsonNode deliveredSecond = deliveryOrder.get(1);
        assertEquals("claim.decided", deliveredFirst.get("eventType").asText());
        assertEquals("claim.submitted", deliveredSecond.get("eventType").asText());

        Instant submittedOccurredAt = Instant.parse(deliveredSecond.get("occurredAt").asText());
        Instant decidedOccurredAt = Instant.parse(deliveredFirst.get("occurredAt").asText());
        assertEquals(deliveredFirst.get("aggregateId").asText(), deliveredSecond.get("aggregateId").asText());
        assertTrue(submittedOccurredAt.isBefore(decidedOccurredAt),
                "True timeline: claim.submitted occurredAt must be before claim.decided occurredAt");

        Instant firstDeliveredOccurredAt = Instant.parse(deliveredFirst.get("occurredAt").asText());
        Instant secondDeliveredOccurredAt = Instant.parse(deliveredSecond.get("occurredAt").asText());
        assertTrue(firstDeliveredOccurredAt.isAfter(secondDeliveredOccurredAt),
                "Delivery order must differ from occurredAt order (decided delivered first despite later occurredAt)");
    }

    @Test
    void mismatchedAmountScenarioUsesDifferentDecimalStrings() throws IOException {
        Path scenarioDir = findRepoRoot().resolve("e2e/fixtures/mismatched-amount");
        JsonNode decided = readFixture(scenarioDir.resolve("claim.decided-approved.json"));
        JsonNode settlement = readFixture(scenarioDir.resolve("settlement.simulated.json"));

        assertEquals(decided.get("aggregateId").asText(), settlement.get("aggregateId").asText());
        String approvedAmount = decided.get("payload").get("approvedAmount").asText();
        String paidAmount = settlement.get("payload").get("paidAmount").asText();
        assertNotEquals(approvedAmount, paidAmount);
    }

    private static String expectedInvalidFixtureKeyword(String fileName) {
        if (fileName.contains("missing-")) {
            return "required";
        }
        if (fileName.contains("extra-")) {
            return "additionalProperties";
        }
        if (fileName.contains("bad-decision")) {
            return "enum";
        }
        throw new IllegalArgumentException("Unmapped invalid fixture name (expected missing-, extra-, or bad-decision): "
                + fileName);
    }

    private static JsonSchema loadSchema(Path schemaPath) {
        if (!Files.isRegularFile(schemaPath)) {
            throw new IllegalStateException("Missing schema: " + schemaPath);
        }
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        return SCHEMA_FACTORY.getSchema(SchemaLocation.of(schemaPath.toUri().toString()), config);
    }

    private static JsonNode readFixture(Path path) throws IOException {
        return OBJECT_MAPPER.readTree(path.toFile());
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

    private static boolean errorsContainKeyword(Set<ValidationMessage> errors, String keyword) {
        return errors.stream().anyMatch(error -> errorText(error).contains(normalizeKeyword(keyword)));
    }

    private static String errorText(ValidationMessage error) {
        return Stream.of(error.getType(), error.getCode(), error.getMessage(), error.getMessageKey())
                .filter(part -> part != null && !part.isBlank())
                .map(ContractFixturesTest::normalizeKeyword)
                .collect(Collectors.joining(" "));
    }

    private static String normalizeKeyword(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String formatErrors(Set<ValidationMessage> errors) {
        return errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .collect(Collectors.joining("; "));
    }

    private static String describeErrors(Path fixture, Set<ValidationMessage> errors) {
        return "Validation failed for " + fixture + ": " + formatErrors(errors);
    }
}
