package com.example.probe;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class of the runtime probes started by scripts/runtime-check.mjs.
 *
 * Log protocol (the runner reads the server's console):
 *   [PROBE] CHECK OK   <what>     a passed check
 *   [PROBE] CHECK FAIL <what>     a failed check
 *   [PROBE] FAILED <error>        the probe threw; the run fails
 *   [PROBE] DONE                  the probe finished this server start (it then stops the server)
 *
 * A run that never logs DONE (crash, hang, a callback that never fired) fails on the runner's timeout.
 *
 * Steps are scheduled on the global region scheduler: on Paper it runs on the main thread (like the Bukkit
 * scheduler), and it also works on Folia, where the Bukkit scheduler is unsupported.
 */
public abstract class Probe extends JavaPlugin {

    /** A step that may throw; every scheduled step goes through {@link #guard}. */
    @FunctionalInterface
    protected interface Step {
        void run() throws Exception;
    }

    private boolean finished;

    @Override
    public final void onEnable() {
        later(startDelay(), this::start);
    }

    /** Ticks to wait after enabling, so the worlds and the other plugins have finished starting. */
    protected long startDelay() {
        return 40L;
    }

    /** Runs the checks for this server start. Call {@link #finish()} when done (possibly from a later step). */
    protected abstract void start() throws Exception;

    protected final void check(String what, boolean ok) {
        getLogger().info("[PROBE] CHECK " + (ok ? "OK   " : "FAIL ") + what);
    }

    protected final void log(String message) {
        getLogger().info("[PROBE] " + message);
    }

    /** Runs {@code step} after {@code ticks} (at least 1) on the main thread (Paper) / global region thread (Folia). */
    protected final void later(long ticks, Step step) {
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> guard(step), Math.max(1L, ticks));
    }

    /** Runs {@code step}; an exception is reported as FAILED and stops the server. */
    protected final void guard(Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            getLogger().severe("[PROBE] FAILED " + t);
            t.printStackTrace();
            finish();
        }
    }

    /** Logs DONE once and stops the server. */
    protected final void finish() {
        if (finished) {
            return;
        }
        finished = true;
        log("DONE");
        getServer().shutdown();
    }
}
