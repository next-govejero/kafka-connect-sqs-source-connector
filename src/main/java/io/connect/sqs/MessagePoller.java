package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.filter.MessageFilterProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsible for polling messages from SQS and applying filters.
 */
public class MessagePoller {
    private static final Logger log = LoggerFactory.getLogger(MessagePoller.class);

    private final SqsClient sqsClient;
    private final SqsSourceConnectorConfig config;
    private final MessageFilterProcessor messageFilterProcessor;
    private final AtomicLong messagesReceived;
    private final AtomicLong messagesDeleted;

    public MessagePoller(SqsClient sqsClient, SqsSourceConnectorConfig config,
            AtomicLong messagesReceived, AtomicLong messagesDeleted) {
        this.sqsClient = sqsClient;
        this.config = config;
        this.messagesReceived = messagesReceived;
        this.messagesDeleted = messagesDeleted;
        this.messageFilterProcessor = new MessageFilterProcessor(config.getMessageFilterPolicy());

        if (messageFilterProcessor.hasFilterPolicy()) {
            log.info("Message filtering enabled with policy: {}", config.getMessageFilterPolicy());
        }
    }

    public List<Message> poll() {
        log.debug("Polling for messages from SQS");

        List<Message> messages = sqsClient.receiveMessages();

        if (messages.isEmpty()) {
            log.trace("No messages received from SQS");
            return null;
        }

        messagesReceived.addAndGet(messages.size());
        log.debug("Received {} messages from SQS", messages.size());

        // Apply message filtering if configured
        if (messageFilterProcessor.hasFilterPolicy()) {
            return filterMessages(messages);
        }

        return messages;
    }

    private List<Message> filterMessages(List<Message> messages) {
        List<Message> filteredOutMessages = new ArrayList<>();
        List<Message> matchingMessages = messageFilterProcessor.filter(messages);

        // Track filtered-out messages for deletion
        for (Message msg : messages) {
            if (!matchingMessages.contains(msg)) {
                filteredOutMessages.add(msg);
            }
        }

        // Delete filtered-out messages immediately
        if (config.isSqsDeleteMessages() && !filteredOutMessages.isEmpty()) {
            try {
                sqsClient.deleteMessages(filteredOutMessages);
                messagesDeleted.addAndGet(filteredOutMessages.size());
                log.debug("Deleted {} filtered-out messages from SQS", filteredOutMessages.size());
            } catch (Exception e) {
                log.error("Failed to delete filtered-out messages from SQS", e);
            }
        }

        if (matchingMessages.isEmpty()) {
            log.trace("All messages were filtered out");
            return null;
        }

        return matchingMessages;
    }

    public MessageFilterProcessor getMessageFilterProcessor() {
        return messageFilterProcessor;
    }
}
