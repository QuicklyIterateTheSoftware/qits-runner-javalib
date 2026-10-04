package eu.wohlben.qits.runner.toolkit.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>A server links two jars and nothing else.</b> A server that renders the install script pins
 * both {@code eu.wohlben.qits:qits-runner-toolkit} and {@code eu.wohlben.qits:qits-runner-protocol}
 * with a {@code *:*} exclusion each, so neither Vert.x, nor the containers driver, nor jboss-logging
 * reaches a Quarkus service. That only works if everything the server can reach — this {@code
 * install} package and {@link RunnerIdentity} — refers to {@code java.*}, to each other, and to
 * classes of {@code eu.wohlben.qits.runner.protocol} (the dependency-free jar, which {@code
 * RunnerIdentity}'s {@code RunnerVocabulary} lives in) and to nothing else.
 *
 * <p>Checked on the compiled class files, not the sources: an import is neither necessary (a
 * fully-qualified name needs none) nor sufficient (a compile-time constant is inlined and leaves no
 * reference at all), while the constant pool is what the JVM actually resolves. Every class name in
 * it, and every type named in a descriptor or generic signature, counts. And <b>transitively</b>:
 * any other toolkit or protocol class the closure reaches is followed and held to the same rule, so
 * a helper {@code RunnerIdentity} grows one day cannot quietly drag Vert.x back in.
 */
class InstallPackageIsDependencyFreeTest {

  private static final String TOOLKIT = "eu/wohlben/qits/runner/toolkit/";
  private static final String PROTOCOL = "eu/wohlben/qits/runner/protocol/";
  private static final String INSTALL = TOOLKIT + "install/";

  /** {@code Lpkg/Name;} or {@code Lpkg/Name<} inside a descriptor or a generic signature. */
  private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([A-Za-z0-9_$/]+)[;<]");

  @Test
  void theInstallPackageAndRunnerIdentityReferOnlyToJavaEachOtherAndTheProtocol() throws Exception {
    Set<String> roots = new TreeSet<>(installClasses());
    roots.add(internal(RunnerIdentity.class));
    assertTrue(roots.contains(internal(InstallScript.class)), roots.toString());
    assertTrue(roots.contains(internal(InstallScript.InstallPage.class)), roots.toString());

    TreeMap<String, Set<String>> violations = new TreeMap<>();
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      String type = queue.pop();
      if (!seen.add(type)) {
        continue;
      }
      for (String reference : references(type)) {
        if (reference.startsWith("java/")) {
          continue;
        }
        if (reference.startsWith(TOOLKIT) || reference.startsWith(PROTOCOL)) {
          queue.add(reference);
          continue;
        }
        violations.computeIfAbsent(type, k -> new TreeSet<>()).add(reference);
      }
    }

    assertEquals(
        new TreeMap<>(), violations, "classes a server loads refer outside java.* and the protocol");
    // The rule held for what was actually reached — and that includes the protocol's vocabulary.
    assertTrue(seen.contains(PROTOCOL + "RunnerVocabulary"), seen.toString());
  }

  @Test
  void theScanSeesAReferenceOutsideTheRule() throws Exception {
    // The scan itself, against a class known to link Vert.x: an empty answer would pass anything.
    Set<String> references = references("eu/wohlben/qits/runner/toolkit/SelfSpec");
    assertTrue(references.contains("io/vertx/core/json/JsonObject"), references.toString());
    assertFalse(references(internal(InstallScript.class)).isEmpty());
  }

  /** Every {@code .class} in the {@code install} package, as compiled into the main output. */
  private static Set<String> installClasses() throws IOException, URISyntaxException {
    Path root =
        Path.of(InstallScript.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path dir = root.resolve(INSTALL);
    Set<String> out = new TreeSet<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".class")) {
          out.add(INSTALL + name.substring(0, name.length() - ".class".length()));
        }
      }
    }
    return out;
  }

  /**
   * Every class a compiled class refers to: its {@code CONSTANT_Class} entries (array descriptors
   * unwrapped) and every type named in a {@code CONSTANT_Utf8} that is not a string literal —
   * field and method descriptors, generic signatures, record components.
   */
  static Set<String> references(String type) throws IOException {
    try (InputStream raw =
        InstallPackageIsDependencyFreeTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
      assertNotNull(raw, type + ".class is not on the classpath");
      DataInputStream in = new DataInputStream(raw);
      if (in.readInt() != 0xCAFEBABE) {
        throw new IOException(type + " is not a class file");
      }
      in.readUnsignedShort();
      in.readUnsignedShort();
      int count = in.readUnsignedShort();
      String[] utf8 = new String[count];
      Set<Integer> classNames = new HashSet<>();
      Set<Integer> literals = new HashSet<>();
      for (int i = 1; i < count; i++) {
        int tag = in.readUnsignedByte();
        switch (tag) {
          case 1 -> utf8[i] = in.readUTF();
          case 7 -> classNames.add(in.readUnsignedShort());
          case 8 -> literals.add(in.readUnsignedShort());
          case 16, 19, 20 -> in.readUnsignedShort(); // MethodType, Module, Package
          case 15 -> {
            in.readUnsignedByte();
            in.readUnsignedShort();
          }
          case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
          case 5, 6 -> {
            in.readLong();
            i++; // a long or a double takes two slots
          }
          default -> throw new IOException(type + ": unknown constant pool tag " + tag);
        }
      }
      Set<String> out = new TreeSet<>();
      for (int index : classNames) {
        String name = utf8[index];
        if (name.startsWith("[")) {
          descriptorTypes(name, out);
        } else {
          out.add(name);
        }
        literals.add(index);
      }
      for (int i = 1; i < count; i++) {
        if (utf8[i] != null && !literals.contains(i)) {
          descriptorTypes(utf8[i], out);
        }
      }
      out.remove(type);
      return out;
    }
  }

  private static void descriptorTypes(String text, Set<String> out) {
    Matcher matcher = DESCRIPTOR_TYPE.matcher(text);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (name.contains("/")) {
        out.add(name);
      }
    }
  }

  private static String internal(Class<?> type) {
    return type.getName().replace('.', '/');
  }
}
