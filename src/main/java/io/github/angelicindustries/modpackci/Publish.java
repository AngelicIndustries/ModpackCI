package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Releases a tag: builds what modpack.ini asks for and uploads it to each destination. A tag
 * containing "snapshot" only makes a GitHub prerelease. Release notes come from
 * .changelogs/&lt;tag&gt;.md, or GitHub generates them.
 */
public final class Publish {
    private Publish() {}

    /** alpha or beta when the version says so, release otherwise. */
    public static String channel(String version) {
        String v = version.toLowerCase(java.util.Locale.ROOT);
        return v.contains("alpha") ? "alpha" : v.contains("beta") ? "beta" : "release";
    }

    public static void run(Project project, String tag) {
        boolean snapshot = tag.contains("snapshot");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        Map<Destination, List<ReleaseType>> plan = plan(project.config, snapshot, System.getenv("GITHUB_TOKEN") != null && System.getenv("GITHUB_REPOSITORY") != null);
        String repo = System.getenv("GITHUB_REPOSITORY");
        String ghToken = System.getenv("GITHUB_TOKEN");
        Path override = project.root.resolve(".changelogs").resolve(tag + ".md");
        String notes = Files.exists(override) ? FileUtil.readString(override)
                : ghToken != null && repo != null ? new GitHubApi(repo, ghToken).generateNotes(tag) : "";
        Set<ReleaseType> needed = EnumSet.noneOf(ReleaseType.class);
        plan.values().forEach(needed::addAll);
        Map<ReleaseType, Path> built;
        try (AutoCloseable restore = project.pack().overrideVersion(version)) {
            project.reloadPack();
            built = Builds.build(project, needed);
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail("couldn't set the pack version: " + e.getMessage(), e);
        } finally {
            project.reloadPack();
        }
        String name = project.pack().name;
        for (Map.Entry<Destination, List<ReleaseType>> e : plan.entrySet()) {
            Destination dest = e.getKey();
            List<Map.Entry<ReleaseType, Path>> files = new ArrayList<>();
            for (ReleaseType type : e.getValue()) {
                files.add(Map.entry(type, built.get(type)));
            }
            switch (dest) {
                case SIX_SEVEN -> new LabrinthApi(dest.displayName, LabrinthApi.SIX_SEVEN, project.config.get(dest.tokenKey))
                        .publish(project.config.get(dest.projectKey), name, version, notes, files);
                case MODRINTH -> new LabrinthApi(dest.displayName, LabrinthApi.MODRINTH, project.config.get(dest.tokenKey))
                        .publish(project.config.get(dest.projectKey), name, version, notes, files);
                case CURSEFORGE -> new CurseForgeApi(project.config.get(dest.tokenKey))
                        .upload(project.config.get(dest.projectKey), name, version, notes, files.get(0).getValue());
                case GITHUB -> {
                    GitHubApi gh = new GitHubApi(repo, ghToken);
                    if (gh.releaseExists(tag)) {
                        Log.notice("GitHub release " + tag + " already exists, nothing uploaded");
                    } else {
                        gh.createRelease(tag, name + " " + version, notes, snapshot,
                                files.stream().map(Map.Entry::getValue).toList());
                    }
                }
            }
        }
    }

    /**
     * What goes where, checked before anything is built: every destination with release types
     * needs its project ID and token.
     */
    static Map<Destination, List<ReleaseType>> plan(Config config, boolean snapshot, boolean onGitHub) {
        Map<Destination, List<ReleaseType>> plan = new LinkedHashMap<>();
        for (Destination dest : Destination.values()) {
            List<ReleaseType> types = config.releaseTypes(dest);
            if (dest.projectKey != null && config.get(dest.projectKey) != null && types.isEmpty()) {
                Log.warn(dest.projectKey.name + " is set but " + dest.typesKey.name + " is empty, so nothing goes to "
                        + dest.displayName);
            }
            if (types.isEmpty() || (snapshot && dest != Destination.GITHUB)) {
                continue;
            }
            if (dest == Destination.GITHUB) {
                if (!onGitHub) {
                    throw new Fail(dest.typesKey.name + " is set, but GitHub releases need GITHUB_TOKEN and "
                            + "GITHUB_REPOSITORY, which GitHub Actions provides");
                }
            } else {
                if (config.get(dest.projectKey) == null) {
                    throw new Fail(dest.typesKey.name + " is set but " + dest.projectKey.name + " isn't. Set it in "
                            + Config.SHARED_FILE + ", or empty " + dest.typesKey.name + ".");
                }
                if (config.get(dest.tokenKey) == null) {
                    throw new Fail(dest.typesKey.name + " is set but " + dest.tokenKey.env + " is missing. Add it as a "
                            + "repository secret, or empty " + dest.typesKey.name + ".");
                }
            }
            plan.put(dest, types);
        }
        if (plan.isEmpty()) {
            throw new Fail("nothing to release: every release types key in " + Config.SHARED_FILE + " is empty");
        }
        // External sites first, GitHub last, the same order as before.
        Map<Destination, List<ReleaseType>> ordered = new LinkedHashMap<>(plan);
        if (ordered.containsKey(Destination.GITHUB)) {
            ordered.put(Destination.GITHUB, ordered.remove(Destination.GITHUB));
        }
        return ordered;
    }
}
