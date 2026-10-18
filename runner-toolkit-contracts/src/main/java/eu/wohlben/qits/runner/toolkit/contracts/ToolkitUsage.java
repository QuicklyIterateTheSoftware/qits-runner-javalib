package eu.wohlben.qits.runner.toolkit.contracts;

import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact.Feature;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Which toolkit calls a daemon's main classes can make</b>, read off their compiled class files:
 * the guard that keeps a daemon's declared features equal to its real use (qits-1163).
 *
 * <p>Every class a class file names — as a class reference, or inside a field or method
 * descriptor — is a UTF-8 entry in its constant pool, so reading the pool is enough: no bytecode
 * library, no class loading, nothing initialised. A toolkit class reached only through another
 * toolkit class (the launcher wiring the bearer) is not in the daemon's pool, which is why the
 * entry points map to every feature they wire:
 *
 * <ul>
 *   <li>{@code RunnerLauncher} — all three: it builds the {@code Telemetry} and runs {@code
 *       RunnerRuntime};
 *   <li>{@code RunnerRuntime} — {@link Feature#REGISTER} and {@link Feature#TOKEN}: it registers
 *       and mints, and ships logs only through a {@code Telemetry} its caller built;
 *   <li>{@code Registration}, {@code Bearer}, {@code Telemetry} — their own feature.
 * </ul>
 */
final class ToolkitUsage {

  private static final Pattern TOOLKIT_CLASS =
      Pattern.compile("eu/wohlben/qits/runner/toolkit/([A-Za-z0-9_]+)(?![A-Za-z0-9_/])");

  /** The toolkit classes that make (or wire) a REST call, and the features each one sends. */
  static final Map<String, Set<Feature>> ENTRY_POINTS =
      Map.of(
          "RunnerLauncher", EnumSet.allOf(Feature.class),
          "RunnerRuntime", EnumSet.of(Feature.REGISTER, Feature.TOKEN),
          "Registration", EnumSet.of(Feature.REGISTER),
          "Bearer", EnumSet.of(Feature.TOKEN),
          "Telemetry", EnumSet.of(Feature.LOG_EXPORT));

  private ToolkitUsage() {}

  /** The features {@code classes} (a directory of class files) can send through the toolkit. */
  static Set<Feature> used(Path classes) {
    Set<Feature> used = EnumSet.noneOf(Feature.class);
    referenced(classes).forEach(name -> used.addAll(ENTRY_POINTS.getOrDefault(name, Set.of())));
    return used;
  }

  /** Every toolkit class the class files under {@code classes} name, by simple name. */
  static Set<String> referenced(Path classes) {
    Set<String> names = new TreeSet<>();
    try (Stream<Path> walk = Files.walk(classes)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
        try (InputStream in = Files.newInputStream(file)) {
          for (String utf8 : utf8Constants(in)) {
            Matcher m = TOOLKIT_CLASS.matcher(utf8);
            while (m.find()) {
              String name = m.group(1);
              int nested = name.indexOf('$');
              names.add(nested < 0 ? name : name.substring(0, nested));
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the class files under " + classes, e);
    }
    return names;
  }

  /** The UTF-8 entries of one class file's constant pool (JVMS 4.4). */
  static List<String> utf8Constants(InputStream classFile) throws IOException {
    DataInputStream in = new DataInputStream(classFile);
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    in.readUnsignedShort(); // minor
    in.readUnsignedShort(); // major
    int count = in.readUnsignedShort();
    List<String> out = new java.util.ArrayList<>();
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> out.add(in.readUTF()); // Utf8: u2 length + modified UTF-8, readUTF's format
        case 3, 4 -> in.skipNBytes(4); // Integer, Float
        case 5, 6 -> {
          in.skipNBytes(8); // Long, Double take two slots
          i++;
        }
        case 7, 8, 16, 19, 20 -> in.skipNBytes(2); // Class, String, MethodType, Module, Package
        case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4); // refs, NameAndType, (Invoke)Dynamic
        case 15 -> in.skipNBytes(3); // MethodHandle
        default -> throw new IOException("unknown constant pool tag " + tag + " at " + i);
      }
    }
    return out;
  }
}
