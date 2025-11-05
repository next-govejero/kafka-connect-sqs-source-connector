package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.VersionUtil;
import org.apache.kafka.common.config.ConfigDef;
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

        log.info("SQS Source Connector configuration:");
        log.info("  Queue URL: {}", config.getSqsQueueUrl());
        log.info("  Kafka Topic: {}", config.getKafkaTopic());
        log.info("  Region: {}", config.getAwsRegion());
        log.info("  Max Messages: {}", config.getSqsMaxMessages());
        log.info("  Wait Time: {}s", config.getSqsWaitTimeSeconds());
        log.info("  Visibility Timeout: {}s", config.getSqsVisibilityTimeoutSeconds());
        log.info("  Delete Messages: {}", config.isSqsDeleteMessages());
        log.info("  SASL Mechanism: {}", config.getSaslMechanism());
        log.info("  Security Protocol: {}", config.getSecurityProtocol());

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

        // For now, we only support single task per connector instance
        // to maintain message ordering from a single queue
        // In future versions, we can support multiple tasks for multiple queues
        Map<String, String> taskConfig = new HashMap<>(configProps);
        taskConfigs.add(taskConfig);

        log.info("Created {} task configuration(s)", taskConfigs.size());
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
