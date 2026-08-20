package io.skis.processor;

import javax.lang.model.element.Element;

record ProcessingProblem(String code, String message, Element element) {}
