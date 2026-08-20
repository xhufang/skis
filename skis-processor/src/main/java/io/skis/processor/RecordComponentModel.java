package io.skis.processor;

import javax.lang.model.element.RecordComponentElement;

record RecordComponentModel(
    String name,
    RecordComponentElement element,
    PropertyModel property,
    String transientDefaultExpression) {}
