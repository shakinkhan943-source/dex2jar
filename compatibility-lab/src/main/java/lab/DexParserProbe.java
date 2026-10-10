package lab;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Standalone reflective probe so each parser dependency is tested in isolation. */
public final class DexParserProbe {
    private DexParserProbe() {}

    public static void main(String[] args) throws Exception {
        String parser = args.length > 0 ? args[0] : System.getProperty("lab.parser", "unknown");
        String fixtureRoot = System.getenv().getOrDefault("DEX_FIXTURE_DIR", "../dex-translator/src/test/resources/dexes");
        Path root = Path.of(fixtureRoot).normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("NO_FIXTURE_DIRECTORY=" + root.toAbsolutePath());
            System.exit(2);
        }

        List<Path> inputs = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dex"))
                .sorted()
                .forEach(inputs::add);
        }
        if (inputs.isEmpty()) {
            System.err.println("NO_DEX_FIXTURES=" + root.toAbsolutePath());
            System.exit(2);
        }

        System.out.println("PARSER=" + parser);
        System.out.println("FIXTURE_ROOT=" + root.toAbsolutePath());
        int passed = 0, failed = 0;
        for (Path input : inputs) {
            String version = readVersion(input);
            try {
                parseWithInstalledLibrary(input.toFile());
                System.out.println("PASS\t" + version + "\t" + input.getFileName());
                passed++;
            } catch (Throwable error) {
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                System.out.println("FAIL\t" + version + "\t" + input.getFileName() + "\t" + cause.getClass().getSimpleName() + ": " + safeMessage(cause));
                failed++;
            }
        }
        System.out.println("SUMMARY\tpassed=" + passed + "\tfailed=" + failed + "\ttotal=" + inputs.size());
        if (passed == 0 || failed > 0) System.exit(1);
    }

    private static void parseWithInstalledLibrary(File file) throws Exception {
        String packageName = System.getProperty("lab.dexlib.package", "org.jf.dexlib2");
        Class<?> opcodesClass = Class.forName(packageName + ".Opcodes");
        Object opcodes;
        try {
            // Upstream dexlib2 exposes getDefault() as a static method.
            opcodes = opcodesClass.getMethod("getDefault").invoke(null);
        } catch (NoSuchMethodException missingDefault) {
            // Ksmali 5.x exposes forApi(int) instead of the upstream getDefault() API.
            opcodes = opcodesClass.getMethod("forApi", int.class).invoke(null, 35);
        }
        Class<?> factoryClass = Class.forName(packageName + ".DexFileFactory");
        Method load = factoryClass.getMethod("loadDexFile", File.class, opcodesClass);
        Object dexFile = load.invoke(null, file, opcodes);
        if (dexFile == null) throw new IllegalStateException("Parser returned null");
        Object classes = dexFile.getClass().getMethod("getClasses").invoke(dexFile);
        if (!(classes instanceof Iterable<?> iterable)) throw new IllegalStateException("No iterable class collection");
        int count = 0;
        for (Object ignored : iterable) count++;
        System.out.println("  parsedClasses=" + count);
    }

    private static String readVersion(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 8 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n') return "INVALID_MAGIC";
            return new String(bytes, 4, 3, java.nio.charset.StandardCharsets.US_ASCII);
        } catch (IOException e) {
            return "UNREADABLE";
        }
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null) return "(no message)";
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
