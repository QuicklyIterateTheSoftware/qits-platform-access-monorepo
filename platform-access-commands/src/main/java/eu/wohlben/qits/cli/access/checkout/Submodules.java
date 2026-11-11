package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one repository says about its own state and its submodules, the way both {@code qits
 * checkout-daemon} ({@link Checkout}) and {@code qits checkout sync} ({@link Sync}) read it: one
 * reading, so "a tracked change" and "a declared submodule" mean the same thing to both.
 */
final class Submodules {

    private Submodules() {
    }

    /**
     * The tracked changes of one repository, its submodules' insides left out: the modified paths
     * {@code status} reports with {@code --ignore-submodules=all}, and every staged path, a staged
     * gitlink included. Untracked files are not tracked changes.
     */
    static Set<String> trackedChanges(Checkout.Git in) throws CliFailure, InterruptedException {
        Set<String> changed = new LinkedHashSet<>();
        for (String line : in.run("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=all")
                .lines().filter(entry -> !entry.isBlank()).toList()) {
            // The status letters, then the path. The output is stripped, so the first line may have
            // lost the blank that stands for an unchanged index.
            changed.add(line.strip().replaceFirst("^\\S{1,2}\\s+", ""));
        }
        changed.addAll(in.run("diff", "--cached", "--name-only", "--ignore-submodules=none").lines()
                .filter(line -> !line.isBlank()).toList());
        return changed;
    }

    /** Every submodule {@code repo}'s {@code .gitmodules} declares: its name, and its path. */
    static Map<String, String> declared(Path repo, Map<String, String> env) throws CliFailure, InterruptedException {
        Map<String, String> declared = new LinkedHashMap<>();
        Checkout.Git in = new Checkout.Git(repo, env);
        String keys = in.ask("config", "-f", ".gitmodules", "--name-only", "--get-regexp", "^submodule\\..*\\.path$");
        if (keys == null || keys.isBlank()) {
            return declared;
        }
        for (String key : keys.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            String name = key.substring("submodule.".length(), key.length() - ".path".length());
            declared.put(name, in.run("config", "-f", ".gitmodules", "--get", key));
        }
        return declared;
    }

    /** A submodule Git has checked out in {@code repo} has a {@code .git}, a file or a directory. */
    static boolean initialised(Path repo, String sub) {
        return Files.exists(repo.resolve(sub).resolve(".git"));
    }

    /** The commit {@code repo}'s index records for {@code sub}, or null when it carries no gitlink there. */
    static String gitlink(Path repo, String sub, Map<String, String> env) throws CliFailure, InterruptedException {
        String staged = new Checkout.Git(repo, env).ask("ls-files", "--stage", "--", sub);
        if (staged == null || !staged.startsWith("160000 ")) {
            return null;
        }
        String[] fields = staged.split("\\s+");
        return fields.length > 1 ? fields[1] : null;
    }
}
