package eu.wohlben.qits.runner.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RunnerImagesTest {

  @TempDir Path dir;

  private static final String REPO = "registry.example:5000/qits/qits-toy-runner";

  private static List<List<String>> removals(FakeDocker fake) {
    return fake.calls("image").stream().filter(c -> c.get(1).equals("rm")).toList();
  }

  private static RunnerImages images(FakeDocker fake, Optional<String> self) {
    return new RunnerImages(
        Toy.IDENTITY, new RunnerDocker(Toy.IDENTITY, fake.binary(), Duration.ofSeconds(10)), self);
  }

  @Test
  void theLeftoverSweepRemovesOnlyUnusedRunnerImagesAndSparesTheRunningOne() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer(
                "image-ls",
                0,
                String.join(
                    "\n",
                    REPO + ":2026.1.1|sha256:own",
                    "qits/qits-toy-runner:latest|sha256:own",
                    REPO + ":2026.1.0|sha256:old",
                    REPO + ":2026.0.9|sha256:used",
                    "qits/qits-toy-runner:<none>|sha256:dangling",
                    "alpine:3|sha256:alpine",
                    REPO + "-other:1|sha256:other",
                    "registry.example:5000/qits/qits-ci-runner:2026.1.0|sha256:neighbour",
                    ""),
                "")
            .answerFor("inspect", "0123456789ab", 0, "sha256:own\n", "")
            // Every tag resolves, so it is the id belt, not a failed lookup, that spares the own
            // image.
            .answerFor("image-inspect", REPO + ":2026.1.1", 0, "sha256:own\n", "")
            .answerFor("image-inspect", "qits/qits-toy-runner:latest", 0, "sha256:own\n", "")
            .answerFor("image-inspect", REPO + ":2026.1.0", 0, "sha256:old\n", "")
            .answerFor("image-inspect", REPO + ":2026.0.9", 0, "sha256:used\n", "")
            .answerFor("ps", "ancestor=sha256:used", 0, "cafebabe0000\n", "");

    assertEquals(1, images(fake, Optional.of("0123456789ab")).sweepLeftovers());

    assertEquals(List.of(List.of("image", "rm", REPO + ":2026.1.0")), removals(fake));
    assertTrue(
        fake.calls()
            .contains(
                List.of(
                    "image", "ls", "--no-trunc", "--format", "{{.Repository}}:{{.Tag}}|{{.ID}}")));
    assertTrue(
        fake.calls().stream().flatMap(List::stream).noneMatch(a -> a.contains("qits-ci-runner")),
        "a neighbouring runner's image is not even looked at");
  }

  @Test
  void aRunnerThatCannotTellItsOwnImageRemovesNothing() throws Exception {
    FakeDocker fake =
        new FakeDocker(dir)
            .answer("image-ls", 0, REPO + ":2026.1.0|sha256:old\n", "")
            .answer("inspect", 1, "", "No such object");
    images(fake, Optional.of("0123456789ab")).sweepLeftovers();
    images(fake, Optional.empty()).sweepLeftovers();

    assertEquals(List.of(), removals(fake));
    assertEquals(List.of(), fake.calls("image"), "it does not even list them");
  }

  @Test
  void aRunnerRepositoryIsRecognisedUnderAnyRegistryAndNothingElseIs() throws Exception {
    RunnerImages images = images(new FakeDocker(dir), Optional.empty());
    assertTrue(images.isRunnerRepository("qits/qits-toy-runner"));
    assertTrue(images.isRunnerRepository("registry.qits.example/qits/qits-toy-runner"));
    assertFalse(images.isRunnerRepository("registry.qits.example/qits/qits-toy-runner-x"));
    assertFalse(images.isRunnerRepository("registry.qits.example/other/qits-toy-runner2"));
    assertFalse(images.isRunnerRepository("registry.qits.example/qits/qits-ci-runner"));
  }
}
