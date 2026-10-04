package eu.wohlben.qits.runner.toolkit;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The decommission helper's job — see {@link Decommission} for the whole arrangement. Run by
 * {@link RunnerLauncher} in helper mode ({@code <envPrefix>DECOMMISSION}), with no settings and no
 * domain: everything it needs arrives in the helper's environment, and everything it does is said
 * through {@code say}, because the helper's {@code docker logs} is the whole record.
 *
 * <p><b>It reaches only what is this runner's</b>: containers and volumes by the exact label
 * filters {@code label=<labelRoot>.process=<id>} and {@code label=<labelRoot>=<id>}, and names that
 * start with {@code <namePrefix>-}. A volume is removed by name, so a labelled one must carry the
 * label and the prefix both. A teardown item, a labelled volume or a state volume of any other name
 * is refused and said, never removed — which is what makes another tenant's builder, network or
 * volume impossible to name here.
 *
 * <p><b>The listings are made through the driver's own argv</b> ({@link DockerArgv#psByLabels},
 * {@link DockerArgv#volumeLs}) on the runner's process seam rather than through the driver's
 * listing methods, which degrade a failed listing to empty: here "could not list" must leave the
 * helper standing with its log, not read as "nothing left".
 */
public final class DecommissionHelper {

  private DecommissionHelper() {}

  /**
   * Remove everything of runner {@code runnerId}, in order, and the helper itself last. Answers the
   * process exit code: 0 when everything went, 1 when anything is left (and then the helper is left
   * too, with this output in its log).
   *
   * @param volume the state volume, or null when there is none to remove
   * @param teardown the domain's own items, each refused unless it starts with {@code
   *     <namePrefix>-}
   * @param self the helper's own container, removed last
   */
  public static int finish(
      RunnerIdentity identity,
      RunnerDocker docker,
      String runnerId,
      String volume,
      List<TeardownItem> teardown,
      Optional<String> self,
      Decommission.Settings settings,
      Consumer<String> say) {
    boolean clean = true;
    String bin = docker.binary();
    RunnerDocker.Result processes = docker.run(LifecycleArgv.psProcess(identity, bin, runnerId));
    if (!processes.ok()) {
      say.accept("could not list runner " + runnerId + "'s containers: " + processes.detail());
      clean = false;
    } else {
      for (String line : processes.stdout().split("\\R")) {
        String id = line.strip().split("\\|", -1)[0].strip();
        if (id.isEmpty()) {
          continue;
        }
        String state =
            SelfUpdate.awaitExit(
                identity, docker, id, settings.exitWaitMillis(), settings.pollMillis());
        clean &= removeContainer(docker, id, "runner container (" + state + ")", say);
      }
    }
    Map<String, String> owned = Map.of(identity.ownerLabel(), runnerId);
    RunnerDocker.Result workloads = docker.run(DockerArgv.psByLabels(bin, owned));
    if (workloads.ok()) {
      for (String id : workloads.stdout().split("\\s+")) {
        if (!id.isBlank()) {
          clean &= removeContainer(docker, id.strip(), "workload container", say);
        }
      }
    } else {
      say.accept("could not list runner " + runnerId + "'s workloads: " + workloads.detail());
      clean = false;
    }
    RunnerDocker.Result volumes = docker.run(DockerArgv.volumeLs(bin, owned));
    if (volumes.ok()) {
      for (String listed : volumes.stdout().split("\\s+")) {
        String name = listed.strip();
        if (name.isEmpty()) {
          continue;
        }
        // A volume is removed by name, and a name this runner does not own is not its to remove
        // whatever label it carries: a label is anybody's to write. Refused, said, and the helper
        // stays with its log — a labelled volume left behind is a question for a person.
        clean &=
            removeVolume(
                docker,
                () -> LifecycleArgv.volumeRm(identity, bin, name),
                name,
                "labelled volume",
                settings,
                say);
      }
    } else {
      say.accept("could not list runner " + runnerId + "'s volumes: " + volumes.detail());
      clean = false;
    }
    for (TeardownItem item : teardown) {
      removeTeardownItem(identity, docker, item, say);
    }
    if (volume != null && !volume.isBlank()) {
      if (!identity.ownsName(volume)) {
        say.accept(
            "refused to remove the state volume "
                + volume
                + ": it does not start with "
                + identity.namePrefix()
                + "-, so it is the operator's");
      } else {
        clean &=
            removeVolume(
                docker,
                () -> LifecycleArgv.volumeRm(identity, bin, volume),
                volume,
                "state volume",
                settings,
                say);
      }
    }
    if (!clean) {
      say.accept("decommission of runner " + runnerId + " is incomplete; this helper stays");
      return 1;
    }
    say.accept("runner " + runnerId + " is decommissioned");
    // Last, and only on success: this is the process being removed. Whatever docker answers, the
    // job is done — an exited helper is the one thing it leaves if this fails.
    self.ifPresent(id -> docker.run(LifecycleArgv.rm(bin, id)));
    return 0;
  }

  /**
   * One of the domain's items: refused unless the name is this runner's, then removed best-effort
   * — an item that would not go is a person's cleanup, not a reason to leave the runner itself
   * undecommissioned.
   */
  private static void removeTeardownItem(
      RunnerIdentity identity, RunnerDocker docker, TeardownItem item, Consumer<String> say) {
    String what = item.type().name().toLowerCase(Locale.ROOT);
    List<String> argv;
    try {
      argv = LifecycleArgv.teardown(identity, docker.binary(), item);
    } catch (IllegalArgumentException refused) {
      say.accept("refused to remove the " + what + " " + item.name() + ": " + refused.getMessage());
      return;
    }
    RunnerDocker.Result gone = docker.run(argv);
    if (gone.ok() || absent(gone.detail())) {
      say.accept("removed the " + what + " " + item.name());
    } else {
      say.accept("could not remove the " + what + " " + item.name() + ": " + gone.detail());
    }
  }

  private static boolean removeContainer(
      RunnerDocker docker, String id, String what, Consumer<String> say) {
    try {
      RunnerDocker.Result gone = docker.run(LifecycleArgv.rm(docker.binary(), id));
      if (gone.ok()) {
        say.accept("removed " + what + " " + id);
        return true;
      }
      say.accept("could not remove " + what + " " + id + ": " + gone.detail());
    } catch (IllegalArgumentException notAnId) {
      say.accept("ignored a line docker ps answered: " + notAnId.getMessage());
      return true;
    }
    return false;
  }

  /**
   * A volume is released a moment after the container using it is removed, so a refusal is
   * retried a few times before it counts. An absent volume is as good as a removed one.
   */
  private static boolean removeVolume(
      RunnerDocker docker,
      java.util.function.Supplier<List<String>> argv,
      String volume,
      String what,
      Decommission.Settings settings,
      Consumer<String> say) {
    List<String> command;
    try {
      command = argv.get();
    } catch (IllegalArgumentException refused) {
      say.accept("refused to remove the " + what + " " + volume + ": " + refused.getMessage());
      return false;
    }
    RunnerDocker.Result gone = null;
    for (int attempt = 0; attempt < Math.max(1, settings.volumeAttempts()); attempt++) {
      gone = docker.run(command);
      if (gone.ok() || gone.detail().toLowerCase(Locale.ROOT).contains("no such volume")) {
        say.accept("removed the " + what + " " + volume);
        return true;
      }
      try {
        Thread.sleep(settings.pollMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    say.accept("could not remove the " + what + " " + volume + ": " + gone.detail());
    return false;
  }

  private static boolean absent(String detail) {
    String text = detail.toLowerCase(Locale.ROOT);
    return text.contains("no such") || text.contains("not found");
  }
}
