package io.skis.dialect;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable set of SQL features supported by a dialect. */
public final class DialectCapabilities {

  private static final DialectCapabilities NONE = new DialectCapabilities(Collections.emptySet());

  private final Set<DialectFeature> features;
  private final int version;

  private DialectCapabilities(Set<DialectFeature> features) {
    this.features = features;
    this.version = calculateVersion(features);
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

  /**
   * Returns a compact, deterministic version of this immutable feature set.
   *
   * <p>The version is derived from stable feature names instead of enum or object identity. It is
   * suitable as one component of an in-process plan identity, but it is not collision-free: callers
   * must retain the complete capability set for equality. Callers must not persist it or interpret
   * its numeric ordering.
   */
  public int version() {
    return version;
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

  private static int calculateVersion(Set<DialectFeature> features) {
    int version = 1;
    for (DialectFeature feature : features) {
      version = 31 * version + feature.name().hashCode();
    }
    return version;
  }
}
