package eu.wohlben.qits.runner.toolkit.contracts.usage;

import eu.wohlben.qits.runner.toolkit.Bearer;
import io.vertx.core.Future;

/** A class that mints through the toolkit's bearer and nothing else. */
public final class UsesBearer {
  private UsesBearer() {}

  public static Future<String> mint(Bearer bearer) {
    return bearer.token();
  }
}
