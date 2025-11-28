package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagePollerTest {

    @Mock
    private SqsClient sqsClient;

    private SqsSourceConnectorConfig config;
    private MessagePoller messagePoller;
    private AtomicLong messagesReceived;
    private AtomicLong messagesDeleted;
    private Map<String, String> props;

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
        messagesReceived = new AtomicLong(0);
        messagesDeleted = new AtomicLong(0);
        messagePoller = new MessagePoller(sqsClient, config, messagesReceived, messagesDeleted);
    }

    @Test
    void shouldReturnMessagesWhenReceived() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().messageId("msg-1").body("body-1").build());
        messages.add(Message.builder().messageId("msg-2").body("body-2").build());

        when(sqsClient.receiveMessages()).thenReturn(messages);

        List<Message> result = messagePoller.poll();

        assertThat(result).hasSize(2);
        assertThat(messagesReceived.get()).isEqualTo(2);
        verify(sqsClient, times(1)).receiveMessages();
    }

    @Test
    void shouldReturnNullWhenNoMessagesReceived() {
        when(sqsClient.receiveMessages()).thenReturn(Collections.emptyList());

        List<Message> result = messagePoller.poll();

        assertThat(result).isNull();
        assertThat(messagesReceived.get()).isEqualTo(0);
        verify(sqsClient, times(1)).receiveMessages();
    }

    @Test
    void shouldFilterMessagesWhenFilterPolicyConfigured() {
        // Re-initialize with filter policy
        props.put(SqsSourceConnectorConfig.SQS_MESSAGE_FILTER_POLICY_CONFIG, "{\"detail-type\":[\"TestEvent\"]}");
        config = new SqsSourceConnectorConfig(props);
        messagePoller = new MessagePoller(sqsClient, config, messagesReceived, messagesDeleted);

        Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> matchingAttrs = new HashMap<>();
        matchingAttrs.put("detail-type", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                .stringValue("TestEvent")
                .dataType("String")
                .build());

        Message matchingMsg = Message.builder()
                .messageId("msg-1")
                .body("body-1")
                .messageAttributes(matchingAttrs)
                .build();

        Map<String, software.amazon.awssdk.services.sqs.model.MessageAttributeValue> nonMatchingAttrs = new HashMap<>();
        nonMatchingAttrs.put("detail-type", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                .stringValue("OtherEvent")
                .dataType("String")
                .build());

        Message nonMatchingMsg = Message.builder()
                .messageId("msg-2")
                .body("body-2")
                .messageAttributes(nonMatchingAttrs)
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(matchingMsg);
        messages.add(nonMatchingMsg);

        when(sqsClient.receiveMessages()).thenReturn(messages);

        List<Message> result = messagePoller.poll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).messageId()).isEqualTo("msg-1");
        assertThat(messagesReceived.get()).isEqualTo(2);

        // Should delete the filtered out message
        verify(sqsClient, times(1)).deleteMessages(anyList());
        assertThat(messagesDeleted.get()).isEqualTo(1);
    }

    @Test
    void shouldNotDeleteFilteredMessagesWhenDeletionDisabled() {
        // Re-initialize with filter policy and deletion disabled
        props.put(SqsSourceConnectorConfig.SQS_MESSAGE_FILTER_POLICY_CONFIG, "{\"detail-type\":[\"TestEvent\"]}");
        props.put(SqsSourceConnectorConfig.SQS_DELETE_MESSAGES_CONFIG, "false");
        config = new SqsSourceConnectorConfig(props);
        messagePoller = new MessagePoller(sqsClient, config, messagesReceived, messagesDeleted);

        Message nonMatchingMsg = Message.builder()
                .messageId("msg-2")
                .body("{\"detail-type\":\"OtherEvent\"}")
                .build();

        when(sqsClient.receiveMessages()).thenReturn(List.of(nonMatchingMsg));

        List<Message> result = messagePoller.poll();

        assertThat(result).isNull(); // All filtered out

        // Should NOT delete the filtered out message
        verify(sqsClient, never()).deleteMessages(anyList());
        assertThat(messagesDeleted.get()).isEqualTo(0);
    }
}
