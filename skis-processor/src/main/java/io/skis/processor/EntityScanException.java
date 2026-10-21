package io.skis.processor;

import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

final class EntityScanException extends Exception {

  private final String code;
  private final @Nullable Element element;

  EntityScanException(String code, String message, @Nullable Element element) {
    super(message);
    this.code = code;
    this.element = element;
  }

  String code() {
    return code;
  }

  @Nullable Element element() {
    return element;
  }
}
