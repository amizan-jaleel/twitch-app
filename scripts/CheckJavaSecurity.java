final class CheckJavaSecurity {
    private static final Runtime.Version MINIMUM = Runtime.Version.parse("21.0.13.1+2");

    static boolean isSupported(Runtime.Version version) {
        // Java versions include a fourth component and significant build numbers.
        return version.feature() == 21
            && version.pre().isEmpty()
            && version.compareToIgnoreOptional(MINIMUM) >= 0;
    }

    public static void main(String[] args) {
        var version = Runtime.version();
        System.out.println("Checking JVM security baseline: " + version);
        if (!isSupported(version)) {
            throw new IllegalStateException(
                "Use a stable Temurin Java 21 release at or after " + MINIMUM
            );
        }
    }
}
