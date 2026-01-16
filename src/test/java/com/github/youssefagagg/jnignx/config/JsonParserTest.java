package com.github.youssefagagg.jnignx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonParserTest {

  @Test
  void testParseBoolean() {
    String json = "{\"a\": true, \"b\": false}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    assertEquals(true, result.get("a"));
    assertEquals(false, result.get("b"));
  }

  @Test
  void testParseNumbers() {
    String json = "{\"int\": 123, \"neg\": -456, \"double\": 12.34, \"negDouble\": -56.78}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    assertEquals(123, result.get("int"));
    assertEquals(-456, result.get("neg"));
    assertEquals(12.34, result.get("double"));
    assertEquals(-56.78, result.get("negDouble"));
  }

  @Test
  void testParseMixedArray() {
    String json = "{\"list\": [1, \"two\", true, 3.14]}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    Object listObj = result.get("list");
    assertInstanceOf(List.class, listObj);
    List<?> list = (List<?>) listObj;

    assertEquals(4, list.size());
    assertEquals(1, list.get(0));
    assertEquals("two", list.get(1));
    assertEquals(true, list.get(2));
    assertEquals(3.14, list.get(3));
  }

  @Test
  void testParseNestedObject() {
    String json = "{\"nested\": {\"key\": \"value\"}}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    Object nestedObj = result.get("nested");
    assertInstanceOf(Map.class, nestedObj);
    Map<?, ?> nested = (Map<?, ?>) nestedObj;

    assertEquals("value", nested.get("key"));
  }

  @Test
  void testParseEmptyStructures() {
    String json = "{\"emptyObj\": {}, \"emptyList\": []}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    Object obj = result.get("emptyObj");
    assertInstanceOf(Map.class, obj);
    assertTrue(((Map<?, ?>) obj).isEmpty());

    Object list = result.get("emptyList");
    assertInstanceOf(List.class, list);
    assertTrue(((List<?>) list).isEmpty());
  }

  @Test
  void testParseStringEscapes() {
    String json = "{\"str\": \"Line1\\nLine2\\tTabbed\\\"Quoted\\\"\"}";
    ConfigLoader parser = new ConfigLoader(json);
    Map<String, Object> result = parser.parseObject();

    String str = (String) result.get("str");
    assertEquals("Line1\nLine2\tTabbed\"Quoted\"", str);
  }

  @Test
  void testInvalidJson() {
    assertThrows(IllegalArgumentException.class, () -> {
      new ConfigLoader("{key: value}").parseObject(); // missing quotes
    });

    assertThrows(IllegalArgumentException.class, () -> {
      new ConfigLoader("{\"key\": \"value\"").parseObject(); // missing closing brace
    });

    assertThrows(IllegalArgumentException.class, () -> {
      new ConfigLoader("{\"key\": true").parseObject(); // incomplete
    });

    assertThrows(IllegalArgumentException.class, () -> {
      new ConfigLoader("{\"key\": truo}").parseObject(); // typo boolean
    });
  }
}
