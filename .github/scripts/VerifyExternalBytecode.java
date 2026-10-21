import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** CI-only consumer/generated class-file guard, including descriptors and generic signatures. */
class VerifyExternalBytecode {
  private static final Pattern INTERNAL = Pattern.compile("io[/.]skis[/.](?:[\\w$]+[/.])*internal[/.]");

  public static void main(String[] arguments) throws IOException {
    if (arguments.length == 0) throw new IllegalArgumentException("Expected consumer output directories");
    verifyFixtures();
    for (String argument : arguments) {
      Path directory = Path.of(argument);
      if (!Files.isDirectory(directory)) throw new IllegalStateException("Missing consumer bytecode: " + directory);
      try (var paths = Files.walk(directory)) {
        List<Path> classes = paths.filter(path -> path.toString().endsWith(".class")).toList();
        if (classes.isEmpty()) throw new IllegalStateException("Empty consumer bytecode: " + directory);
        for (Path file : classes) verify(file);
        System.out.println("External bytecode verified: " + classes.size() + " classes in " + directory);
      }
    }
  }

  private static void verify(Path file) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
      if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class file " + file);
      input.readUnsignedShort();
      input.readUnsignedShort();
      int count = input.readUnsignedShort();
      for (int index = 1; index < count; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> {
            if (INTERNAL.matcher(input.readUTF()).find()) {
              throw new IllegalStateException("External/generated class references internal: " + file);
            }
          }
          case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
          case 5, 6 -> { input.skipNBytes(8); index++; }
          case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
          case 15 -> input.skipNBytes(3);
          default -> throw new IOException("Unknown constant-pool tag " + tag + " in " + file);
        }
      }
    }
  }

  private static void verifyFixtures() throws IOException {
    Path temporary = Files.createTempDirectory("skis-external-bytecode-");
    Path hidden = temporary.resolve("Hidden.java");
    Path bad = temporary.resolve("Bad.java");
    Path safe = temporary.resolve("Safe.java");
    Files.writeString(hidden, "package io.skis.fixture.internal; public class Hidden {}");
    Files.writeString(bad, "package example; public class Bad { private java.util.List<io.skis.fixture.internal.Hidden[]> hidden; }");
    Files.writeString(safe, "package example; public class Safe { private java.util.List<String> value; }");
    int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21", "-d",
        temporary.toString(), hidden.toString(), bad.toString(), safe.toString());
    if (result != 0) throw new IllegalStateException("Cannot compile external bytecode fixtures");
    verify(temporary.resolve("example/Safe.class"));
    try {
      verify(temporary.resolve("example/Bad.class"));
    } catch (IllegalStateException expected) {
      return;
    }
    throw new IllegalStateException("Generic internal reference fixture escaped bytecode guard");
  }
}
