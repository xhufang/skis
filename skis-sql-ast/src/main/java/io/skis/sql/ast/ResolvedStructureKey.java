package io.skis.sql.ast;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, value-independent structural key produced after query-block scope resolution.
 *
 * <p>The key contains stable paths, occurrence ordinals, descriptors, and child structure only.
 * Runtime parameter values and JVM object identities never enter it.
 */
public sealed interface ResolvedStructureKey
    permits ResolvedStructureKey.Atom, ResolvedStructureKey.Node {

  /** Returns an unambiguous length-prefixed form suitable as fingerprint input. */
  default String canonicalForm() {
    StringBuilder result = new StringBuilder();
    appendCanonical(this, result);
    return result.toString();
  }

  /** Structural leaf with deterministic attributes. */
  record Atom(String kind, List<String> attributes) implements ResolvedStructureKey {

    public Atom {
      requireKind(kind);
      attributes = copyAttributes(attributes);
    }
  }

  /** Structural node with deterministic attributes and ordered children. */
  record Node(String kind, List<String> attributes, List<ResolvedStructureKey> children)
      implements ResolvedStructureKey {

    public Node {
      requireKind(kind);
      attributes = copyAttributes(attributes);
      Objects.requireNonNull(children, "children");
      children = List.copyOf(children);
      children.forEach(child -> Objects.requireNonNull(child, "structure child"));
    }
  }

  /** Creates an attribute-free structural leaf. */
  static Atom atom(String kind) {
    return new Atom(kind, List.of());
  }

  /** Creates an attribute-free structural node. */
  static Node node(String kind, List<ResolvedStructureKey> children) {
    return new Node(kind, List.of(), children);
  }

  private static void requireKind(String kind) {
    Objects.requireNonNull(kind, "kind");
    if (kind.isBlank()) {
      throw new IllegalArgumentException("structure key kind must not be blank");
    }
  }

  private static List<String> copyAttributes(List<String> values) {
    Objects.requireNonNull(values, "attributes");
    List<String> copy = List.copyOf(values);
    copy.forEach(value -> Objects.requireNonNull(value, "structure attribute"));
    return copy;
  }

  private static void appendCanonical(ResolvedStructureKey key, StringBuilder target) {
    switch (key) {
      case Atom atom -> {
        target.append('A');
        appendText(atom.kind(), target);
        appendAttributes(atom.attributes(), target);
      }
      case Node node -> {
        target.append('N');
        appendText(node.kind(), target);
        appendAttributes(node.attributes(), target);
        target.append(node.children().size()).append(':');
        for (ResolvedStructureKey child : node.children()) {
          appendCanonical(child, target);
        }
      }
    }
  }

  private static void appendAttributes(List<String> attributes, StringBuilder target) {
    target.append(attributes.size()).append(':');
    for (String attribute : attributes) {
      appendText(attribute, target);
    }
  }

  private static void appendText(String value, StringBuilder target) {
    target.append(value.length()).append(':').append(value);
  }
}
