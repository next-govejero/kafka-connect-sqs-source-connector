package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.VersionUtil;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQS Source Connector for Kafka Connect.
 * Streams messages from AWS SQS queues into Kafka topics.
 */
public class SqsSourceConnector extends SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(SqsSourceConnector.class);

    private Map<String, String> configProps;
    private SqsSourceConnectorConfig config;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        log.info("Starting SQS Source Connector");
        this.configProps = props;
        this.config = new SqsSourceConnectorConfig(props);

        List<String> queueUrls = config.getQueueUrls();

        log.info("SQS Source Connector configuration:");
        if (queueUrls.size() > 1) {
            log.info("  Multi-Queue Mode: {} queues configured", queueUrls.size());
            for (int i = 0; i < queueUrls.size(); i++) {
                log.info("    Queue {}: {}", i + 1, queueUrls.get(i));
            }
        } else {
            log.info("  Queue URL: {}", queueUrls.isEmpty() ? "none" : queueUrls.get(0));
        }
        log.info("  Kafka Topic: {}", config.getKafkaTopic());
        log.info("  Region: {}", config.getAwsRegion());
        log.info("  Max Messages: {}", config.getSqsMaxMessages());
        log.info("  Wait Time: {}s", config.getSqsWaitTimeSeconds());
        log.info("  Visibility Timeout: {}s", config.getSqsVisibilityTimeoutSeconds());
        log.info("  Delete Messages: {}", config.isSqsDeleteMessages());
        log.info("  SASL Mechanism: {}", config.getSaslMechanism());
        log.info("  Security Protocol: {}", config.getSecurityProtocol());

        if (config.getMessageFilterPolicy() != null) {
            log.info("  Message Filter Policy: {}", config.getMessageFilterPolicy());
        }

        if (config.getDlqTopic() != null) {
            log.info("  Dead Letter Queue Topic: {}", config.getDlqTopic());
        }
    }

    @Override
    public Class<? extends Task> taskClass() {
        return SqsSourceTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        log.info("Setting up task configurations for {} max tasks", maxTasks);

        List<Map<String, String>> taskConfigs = new ArrayList<>();
        List<String> queueUrls = config.getQueueUrls();

        if (queueUrls.isEmpty()) {
            throw new ConfigException("No SQS queue URLs configured");
        }

        // Multi-queue support: create one task per queue URL
        // Each task gets its own queue to process, enabling parallel consumption
        int numQueues = queueUrls.size();
        int numTasks = Math.min(numQueues, maxTasks);

        if (numTasks < numQueues) {
            log.warn("maxTasks ({}) is less than number of queues ({}). " +
                     "Some tasks will process multiple queues.", maxTasks, numQueues);
        }

        // Distribute queues across tasks
        // If numTasks >= numQueues: each queue gets its own task
        // If numTasks < numQueues: queues are distributed (not recommended)
        if (numTasks >= numQueues) {
            // Ideal case: one task per queue
            for (int i = 0; i < numQueues; i++) {
                Map<String, String> taskConfig = new HashMap<>(configProps);
                String queueUrl = queueUrls.get(i);

                // Override the queue URL for this specific task
                taskConfig.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG, queueUrl);
                // Clear the multi-queue config to avoid confusion in the task
                taskConfig.remove(SqsSourceConnectorConfig.SQS_QUEUE_URLS_CONFIG);

                taskConfigs.add(taskConfig);
                log.info("Task {} assigned to queue: {}", i, queueUrl);
            }
        } else {
            // Less ideal: distribute queues across available tasks
            // This scenario should be avoided by ensuring maxTasks >= numQueues
            for (int i = 0; i < numTasks; i++) {
                Map<String, String> taskConfig = new HashMap<>(configProps);
                // Each task gets the queue at its index
                // Additional queues beyond numTasks will not be processed
                String queueUrl = queueUrls.get(i);
                taskConfig.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG, queueUrl);
                taskConfig.remove(SqsSourceConnectorConfig.SQS_QUEUE_URLS_CONFIG);

                taskConfigs.add(taskConfig);
                log.warn("Task {} assigned to queue: {} (limited by maxTasks)", i, queueUrl);
            }

            // Log warning about unassigned queues
            if (numQueues > numTasks) {
                for (int i = numTasks; i < numQueues; i++) {
                    log.error("Queue {} is NOT assigned to any task due to maxTasks limit: {}",
                              i, queueUrls.get(i));
                }
            }
        }

        log.info("Created {} task configuration(s) for {} queue(s)", taskConfigs.size(), numQueues);
        return taskConfigs;
    }

    @Override
    public void stop() {
        log.info("Stopping SQS Source Connector");
        // Cleanup resources if needed
    }

    @Override
    public ConfigDef config() {
        return SqsSourceConnectorConfig.CONFIG_DEF;
    }
}
