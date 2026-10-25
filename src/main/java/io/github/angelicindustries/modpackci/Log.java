package io.github.angelicindustries.modpackci;

/** Console output. On GitHub Actions, warnings and notices also become run annotations. */
public final class Log {
    private static final boolean GITHUB = "true".equals(System.getenv("GITHUB_ACTIONS"));

    private Log() {}

    public static void info(String message) {
        System.out.println(message);
    }

    public static void warn(String message) {
        System.err.println(GITHUB ? "::warning::" + message : "warning: " + message);
    }

    public static void notice(String message) {
        System.out.println(GITHUB ? "::notice::" + message : message);
    }

    public static boolean onGitHubActions() {
        return GITHUB;
    }
}
