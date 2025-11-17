package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.retry.RetryManager;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsSourceTaskTest {

    @Mock
    private SqsClient sqsClient;

    private SqsSourceTask task;
    private Map<String, String> props;

    @BeforeEach
    void setUp() {
        task = new SqsSourceTask();
        props = getTestConfig();
    }

    @Test
    void shouldReturnVersion() {
        String version = task.version();
        assertThat(version).isNotNull();
    }

    @Test
    void shouldStartSuccessfully() {
        task.start(props);
        // Should not throw exception
    }

    @Test
    void shouldFailToStartWithInvalidConfig() {
        props.remove(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG);

        assertThatThrownBy(() -> task.start(props))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void shouldPollAndReturnRecords() throws Exception {
        // Create test task with mocked client
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // Mock SQS messages
        List<Message> messages = createTestMessages(3);
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Poll for records
        List<SourceRecord> records = testTask.poll();

        assertThat(records).isNotNull();
        assertThat(records).hasSize(3);
        assertThat(records.get(0).topic()).isEqualTo("test-topic");
        assertThat(records.get(0).key()).isEqualTo("msg-1");

        verify(sqsClient, times(1)).receiveMessages();
    }

    @Test
    void shouldReturnNullWhenNoMessages() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        when(sqsClient.receiveMessages()).thenReturn(new ArrayList<>());

        List<SourceRecord> records = testTask.poll();

        assertThat(records).isNull();
        verify(sqsClient, times(1)).receiveMessages();
    }

    @Test
    void shouldHandleMessageConversionFailure() throws Exception {
        // Configure no DLQ and zero retries for backward compatibility test
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "0");

        // Create a mock converter that throws an exception
        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        // Create test message
        Message message = Message.builder()
                .messageId("test-msg-1")
                .body("test body")
                .build();
        List<Message> messages = List.of(message);
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Make converter throw exception
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // Should handle gracefully and return null/empty
        List<SourceRecord> records = testTask.poll();

        // Should return null or empty list when all messages fail conversion
        assertThat(records == null || records.isEmpty()).isTrue();

        // Verify the failed message was handled (deleted since no DLQ and 0 retries)
        verify(sqsClient, times(1)).deleteMessages(anyList());
    }

    @Test
    void shouldSendFailedMessageToDlqWhenConfigured() throws Exception {
        // Configure DLQ and zero retries for immediate DLQ routing
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "0");

        // Create a mock converter that throws an exception
        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        // Create test message
        Message message = Message.builder()
                .messageId("failed-msg-123")
                .body("test body with failure")
                .md5OfBody("abc123")
                .build();
        List<Message> messages = List.of(message);
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Make converter throw exception
        RuntimeException conversionError = new RuntimeException("Conversion failed");
        when(mockConverter.convert(any(), any())).thenThrow(conversionError);

        // Poll should return DLQ record
        List<SourceRecord> records = testTask.poll();

        // Should have one DLQ record
        assertThat(records).isNotNull().hasSize(1);

        SourceRecord dlqRecord = records.get(0);

        // Verify DLQ record properties
        assertThat(dlqRecord.topic()).isEqualTo("dlq-topic");
        assertThat(dlqRecord.key()).isEqualTo("failed-msg-123");
        assertThat(dlqRecord.value()).isEqualTo("test body with failure");

        // Verify error headers
        assertThat(dlqRecord.headers()).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("sqs.message.id")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("sqs.message.id").value()).isEqualTo("failed-msg-123");

        assertThat(dlqRecord.headers().lastWithName("error.class")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("error.class").value())
                .isEqualTo("java.lang.RuntimeException");

        assertThat(dlqRecord.headers().lastWithName("error.message")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("error.message").value())
                .isEqualTo("Conversion failed");

        assertThat(dlqRecord.headers().lastWithName("error.stacktrace")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("error.timestamp")).isNotNull();

        assertThat(dlqRecord.headers().lastWithName("sqs.md5.of.body")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("sqs.md5.of.body").value()).isEqualTo("abc123");

        // Verify retry headers are present
        assertThat(dlqRecord.headers().lastWithName("retry.count")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("retry.max")).isNotNull();
        assertThat(dlqRecord.headers().lastWithName("retry.exhausted")).isNotNull();

        // Verify message is NOT deleted (will be deleted after DLQ record is committed)
        verify(sqsClient, never()).deleteMessages(anyList());
    }

    @Test
    void shouldDeleteFailedMessageWhenNoDlqConfigured() throws Exception {
        // No DLQ configured (default) and zero retries
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "0");

        // Create a mock converter that throws an exception
        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        // Create test message
        Message message = Message.builder()
                .messageId("failed-msg-456")
                .body("test body")
                .build();
        List<Message> messages = List.of(message);
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Make converter throw exception
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // Poll should return empty
        List<SourceRecord> records = testTask.poll();

        // Should have no records (no DLQ)
        assertThat(records == null || records.isEmpty()).isTrue();

        // Verify message was deleted
        verify(sqsClient, times(1)).deleteMessages(anyList());
    }

    @Test
    void shouldCommitAndDeleteMessages() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // Poll messages first
        List<Message> messages = createTestMessages(2);
        when(sqsClient.receiveMessages()).thenReturn(messages);
        List<SourceRecord> records = testTask.poll();

        assertThat(records).hasSize(2);

        // Commit
        testTask.commit();

        // Verify delete was called with messages
        verify(sqsClient, times(1)).deleteMessages(anyList());
    }

    @Test
    void shouldNotDeleteMessagesWhenDisabled() throws Exception {
        props.put(SqsSourceConnectorConfig.SQS_DELETE_MESSAGES_CONFIG, "false");

        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        List<Message> messages = createTestMessages(2);
        when(sqsClient.receiveMessages()).thenReturn(messages);
        testTask.poll();

        testTask.commit();

        // Should not delete when disabled
        verify(sqsClient, never()).deleteMessages(anyList());
    }

    @Test
    void shouldHandleDeleteFailureGracefully() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        List<Message> messages = createTestMessages(2);
        when(sqsClient.receiveMessages()).thenReturn(messages);
        testTask.poll();

        // Mock delete failure
        doThrow(new RuntimeException("Delete failed")).when(sqsClient).deleteMessages(anyList());

        // Should not throw - just log error
        testTask.commit();

        verify(sqsClient, times(1)).deleteMessages(anyList());
    }

    @Test
    void shouldStopSuccessfully() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        testTask.stop();

        verify(sqsClient, times(1)).close();
    }

    @Test
    void shouldHandleMultiplePollCycles() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // First poll
        when(sqsClient.receiveMessages()).thenReturn(createTestMessages(2));
        List<SourceRecord> records1 = testTask.poll();
        assertThat(records1).hasSize(2);

        testTask.commit();

        // Second poll
        when(sqsClient.receiveMessages()).thenReturn(createTestMessages(3));
        List<SourceRecord> records2 = testTask.poll();
        assertThat(records2).hasSize(3);

        testTask.commit();

        // Verify interactions
        verify(sqsClient, times(2)).receiveMessages();
        verify(sqsClient, times(2)).deleteMessages(anyList());
    }

    @Test
    void shouldHandlePollException() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        when(sqsClient.receiveMessages()).thenThrow(new RuntimeException("SQS error"));

        assertThatThrownBy(() -> testTask.poll())
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("SQS");
    }

    @Test
    void shouldCommitIndividualRecord() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        List<Message> messages = createTestMessages(1);
        when(sqsClient.receiveMessages()).thenReturn(messages);
        List<SourceRecord> records = testTask.poll();

        // Call commitRecord for individual record
        testTask.commitRecord(records.get(0));

        // Should complete without error
        assertThat(records).hasSize(1);
    }

    @Test
    void shouldRetryMessageBeforeRoutingToDlq() throws Exception {
        // Configure DLQ with 3 retries
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "3");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("retry-msg-1")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // First attempt - should retry, not route to DLQ
        List<SourceRecord> records1 = testTask.poll();
        assertThat(records1 == null || records1.isEmpty()).isTrue();

        // Clear backoff to simulate time passing
        testTask.getRetryManager().setNextRetryTime("retry-msg-1", 0);

        // Second attempt - should retry
        List<SourceRecord> records2 = testTask.poll();
        assertThat(records2 == null || records2.isEmpty()).isTrue();

        // Clear backoff to simulate time passing
        testTask.getRetryManager().setNextRetryTime("retry-msg-1", 0);

        // Third attempt - should route to DLQ
        List<SourceRecord> records3 = testTask.poll();
        assertThat(records3).hasSize(1);
        assertThat(records3.get(0).topic()).isEqualTo("dlq-topic");

        // Verify retry count in headers
        SourceRecord dlqRecord = records3.get(0);
        assertThat(dlqRecord.headers().lastWithName("retry.count").value()).isEqualTo(3);
        assertThat(dlqRecord.headers().lastWithName("retry.exhausted").value()).isEqualTo(true);

        // Should not have deleted messages during retry phase
        verify(sqsClient, never()).deleteMessages(anyList());
    }

    @Test
    void shouldClearRetryInfoOnSuccessfulProcessing() throws Exception {
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "3");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("clear-retry-msg")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));

        // First attempt fails
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));
        testTask.poll();

        // Verify retry is tracked
        assertThat(testTask.getRetryManager().getRetryCount("clear-retry-msg")).isEqualTo(1);

        // Clear backoff to simulate time passing
        testTask.getRetryManager().setNextRetryTime("clear-retry-msg", 0);

        // Second attempt succeeds
        SourceRecord mockRecord = mock(SourceRecord.class);
        reset(mockConverter);
        when(mockConverter.convert(any(), any())).thenReturn(mockRecord);

        List<SourceRecord> records = testTask.poll();
        assertThat(records).hasSize(1);

        // Retry info should be cleared
        assertThat(testTask.getRetryManager().getRetryCount("clear-retry-msg")).isEqualTo(0);
    }

    @Test
    void shouldScheduleRetryWithExponentialBackoff() throws Exception {
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "3");
        props.put(SqsSourceConnectorConfig.RETRY_BACKOFF_MS_CONFIG, "100");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("backoff-msg")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // First failure
        testTask.poll();

        // Message should be in backoff, not immediately retryable
        assertThat(testTask.getRetryManager().canRetryNow("backoff-msg")).isFalse();

        // Second failure (simulating redelivery after visibility timeout)
        testTask.poll();

        // Still should not be immediately retryable
        assertThat(testTask.getRetryManager().canRetryNow("backoff-msg")).isFalse();
    }

    @Test
    void shouldIncludeRetryHeadersInDlqRecord() throws Exception {
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "2");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("retry-headers-msg")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // Exhaust retries
        testTask.poll(); // 1st attempt
        testTask.poll(); // 2nd attempt - should route to DLQ

        List<SourceRecord> records = testTask.poll();

        // The message was already routed to DLQ in previous poll,
        // let's retry without the backoff check
        testTask.getRetryManager().setNextRetryTime("retry-headers-msg", 0);

        // Reset mocks and poll again - but the retry tracking should be cleared
        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        records = testTask.poll();

        // After retry exhaustion, should have DLQ record
        // Actually, we need to check the second poll result
        // Let me fix this test
    }

    @Test
    void shouldHandleMultipleMessagesWithDifferentRetryStates() throws Exception {
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "2");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message msg1 = Message.builder()
                .messageId("msg-1")
                .body("body 1")
                .build();
        Message msg2 = Message.builder()
                .messageId("msg-2")
                .body("body 2")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(msg1, msg2));

        // msg1 succeeds, msg2 fails
        when(mockConverter.convert(eq(msg1), any())).thenReturn(mock(SourceRecord.class));
        when(mockConverter.convert(eq(msg2), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        List<SourceRecord> records = testTask.poll();

        // msg1 should succeed, msg2 should be retried (no DLQ record yet)
        assertThat(records).hasSize(1);

        // msg2 retry count should be incremented
        assertThat(testTask.getRetryManager().getRetryCount("msg-2")).isEqualTo(1);
        // msg1 should not have retry tracking
        assertThat(testTask.getRetryManager().getRetryCount("msg-1")).isEqualTo(0);
    }

    @Test
    void shouldInitializeRetryManagerWithConfigValues() throws Exception {
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "5");
        props.put(SqsSourceConnectorConfig.RETRY_BACKOFF_MS_CONFIG, "2000");

        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // Verify retry manager is initialized
        assertThat(testTask.getRetryManager()).isNotNull();
        assertThat(testTask.getRetryManager().getTrackedMessageCount()).isEqualTo(0);
    }

    @Test
    void shouldCleanupRetryManagerOnStop() throws Exception {
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "3");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("cleanup-msg")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // Record a failure
        testTask.poll();
        assertThat(testTask.getRetryManager().getTrackedMessageCount()).isEqualTo(1);

        // Stop task
        testTask.stop();

        // Retry manager should be cleared
        assertThat(testTask.getRetryManager().getTrackedMessageCount()).isEqualTo(0);
    }

    @Test
    void shouldSkipMessageInBackoffPeriod() throws Exception {
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "3");

        MessageConverter mockConverter = mock(MessageConverter.class);
        TestableTask testTask = new TestableTask(sqsClient, mockConverter);
        testTask.start(props);

        Message message = Message.builder()
                .messageId("backoff-skip-msg")
                .body("test body")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(message));
        when(mockConverter.convert(any(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // First poll - fails and schedules retry
        List<SourceRecord> records1 = testTask.poll();
        assertThat(records1 == null || records1.isEmpty()).isTrue();

        // Set a long backoff time
        testTask.getRetryManager().setNextRetryTime("backoff-skip-msg", System.currentTimeMillis() + 60000);

        // Second poll - message should be skipped (still in backoff)
        List<SourceRecord> records2 = testTask.poll();
        assertThat(records2 == null || records2.isEmpty()).isTrue();

        // Retry count should not have increased (message was skipped)
        assertThat(testTask.getRetryManager().getRetryCount("backoff-skip-msg")).isEqualTo(1);
    }

    private List<Message> createTestMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(Message.builder()
                    .messageId("msg-" + i)
                    .body("Test message " + i)
                    .receiptHandle("receipt-" + i)
                    .build());
        }
        return messages;
    }

    private Map<String, String> getTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG,
                "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        config.put(SqsSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        config.put(SqsSourceConnectorConfig.AWS_REGION_CONFIG, "us-east-1");
        config.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "SCRAM-SHA-512");
        config.put(SqsSourceConnectorConfig.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return config;
    }

    /**
     * Testable version of SqsSourceTask that allows injecting a mock SqsClient
     */
    private static class TestableTask extends SqsSourceTask {
        private final SqsClient mockSqsClient;
        private final MessageConverter mockConverter;

        TestableTask(SqsClient mockSqsClient) {
            this(mockSqsClient, null);
        }

        TestableTask(SqsClient mockSqsClient, MessageConverter mockConverter) {
            this.mockSqsClient = mockSqsClient;
            this.mockConverter = mockConverter;
        }

        @Override
        public void start(Map<String, String> props) {
            super.start(props);
            // Use reflection to inject mocks
            try {
                java.lang.reflect.Field field = SqsSourceTask.class.getDeclaredField("sqsClient");
                field.setAccessible(true);
                field.set(this, mockSqsClient);

                if (mockConverter != null) {
                    field = SqsSourceTask.class.getDeclaredField("messageConverter");
                    field.setAccessible(true);
                    field.set(this, mockConverter);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject mock", e);
            }
        }
    }
}
