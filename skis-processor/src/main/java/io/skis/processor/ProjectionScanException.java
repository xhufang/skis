package io.skis.processor;

import javax.lang.model.element.Element;

final class ProjectionScanException extends Exception {

  private final String code;
  private final Element element;

  ProjectionScanException(String code, String message, Element element) {
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
