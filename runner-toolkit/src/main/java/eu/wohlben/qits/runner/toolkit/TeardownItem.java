package eu.wohlben.qits.runner.toolkit;

/**
 * A named resource a runner created on its node that its decommission helper must remove too,
 * beyond what carries the runner's own labels — a builder container, its network. Offered by
 * {@link RunnerDomain#teardown()}.
 *
 * <p><b>Refused unless the name starts with the identity's {@code <namePrefix>-}</b>, where the
 * helper removes it: that is what makes CI's "remove the node's buildkitd" impossible to write for a
 * runner that does not own it. The name is checked there, against the identity, rather than here,
 * because an item does not know whose it is.
 *
 * @param type what docker calls the thing, which picks the verb that removes it
 * @param name the resource's exact name — never a filter
 */
public record TeardownItem(Type type, String name) {

  /** What kind of resource an item is. */
  public enum Type {
    CONTAINER,
    VOLUME,
    NETWORK
  }

  public TeardownItem {
    if (type == null) {
      throw new IllegalArgumentException("a teardown item needs a type");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a teardown item needs a name");
    }
  }
}
