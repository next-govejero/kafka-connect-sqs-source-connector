package io.connect.sqs.filter;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFilterProcessorTest {

    @Test
    void shouldPassAllMessagesWhenNoFilterConfigured() {
        MessageFilterProcessor processor = new MessageFilterProcessor(null);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Collections.emptyMap()),
                createMessage("msg2", Collections.emptyMap())
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(processor.hasFilterPolicy()).isFalse();
    }

    @Test
    void shouldFilterByExactMatch() {
        String policy = "{\"Type\":[\"order\",\"payment\"]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order")),
                createMessage("msg2", Map.of("Type", "payment")),
                createMessage("msg3", Map.of("Type", "notification")),
                createMessage("msg4", Collections.emptyMap())
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg2");
        assertThat(processor.getMessagesFiltered()).isEqualTo(2);
        assertThat(processor.getMessagesPassed()).isEqualTo(2);
    }

    @Test
    void shouldFilterByPrefixMatch() {
        String policy = "{\"Environment\":[{\"prefix\":\"prod-\"}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Environment", "prod-east")),
                createMessage("msg2", Map.of("Environment", "prod-west")),
                createMessage("msg3", Map.of("Environment", "dev-local")),
                createMessage("msg4", Map.of("Environment", "staging"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg2");
    }

    @Test
    void shouldFilterByExistsCondition() {
        String policy = "{\"Priority\":[{\"exists\":true}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Priority", "high")),
                createMessage("msg2", Map.of("Type", "order")),
                createMessage("msg3", Map.of("Priority", "low"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg3");
    }

    @Test
    void shouldFilterByNotExistsCondition() {
        String policy = "{\"Priority\":[{\"exists\":false}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Priority", "high")),
                createMessage("msg2", Map.of("Type", "order")),
                createMessage("msg3", Map.of("Priority", "low"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg2");
    }

    @Test
    void shouldFilterByNumericGreaterThanOrEqual() {
        String policy = "{\"price\":[{\"numeric\":[\">\", 100]}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("price", "150")),
                createMessage("msg2", Map.of("price", "50")),
                createMessage("msg3", Map.of("price", "200"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg3");
    }

    @Test
    void shouldFilterByNumericLessThan() {
        String policy = "{\"quantity\":[{\"numeric\":[\"<\", 10]}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("quantity", "5")),
                createMessage("msg2", Map.of("quantity", "15")),
                createMessage("msg3", Map.of("quantity", "3"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg3");
    }

    @Test
    void shouldCombineMultipleAttributeConditionsWithAndLogic() {
        // Both conditions must match (AND logic between attributes)
        String policy = "{\"Type\":[\"order\"],\"Priority\":[\"high\"]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order", "Priority", "high")),
                createMessage("msg2", Map.of("Type", "order", "Priority", "low")),
                createMessage("msg3", Map.of("Type", "payment", "Priority", "high")),
                createMessage("msg4", Map.of("Type", "order"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
    }

    @Test
    void shouldAllowOrLogicWithinAttribute() {
        // Any of the values can match (OR logic within attribute)
        String policy = "{\"Status\":[\"pending\",\"processing\",\"completed\"]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Status", "pending")),
                createMessage("msg2", Map.of("Status", "failed")),
                createMessage("msg3", Map.of("Status", "completed")),
                createMessage("msg4", Map.of("Status", "cancelled"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg3");
    }

    @Test
    void shouldHandleEmptyFilterPolicy() {
        MessageFilterProcessor processor = new MessageFilterProcessor("");

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order")),
                createMessage("msg2", Map.of("Type", "payment"))
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(processor.hasFilterPolicy()).isFalse();
    }

    @Test
    void shouldHandleInvalidJsonGracefully() {
        MessageFilterProcessor processor = new MessageFilterProcessor("invalid json");

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order"))
        );

        List<Message> filtered = processor.filter(messages);

        // Should pass all messages when filter is invalid
        assertThat(filtered).hasSize(1);
        assertThat(processor.hasFilterPolicy()).isFalse();
    }

    @Test
    void shouldTrackFilterStatistics() {
        String policy = "{\"Type\":[\"order\"]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> batch1 = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order")),
                createMessage("msg2", Map.of("Type", "payment"))
        );

        List<Message> batch2 = Arrays.asList(
                createMessage("msg3", Map.of("Type", "order")),
                createMessage("msg4", Map.of("Type", "order")),
                createMessage("msg5", Map.of("Type", "notification"))
        );

        processor.filter(batch1);
        processor.filter(batch2);

        assertThat(processor.getMessagesPassed()).isEqualTo(3); // msg1, msg3, msg4
        assertThat(processor.getMessagesFiltered()).isEqualTo(2); // msg2, msg5
    }

    @Test
    void shouldResetStatistics() {
        String policy = "{\"Type\":[\"order\"]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order")),
                createMessage("msg2", Map.of("Type", "payment"))
        );

        processor.filter(messages);
        assertThat(processor.getMessagesPassed()).isEqualTo(1);
        assertThat(processor.getMessagesFiltered()).isEqualTo(1);

        processor.resetStats();
        assertThat(processor.getMessagesPassed()).isEqualTo(0);
        assertThat(processor.getMessagesFiltered()).isEqualTo(0);
    }

    @Test
    void shouldHandleComplexFilterWithMixedConditions() {
        String policy = "{\"Type\":[\"order\",{\"prefix\":\"urgent-\"}],\"Region\":[{\"exists\":true}]}";
        MessageFilterProcessor processor = new MessageFilterProcessor(policy);

        List<Message> messages = Arrays.asList(
                createMessage("msg1", Map.of("Type", "order", "Region", "us-east")),
                createMessage("msg2", Map.of("Type", "urgent-alert", "Region", "eu-west")),
                createMessage("msg3", Map.of("Type", "payment", "Region", "us-west")),
                createMessage("msg4", Map.of("Type", "order")) // Missing Region
        );

        List<Message> filtered = processor.filter(messages);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).messageId()).isEqualTo("msg1");
        assertThat(filtered.get(1).messageId()).isEqualTo("msg2");
    }

    private Message createMessage(String messageId, Map<String, String> attributes) {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            messageAttributes.put(entry.getKey(),
                    MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(entry.getValue())
                            .build());
        }

        return Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-" + messageId)
                .body("test body")
                .messageAttributes(messageAttributes)
                .build();
    }
}
