package com.hvmanker.insurance.approval.integration;

import com.hvmanker.insurance.approval.ApprovalServiceApplication;
import com.hvmanker.insurance.approval.analysis.RulesOnlyAnalyser;
import com.hvmanker.insurance.approval.domain.ClaimWorkflowStore;
import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import com.hvmanker.insurance.approval.messaging.AnalysisListener;
import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent;
import com.hvmanker.insurance.common.events.DecidedBy;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.approval.config.ApprovalKafkaListenerConfig;
import com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.events.ManualClaimDecisionEvent;
import com.hvmanker.insurance.common.outbox.OutboxMessageSender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_COMPLETED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_APPROVED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_MANUAL_DECISION;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_REJECTED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_SUBMITTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import org.mockito.stubbing.Answer;

@SpringBootTest(
        classes = ApprovalServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApprovalDecisionIT {
    private static final String TOKEN = "test-internal-token-1A3";
    private static final String AUTH = "Bearer " + TOKEN;
    private static final String POLICY = "POL-1A3-TEST";
    private static final String REASON = "test reason 1A.3";
    private static final BigDecimal CLAIMED = new BigDecimal("4000.00");
    private static final BigDecimal HIGH_CLAIMED = new BigDecimal("15000.00");
    private static final String ANALYSIS_COMPLETED_GROUP = "approval-service-analysis-completed";
    private static final String RULES_ANALYSER_GROUP = "approval-service-rules-analyser";

    private static final UUID AGENT_A = uuid("0000000003a1");
    private static final UUID AGENT_B = uuid("0000000003a2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("insurance")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("INTERNAL_API_TOKEN", () -> TOKEN);
        registry.add("approval.analyser.enabled", () -> "false");
        registry.add("insurance.outbox.poll-interval-ms", () -> "200");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    ApprovalService approvalService;

    @Autowired
    AnalysisListener analysisListener;

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    InboxGuard inboxGuard;

    @Autowired
    TransactionTemplate transactionTemplate;

    @SpyBean
    ClaimWorkflowStore claimWorkflowStore;

    private Logger approvalServiceLogger;
    private Level approvalServicePreviousLevel;
    private ListAppender<ILoggingEvent> approvalServiceAppender;

    private CountDownLatch humanEntered = new CountDownLatch(1);
    private CountDownLatch humanRelease = new CountDownLatch(1);
    private CountDownLatch autoEntered = new CountDownLatch(1);
    private CountDownLatch autoRelease = new CountDownLatch(1);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @BeforeAll
    static void createTopics() throws ExecutionException, InterruptedException {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            admin.createTopics(List.of(
                    new NewTopic(CLAIM_SUBMITTED, 1, (short) 1),
                    new NewTopic(CLAIM_MANUAL_DECISION, 1, (short) 1),
                    new NewTopic(CLAIM_APPROVED, 1, (short) 1),
                    new NewTopic(CLAIM_REJECTED, 1, (short) 1),
                    new NewTopic(CLAIM_ANALYSIS_REQUESTED, 1, (short) 1),
                    new NewTopic(CLAIM_ANALYSIS_COMPLETED, 1, (short) 1))).all().get();
        }
    }

    @BeforeEach
    void truncateTables() {
        jdbcTemplate.execute(
                "TRUNCATE claim_workflow, claim_approvals, decision_audit, outbox, processed_events RESTART IDENTITY");
        approvalServiceLogger = (Logger) LoggerFactory.getLogger(ApprovalService.class);
        approvalServicePreviousLevel = approvalServiceLogger.getLevel();
        approvalServiceAppender = new ListAppender<>();
        approvalServiceAppender.start();
        approvalServiceLogger.addAppender(approvalServiceAppender);
        approvalServiceLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDownSpyAndLogging() {
        if (approvalServiceAppender != null) {
            approvalServiceLogger.detachAppender(approvalServiceAppender);
            approvalServiceLogger.setLevel(approvalServicePreviousLevel);
            approvalServiceAppender = null;
        }
        reset(claimWorkflowStore);
    }

    @Test
    void submittedClaimIsAnalysisPendingAndNotApproved() throws Exception {
        UUID claimId = uuid("000000000321");
        UUID eventId = uuid("0000000003e1");
        ClaimSubmittedEvent event = new ClaimSubmittedEvent(
                claimId,
                POLICY,
                "Test Member",
                "AUTO",
                CLAIMED,
                Instant.parse("2026-07-01T12:00:00Z"));
        publishClaimSubmitted(event, eventId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                        Integer.class,
                        "approval-service",
                        eventId)));

        assertEquals(1, countWorkflowRows(claimId));
        Map<String, Object> workflow = workflowRow(claimId);
        assertEquals("ANALYSIS_PENDING", workflow.get("status"));
        assertEquals(POLICY, workflow.get("policy_number"));
        assertEquals(0, ((BigDecimal) workflow.get("claimed_amount")).compareTo(CLAIMED));
        assertEquals(0, countApprovals(claimId));
        assertEquals(1, countOutboxForAggregate(claimId, CLAIM_ANALYSIS_REQUESTED));
        assertEquals(0, decisionOutboxCount(claimId));
        assertEquals(0, countDecisionAudit(claimId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void humanLosesToSeededAutoApproval(String action) throws Exception {
        UUID claimId = action.equals("APPROVED") ? uuid("000000000311") : uuid("000000000312");
        UUID decisionId = action.equals("APPROVED") ? uuid("0000000003d1") : uuid("0000000003d2");
        UUID outboxEventId = action.equals("APPROVED") ? uuid("0000000003c1") : uuid("0000000003c2");
        seedAutoApprovedClaim(claimId, decisionId, outboxEventId);
        Map<String, Object> snapshot = approvalSnapshot(claimId);

        HttpResponse<String> response = decide(claimId, action, AGENT_A);
        assertConflictResponse(response);
        assertEquals(1, countApprovals(claimId));
        assertApprovalSnapshotEquals(snapshot, claimId);
        assertEquals("APPROVED", workflowStatus(claimId));
        assertEquals(1, decisionOutboxCount(claimId));
        assertEquals(outboxEventId, singleOutboxEventId(claimId));
        assertConflictAudit(claimId, AGENT_A.toString(), "AGENT", action);
    }

    @ParameterizedTest
    @CsvSource({
            "APPROVED,APPROVED,000000000301",
            "APPROVED,REJECTED,000000000302",
            "REJECTED,APPROVED,000000000303",
            "REJECTED,REJECTED,000000000304"
    })
    void secondDecideMatrix(String first, String second, String claimSuffix) throws Exception {
        UUID claimId = uuid(claimSuffix);
        seedDecidable(claimId);
        HttpResponse<String> firstResponse = decide(claimId, first, AGENT_A);
        assertEquals(200, firstResponse.statusCode());
        Map<String, Object> snapshot = approvalSnapshot(claimId);

        HttpResponse<String> secondResponse = decide(claimId, second, AGENT_B);
        assertConflictResponse(secondResponse);
        assertEquals(1, countApprovals(claimId));
        assertApprovalSnapshotEquals(snapshot, claimId);
        assertEquals(first, workflowStatus(claimId));
        assertEquals(1, decisionOutboxCount(claimId));
        String expectedTopic = first.equals("APPROVED") ? CLAIM_APPROVED : CLAIM_REJECTED;
        assertEquals(1, countOutboxForAggregate(claimId, expectedTopic));
        assertConflictAudit(claimId, AGENT_B.toString(), "AGENT", second);
    }

    @Test
    void approveOutboxPayloadCarriesDecidedByAgent() throws Exception {
        UUID claimId = uuid("000000000322");
        seedDecidable(claimId);
        HttpResponse<String> response = decide(claimId, "APPROVED", AGENT_A);
        assertEquals(200, response.statusCode());
        assertEquals(1, decisionOutboxCount(claimId));
        Map<String, Object> outbox = singleDecisionOutboxRow(claimId);
        assertEquals(CLAIM_APPROVED, outbox.get("topic"));
        JsonNode payload = objectMapper.readTree(outbox.get("payload").toString());
        Set<String> decidedByKeys = fieldNames(payload.get("decidedBy"));
        assertEquals(Set.of("userId", "role"), decidedByKeys);
        assertEquals(AGENT_A.toString(), payload.get("decidedBy").get("userId").asText());
        assertEquals("AGENT", payload.get("decidedBy").get("role").asText());
        UUID decisionId = jdbcTemplate.queryForObject(
                "SELECT decision_id FROM claim_approvals WHERE claim_id = ?",
                UUID.class,
                claimId);
        assertEquals(decisionId.toString(), payload.get("decisionId").asText());
        assertEquals(outbox.get("event_id").toString(), payload.get("eventId").asText());
        assertEquals(claimId.toString(), payload.get("claimId").asText());
        assertEquals(0, payload.get("approvedAmount").decimalValue().compareTo(CLAIMED));
        assertEquals(0, payload.get("claimedAmount").decimalValue().compareTo(CLAIMED));
        Map<String, Object> approval = approvalSnapshot(claimId);
        assertEquals("HUMAN", approval.get("source"));
        assertEquals(AGENT_A.toString(), approval.get("decided_by_user_id"));
        assertEquals("AGENT", approval.get("decided_by_role"));
        assertEquals(0, ((BigDecimal) approval.get("approved_amount")).compareTo(CLAIMED));
        assertEquals(0, countDecisionAudit(claimId));
    }

    @Test
    void rejectOutboxPayloadCarriesDecidedByAgent() throws Exception {
        UUID claimId = uuid("000000000323");
        seedDecidable(claimId);
        HttpResponse<String> response = decide(claimId, "REJECTED", AGENT_A);
        assertEquals(200, response.statusCode());
        assertEquals(1, decisionOutboxCount(claimId));
        Map<String, Object> outbox = singleDecisionOutboxRow(claimId);
        assertEquals(CLAIM_REJECTED, outbox.get("topic"));
        JsonNode payload = objectMapper.readTree(outbox.get("payload").toString());
        Set<String> decidedByKeys = fieldNames(payload.get("decidedBy"));
        assertEquals(Set.of("userId", "role"), decidedByKeys);
        assertEquals(AGENT_A.toString(), payload.get("decidedBy").get("userId").asText());
        assertEquals("AGENT", payload.get("decidedBy").get("role").asText());
        UUID decisionId = jdbcTemplate.queryForObject(
                "SELECT decision_id FROM claim_approvals WHERE claim_id = ?",
                UUID.class,
                claimId);
        assertEquals(decisionId.toString(), payload.get("decisionId").asText());
        assertEquals(outbox.get("event_id").toString(), payload.get("eventId").asText());
        assertEquals(claimId.toString(), payload.get("claimId").asText());
        assertEquals(0, countDecisionAudit(claimId));
    }

    @Test
    void decideWithoutServiceTokenIs401() throws Exception {
        UUID claimId = uuid("000000000321");
        seedDecidable(claimId);
        String body = standardBody("APPROVED", AGENT_A);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(decideUri(claimId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertWritesNothing(claimId);
    }

    @Test
    void lookupWithoutServiceTokenIs401() throws Exception {
        UUID decisionId = prepareLookupFixtures().decisionApproved();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lookupUri(decisionId))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertFalse(response.body().contains(uuid("000000000335").toString()));
    }

    @Test
    void lookupWithUserSessionCookieIs401() throws Exception {
        UUID decisionId = prepareLookupFixtures().decisionApproved();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lookupUri(decisionId))
                .header("Cookie", "JSESSIONID=test-user-session-1A3")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void lookupWithUserTokenIs403() throws Exception {
        UUID decisionId = prepareLookupFixtures().decisionApproved();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lookupUri(decisionId))
                .header("Authorization", "Bearer test-user-token-1A3")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void lookupNonGetMethodsAre405(String method) throws Exception {
        LookupFixture fixture = prepareLookupFixtures();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(lookupUri(fixture.decisionApproved()))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
            case "DELETE" -> builder.DELETE().build();
            case "PATCH" -> builder.method("PATCH", HttpRequest.BodyPublishers.ofString("{}")).build();
            default -> throw new IllegalArgumentException(method);
        };
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals(1, countApprovals(uuid("000000000335")));
    }

    @Test
    void lookupApprovedReturnsExactKeySet() throws Exception {
        UUID decisionId = prepareLookupFixtures().decisionApproved();
        HttpResponse<String> response = lookup(decisionId);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(Set.of("decisionId", "claimId", "action", "approvedAmount", "decidedBy"), fieldNames(body));
        assertEquals(Set.of("userId", "role"), fieldNames(body.get("decidedBy")));
        assertEquals(decisionId.toString(), body.get("decisionId").asText());
        assertEquals(uuid("000000000335").toString(), body.get("claimId").asText());
        assertEquals("APPROVED", body.get("action").asText());
        assertEquals(0, body.get("approvedAmount").decimalValue().compareTo(CLAIMED));
        assertEquals(AGENT_A.toString(), body.get("decidedBy").get("userId").asText());
        assertEquals("AGENT", body.get("decidedBy").get("role").asText());
        assertFalse(response.body().contains(POLICY));
        assertFalse(response.body().contains(REASON));
    }

    @Test
    void lookupRejectedReturnsExactKeySet() throws Exception {
        UUID decisionId = prepareLookupFixtures().decisionRejected();
        HttpResponse<String> response = lookup(decisionId);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(Set.of("decisionId", "claimId", "action", "approvedAmount", "decidedBy"), fieldNames(body));
        assertEquals("REJECTED", body.get("action").asText());
        assertTrue(body.get("approvedAmount").isNull());
        assertEquals(Set.of("userId", "role"), fieldNames(body.get("decidedBy")));
        assertFalse(response.body().contains(POLICY));
        assertFalse(response.body().contains(REASON));
    }

    @Test
    void lookupUnknownIdIs404() throws Exception {
        UUID unknown = uuid("0000000003ff");
        HttpResponse<String> response = lookup(unknown);
        assertEquals(404, response.statusCode());
    }

    @Test
    void actuatorHealthOpenWithoutToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void partialApprovalAmountShowsOnLookupAndPayload() throws Exception {
        UUID claimId = uuid("000000000324");
        seedDecidable(claimId);
        String body = """
                {"action":"APPROVED","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"},"approvedAmount":2500.00}
                """.formatted(REASON, AGENT_A);
        HttpResponse<String> response = postDecide(claimId, body);
        assertEquals(200, response.statusCode());
        Map<String, Object> approval = approvalSnapshot(claimId);
        assertEquals(0, ((BigDecimal) approval.get("approved_amount")).compareTo(new BigDecimal("2500.00")));
        assertEquals(1, countOutboxForAggregate(claimId, CLAIM_APPROVED));
        JsonNode payload = objectMapper.readTree(singleDecisionOutboxRow(claimId).get("payload").toString());
        assertEquals(0, payload.get("approvedAmount").decimalValue().compareTo(new BigDecimal("2500.00")));
        assertEquals(0, payload.get("claimedAmount").decimalValue().compareTo(CLAIMED));
        UUID decisionId = (UUID) approval.get("decision_id");
        HttpResponse<String> lookupResponse = lookup(decisionId);
        JsonNode lookupBody = objectMapper.readTree(lookupResponse.body());
        assertEquals(Set.of("decisionId", "claimId", "action", "approvedAmount", "decidedBy"), fieldNames(lookupBody));
        assertEquals(0, lookupBody.get("approvedAmount").decimalValue().compareTo(new BigDecimal("2500.00")));
        assertEquals(0, countDecisionAudit(claimId));
    }

    @Test
    void approveAboveClaimedAmountIs400() throws Exception {
        UUID claimId = uuid("000000000326");
        seedDecidable(claimId);
        String body = """
                {"action":"APPROVED","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"},"approvedAmount":4000.01}
                """.formatted(REASON, AGENT_A);
        HttpResponse<String> response = postDecide(claimId, body);
        assertInvalidDecisionResponse(response);
        assertWritesNothing(claimId);
        assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
    }

    @Test
    void rejectWithApprovedAmountIs400() throws Exception {
        UUID claimId = uuid("000000000327");
        seedDecidable(claimId);
        String body = """
                {"action":"REJECTED","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"},"approvedAmount":100.00}
                """.formatted(REASON, AGENT_A);
        HttpResponse<String> response = postDecide(claimId, body);
        assertInvalidDecisionResponse(response);
        assertWritesNothing(claimId);
        assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
    }

    @Test
    void manualDecisionEventAdapterDecidesOnceThenConflicts() throws Exception {
        UUID claimId = uuid("000000000325");
        seedDecidable(claimId);
        ManualClaimDecisionEvent approvedEvent = new ManualClaimDecisionEvent(
                claimId,
                POLICY,
                CLAIMED,
                true,
                REASON,
                "agent-1a3@test.local",
                Instant.parse("2026-07-01T12:10:00Z"));
        publishManualDecision(approvedEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, countApprovals(claimId)));
        Map<String, Object> firstSnapshot = approvalSnapshot(claimId);
        assertEquals("APPROVED", firstSnapshot.get("decision"));
        assertEquals("HUMAN", firstSnapshot.get("source"));
        assertEquals("agent-1a3@test.local", firstSnapshot.get("decided_by_user_id"));
        assertEquals("AGENT", firstSnapshot.get("decided_by_role"));
        assertEquals(0, ((BigDecimal) firstSnapshot.get("approved_amount")).compareTo(CLAIMED));
        assertEquals(REASON, firstSnapshot.get("reason"));
        assertEquals(1, decisionOutboxCount(claimId));
        JsonNode payload = objectMapper.readTree(singleDecisionOutboxRow(claimId).get("payload").toString());
        assertEquals("agent-1a3@test.local", payload.get("decidedBy").get("userId").asText());
        assertEquals("AGENT", payload.get("decidedBy").get("role").asText());
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals("APPROVED", workflowStatus(claimId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class));

        ManualClaimDecisionEvent rejectEvent = new ManualClaimDecisionEvent(
                claimId,
                POLICY,
                CLAIMED,
                false,
                REASON,
                "agent-1a3@test.local",
                Instant.parse("2026-07-01T12:10:00Z"));
        publishManualDecision(rejectEvent);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1, countDecisionAudit(claimId)));
        assertEquals(1, countApprovals(claimId));
        assertApprovalSnapshotEquals(firstSnapshot, claimId);
        assertEquals(1, decisionOutboxCount(claimId));
        assertConflictAudit(claimId, "agent-1a3@test.local", "AGENT", "REJECTED");
    }

    @Test
    void decideOnClaimWithoutWorkflowRowIs404() throws Exception {
        UUID claimId = uuid("000000000328");
        HttpResponse<String> response = decide(claimId, "APPROVED", AGENT_A);
        assertNotFoundResponse(response);
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, claimId));
        assertEquals(0, countWorkflowRows(claimId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDecisionCases")
    void invalidDecisionBodyIs400(String caseName, UUID claimId, boolean seedWorkflow, String bodyJson)
            throws Exception {
        if (seedWorkflow) {
            seedDecidable(claimId);
        }
        HttpResponse<String> response = postDecide(claimId, bodyJson);
        assertInvalidDecisionResponse(response);
        assertWritesNothing(claimId);
        if (seedWorkflow) {
            assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
        } else {
            assertEquals(0, countWorkflowRows(claimId));
        }
    }

    static Stream<Arguments> invalidDecisionCases() {
        String agent = AGENT_A.toString();
        String baseReason = REASON;
        return Stream.of(
                Arguments.of(
                        "amountZero",
                        uuid("000000000341"),
                        true,
                        bodyWithAmount(agent, "0.00")),
                Arguments.of(
                        "amountNegative",
                        uuid("000000000342"),
                        true,
                        bodyWithAmount(agent, "-1.00")),
                Arguments.of(
                        "amountScaleThree",
                        uuid("000000000343"),
                        true,
                        bodyWithAmount(agent, "100.001")),
                Arguments.of(
                        "roleNotAgent",
                        uuid("000000000344"),
                        true,
                        """
                                {"action":"APPROVED","reason":"%s","decidedBy":{"userId":"%s","role":"ADJUSTER"}}
                                """.formatted(baseReason, agent)),
                Arguments.of(
                        "reasonBlank",
                        uuid("000000000345"),
                        true,
                        """
                                {"action":"APPROVED","reason":"   ","decidedBy":{"userId":"%s","role":"AGENT"}}
                                """.formatted(agent)),
                Arguments.of(
                        "reasonTooLong",
                        uuid("000000000346"),
                        true,
                        """
                                {"action":"APPROVED","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"}}
                                """.formatted("r".repeat(501), agent)),
                Arguments.of(
                        "malformedJson",
                        uuid("000000000348"),
                        true,
                        "{\"action\":\"APPROVED\","),
                Arguments.of(
                        "actionUnknown",
                        uuid("000000000349"),
                        true,
                        """
                                {"action":"MAYBE","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"}}
                                """.formatted(baseReason, agent)),
                Arguments.of(
                        "decidedByMissingNoWorkflow",
                        uuid("000000000350"),
                        false,
                        """
                                {"action":"APPROVED","reason":"%s"}
                                """.formatted(baseReason)));
    }

    @Test
    void analysisCompletedAutoApproves4000WithNoFlags() throws Exception {
        UUID claimId = uuid("0000000003b1");
        seedDecidable(claimId);
        UUID eventId = uuid("0000000003f1");
        invokeAnalysisCompleted(claimId, eventId, List.of());

        assertEquals("APPROVED", workflowStatus(claimId));
        assertEquals(1, countApprovals(claimId));
        Map<String, Object> approval = approvalSnapshot(claimId);
        assertEquals("AUTO", approval.get("source"));
        assertEquals(DecidedBy.AUTO_APPROVAL.userId(), approval.get("decided_by_user_id"));
        assertEquals("SYSTEM", approval.get("decided_by_role"));
        assertEquals("APPROVED", approval.get("decision"));
        assertNotNull(approval.get("decision_id"));
        assertEquals(0, ((BigDecimal) approval.get("approved_amount")).compareTo(CLAIMED));
        assertEquals(1, countOutboxForAggregate(claimId, CLAIM_APPROVED));
        assertEquals(0, countOutboxForAggregate(claimId, CLAIM_REJECTED));
        Map<String, Object> outbox = singleDecisionOutboxRow(claimId);
        JsonNode payload = objectMapper.readTree(outbox.get("payload").toString());
        assertEquals(approval.get("decision_id").toString(), payload.get("decisionId").asText());
        assertEquals(DecidedBy.AUTO_APPROVAL.userId(), payload.get("decidedBy").get("userId").asText());
        assertEquals("SYSTEM", payload.get("decidedBy").get("role").asText());
        assertEquals(0, payload.get("approvedAmount").decimalValue().compareTo(CLAIMED));
        assertEquals(0, payload.get("claimedAmount").decimalValue().compareTo(CLAIMED));
        assertEquals(outbox.get("event_id").toString(), payload.get("eventId").asText());
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
        assertEquals(0, countLateAnalysisIgnoredLines());
    }

    @Test
    void analysisCompletedHighAmountRoutesToReviewReadyWithoutDecisionRow() {
        UUID claimId = uuid("0000000003b2");
        seedWorkflow(claimId, "ANALYSIS_PENDING", HIGH_CLAIMED);
        UUID eventId = uuid("0000000003f2");
        invokeAnalysisCompleted(claimId, eventId, List.of());

        assertEquals("REVIEW_READY", workflowStatus(claimId));
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, decisionOutboxCount(claimId));
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
        assertEquals(0, countLateAnalysisIgnoredLines());
    }

    @Test
    void caseOneHumanRejectBeforeLateAnalysisCompleted() throws Exception {
        UUID claimId = uuid("0000000003b3");
        seedDecidable(claimId);
        HttpResponse<String> rejectResponse = decide(claimId, "REJECTED", AGENT_A);
        assertEquals(200, rejectResponse.statusCode());
        assertEquals("REJECTED", workflowStatus(claimId));

        UUID eventId = uuid("0000000003f3");
        invokeAnalysisCompleted(claimId, eventId, List.of());

        assertEquals("REJECTED", workflowStatus(claimId));
        assertEquals(1, countApprovals(claimId));
        Map<String, Object> approval = approvalSnapshot(claimId);
        assertEquals("HUMAN", approval.get("source"));
        assertEquals(AGENT_A.toString(), approval.get("decided_by_user_id"));
        assertEquals("REJECTED", approval.get("decision"));
        assertEquals(1, countOutboxForAggregate(claimId, CLAIM_REJECTED));
        assertEquals(0, countOutboxForAggregate(claimId, CLAIM_APPROVED));
        assertEquals(1, countDecisionAudit(claimId));
        assertConflictAudit(claimId, DecidedBy.AUTO_APPROVAL.userId(), "SYSTEM", "APPROVED");
        assertEquals(
                1,
                countApprovalServiceMessage(
                        "late analysis ignored claimId=" + claimId + " eventId=" + eventId + " status=REJECTED"));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
    }

    @Test
    void nothingReachesApprovedWithoutAnalysisCompleted() throws Exception {
        UUID claimId = uuid("0000000003b4");
        UUID eventId = uuid("0000000003e4");
        ClaimSubmittedEvent event = new ClaimSubmittedEvent(
                claimId,
                POLICY,
                "Test Member",
                "AUTO",
                CLAIMED,
                Instant.parse("2026-07-01T12:01:00Z"));
        publishClaimSubmitted(event, eventId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT sent_at FROM outbox WHERE aggregate_id = ? AND topic = ?",
                    Object.class,
                    claimId,
                    CLAIM_ANALYSIS_REQUESTED));
        });

        UUID sentinelClaimId = uuid("0000000003b5");
        seedDecidable(sentinelClaimId);
        invokeAnalysisCompleted(sentinelClaimId, uuid("0000000003f5"), List.of());
        assertEquals("APPROVED", workflowStatus(sentinelClaimId));

        assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, countOutboxForAggregate(claimId, CLAIM_APPROVED));
        assertEquals(0, countDecisionAudit(claimId));
    }

    @Test
    void redeliveredAnalysisRequestedEmitsOneCompletedAndNoConflict() throws Exception {
        UUID claimId = uuid("0000000003b6");
        UUID requestedEventId = uuid("0000000003e6");
        seedDecidable(claimId);
        RulesOnlyAnalyser analyser =
                new RulesOnlyAnalyser(outboxWriter, objectMapper, inboxGuard, transactionTemplate, 0L, true);
        ConsumerRecord<String, ClaimAnalysisRequestedEvent> requestedRecord =
                analysisRequestedRecord(claimId, requestedEventId);

        Logger analyserLogger = (Logger) LoggerFactory.getLogger(RulesOnlyAnalyser.class);
        Level analyserPreviousLevel = analyserLogger.getLevel();
        ListAppender<ILoggingEvent> analyserAppender = new ListAppender<>();
        analyserAppender.start();
        analyserLogger.addAppender(analyserAppender);
        analyserLogger.setLevel(Level.INFO);

        try {
            analyser.onAnalysisRequested(requestedRecord);
            assertEquals(1, processedEventsCount(RULES_ANALYSER_GROUP, requestedEventId));
            analyser.onAnalysisRequested(requestedRecord);

            assertEquals(1, countOutboxForAggregate(claimId, CLAIM_ANALYSIS_COMPLETED));
            assertEquals(
                    1,
                    analyserAppender.list.stream()
                            .filter(e -> e.getFormattedMessage()
                                    .equals("duplicate event skipped claimId="
                                            + claimId
                                            + " eventId="
                                            + requestedEventId))
                            .count());

            deliverAllAnalysisCompletedOutboxRows(claimId);

            List<Map<String, Object>> completedRows = jdbcTemplate.queryForList(
                    "SELECT event_id, payload FROM outbox WHERE aggregate_id = ? AND topic = ? ORDER BY id",
                    claimId,
                    CLAIM_ANALYSIS_COMPLETED);
            assertEquals(1, completedRows.size());
            JsonNode completedPayload = objectMapper.readTree(completedRows.get(0).get("payload").toString());
            assertEquals(claimId.toString(), completedPayload.get("claimId").asText());
            assertEquals(completedRows.get(0).get("event_id").toString(), completedPayload.get("eventId").asText());
            assertTrue(completedPayload.get("flags").isArray());
            assertEquals(0, completedPayload.get("flags").size());

            assertEquals("APPROVED", workflowStatus(claimId));
            assertEquals(1, countApprovals(claimId));
            assertEquals("AUTO", approvalSnapshot(claimId).get("source"));
            assertEquals(1, countOutboxForAggregate(claimId, CLAIM_APPROVED));
            assertEquals(0, countDecisionAudit(claimId));
            assertEquals(0, countLateAnalysisIgnoredLines());
        } finally {
            analyserLogger.detachAppender(analyserAppender);
            analyserLogger.setLevel(analyserPreviousLevel);
        }
    }

    @Test
    void lateAnalysisOnHumanDecidedHighAmountLogsWithoutConflict() throws Exception {
        UUID claimId = uuid("0000000003b8");
        seedWorkflow(claimId, "ANALYSIS_PENDING", HIGH_CLAIMED);
        HttpResponse<String> response = decide(claimId, "APPROVED", AGENT_A);
        assertEquals(200, response.statusCode());

        UUID eventId = uuid("0000000003f8");
        invokeAnalysisCompleted(claimId, eventId, List.of());

        assertEquals("APPROVED", workflowStatus(claimId));
        assertEquals(
                1,
                countApprovalServiceMessage(
                        "late analysis ignored claimId=" + claimId + " eventId=" + eventId + " status=APPROVED"));
        assertEquals(0, countDecisionAudit(claimId));
        Map<String, Object> approval = approvalSnapshot(claimId);
        assertEquals("HUMAN", approval.get("source"));
        assertEquals(AGENT_A.toString(), approval.get("decided_by_user_id"));
        assertEquals("AGENT", approval.get("decided_by_role"));
        assertEquals(1, countOutboxForAggregate(claimId, CLAIM_APPROVED));
        assertEquals(0, countOutboxForAggregate(claimId, CLAIM_REJECTED));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
        assertEquals(0, countUnknownClaimIgnoredLines());
    }

    @Test
    void analysisCompletedWithFlagRoutesToReviewReady() {
        UUID claimId = uuid("0000000003b9");
        seedDecidable(claimId);
        UUID eventId = uuid("0000000003f9");
        invokeAnalysisCompleted(claimId, eventId, List.of("TEST_FLAG"));

        assertEquals("REVIEW_READY", workflowStatus(claimId));
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, decisionOutboxCount(claimId));
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
        assertEquals(0, countLateAnalysisIgnoredLines());
    }

    @Test
    void analysisCompletedForUnknownClaimLogsWarnAndRecordsInbox() {
        UUID claimId = uuid("0000000003b7");
        UUID eventId = uuid("0000000003f7");
        ConsumerRecord<String, ClaimAnalysisCompletedEvent> record = analysisCompletedRecord(claimId, eventId, List.of());
        analysisListener.onAnalysisCompleted(record);
        analysisListener.onAnalysisCompleted(record);

        assertEquals(
                1,
                approvalServiceAppender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .filter(e -> e.getFormattedMessage()
                                .equals("event for unknown claim ignored claimId=" + claimId + " eventId=" + eventId))
                        .count());
        assertEquals(
                1,
                countApprovalServiceMessage("duplicate event skipped claimId=" + claimId + " eventId=" + eventId));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, eventId));
        assertEquals(0, countWorkflowRows(claimId));
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, countDecisionAudit(claimId));
        assertEquals(0, countAllOutboxForClaim(claimId));
        assertEquals(0, countLateAnalysisIgnoredLines());
    }

    @RepeatedTest(20)
    void caseTwoRaceHumanWins(RepetitionInfo repetition) throws Exception {
        runCaseTwoRaceIteration(true, repetition.getCurrentRepetition());
    }

    @RepeatedTest(20)
    void caseTwoRaceAutoWins(RepetitionInfo repetition) throws Exception {
        runCaseTwoRaceIteration(false, repetition.getCurrentRepetition());
    }

    @Test
    void manualDecisionErrorHandlerUsesFixedBackOffAndLogsDrop() {
        FixedBackOff backOff = applicationContext.getBean("manualDecisionBackOff", FixedBackOff.class);
        assertEquals(2000L, backOff.getInterval());
        assertEquals(14L, backOff.getMaxAttempts());

        ConsumerRecordRecoverer recoverer = applicationContext.getBean("manualDecisionRecoverer", ConsumerRecordRecoverer.class);
        Logger logger = (Logger) LoggerFactory.getLogger(ApprovalKafkaListenerConfig.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ERROR);

        UUID claimId = uuid("000000000347");
        ManualClaimDecisionEvent event = new ManualClaimDecisionEvent(
                claimId,
                POLICY,
                CLAIMED,
                true,
                REASON,
                "agent-1a3@test.local",
                Instant.parse("2026-07-01T12:10:00Z"));
        ConsumerRecord<String, ManualClaimDecisionEvent> record =
                new ConsumerRecord<>(CLAIM_MANUAL_DECISION, 0, 0L, claimId.toString(), event);
        recoverer.accept(record, new RuntimeException("test"));

        assertEquals(1, appender.list.size());
        assertEquals(
                "manual decision dropped claimId=00000000-0000-0000-0000-000000000347 alert=true",
                appender.list.get(0).getFormattedMessage());
        assertWritesNothing(claimId);
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private record LookupFixture(UUID decisionApproved, UUID decisionRejected) {
    }

    private LookupFixture prepareLookupFixtures() throws Exception {
        UUID approvedClaim = uuid("000000000335");
        UUID rejectedClaim = uuid("000000000336");
        seedDecidable(approvedClaim);
        seedDecidable(rejectedClaim);
        decide(approvedClaim, "APPROVED", AGENT_A);
        decide(rejectedClaim, "REJECTED", AGENT_A);
        UUID dA = jdbcTemplate.queryForObject(
                "SELECT decision_id FROM claim_approvals WHERE claim_id = ?",
                UUID.class,
                approvedClaim);
        UUID dR = jdbcTemplate.queryForObject(
                "SELECT decision_id FROM claim_approvals WHERE claim_id = ?",
                UUID.class,
                rejectedClaim);
        return new LookupFixture(dA, dR);
    }

    private static String bodyWithAmount(String agentId, String amount) {
        return """
                {"action":"APPROVED","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"},"approvedAmount":%s}
                """.formatted(REASON, agentId, amount);
    }

    private void seedDecidable(UUID claimId) {
        seedWorkflow(claimId, "ANALYSIS_PENDING", CLAIMED);
    }

    private void seedWorkflow(UUID claimId, String status, BigDecimal claimedAmount) {
        jdbcTemplate.update(
                """
                INSERT INTO claim_workflow (claim_id, status, policy_number, claimed_amount, updated_at)
                VALUES (?, ?, ?, ?, now())
                """,
                claimId,
                status,
                POLICY,
                claimedAmount);
    }

    private void invokeAnalysisCompleted(UUID claimId, UUID eventId, List<String> flags) {
        analysisListener.onAnalysisCompleted(analysisCompletedRecord(claimId, eventId, flags));
    }

    private ConsumerRecord<String, ClaimAnalysisCompletedEvent> analysisCompletedRecord(
            UUID claimId, UUID eventId, List<String> flags) {
        ClaimAnalysisCompletedEvent event = new ClaimAnalysisCompletedEvent(eventId, claimId, flags);
        ConsumerRecord<String, ClaimAnalysisCompletedEvent> record =
                new ConsumerRecord<>(CLAIM_ANALYSIS_COMPLETED, 0, 0L, claimId.toString(), event);
        record.headers().add(
                new RecordHeader(
                        OutboxMessageSender.EVENT_ID_HEADER,
                        eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private ConsumerRecord<String, ClaimAnalysisRequestedEvent> analysisRequestedRecord(UUID claimId, UUID eventId) {
        ClaimAnalysisRequestedEvent event = new ClaimAnalysisRequestedEvent(eventId, claimId, CLAIMED);
        ConsumerRecord<String, ClaimAnalysisRequestedEvent> record =
                new ConsumerRecord<>(CLAIM_ANALYSIS_REQUESTED, 0, 0L, claimId.toString(), event);
        record.headers().add(
                new RecordHeader(
                        OutboxMessageSender.EVENT_ID_HEADER,
                        eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private void deliverAllAnalysisCompletedOutboxRows(UUID claimId) throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_id, payload FROM outbox WHERE aggregate_id = ? AND topic = ? ORDER BY id",
                claimId,
                CLAIM_ANALYSIS_COMPLETED);
        for (Map<String, Object> row : rows) {
            UUID eventId = (UUID) row.get("event_id");
            JsonNode payload = objectMapper.readTree(row.get("payload").toString());
            List<String> flags = objectMapper.convertValue(
                    payload.get("flags"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            invokeAnalysisCompleted(claimId, eventId, flags);
        }
    }

    private void runCaseTwoRaceIteration(boolean humanWins, int repetition) throws Exception {
        long claimSuffix = humanWins ? 350L + repetition : 370L + repetition;
        UUID claimId = uuid(String.format("%012d", claimSuffix));
        seedDecidable(claimId);
        resetForNextRun();
        installRaceStoreLatches(claimId);
        UUID analysisEventId = humanWins ? raceHumanEventId(repetition) : raceAutoEventId(repetition);
        AtomicReference<HttpResponse<String>> humanResponse = new AtomicReference<>();
        AtomicReference<Throwable> humanError = new AtomicReference<>();
        AtomicReference<Throwable> autoError = new AtomicReference<>();
        Thread human = new Thread(() -> {
            try {
                humanResponse.set(decide(claimId, "APPROVED", AGENT_A));
            } catch (Throwable throwable) {
                humanError.set(throwable);
            }
        });
        Thread auto = new Thread(() -> {
            try {
                invokeAnalysisCompleted(claimId, analysisEventId, List.of());
            } catch (Throwable throwable) {
                autoError.set(throwable);
            }
        });
        human.start();
        auto.start();
        if (!humanEntered.await(10, TimeUnit.SECONDS) || !autoEntered.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("race entered latch timed out");
        }
        if (humanWins) {
            humanRelease.countDown();
            human.join(10_000);
            assertFalse(human.isAlive());
            autoRelease.countDown();
            auto.join(10_000);
            assertFalse(auto.isAlive());
        } else {
            autoRelease.countDown();
            auto.join(10_000);
            assertFalse(auto.isAlive());
            humanRelease.countDown();
            human.join(10_000);
            assertFalse(human.isAlive());
        }
        if (humanError.get() != null) {
            throw new Exception(humanError.get());
        }
        if (autoError.get() != null) {
            throw new Exception(autoError.get());
        }
        assertEquals("APPROVED", workflowStatus(claimId));
        assertEquals(1, countApprovals(claimId));
        assertEquals(1, countDecisionAudit(claimId));
        assertEquals(1, processedEventsCount(ANALYSIS_COMPLETED_GROUP, analysisEventId));
        Map<String, Object> approval = approvalSnapshot(claimId);
        Map<String, Object> outbox = singleDecisionOutboxRow(claimId);
        JsonNode payload = objectMapper.readTree(outbox.get("payload").toString());
        if (humanWins) {
            assertEquals(200, humanResponse.get().statusCode());
            assertEquals("HUMAN", approval.get("source"));
            assertEquals(AGENT_A.toString(), approval.get("decided_by_user_id"));
            assertEquals("AGENT", approval.get("decided_by_role"));
            assertEquals(1, countOutboxForAggregate(claimId, CLAIM_APPROVED));
            assertEquals(0, countOutboxForAggregate(claimId, CLAIM_REJECTED));
            assertEquals(
                    "{\"userId\":\""
                            + AGENT_A
                            + "\",\"role\":\"AGENT\"}",
                    payload.get("decidedBy").toString());
            assertConflictAudit(claimId, DecidedBy.AUTO_APPROVAL.userId(), "SYSTEM", "APPROVED");
            assertEquals(
                    1,
                    countApprovalServiceMessage(
                            "late analysis ignored claimId="
                                    + claimId
                                    + " eventId="
                                    + analysisEventId
                                    + " status=APPROVED"));
        } else {
            assertConflictResponse(humanResponse.get());
            assertEquals("AUTO", approval.get("source"));
            assertEquals(DecidedBy.AUTO_APPROVAL.userId(), approval.get("decided_by_user_id"));
            assertEquals("SYSTEM", approval.get("decided_by_role"));
            assertEquals(
                    "{\"userId\":\""
                            + DecidedBy.AUTO_APPROVAL.userId()
                            + "\",\"role\":\"SYSTEM\"}",
                    payload.get("decidedBy").toString());
            assertConflictAudit(claimId, AGENT_A.toString(), "AGENT", "APPROVED");
            assertEquals(0, countLateAnalysisIgnoredLines());
        }
    }

    private void resetForNextRun() {
        humanEntered = new CountDownLatch(1);
        humanRelease = new CountDownLatch(1);
        autoEntered = new CountDownLatch(1);
        autoRelease = new CountDownLatch(1);
    }

    private void installRaceStoreLatches(UUID claimId) {
        doAnswer(latched(humanEntered, humanRelease))
                .when(claimWorkflowStore)
                .transitionDecidedStatus(eq(claimId), eq("APPROVED"));
        doAnswer(latched(autoEntered, autoRelease))
                .when(claimWorkflowStore)
                .transitionFromAnalysisPending(eq(claimId), eq("APPROVED"));
    }

    private static Answer<Object> latched(CountDownLatch entered, CountDownLatch release) {
        return invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("release latch timed out");
            }
            return invocation.callRealMethod();
        };
    }

    private static UUID raceHumanEventId(int repetition) {
        return UUID.nameUUIDFromBytes(("race-h-" + repetition).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID raceAutoEventId(int repetition) {
        return UUID.nameUUIDFromBytes(("race-a-" + repetition).getBytes(StandardCharsets.UTF_8));
    }

    private int processedEventsCount(String consumerGroup, UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                Integer.class,
                consumerGroup,
                eventId);
    }

    private long countApprovalServiceMessage(String message) {
        return approvalServiceAppender.list.stream()
                .filter(event -> event.getFormattedMessage().equals(message))
                .count();
    }

    private long countLateAnalysisIgnoredLines() {
        return approvalServiceAppender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("late analysis ignored claimId="))
                .count();
    }

    private long countUnknownClaimIgnoredLines() {
        return approvalServiceAppender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("event for unknown claim ignored claimId="))
                .count();
    }

    private int countAllOutboxForClaim(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class,
                claimId);
    }

    private void seedAutoApprovedClaim(UUID claimId, UUID decisionId, UUID outboxEventId) {
        jdbcTemplate.update(
                """
                INSERT INTO claim_workflow (claim_id, status, policy_number, claimed_amount, updated_at)
                VALUES (?, 'APPROVED', ?, ?, now())
                """,
                claimId,
                POLICY,
                CLAIMED);
        jdbcTemplate.update(
                """
                INSERT INTO claim_approvals (
                    claim_id, policy_number, requested_amount, approved_amount, decision, reason,
                    decided_at, decision_id, decided_by_user_id, decided_by_role, source)
                VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?, 'system:auto-approval', 'SYSTEM', 'AUTO')
                """,
                claimId,
                POLICY,
                CLAIMED,
                CLAIMED,
                REASON,
                Instant.parse("2026-07-01T12:05:00Z"),
                decisionId);
        jdbcTemplate.update(
                """
                INSERT INTO outbox (event_id, aggregate_id, topic, payload, created_at)
                VALUES (?, ?, ?, '{}'::jsonb, now())
                """,
                outboxEventId,
                claimId,
                CLAIM_APPROVED);
    }

    private void publishClaimSubmitted(ClaimSubmittedEvent event, UUID eventId) {
        Map<String, Object> props = producerProps(false);
        KafkaTemplate<String, ClaimSubmittedEvent> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        try {
            ProducerRecord<String, ClaimSubmittedEvent> record =
                    new ProducerRecord<>(CLAIM_SUBMITTED, event.claimId().toString(), event);
            record.headers().add(new RecordHeader("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8)));
            template.send(record).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception);
        } finally {
            template.destroy();
        }
    }

    private void publishManualDecision(ManualClaimDecisionEvent event) {
        Map<String, Object> props = producerProps(true);
        KafkaTemplate<String, ManualClaimDecisionEvent> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        try {
            template.send(CLAIM_MANUAL_DECISION, event.claimId().toString(), event).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception);
        } finally {
            template.destroy();
        }
    }

    private Map<String, Object> producerProps(boolean addTypeHeaders) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, addTypeHeaders);
        return props;
    }

    private HttpResponse<String> decide(UUID claimId, String action, UUID agentId) throws Exception {
        return postDecide(claimId, standardBody(action, agentId));
    }

    private HttpResponse<String> postDecide(UUID claimId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(decideUri(claimId))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> lookup(UUID decisionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lookupUri(decisionId))
                .header("Authorization", AUTH)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI decideUri(UUID claimId) {
        return URI.create("http://localhost:" + port + "/internal/claims/" + claimId + "/decisions");
    }

    private URI lookupUri(UUID decisionId) {
        return URI.create("http://localhost:" + port + "/internal/decisions/" + decisionId);
    }

    private static String standardBody(String action, UUID agentId) {
        return """
                {"action":"%s","reason":"%s","decidedBy":{"userId":"%s","role":"AGENT"}}
                """.formatted(action, REASON, agentId);
    }

    private void assertConflictResponse(HttpResponse<String> response) throws Exception {
        assertEquals(409, response.statusCode());
        JsonNode node = objectMapper.readTree(response.body());
        assertEquals(Set.of("code"), fieldNames(node));
        assertEquals("CLAIM_ALREADY_DECIDED", node.get("code").asText());
    }

    private void assertInvalidDecisionResponse(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode());
        JsonNode node = objectMapper.readTree(response.body());
        assertEquals(Set.of("code"), fieldNames(node));
        assertEquals("INVALID_DECISION_REQUEST", node.get("code").asText());
    }

    private void assertNotFoundResponse(HttpResponse<String> response) throws Exception {
        assertEquals(404, response.statusCode());
        JsonNode node = objectMapper.readTree(response.body());
        assertEquals(Set.of("code"), fieldNames(node));
        assertEquals("CLAIM_NOT_FOUND", node.get("code").asText());
    }

    private void assertConflictAudit(UUID claimId, String userId, String role, String attempted) {
        assertEquals(1, countDecisionAudit(claimId));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, actor_role, attempted_decision, outcome, reason_code, at "
                        + "FROM decision_audit WHERE claim_id = ?",
                claimId);
        assertEquals(userId, row.get("actor_user_id"));
        assertEquals(role, row.get("actor_role"));
        assertEquals(attempted, row.get("attempted_decision"));
        assertEquals("CONFLICT", row.get("outcome"));
        assertEquals("CLAIM_ALREADY_DECIDED", row.get("reason_code"));
        assertNotNull(row.get("at"));
    }

    private void assertWritesNothing(UUID claimId) {
        assertEquals(0, countApprovals(claimId));
        assertEquals(0, decisionOutboxCount(claimId));
        assertEquals(0, countDecisionAudit(claimId));
        Integer workflowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim_workflow WHERE claim_id = ?",
                Integer.class,
                claimId);
        if (workflowCount == 0) {
            return;
        }
        assertEquals("ANALYSIS_PENDING", workflowStatus(claimId));
    }

    private void assertApprovalSnapshotEquals(Map<String, Object> snapshot, UUID claimId) {
        assertApprovalSnapshotEquals(snapshot, approvalSnapshot(claimId));
    }

    private void assertApprovalSnapshotEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String key : expected.keySet()) {
            Object expectedValue = expected.get(key);
            Object actualValue = actual.get(key);
            if (expectedValue instanceof BigDecimal expectedDecimal && actualValue instanceof BigDecimal actualDecimal) {
                assertEquals(0, expectedDecimal.compareTo(actualDecimal));
            } else {
                assertEquals(expectedValue, actualValue);
            }
        }
    }

    private Map<String, Object> approvalSnapshot(UUID claimId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT claim_id, policy_number, requested_amount, approved_amount, decision, reason,
                       decided_at, decision_id, decided_by_user_id, decided_by_role, source
                FROM claim_approvals WHERE claim_id = ?
                """,
                claimId);
    }

    private Map<String, Object> workflowRow(UUID claimId) {
        return jdbcTemplate.queryForMap("SELECT * FROM claim_workflow WHERE claim_id = ?", claimId);
    }

    private String workflowStatus(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM claim_workflow WHERE claim_id = ?",
                String.class,
                claimId);
    }

    private int countWorkflowRows(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim_workflow WHERE claim_id = ?",
                Integer.class,
                claimId);
    }

    private int countApprovals(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM claim_approvals WHERE claim_id = ?",
                Integer.class,
                claimId);
    }

    private int countDecisionAudit(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM decision_audit WHERE claim_id = ?",
                Integer.class,
                claimId);
    }

    private int decisionOutboxCount(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic IN ('claim.approved','claim.rejected')",
                Integer.class,
                claimId);
    }

    private int countOutboxForAggregate(UUID claimId, String topic) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = ?",
                Integer.class,
                claimId,
                topic);
    }

    private UUID singleOutboxEventId(UUID claimId) {
        return jdbcTemplate.queryForObject(
                "SELECT event_id FROM outbox WHERE aggregate_id = ? AND topic IN ('claim.approved','claim.rejected')",
                UUID.class,
                claimId);
    }

    private Map<String, Object> singleDecisionOutboxRow(UUID claimId) {
        return jdbcTemplate.queryForMap(
                "SELECT event_id, topic, payload FROM outbox WHERE aggregate_id = ? AND topic IN ('claim.approved','claim.rejected')",
                claimId);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-" + suffix);
    }
}
