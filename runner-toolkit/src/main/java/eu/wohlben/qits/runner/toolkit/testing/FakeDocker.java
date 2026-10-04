package eu.wohlben.qits.runner.toolkit.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A shell script that answers like docker, written into a suite's own temp directory and run by
 * the production process seam — the runner's {@code <envPrefix>DOCKER_BINARY} points at {@link
 * #binary()}, and qits-containers-driver's {@code ContainerProcess} forks it exactly as it would
 * fork docker. So the seam under test is the real one.
 *
 * <p><b>It records every invocation</b> (one record per call, one field per argv element, separated
 * by ASCII unit/record separators so an argument holding a newline survives the round trip), and
 * answers in one of two ways:
 *
 * <ul>
 *   <li><b>Scripted</b>, from files: the answer key is the first argument, or the first two for
 *       {@code image}/{@code network}/{@code volume}, and {@code answers/<key>.code}, {@code .out}
 *       and {@code .err} are the exit code, stdout and stderr ({@link #answer}). A more specific
 *       answer wins when there is one: {@code <key>+<last argument>}, the last argument with
 *       everything outside {@code [A-Za-z0-9._-]} made {@code _} — the object a call is about
 *       ({@link #answerFor}). {@code .sleep} makes the call hang that many seconds ({@link #hang}).
 *   <li><b>Stateful</b>, when nothing is scripted for the call: a small model of containers and
 *       volumes with their labels. {@code run} creates a container (a taken name is docker's own
 *       conflict, exit 125), {@code rm} removes one, {@code ps} lists by exact {@code label=k=v} or
 *       {@code label=k} filters, and {@code volume create}/{@code ls}/{@code rm} do the same for
 *       volumes. Any other filter — a {@code name=} filter in particular — matches nothing, so a
 *       caller that relied on one finds out. Every other verb answers exit 0 and silence.
 * </ul>
 *
 * <p>The state is what a suite with two runners on one node reads afterwards ({@link
 * #containers()}, {@link #volumes()}): what one runner removed, and what it left of the other's.
 *
 * <p>Two runners on one node share one fake and tell their argv apart through a binary each
 * ({@link #binaryFor}, {@link #callsBy}).
 *
 * <p>A leading {@code --config <dir>} (a throwaway registry login) is recorded with what the
 * directory held at that moment ({@link #configs()}), then skipped for the answer.
 *
 * <p>Shipped in the toolkit's main jar for an embedder's suite; no JUnit, POSIX {@code sh} only.
 */
public final class FakeDocker {

  private static final String SCRIPT =
      """
      #!/bin/sh
      dir=$(dirname "$0")
      # One record, written with one printf, so two callers on one node never interleave fields.
      record=$(for a in "$@"; do printf '%s\\037' "$a"; done; printf '\\036')
      printf '%s' "$record" >> "$dir/calls.log"
      [ -n "$FAKE_DOCKER_CALLER" ] && printf '%s' "$record" >> "$dir/calls-$FAKE_DOCKER_CALLER.log"
      if [ "$1" = "--config" ]; then
        { stat -c '%a' "$2" "$2/config.json" | tr '\\n' ' '; printf '\\037'; \\
          cat "$2/config.json"; printf '\\036'; } >> "$dir/configs.log"
        shift 2
      fi
      key="$1"
      case "$1" in image|network|volume) key="$1-$2";; esac
      for last in "$@"; do :; done
      obj=$(printf '%s' "$last" | tr -c 'A-Za-z0-9._-' '_')
      a="$dir/answers/$key+$obj"
      [ -f "$a.code" ] || [ -f "$a.out" ] || [ -f "$a.err" ] || a="$dir/answers/$key"
      [ -f "$a.sleep" ] && sleep "$(cat "$a.sleep")"
      if [ -f "$a.code" ] || [ -f "$a.out" ] || [ -f "$a.err" ]; then
        [ -f "$a.out" ] && cat "$a.out"
        [ -f "$a.err" ] && cat "$a.err" >&2
        code=0
        [ -f "$a.code" ] && code=$(cat "$a.code")
        exit "$code"
      fi

      # Nothing scripted: the state model.
      containers="$dir/state/containers"
      volumes="$dir/state/volumes"
      mkdir -p "$containers" "$volumes"

      # matches <labels file> <filter>...: every filter must hold; an unknown filter never does.
      matches() {
        f="$1"; shift
        for flt in "$@"; do
          case "$flt" in
            label=*=*) grep -Fxq -- "${flt#label=}" "$f" || return 1;;
            label=*) cut -d= -f1 "$f" | grep -Fxq -- "${flt#label=}" || return 1;;
            *) return 1;;
          esac
        done
        return 0
      }

      # list <store> <args...>: the names in a store whose labels match every --filter.
      list() {
        store="$1"; shift
        filters=""
        while [ $# -gt 0 ]; do
          case "$1" in
            --filter|-f) filters="$filters
      $2"; shift 2;;
            --filter=*) filters="$filters
      ${1#--filter=}"; shift;;
            *) shift;;
          esac
        done
        for entry in "$store"/*; do
          [ -d "$entry" ] || continue
          set -f
          IFS='
      '
          # shellcheck disable=SC2086
          if matches "$entry/labels" $filters; then basename "$entry"; fi
          unset IFS
          set +f
        done
      }

      # create <store> <name> <args...>: a new entry with the --label arguments as its labels.
      create() {
        entry="$1/$2"; shift 2
        mkdir -p "$entry"
        : > "$entry/labels"
        while [ $# -gt 0 ]; do
          case "$1" in
            --label|-l) printf '%s\\n' "$2" >> "$entry/labels"; shift 2;;
            --label=*) printf '%s\\n' "${1#--label=}" >> "$entry/labels"; shift;;
            *) shift;;
          esac
        done
      }

      case "$1" in
        run)
          shift
          name=""
          prev=""
          for arg in "$@"; do
            [ "$prev" = "--name" ] && name="$arg"
            case "$arg" in --name=*) name="${arg#--name=}";; esac
            prev="$arg"
          done
          [ -n "$name" ] || name="fake-$$"
          if [ -d "$containers/$name" ]; then
            echo "docker: Error response from daemon: Conflict. The container name \\"/$name\\" is already in use." >&2
            exit 125
          fi
          create "$containers" "$name" "$@"
          printf '%s\\n' "$last" > "$containers/$name/image"
          echo "$name"
          ;;
        ps)
          shift
          list "$containers" "$@"
          ;;
        rm)
          shift
          code=0
          for arg in "$@"; do
            case "$arg" in -*) continue;; esac
            if [ -d "$containers/$arg" ]; then
              rm -rf "$containers/$arg"
            else
              echo "Error response from daemon: No such container: $arg" >&2
              code=1
            fi
          done
          exit "$code"
          ;;
        volume)
          case "$2" in
            create)
              shift 2
              if [ ! -d "$volumes/$last" ]; then create "$volumes" "$last" "$@"; fi
              echo "$last"
              ;;
            ls)
              shift 2
              list "$volumes" "$@"
              ;;
            rm)
              shift 2
              code=0
              for arg in "$@"; do
                case "$arg" in -*) continue;; esac
                if [ -d "$volumes/$arg" ]; then
                  rm -rf "$volumes/$arg"
                else
                  echo "Error response from daemon: get $arg: no such volume" >&2
                  code=1
                fi
              done
              exit "$code"
              ;;
          esac
          ;;
      esac
      exit 0
      """;

  private final Path dir;
  private final String binary;

  /** A fake docker in {@code dir}, a directory of the suite's own. */
  public FakeDocker(Path dir) throws IOException {
    this.dir = dir;
    Files.createDirectories(dir.resolve("answers"));
    Files.createDirectories(dir.resolve("state").resolve("containers"));
    Files.createDirectories(dir.resolve("state").resolve("volumes"));
    Path script = dir.resolve("docker");
    Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    this.binary = script.toString();
  }

  /** The script's path — what {@code <envPrefix>DOCKER_BINARY} is set to. */
  public String binary() {
    return binary;
  }

  /**
   * A docker binary of {@code caller}'s own on this one node: a wrapper that runs {@link #binary()}
   * with {@code FAKE_DOCKER_CALLER=<caller>} set, so every call through it is also recorded under
   * that caller ({@link #callsBy}). Two runners sharing one fake each point their {@code
   * <envPrefix>DOCKER_BINARY} at their own, and the suite can tell whose argv is whose while both
   * see — and change — one node's state. Created once; asking again answers the same path.
   *
   * @param caller {@code [a-z0-9-]+}, the name the suite reads the calls back by
   */
  public String binaryFor(String caller) throws IOException {
    if (caller == null || !caller.matches("[a-z0-9-]+")) {
      throw new IllegalArgumentException("a caller is named [a-z0-9-]+, not '" + caller + "'");
    }
    Path wrapper = dir.resolve("docker-" + caller);
    if (!Files.exists(wrapper)) {
      Files.writeString(
          wrapper,
          "#!/bin/sh\nFAKE_DOCKER_CALLER=" + caller + " exec '" + binary + "' \"$@\"\n",
          StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    return wrapper.toString();
  }

  public Path dir() {
    return dir;
  }

  /** Make {@code key} answer with this exit code, stdout and stderr. */
  public FakeDocker answer(String key, int code, String out, String err) throws IOException {
    Path answers = dir.resolve("answers");
    Files.writeString(answers.resolve(key + ".code"), String.valueOf(code));
    Files.writeString(answers.resolve(key + ".out"), out == null ? "" : out);
    Files.writeString(answers.resolve(key + ".err"), err == null ? "" : err);
    return this;
  }

  /** Make {@code key} answer this way only when its last argument is {@code object}. */
  public FakeDocker answerFor(String key, String object, int code, String out, String err)
      throws IOException {
    return answer(key + "+" + safe(object), code, out, err);
  }

  /** Make {@code key} hang {@code seconds} before it answers, for a deadline. */
  public FakeDocker hang(String key, int seconds) throws IOException {
    Files.writeString(dir.resolve("answers").resolve(key + ".sleep"), String.valueOf(seconds));
    return this;
  }

  /** A container already on the node, with its labels — what an earlier process left. */
  public FakeDocker container(String name, Map<String, String> labels) throws IOException {
    seed(dir.resolve("state").resolve("containers").resolve(name), labels);
    return this;
  }

  /** A volume already on the node, with its labels. */
  public FakeDocker volume(String name, Map<String, String> labels) throws IOException {
    seed(dir.resolve("state").resolve("volumes").resolve(name), labels);
    return this;
  }

  /** The containers on the node now, by name, each with its labels. */
  public Map<String, Map<String, String>> containers() {
    return state("containers");
  }

  /** The volumes on the node now, by name, each with its labels. */
  public Map<String, Map<String, String>> volumes() {
    return state("volumes");
  }

  /** Every call, each as its argv WITHOUT the binary path — so assertions read like docker. */
  public List<List<String>> calls() {
    return records(dir.resolve("calls.log")).stream()
        .map(fields -> List.of(Arrays.copyOf(fields, fields.length - 1)))
        .toList();
  }

  /**
   * Every call made through {@link #binaryFor caller}'s binary, as {@link #calls()} spells them —
   * the argv one tenant of a shared node issued, and only that.
   */
  public List<List<String>> callsBy(String caller) {
    return records(dir.resolve("calls-" + caller + ".log")).stream()
        .map(fields -> List.of(Arrays.copyOf(fields, fields.length - 1)))
        .toList();
  }

  /**
   * The calls whose first argument is {@code verb}, a leading {@code --config <dir>} skipped — so a
   * pull under a throwaway login is still a pull.
   */
  public List<List<String>> calls(String verb) {
    return calls().stream()
        .map(FakeDocker::withoutConfig)
        .filter(c -> !c.isEmpty() && c.getFirst().equals(verb))
        .toList();
  }

  /** {@code call} without a leading {@code --config <dir>}. */
  public static List<String> withoutConfig(List<String> call) {
    return call.size() >= 2 && call.getFirst().equals("--config")
        ? call.subList(2, call.size())
        : call;
  }

  /**
   * What each {@code --config} call found in its directory at the moment docker ran, in call order:
   * {@code [<dir mode> <file mode>, <config.json>]}. Read by the script itself, because the caller
   * deletes the directory as soon as the call is over.
   */
  public List<List<String>> configs() {
    return records(dir.resolve("configs.log")).stream()
        .map(fields -> List.of(fields[0].strip(), fields[1]))
        .toList();
  }

  private static List<String[]> records(Path log) {
    if (!Files.exists(log)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<String[]> records = new ArrayList<>();
    for (String record : text.split("\u001e")) {
      if (!record.isEmpty()) {
        records.add(record.split("\u001f", -1));
      }
    }
    return records;
  }

  private Map<String, Map<String, String>> state(String store) {
    Map<String, Map<String, String>> state = new TreeMap<>();
    try (Stream<Path> entries = Files.list(dir.resolve("state").resolve(store))) {
      for (Path entry : entries.toList()) {
        Map<String, String> labels = new LinkedHashMap<>();
        Path file = entry.resolve("labels");
        if (Files.exists(file)) {
          for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
              labels.put(line.substring(0, eq), line.substring(eq + 1));
            } else if (!line.isEmpty()) {
              labels.put(line, "");
            }
          }
        }
        state.put(entry.getFileName().toString(), labels);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return state;
  }

  private static void seed(Path entry, Map<String, String> labels) throws IOException {
    Files.createDirectories(entry);
    StringBuilder text = new StringBuilder();
    labels.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
    Files.writeString(entry.resolve("labels"), text.toString(), StandardCharsets.UTF_8);
  }

  private static String safe(String object) {
    return object.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
