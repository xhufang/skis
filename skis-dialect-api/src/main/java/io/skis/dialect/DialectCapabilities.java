package io.skis.dialect;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable set of SQL features supported by a dialect. */
public final class DialectCapabilities {

  private static final DialectCapabilities NONE = new DialectCapabilities(Collections.emptySet());

  private final Set<DialectFeature> features;

  private DialectCapabilities(Set<DialectFeature> features) {
    this.features = features;
  }

  /** Creates an empty capability set. */
  public static DialectCapabilities none() {
    return NONE;
  }

  /** Creates a capability set from the supplied features. */
  public static DialectCapabilities of(DialectFeature... features) {
    Objects.requireNonNull(features, "features");
    if (features.length == 0) {
      return NONE;
    }
    EnumSet<DialectFeature> copy = EnumSet.noneOf(DialectFeature.class);
    for (DialectFeature feature : features) {
      copy.add(Objects.requireNonNull(feature, "feature"));
    }
    return new DialectCapabilities(Collections.unmodifiableSet(copy));
  }

  /** Returns whether the dialect supports a feature. */
  public boolean supports(DialectFeature feature) {
    return features.contains(Objects.requireNonNull(feature, "feature"));
  }

  /** Returns an immutable view of all supported features. */
  public Set<DialectFeature> features() {
    return features;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof DialectCapabilities capabilities
            && features.equals(capabilities.features);
  }

  @Override
  public int hashCode() {
    return features.hashCode();
  }

  @Override
  public String toString() {
    return features.toString();
  }
}
