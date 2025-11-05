package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
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
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // Create message with invalid format to trigger conversion error
        List<Message> messages = List.of(
                Message.builder()
                        .messageId("invalid-msg")
                        .body(null) // This might cause conversion issues
                        .build()
        );
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Should handle gracefully
        List<SourceRecord> records = testTask.poll();

        // Depending on error handling, might return empty or null
        assertThat(records == null || records.isEmpty()).isTrue();
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

        TestableTask(SqsClient mockSqsClient) {
            this.mockSqsClient = mockSqsClient;
        }

        @Override
        public void start(Map<String, String> props) {
            super.start(props);
            // Use reflection or a protected setter to inject the mock
            // For now, we'll rely on the actual implementation
            // In production code, you might want to add a package-private setter
            try {
                java.lang.reflect.Field field = SqsSourceTask.class.getDeclaredField("sqsClient");
                field.setAccessible(true);
                field.set(this, mockSqsClient);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject mock", e);
            }
        }
    }
}
