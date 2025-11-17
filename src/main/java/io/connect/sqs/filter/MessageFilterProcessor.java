package io.connect.sqs.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes and filters SQS messages based on message attributes.
 * Implements client-side filtering similar to SNS filter policies.
 *
 * Supports the following filter operations:
 * - Exact match: {"Type": ["order", "payment"]} - matches if Type equals "order" OR "payment"
 * - Prefix match: {"Type": [{"prefix": "prod-"}]} - matches if Type starts with "prod-"
 * - Exists check: {"Type": [{"exists": true}]} - matches if Type attribute exists
 * - Numeric comparison: {"price": [{"numeric": [">=", 100]}]} - matches if price >= 100
 *
 * This filtering happens after messages are received from SQS but before they
 * are converted to Kafka records. Messages that don't match the filter are
 * immediately deleted from SQS.
 */
public class MessageFilterProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageFilterProcessor.class);

    private final Map<String, List<FilterCondition>> filterPolicy;
    private final AtomicLong messagesFiltered = new AtomicLong(0);
    private final AtomicLong messagesPassed = new AtomicLong(0);

    /**
     * Creates a MessageFilterProcessor with the given filter policy JSON.
     *
     * @param filterPolicyJson JSON string representing the filter policy, or null for no filtering
     */
    public MessageFilterProcessor(String filterPolicyJson) {
        this.filterPolicy = parseFilterPolicy(filterPolicyJson);
        if (!filterPolicy.isEmpty()) {
            log.info("Initialized message filter with {} attribute rules", filterPolicy.size());
            for (Map.Entry<String, List<FilterCondition>> entry : filterPolicy.entrySet()) {
                log.info("  Attribute '{}': {} conditions", entry.getKey(), entry.getValue().size());
            }
        } else {
            log.info("No message filter policy configured - all messages will pass through");
        }
    }

    /**
     * Filters a list of messages based on the configured filter policy.
     *
     * @param messages The messages to filter
     * @return List of messages that match the filter policy
     */
    public List<Message> filter(List<Message> messages) {
        if (filterPolicy.isEmpty()) {
            messagesPassed.addAndGet(messages.size());
            return messages;
        }

        List<Message> matchingMessages = new ArrayList<>();

        for (Message message : messages) {
            if (matchesFilter(message)) {
                matchingMessages.add(message);
                messagesPassed.incrementAndGet();
            } else {
                messagesFiltered.incrementAndGet();
                log.debug("Message {} filtered out by policy", message.messageId());
            }
        }

        if (messages.size() != matchingMessages.size()) {
            log.info("Filtered {} messages: {} passed, {} filtered out",
                    messages.size(), matchingMessages.size(), messages.size() - matchingMessages.size());
        }

        return matchingMessages;
    }

    /**
     * Checks if a message matches the filter policy.
     * A message matches if ALL attribute conditions are satisfied (AND logic).
     * Within each attribute, ANY condition can match (OR logic).
     *
     * @param message The message to check
     * @return true if the message matches the filter policy
     */
    public boolean matchesFilter(Message message) {
        if (filterPolicy.isEmpty()) {
            return true;
        }

        Map<String, MessageAttributeValue> messageAttributes = message.messageAttributes();

        // All attribute rules must match (AND logic between attributes)
        for (Map.Entry<String, List<FilterCondition>> entry : filterPolicy.entrySet()) {
            String attributeName = entry.getKey();
            List<FilterCondition> conditions = entry.getValue();

            MessageAttributeValue attributeValue = messageAttributes != null
                    ? messageAttributes.get(attributeName)
                    : null;

            // At least one condition must match (OR logic within attribute)
            boolean attributeMatches = false;
            for (FilterCondition condition : conditions) {
                if (condition.matches(attributeValue)) {
                    attributeMatches = true;
                    break;
                }
            }

            if (!attributeMatches) {
                log.trace("Message {} failed filter on attribute '{}'", message.messageId(), attributeName);
                return false;
            }
        }

        return true;
    }

    /**
     * Gets the number of messages that have been filtered out.
     *
     * @return Count of filtered messages
     */
    public long getMessagesFiltered() {
        return messagesFiltered.get();
    }

    /**
     * Gets the number of messages that passed the filter.
     *
     * @return Count of passed messages
     */
    public long getMessagesPassed() {
        return messagesPassed.get();
    }

    /**
     * Checks if a filter policy is configured.
     *
     * @return true if filtering is active
     */
    public boolean hasFilterPolicy() {
        return !filterPolicy.isEmpty();
    }

    /**
     * Resets the filter statistics.
     */
    public void resetStats() {
        messagesFiltered.set(0);
        messagesPassed.set(0);
    }

    /**
     * Parses a JSON filter policy string into a map of attribute conditions.
     * Example policy: {"Type":["order","payment"],"Priority":[{"prefix":"high"}]}
     *
     * @param filterPolicyJson The JSON policy string
     * @return Map of attribute names to their filter conditions
     */
    private Map<String, List<FilterCondition>> parseFilterPolicy(String filterPolicyJson) {
        Map<String, List<FilterCondition>> policy = new HashMap<>();

        if (filterPolicyJson == null || filterPolicyJson.trim().isEmpty()) {
            return policy;
        }

        try {
            String json = filterPolicyJson.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                log.error("Invalid filter policy JSON format: {}", filterPolicyJson);
                return policy;
            }

            // Remove outer braces
            json = json.substring(1, json.length() - 1).trim();

            // Parse each attribute
            int pos = 0;
            while (pos < json.length()) {
                // Skip whitespace
                while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                    pos++;
                }

                if (pos >= json.length()) {
                    break;
                }

                // Parse attribute name (must be in quotes)
                if (json.charAt(pos) != '"') {
                    log.error("Expected attribute name at position {}", pos);
                    break;
                }

                int nameStart = pos + 1;
                int nameEnd = json.indexOf('"', nameStart);
                if (nameEnd == -1) {
                    log.error("Unclosed attribute name starting at position {}", pos);
                    break;
                }

                String attributeName = json.substring(nameStart, nameEnd);
                pos = nameEnd + 1;

                // Skip to colon
                while (pos < json.length() && json.charAt(pos) != ':') {
                    pos++;
                }
                pos++; // skip colon

                // Skip whitespace
                while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                    pos++;
                }

                // Parse value array
                if (pos >= json.length() || json.charAt(pos) != '[') {
                    log.error("Expected array for attribute '{}' at position {}", attributeName, pos);
                    break;
                }

                int arrayStart = pos;
                int arrayEnd = findMatchingBracket(json, pos);
                if (arrayEnd == -1) {
                    log.error("Unclosed array for attribute '{}'", attributeName);
                    break;
                }

                String arrayJson = json.substring(arrayStart, arrayEnd + 1);
                List<FilterCondition> conditions = parseConditionsArray(arrayJson);
                policy.put(attributeName, conditions);

                pos = arrayEnd + 1;

                // Skip to next attribute (comma) or end
                while (pos < json.length() && json.charAt(pos) != ',') {
                    pos++;
                }
                if (pos < json.length() && json.charAt(pos) == ',') {
                    pos++; // skip comma
                }
            }

            log.debug("Parsed filter policy with {} attributes", policy.size());

        } catch (Exception e) {
            log.error("Failed to parse filter policy JSON: {}", filterPolicyJson, e);
        }

        return policy;
    }

    /**
     * Finds the matching closing bracket for an opening bracket.
     *
     * @param json The JSON string
     * @param start The position of the opening bracket
     * @return The position of the matching closing bracket, or -1 if not found
     */
    private int findMatchingBracket(String json, int start) {
        char openBracket = json.charAt(start);
        char closeBracket = openBracket == '[' ? ']' : '}';

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == openBracket) {
                    depth++;
                } else if (c == closeBracket) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Parses an array of filter conditions from JSON.
     * Example: ["order","payment",{"prefix":"high"}]
     *
     * @param arrayJson The JSON array string
     * @return List of filter conditions
     */
    private List<FilterCondition> parseConditionsArray(String arrayJson) {
        List<FilterCondition> conditions = new ArrayList<>();

        // Remove outer brackets
        String content = arrayJson.substring(1, arrayJson.length() - 1).trim();

        if (content.isEmpty()) {
            return conditions;
        }

        int pos = 0;
        while (pos < content.length()) {
            // Skip whitespace
            while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
                pos++;
            }

            if (pos >= content.length()) {
                break;
            }

            char c = content.charAt(pos);

            if (c == '"') {
                // String value - exact match
                int valueEnd = content.indexOf('"', pos + 1);
                if (valueEnd == -1) {
                    break;
                }
                String value = content.substring(pos + 1, valueEnd);
                conditions.add(new ExactMatchCondition(value));
                pos = valueEnd + 1;

            } else if (c == '{') {
                // Object - special condition (prefix, exists, numeric)
                int objectEnd = findMatchingBracket(content, pos);
                if (objectEnd == -1) {
                    break;
                }
                String objectJson = content.substring(pos, objectEnd + 1);
                FilterCondition condition = parseSpecialCondition(objectJson);
                if (condition != null) {
                    conditions.add(condition);
                }
                pos = objectEnd + 1;

            } else if (Character.isDigit(c) || c == '-') {
                // Numeric value - exact match
                int valueEnd = pos;
                while (valueEnd < content.length() &&
                       (Character.isDigit(content.charAt(valueEnd)) ||
                        content.charAt(valueEnd) == '.' ||
                        content.charAt(valueEnd) == '-')) {
                    valueEnd++;
                }
                String numStr = content.substring(pos, valueEnd);
                conditions.add(new ExactMatchCondition(numStr));
                pos = valueEnd;

            } else if (c == ',') {
                pos++;
            } else {
                pos++;
            }
        }

        return conditions;
    }

    /**
     * Parses a special condition object like {"prefix": "prod-"} or {"exists": true}.
     *
     * @param objectJson The JSON object string
     * @return The parsed condition, or null if parsing fails
     */
    private FilterCondition parseSpecialCondition(String objectJson) {
        // Remove braces
        String content = objectJson.substring(1, objectJson.length() - 1).trim();

        // Find the key
        if (!content.startsWith("\"")) {
            return null;
        }

        int keyEnd = content.indexOf('"', 1);
        if (keyEnd == -1) {
            return null;
        }

        String key = content.substring(1, keyEnd);

        // Find the value
        int colonPos = content.indexOf(':', keyEnd);
        if (colonPos == -1) {
            return null;
        }

        String valueStr = content.substring(colonPos + 1).trim();

        switch (key.toLowerCase()) {
            case "prefix":
                if (valueStr.startsWith("\"") && valueStr.endsWith("\"")) {
                    String prefix = valueStr.substring(1, valueStr.length() - 1);
                    return new PrefixMatchCondition(prefix);
                }
                break;

            case "exists":
                boolean exists = valueStr.equals("true");
                return new ExistsCondition(exists);

            case "numeric":
                // Parse numeric comparison array like [">=", 100]
                if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
                    return parseNumericCondition(valueStr);
                }
                break;

            default:
                log.warn("Unknown filter condition type: {}", key);
        }

        return null;
    }

    /**
     * Parses a numeric condition array like [">=", 100] or ["<", 50, ">=", 10].
     *
     * @param arrayJson The JSON array string
     * @return The parsed numeric condition
     */
    private FilterCondition parseNumericCondition(String arrayJson) {
        // Simple parsing for single comparison
        String content = arrayJson.substring(1, arrayJson.length() - 1).trim();
        String[] parts = content.split(",");

        if (parts.length >= 2) {
            String operator = parts[0].trim().replace("\"", "");
            String valueStr = parts[1].trim();
            try {
                double value = Double.parseDouble(valueStr);
                return new NumericCondition(operator, value);
            } catch (NumberFormatException e) {
                log.error("Invalid numeric value in filter condition: {}", valueStr);
            }
        }

        return null;
    }

    /**
     * Base interface for filter conditions.
     */
    private interface FilterCondition {
        boolean matches(MessageAttributeValue attributeValue);
    }

    /**
     * Exact match condition - value must equal exactly.
     */
    private static class ExactMatchCondition implements FilterCondition {
        private final String expectedValue;

        ExactMatchCondition(String expectedValue) {
            this.expectedValue = expectedValue;
        }

        @Override
        public boolean matches(MessageAttributeValue attributeValue) {
            if (attributeValue == null) {
                return false;
            }
            String actualValue = attributeValue.stringValue();
            return expectedValue.equals(actualValue);
        }
    }

    /**
     * Prefix match condition - value must start with prefix.
     */
    private static class PrefixMatchCondition implements FilterCondition {
        private final String prefix;

        PrefixMatchCondition(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean matches(MessageAttributeValue attributeValue) {
            if (attributeValue == null || attributeValue.stringValue() == null) {
                return false;
            }
            return attributeValue.stringValue().startsWith(prefix);
        }
    }

    /**
     * Exists condition - checks if attribute exists (or doesn't exist).
     */
    private static class ExistsCondition implements FilterCondition {
        private final boolean shouldExist;

        ExistsCondition(boolean shouldExist) {
            this.shouldExist = shouldExist;
        }

        @Override
        public boolean matches(MessageAttributeValue attributeValue) {
            boolean exists = attributeValue != null;
            return shouldExist == exists;
        }
    }

    /**
     * Numeric condition - compares numeric values.
     */
    private static class NumericCondition implements FilterCondition {
        private final String operator;
        private final double value;

        NumericCondition(String operator, double value) {
            this.operator = operator;
            this.value = value;
        }

        @Override
        public boolean matches(MessageAttributeValue attributeValue) {
            if (attributeValue == null || attributeValue.stringValue() == null) {
                return false;
            }

            try {
                double actualValue = Double.parseDouble(attributeValue.stringValue());

                switch (operator) {
                    case "=":
                    case "==":
                        return actualValue == value;
                    case "!=":
                        return actualValue != value;
                    case "<":
                        return actualValue < value;
                    case "<=":
                        return actualValue <= value;
                    case ">":
                        return actualValue > value;
                    case ">=":
                        return actualValue >= value;
                    default:
                        log.warn("Unknown numeric operator: {}", operator);
                        return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
