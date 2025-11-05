package io.connect.sqs;

import org.apache.kafka.connect.connector.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqsSourceConnectorTest {

    private SqsSourceConnector connector;
    private Map<String, String> props;

    @BeforeEach
    void setUp() {
        connector = new SqsSourceConnector();
        props = getTestConfig();
    }

    @Test
    void shouldReturnVersion() {
        String version = connector.version();
        assertThat(version).isNotNull();
    }

    @Test
    void shouldStartConnector() {
        connector.start(props);
        // Should not throw exception
    }

    @Test
    void shouldReturnTaskClass() {
        Class<? extends Task> taskClass = connector.taskClass();
        assertThat(taskClass).isEqualTo(SqsSourceTask.class);
    }

    @Test
    void shouldCreateSingleTaskConfig() {
        connector.start(props);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);

        assertThat(taskConfigs).hasSize(1);
        assertThat(taskConfigs.get(0)).containsAllEntriesOf(props);
    }

    @Test
    void shouldCreateSingleTaskEvenWhenMaxTasksIsHigher() {
        connector.start(props);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // Currently only supports single task per queue
        assertThat(taskConfigs).hasSize(1);
    }

    @Test
    void shouldStopConnector() {
        connector.start(props);
        connector.stop();
        // Should not throw exception
    }

    @Test
    void shouldReturnConfigDef() {
        assertThat(connector.config()).isNotNull();
    }

    private Map<String, String> getTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        config.put("kafka.topic", "test-topic");
        config.put("aws.region", "us-east-1");
        config.put("sasl.mechanism", "SCRAM-SHA-512");
        config.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return config;
    }
}
