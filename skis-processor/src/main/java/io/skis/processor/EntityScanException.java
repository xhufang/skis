package io.skis.processor;

import javax.lang.model.element.Element;

final class EntityScanException extends Exception {

  private final String code;
  private final Element element;

  EntityScanException(String code, String message, Element element) {
    super(message);
    this.code = code;
    this.element = element;
  }

  String code() {
    return code;
  }

  Element element() {
    return element;
  }
}
