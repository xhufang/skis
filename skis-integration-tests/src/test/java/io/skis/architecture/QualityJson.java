package io.skis.architecture;

import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict reader for the string/object/array policy format; no production dependency. */
final class QualityJson {
  private final StreamTokenizer tokens;
  private int token;

  private QualityJson(String source) throws IOException {
    tokens = new StreamTokenizer(new StringReader(source));
    tokens.resetSyntax();
    tokens.whitespaceChars(0, ' ');
    tokens.quoteChar('"');
    tokens.wordChars('a', 'z');
    next();
  }

  static Map<String, Object> read(Path path) throws IOException {
    QualityJson parser = new QualityJson(Files.readString(path));
    Map<String, Object> result = object(parser.value());
    if (parser.token != StreamTokenizer.TT_EOF) throw new IOException("Trailing JSON: " + path);
    return result;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(Object value) {
    if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
    return (List<Object>) value;
  }

  static String string(Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Expected nonblank JSON string");
    }
    return text;
  }

  private void next() throws IOException { token = tokens.nextToken(); }

  private Object value() throws IOException {
    if (token == '"') { String text = tokens.sval; next(); return text; }
    if (token == '{') {
      Map<String, Object> values = new LinkedHashMap<>();
      next();
      while (token != '}') {
        if (token != '"') throw new IOException("Expected quoted JSON key");
        String key = tokens.sval;
        next();
        if (token != ':') throw new IOException("Expected JSON colon");
        next();
        if (values.putIfAbsent(key, value()) != null) throw new IOException("Duplicate key: " + key);
        if (token == '}') break;
        if (token != ',') throw new IOException("Expected JSON comma");
        next();
        if (token == '}') throw new IOException("Trailing JSON comma");
      }
      next();
      return values;
    }
    if (token == '[') {
      List<Object> values = new ArrayList<>();
      next();
      while (token != ']') {
        values.add(value());
        if (token == ']') break;
        if (token != ',') throw new IOException("Expected JSON comma");
        next();
        if (token == ']') throw new IOException("Trailing JSON comma");
      }
      next();
      return values;
    }
    throw new IOException("Unsupported policy JSON token at line " + tokens.lineno());
  }
}
