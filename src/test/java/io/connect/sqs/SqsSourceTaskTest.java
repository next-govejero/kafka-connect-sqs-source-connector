package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsSourceTaskTest {

    @Mock
    private SqsClient sqsClient;
    @Mock
    private MessagePoller messagePoller;
    @Mock
    private MessageProcessor messageProcessor;

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
        // Create test task with mocked components
        TestableTask testTask = new TestableTask(sqsClient, messagePoller, messageProcessor);
        testTask.start(props);

        // Mock polling
        List<Message> messages = createTestMessages(2);
        when(messagePoller.poll()).thenReturn(messages);

        // Mock processing
        List<SourceRecord> expectedRecords = new ArrayList<>();
        expectedRecords.add(mock(SourceRecord.class));
        expectedRecords.add(mock(SourceRecord.class));
        when(messageProcessor.processMessages(messages)).thenReturn(expectedRecords);

        // Poll
        List<SourceRecord> records = testTask.poll();

        assertThat(records).hasSize(2);
        verify(messagePoller).poll();
        verify(messageProcessor).processMessages(messages);
    }

    @Test
    void shouldReturnNullWhenNoMessagesPolled() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient, messagePoller, messageProcessor);
        testTask.start(props);

        when(messagePoller.poll()).thenReturn(null);

        List<SourceRecord> records = testTask.poll();

        assertThat(records).isNull();
        verify(messagePoller).poll();
        verify(messageProcessor, never()).processMessages(any());
    }

    @Test
    void shouldReturnNullWhenProcessingReturnsEmpty() throws Exception {
        TestableTask testTask = new TestableTask(sqsClient, messagePoller, messageProcessor);
        testTask.start(props);

        List<Message> messages = createTestMessages(1);
        when(messagePoller.poll()).thenReturn(messages);
        when(messageProcessor.processMessages(messages)).thenReturn(Collections.emptyList());

        List<SourceRecord> records = testTask.poll();

        assertThat(records).isNull();
    }

    @Test
    void shouldCommitAndDeleteMessages() throws Exception {
        // For this test we need to use the real task logic for pendingMessages,
        // but we can't easily access the private map.
        // However, the real SqsSourceTask uses the real MessageProcessor if we don't
        // mock it.
        // But we want to test SqsSourceTask's commit logic.

        // Let's use a real task but inject a mock SqsClient.
        // The MessageProcessor will populate the pendingMessages map in the task.

        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        // We need to simulate processing adding to pendingMessages.
        // Since we can't easily access the internal map from test, we rely on the fact
        // that
        // the real MessageProcessor (which is created inside start()) shares the map
        // reference.

        // Actually, TestableTask creates its own helpers if we don't provide them.
        // But we need to intercept receiveMessages.

        List<Message> messages = createTestMessages(1);
        when(sqsClient.receiveMessages()).thenReturn(messages);

        // Poll to populate pending messages
        testTask.poll();

        // Commit
        testTask.commit();

        // Verify delete was called
        verify(sqsClient).deleteMessages(anyList());
    }

    @Test
    void shouldStopSuccessfully() {
        TestableTask testTask = new TestableTask(sqsClient);
        testTask.start(props);

        testTask.stop();

        verify(sqsClient).close();
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
     * Testable version of SqsSourceTask that allows injecting mocks
     */
    private static class TestableTask extends SqsSourceTask {
        private final SqsClient mockSqsClient;
        private final MessagePoller mockMessagePoller;
        private final MessageProcessor mockMessageProcessor;

        TestableTask(SqsClient mockSqsClient) {
            this(mockSqsClient, null, null);
        }

        TestableTask(SqsClient mockSqsClient, MessagePoller mockMessagePoller, MessageProcessor mockMessageProcessor) {
            this.mockSqsClient = mockSqsClient;
            this.mockMessagePoller = mockMessagePoller;
            this.mockMessageProcessor = mockMessageProcessor;
        }

        @Override
        public void start(Map<String, String> props) {
            super.start(props);
            // Inject mocks via reflection
            try {
                java.lang.reflect.Field clientField = SqsSourceTask.class.getDeclaredField("sqsClient");
                clientField.setAccessible(true);
                clientField.set(this, mockSqsClient);

                if (mockMessagePoller != null) {
                    java.lang.reflect.Field pollerField = SqsSourceTask.class.getDeclaredField("messagePoller");
                    pollerField.setAccessible(true);
                    pollerField.set(this, mockMessagePoller);
                } else {
                    // If no mock poller provided, we must update the real poller to use the mock
                    // client
                    // OR create a new poller with the mock client
                    // Since MessagePoller doesn't have a setter for client, we create a new one
                    // We need access to config and metrics which are in the task

                    java.lang.reflect.Field configField = SqsSourceTask.class.getDeclaredField("config");
                    configField.setAccessible(true);
                    SqsSourceConnectorConfig config = (SqsSourceConnectorConfig) configField.get(this);

                    java.lang.reflect.Field receivedField = SqsSourceTask.class.getDeclaredField("messagesReceived");
                    receivedField.setAccessible(true);
                    java.util.concurrent.atomic.AtomicLong messagesReceived = (java.util.concurrent.atomic.AtomicLong) receivedField
                            .get(this);

                    java.lang.reflect.Field deletedField = SqsSourceTask.class.getDeclaredField("messagesDeleted");
                    deletedField.setAccessible(true);
                    java.util.concurrent.atomic.AtomicLong messagesDeleted = (java.util.concurrent.atomic.AtomicLong) deletedField
                            .get(this);

                    MessagePoller newPoller = new MessagePoller(mockSqsClient, config, messagesReceived,
                            messagesDeleted);

                    java.lang.reflect.Field pollerField = SqsSourceTask.class.getDeclaredField("messagePoller");
                    pollerField.setAccessible(true);
                    pollerField.set(this, newPoller);
                }

                if (mockMessageProcessor != null) {
                    java.lang.reflect.Field processorField = SqsSourceTask.class.getDeclaredField("messageProcessor");
                    processorField.setAccessible(true);
                    processorField.set(this, mockMessageProcessor);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject mocks", e);
            }
        }
    }
}
