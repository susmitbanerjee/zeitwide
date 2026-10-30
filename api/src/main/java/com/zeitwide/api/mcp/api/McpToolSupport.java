package com.zeitwide.api.mcp.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeitwide.api.answers.app.DatasetUnavailableException;
import com.zeitwide.api.answers.domain.OutOfRangeException;
import com.zeitwide.api.answers.domain.UnknownCalendarException;
import com.zeitwide.api.web.RequestValidationException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public final class McpToolSupport {

  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
  private final ObjectMapper json;

  public McpToolSupport(ObjectMapper json) {
    this.json = json;
  }

  public McpServerFeatures.SyncToolSpecification tool(
      String name,
      String description,
      Map<String, Object> properties,
      List<String> required,
      Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
    McpSchema.JsonSchema schema =
        new McpSchema.JsonSchema("object", properties, required, false, null, null);
    McpSchema.Tool tool =
        McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> handler.apply(request.arguments()))
        .build();
  }

  public McpSchema.CallToolResult invoke(java.util.function.Supplier<?> action) {
    try {
      Object output = action.get();
      String serialized = json.writeValueAsString(output);
      Map<String, Object> structured = json.convertValue(output, OBJECT_MAP);
      return McpSchema.CallToolResult.builder()
          .addTextContent(serialized)
          .structuredContent(structured)
          .build();
    } catch (RequestValidationException | IllegalArgumentException invalid) {
      return error("validation_failed", "The tool arguments are invalid");
    } catch (UnknownCalendarException unknown) {
      return error("unknown_calendar", "One or more calendars are not in the dataset");
    } catch (OutOfRangeException outOfRange) {
      return error("out_of_range", "The requested date is outside calendar source coverage");
    } catch (DatasetUnavailableException unavailable) {
      return error("dataset_unavailable", "No dataset release is currently available");
    } catch (JsonProcessingException serializationFailure) {
      throw new IllegalStateException(
          "The MCP tool result could not be serialized", serializationFailure);
    }
  }

  public static Map<String, Object> string(
      String description, String format, String pattern, List<String> values) {
    return string(description, format, pattern, values, null);
  }

  public static Map<String, Object> string(
      String description, String format, String pattern, List<String> values, Integer maxLength) {
    Map<String, Object> property = new java.util.LinkedHashMap<>();
    property.put("type", "string");
    property.put("description", description);
    if (format != null) {
      property.put("format", format);
    }
    if (pattern != null) {
      property.put("pattern", pattern);
    }
    if (values != null) {
      property.put("enum", List.copyOf(values));
    }
    if (maxLength != null) {
      property.put("maxLength", maxLength);
    }
    return Map.copyOf(property);
  }

  public static Map<String, Object> integer(String description, int minimum, int maximum) {
    return Map.of(
        "type", "integer", "description", description, "minimum", minimum, "maximum", maximum);
  }

  public static Map<String, Object> array(
      String description, Map<String, Object> items, int maximum, boolean unique) {
    return array(description, items, 0, maximum, unique);
  }

  public static Map<String, Object> array(
      String description, Map<String, Object> items, int minimum, int maximum, boolean unique) {
    Map<String, Object> schema = new java.util.LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", items);
    if (minimum > 0) {
      schema.put("minItems", minimum);
    }
    schema.put("maxItems", maximum);
    schema.put("uniqueItems", unique);
    return Map.copyOf(schema);
  }

  public static Map<String, Object> arguments(Map<String, Object> values, String... allowed) {
    if (values == null) {
      throw new McpInputException();
    }
    Set<String> allowedNames = Set.of(allowed);
    if (!allowedNames.containsAll(values.keySet())) {
      throw new McpInputException();
    }
    return values;
  }

  public static String requiredString(Map<String, Object> values, String name) {
    Object value = values.get(name);
    if (value instanceof String string && !string.isBlank()) {
      return string;
    }
    throw new McpInputException();
  }

  public static String optionalString(Map<String, Object> values, String name) {
    Object value = values.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String string && !string.isBlank()) {
      return string;
    }
    throw new McpInputException();
  }

  public static int optionalInteger(Map<String, Object> values, String name, int fallback) {
    Object value = values.get(name);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      double numeric = number.doubleValue();
      int integer = number.intValue();
      if (Double.isFinite(numeric) && numeric == integer) {
        return integer;
      }
    }
    throw new McpInputException();
  }

  public static int requiredInteger(Map<String, Object> values, String name) {
    if (!values.containsKey(name)) {
      throw new McpInputException();
    }
    return optionalInteger(values, name, 0);
  }

  public static List<String> optionalStrings(
      Map<String, Object> values, String name, int maximum, String pattern) {
    return optionalStrings(values, name, maximum, Integer.MAX_VALUE, pattern);
  }

  public static List<String> optionalStrings(
      Map<String, Object> values, String name, int maximum, int maximumLength, String pattern) {
    Object value = values.get(name);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list) || list.size() > maximum) {
      throw new McpInputException();
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String string)
          || string.length() > maximumLength
          || !string.matches(pattern)) {
        throw new McpInputException();
      }
      result.add(string);
    }
    if (result.stream().distinct().count() != result.size()) {
      throw new McpInputException();
    }
    return List.copyOf(result);
  }

  public static List<String> requiredStrings(
      Map<String, Object> values, String name, int minimum, int maximum, String pattern) {
    return requiredStrings(values, name, minimum, maximum, Integer.MAX_VALUE, pattern);
  }

  public static List<String> requiredStrings(
      Map<String, Object> values,
      String name,
      int minimum,
      int maximum,
      int maximumLength,
      String pattern) {
    List<String> result = optionalStrings(values, name, maximum, maximumLength, pattern);
    if (result.size() < minimum) {
      throw new McpInputException();
    }
    return result;
  }

  public static McpSchema.CallToolResult error(String code, String detail) {
    Map<String, Object> structured = Map.of("code", code, "detail", detail);
    return McpSchema.CallToolResult.builder()
        .addTextContent(detail)
        .structuredContent(structured)
        .isError(true)
        .build();
  }
}
