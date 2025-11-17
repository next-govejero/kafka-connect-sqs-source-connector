package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Message converter that converts SQS messages to JSON Schema format using Confluent Schema Registry.
 *
 * <p>This converter supports:
 * <ul>
 *   <li>Automatic JSON Schema inference from JSON messages</li>
 *   <li>Schema validation with JSON Schema</li>
 *   <li>Schema registration with Schema Registry</li>
 *   <li>Schema evolution support</li>
 *   <li>Custom schema ID configuration</li>
 * </ul>
 *
 * <p>Example configuration:
 * <pre>
 * message.converter.class=io.connect.sqs.converter.JsonSchemaMessageConverter
 * schema.registry.url=http://schema-registry:8081
 * value.schema.id=1  # Optional: use specific schema ID
 * schema.auto.register=true
 * </pre>
 */
public class JsonSchemaMessageConverter extends SchemaRegistryConverter {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonSchema cachedSchema;
    private Schema cachedConnectSchema;

    @Override
    protected String getSchemaType() {
        return "JSON Schema";
    }

    @Override
    protected SchemaAndValue convertValue(Message message, SqsSourceConnectorConfig config) {
        String messageBody = message.body();

        try {
            // Parse the JSON message
            JsonNode jsonNode = objectMapper.readTree(messageBody);

            if (!jsonNode.isObject()) {
                // If not an object, wrap in a simple record
                return createSimpleStringValue(messageBody);
            }

            // Get or create JSON Schema
            JsonSchema jsonSchema = getOrCreateSchema(jsonNode, config);

            // Validate message against schema
            validateMessage(messageBody, jsonSchema);

            // Convert to Connect schema and value
            Schema connectSchema = jsonSchemaToConnectSchema(jsonSchema, config.getKafkaTopic());
            Object connectValue = jsonToConnectValue(jsonNode, connectSchema);

            log.debug("Converted message {} to JSON Schema format with schema {}",
                    message.messageId(), jsonSchema.name());

            return new SchemaAndValue(connectSchema, connectValue);

        } catch (IOException e) {
            log.warn("Failed to parse message {} as JSON, using raw string", message.messageId(), e);
            return createSimpleStringValue(messageBody);
        } catch (Exception e) {
            throw new DataException("Failed to convert message " + message.messageId() + " to JSON Schema", e);
        }
    }

    private SchemaAndValue createSimpleStringValue(String value) {
        return new SchemaAndValue(Schema.STRING_SCHEMA, value);
    }

    private JsonSchema getOrCreateSchema(JsonNode jsonNode, SqsSourceConnectorConfig config) {
        Integer schemaId = config.getValueSchemaId();

        if (schemaId != null) {
            // Use specified schema ID
            String schemaString = fetchSchemaById(schemaId);
            return new JsonSchema(schemaString);
        }

        if (config.isSchemaUseLatestVersion()) {
            // Use latest schema from registry
            String subject = getSubjectName(config.getKafkaTopic(), false);
            String schemaString = fetchLatestSchema(subject);
            return new JsonSchema(schemaString);
        }

        // Use cached schema if available
        if (cachedSchema != null) {
            return cachedSchema;
        }

        // Generate JSON Schema from JSON structure
        JsonSchema inferredSchema = inferSchemaFromJson(jsonNode, config.getKafkaTopic());

        if (config.isSchemaAutoRegister()) {
            // Register the schema
            String subject = getSubjectName(config.getKafkaTopic(), false);
            int registeredId = registerSchema(subject, inferredSchema);
            log.info("Registered JSON Schema with ID {} for subject {}", registeredId, subject);
        }

        cachedSchema = inferredSchema;
        return inferredSchema;
    }

    private JsonSchema inferSchemaFromJson(JsonNode jsonNode, String topicName) {
        ObjectNode schemaNode = objectMapper.createObjectNode();
        schemaNode.put("$schema", "http://json-schema.org/draft-07/schema#");
        schemaNode.put("title", topicName.replaceAll("[^a-zA-Z0-9]", "_") + "_value");
        schemaNode.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        Iterator<Map.Entry<String, JsonNode>> fieldIterator = jsonNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> field = fieldIterator.next();
            ObjectNode fieldSchema = inferJsonSchemaType(field.getValue());
            properties.set(field.getKey(), fieldSchema);
            // All fields are considered required by default
            required.add(field.getKey());
        }

        schemaNode.set("properties", properties);
        schemaNode.set("required", required);
        schemaNode.put("additionalProperties", false);

        String schemaString = schemaNode.toString();
        log.debug("Generated JSON Schema:\n{}", schemaString);

        return new JsonSchema(schemaString);
    }

    private ObjectNode inferJsonSchemaType(JsonNode value) {
        ObjectNode typeNode = objectMapper.createObjectNode();

        if (value.isNull()) {
            ArrayNode types = objectMapper.createArrayNode();
            types.add("string");
            types.add("null");
            typeNode.set("type", types);
        } else if (value.isBoolean()) {
            typeNode.put("type", "boolean");
        } else if (value.isInt() || value.isLong()) {
            typeNode.put("type", "integer");
        } else if (value.isDouble() || value.isFloat() || value.isNumber()) {
            typeNode.put("type", "number");
        } else if (value.isTextual()) {
            typeNode.put("type", "string");
        } else if (value.isArray()) {
            typeNode.put("type", "array");
            if (value.isEmpty()) {
                typeNode.set("items", objectMapper.createObjectNode().put("type", "string"));
            } else {
                typeNode.set("items", inferJsonSchemaType(value.get(0)));
            }
        } else if (value.isObject()) {
            typeNode.put("type", "object");
            ObjectNode nestedProperties = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> nestedFields = value.fields();
            while (nestedFields.hasNext()) {
                Map.Entry<String, JsonNode> nestedField = nestedFields.next();
                nestedProperties.set(nestedField.getKey(), inferJsonSchemaType(nestedField.getValue()));
            }
            typeNode.set("properties", nestedProperties);
            typeNode.put("additionalProperties", true);
        } else {
            typeNode.put("type", "string");
        }

        return typeNode;
    }

    private void validateMessage(String messageBody, JsonSchema schema) {
        try {
            schema.validate(objectMapper.readTree(messageBody));
            log.debug("Message validated successfully against JSON Schema");
        } catch (Exception e) {
            log.warn("Message validation failed against JSON Schema: {}", e.getMessage());
            // Continue processing even if validation fails (for schema evolution)
        }
    }

    private Schema jsonSchemaToConnectSchema(JsonSchema jsonSchema, String topicName) {
        try {
            JsonNode schemaNode = objectMapper.readTree(jsonSchema.canonicalString());
            String schemaName = topicName.replaceAll("[^a-zA-Z0-9]", "_") + "_value";

            if (schemaNode.has("title")) {
                schemaName = schemaNode.get("title").asText();
            }

            cachedConnectSchema = jsonSchemaNodeToConnectSchema(schemaNode, schemaName);
            return cachedConnectSchema;
        } catch (Exception e) {
            throw new DataException("Failed to convert JSON Schema to Connect Schema", e);
        }
    }

    private Schema jsonSchemaNodeToConnectSchema(JsonNode schemaNode, String schemaName) {
        if (!schemaNode.has("type")) {
            return Schema.OPTIONAL_STRING_SCHEMA;
        }

        JsonNode typeNode = schemaNode.get("type");
        String type;

        if (typeNode.isArray()) {
            // Handle nullable types (e.g., ["string", "null"])
            type = "string";
            for (JsonNode t : typeNode) {
                if (!t.asText().equals("null")) {
                    type = t.asText();
                    break;
                }
            }
        } else {
            type = typeNode.asText();
        }

        switch (type) {
            case "object":
                return jsonObjectSchemaToConnect(schemaNode, schemaName);
            case "array":
                return jsonArraySchemaToConnect(schemaNode);
            case "string":
                return Schema.OPTIONAL_STRING_SCHEMA;
            case "integer":
                return Schema.OPTIONAL_INT64_SCHEMA;
            case "number":
                return Schema.OPTIONAL_FLOAT64_SCHEMA;
            case "boolean":
                return Schema.OPTIONAL_BOOLEAN_SCHEMA;
            case "null":
                return Schema.OPTIONAL_STRING_SCHEMA;
            default:
                return Schema.OPTIONAL_STRING_SCHEMA;
        }
    }

    private Schema jsonObjectSchemaToConnect(JsonNode schemaNode, String schemaName) {
        SchemaBuilder builder = SchemaBuilder.struct().name(schemaName).optional();

        if (schemaNode.has("properties")) {
            JsonNode properties = schemaNode.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                Schema fieldSchema = jsonSchemaNodeToConnectSchema(field.getValue(),
                        schemaName + "_" + fieldName);
                builder.field(fieldName, fieldSchema);
            }
        }

        return builder.build();
    }

    private Schema jsonArraySchemaToConnect(JsonNode schemaNode) {
        Schema itemSchema = Schema.OPTIONAL_STRING_SCHEMA;

        if (schemaNode.has("items")) {
            itemSchema = jsonSchemaNodeToConnectSchema(schemaNode.get("items"), "array_item");
        }

        return SchemaBuilder.array(itemSchema).optional().build();
    }

    private Object jsonToConnectValue(JsonNode jsonNode, Schema connectSchema) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }

        switch (connectSchema.type()) {
            case STRUCT:
                return jsonObjectToStruct(jsonNode, connectSchema);
            case ARRAY:
                return jsonArrayToList(jsonNode, connectSchema);
            case STRING:
                return jsonNode.asText();
            case INT32:
                return jsonNode.asInt();
            case INT64:
                return jsonNode.asLong();
            case FLOAT32:
                return (float) jsonNode.asDouble();
            case FLOAT64:
                return jsonNode.asDouble();
            case BOOLEAN:
                return jsonNode.asBoolean();
            case BYTES:
                try {
                    return jsonNode.binaryValue();
                } catch (Exception e) {
                    return jsonNode.asText().getBytes();
                }
            default:
                return jsonNode.asText();
        }
    }

    private Struct jsonObjectToStruct(JsonNode jsonNode, Schema connectSchema) {
        if (!jsonNode.isObject()) {
            throw new DataException("Expected JSON object but got: " + jsonNode.getNodeType());
        }

        Struct struct = new Struct(connectSchema);

        for (org.apache.kafka.connect.data.Field field : connectSchema.fields()) {
            JsonNode fieldValue = jsonNode.get(field.name());
            Object connectValue = jsonToConnectValue(fieldValue, field.schema());
            struct.put(field, connectValue);
        }

        return struct;
    }

    private List<Object> jsonArrayToList(JsonNode jsonNode, Schema connectSchema) {
        if (!jsonNode.isArray()) {
            throw new DataException("Expected JSON array but got: " + jsonNode.getNodeType());
        }

        List<Object> list = new ArrayList<>();
        Schema elementSchema = connectSchema.valueSchema();

        for (JsonNode element : jsonNode) {
            list.add(jsonToConnectValue(element, elementSchema));
        }

        return list;
    }
}
