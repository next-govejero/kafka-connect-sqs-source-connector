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
    void shouldCreateMultipleTasksForSingleQueueWhenMaxTasksGreaterThanOne() {
        connector.start(props);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // Single-queue multi-task mode: creates 5 tasks for parallel processing
        assertThat(taskConfigs).hasSize(5);

        // All tasks should point to the same queue
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.get("sqs.queue.url"))
                    .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        }

        // Verify sqs.queue.urls is not present in task configs
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.containsKey("sqs.queue.urls")).isFalse();
        }
    }

    @Test
    void shouldCreateSingleTaskWhenMaxTasksIsOne() {
        connector.start(props);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);

        // Single task when maxTasks = 1
        assertThat(taskConfigs).hasSize(1);
        assertThat(taskConfigs.get(0).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
    }

    @Test
    void shouldCreateMultipleTasksForMultipleQueues() {
        Map<String, String> multiQueueProps = getMultiQueueTestConfig();
        connector.start(multiQueueProps);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(10);

        // Should create one task per queue
        assertThat(taskConfigs).hasSize(3);

        // Each task should have its own queue URL
        assertThat(taskConfigs.get(0).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue1");
        assertThat(taskConfigs.get(1).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue2");
        assertThat(taskConfigs.get(2).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue3");

        // Each task should NOT have the multi-queue config (to avoid confusion)
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.containsKey("sqs.queue.urls")).isFalse();
        }
    }

    @Test
    void shouldLimitTasksToMaxTasksWhenLessThanQueues() {
        Map<String, String> multiQueueProps = getMultiQueueTestConfig();
        connector.start(multiQueueProps);

        // Request only 2 tasks but we have 3 queues
        List<Map<String, String>> taskConfigs = connector.taskConfigs(2);

        // Should only create 2 tasks (limited by maxTasks)
        assertThat(taskConfigs).hasSize(2);

        // First two queues should be assigned
        assertThat(taskConfigs.get(0).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue1");
        assertThat(taskConfigs.get(1).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue2");
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

    @Test
    void shouldHandleDuplicateUrlsThenCreateMultipleTasks() {
        Map<String, String> duplicateUrlProps = getTestConfig();
        duplicateUrlProps.remove("sqs.queue.url");
        duplicateUrlProps.put("sqs.queue.urls",
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1");

        connector.start(duplicateUrlProps);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // After deduplication: 1 unique queue with maxTasks=5 → 5 tasks
        assertThat(taskConfigs).hasSize(5);

        // All tasks point to same deduplicated queue
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.get("sqs.queue.url"))
                    .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue1");
        }
    }

    @Test
    void shouldNotBreakExistingMultiQueueBehavior() {
        Map<String, String> multiQueueProps = getMultiQueueTestConfig();
        connector.start(multiQueueProps);

        // 3 unique queues with maxTasks=5
        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // Should create 3 tasks (one per queue)
        assertThat(taskConfigs).hasSize(3);

        // Each task should have its own queue URL
        assertThat(taskConfigs.get(0).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue1");
        assertThat(taskConfigs.get(1).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue2");
        assertThat(taskConfigs.get(2).get("sqs.queue.url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/queue3");
    }

    @Test
    void shouldRespectMaxTasksLimitInSingleQueueMode() {
        connector.start(props);

        List<Map<String, String>> taskConfigs = connector.taskConfigs(10);

        // Should create 10 tasks for single queue
        assertThat(taskConfigs).hasSize(10);

        // All tasks should point to the same queue
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.get("sqs.queue.url"))
                    .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        }
    }

    @Test
    void shouldHandleMixedDuplicateAndUniqueUrls() {
        Map<String, String> mixedProps = getTestConfig();
        mixedProps.remove("sqs.queue.url");
        mixedProps.put("sqs.queue.urls",
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue2," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue2," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue3");

        connector.start(mixedProps);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(5);

        // After deduplication: 3 unique queues
        // With maxTasks=5, should create 3 tasks (one per queue)
        assertThat(taskConfigs).hasSize(3);
    }

    @Test
    void shouldCreateTasksMatchingMaxTasksForVariousValues() {
        connector.start(props);

        // Test maxTasks = 2
        assertThat(connector.taskConfigs(2)).hasSize(2);

        // Test maxTasks = 3
        assertThat(connector.taskConfigs(3)).hasSize(3);

        // Test maxTasks = 7
        assertThat(connector.taskConfigs(7)).hasSize(7);
    }

    @Test
    void shouldPreserveAllConfigPropertiesInTaskConfigs() {
        Map<String, String> fullConfig = getTestConfig();
        fullConfig.put("dlq.topic", "test-dlq");
        fullConfig.put("max.retries", "5");
        fullConfig.put("retry.backoff.ms", "2000");

        connector.start(fullConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(3);

        // Verify all tasks preserve config properties
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig.get("dlq.topic")).isEqualTo("test-dlq");
            assertThat(taskConfig.get("max.retries")).isEqualTo("5");
            assertThat(taskConfig.get("retry.backoff.ms")).isEqualTo("2000");
            assertThat(taskConfig.get("kafka.topic")).isEqualTo("test-topic");
        }
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

    private Map<String, String> getMultiQueueTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("sqs.queue.urls",
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue1," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue2," +
                "https://sqs.us-east-1.amazonaws.com/123456789012/queue3");
        config.put("kafka.topic", "test-topic");
        config.put("aws.region", "us-east-1");
        config.put("sasl.mechanism", "SCRAM-SHA-512");
        config.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return config;
    }
}

