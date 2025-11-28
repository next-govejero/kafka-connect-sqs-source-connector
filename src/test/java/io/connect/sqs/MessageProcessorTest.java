package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.fifo.DeduplicationTracker;
import io.connect.sqs.retry.RetryDecision;
import io.connect.sqs.retry.RetryManager;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageProcessorTest {

    @Mock
    private MessageConverter messageConverter;
    @Mock
    private RetryManager retryManager;
    @Mock
    private DeduplicationTracker deduplicationTracker;
    @Mock
    private DlqSender dlqSender;

    private SqsSourceConnectorConfig config;
    private MessageProcessor messageProcessor;
    private Map<String, String> props;

    // State
    private AtomicLong messagesFailed;
    private AtomicLong messagesRetried;
    private AtomicLong messagesDeduplicated;
    private Map<String, Message> pendingMessages;
    private Map<String, Message> messagesInRetry;

    @BeforeEach
    void setUp() {
        props = new HashMap<>();
        props.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG,
                "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        props.put(SqsSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        props.put(SqsSourceConnectorConfig.AWS_REGION_CONFIG, "us-east-1");
        props.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "SCRAM-SHA-512");
        props.put(SqsSourceConnectorConfig.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");

        config = new SqsSourceConnectorConfig(props);

        messagesFailed = new AtomicLong(0);
        messagesRetried = new AtomicLong(0);
        messagesDeduplicated = new AtomicLong(0);
        pendingMessages = new ConcurrentHashMap<>();
        messagesInRetry = new ConcurrentHashMap<>();

        messageProcessor = new MessageProcessor(
                config, messageConverter, retryManager, deduplicationTracker, dlqSender,
                messagesFailed, messagesRetried, messagesDeduplicated,
                pendingMessages, messagesInRetry);
    }

    @Test
    void shouldProcessValidMessages() {
        Message msg = Message.builder().messageId("msg-1").body("body").build();
        List<Message> messages = List.of(msg);

        when(retryManager.canRetryNow(anyString())).thenReturn(true);
        when(messageConverter.convert(any(), any())).thenReturn(mock(SourceRecord.class));

        List<SourceRecord> records = messageProcessor.processMessages(messages);

        assertThat(records).hasSize(1);
        assertThat(pendingMessages).containsKey("msg-1");
        verify(retryManager).clearRetryInfo("msg-1");
    }

    @Test
    void shouldHandleConversionFailureAndRetry() {
        Message msg = Message.builder().messageId("msg-1").body("body").build();
        List<Message> messages = List.of(msg);

        when(retryManager.canRetryNow(anyString())).thenReturn(true);
        when(messageConverter.convert(any(), any())).thenThrow(new RuntimeException("fail"));

        // Should retry
        RetryDecision decision = RetryDecision.retry("msg-1", 1, 1000);
        when(retryManager.recordFailure(anyString(), any())).thenReturn(decision);

        List<SourceRecord> records = messageProcessor.processMessages(messages);

        assertThat(records).isEmpty(); // No record produced yet
        assertThat(messagesRetried.get()).isEqualTo(1);
        assertThat(messagesInRetry).containsKey("msg-1");
        verify(retryManager).setNextRetryTime("msg-1", 1000);
    }

    @Test
    void shouldHandleConversionFailureAndDlq() {
        Message msg = Message.builder().messageId("msg-1").body("body").build();
        List<Message> messages = List.of(msg);

        when(retryManager.canRetryNow(anyString())).thenReturn(true);
        when(messageConverter.convert(any(), any())).thenThrow(new RuntimeException("fail"));

        // Should NOT retry (exhausted)
        RetryDecision decision = RetryDecision.routeToDlq("msg-1", 3);
        when(retryManager.recordFailure(anyString(), any())).thenReturn(decision);

        SourceRecord dlqRecord = mock(SourceRecord.class);
        when(dlqRecord.topic()).thenReturn("dlq-topic");
        when(dlqSender.createDlqRecord(any(), any(), eq(3))).thenReturn(dlqRecord);

        List<SourceRecord> records = messageProcessor.processMessages(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isEqualTo(dlqRecord);
        assertThat(pendingMessages).containsKey("msg-1"); // Should be pending for deletion
    }

    static Stream<String> randomStringProvider() {
        return Stream.generate(() -> {
            byte[] array = new byte[7]; // length is bounded by 7
            new Random().nextBytes(array);
            return new String(array);
        }).limit(20);
    }

    @ParameterizedTest
    @MethodSource("randomStringProvider")
    void shouldHandleRandomMessageBodies(String randomBody) {
        Message msg = Message.builder().messageId("msg-" + randomBody.hashCode()).body(randomBody).build();
        List<Message> messages = List.of(msg);

        when(retryManager.canRetryNow(anyString())).thenReturn(true);

        // We don't know if conversion will succeed or fail, but it shouldn't crash the
        // processor
        try {
            when(messageConverter.convert(any(), any())).thenReturn(mock(SourceRecord.class));
        } catch (Exception e) {
            // Mock might throw if we configured it to, but here we just want to ensure
            // processMessages doesn't throw
        }

        List<SourceRecord> records = messageProcessor.processMessages(messages);

        // Just verify no exception was thrown and state is consistent
        if (!records.isEmpty()) {
            assertThat(pendingMessages).containsKey(msg.messageId());
        }
    }
}
