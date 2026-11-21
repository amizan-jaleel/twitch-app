final class CheckJavaSecurityTest {
    private static void check(String version, boolean expected) {
        boolean actual = CheckJavaSecurity.isSupported(Runtime.Version.parse(version));
        if (actual != expected) {
            throw new AssertionError(version + ": expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        check("11.0.33+1", false);
        check("17.0.21.1+2", false);
        check("21.0.12+7", false);
        check("21.0.13+9-LTS", false);
        check("21.0.13+100000002", false);
        check("21.0.13.1", false);
        check("21.0.13.1+1", false);
        check("21.0.13.1+2", true);
        check("21.0.13.1+2-LTS", true);
        check("21.0.13.1+3", true);
        check("21.0.13.2+1", true);
        check("21.0.14+1", true);
        check("21.0.14-ea+20", false);
        check("22.0.2+9", false);
        System.out.println("Java security baseline: 14 version cases passed");
    }
}
