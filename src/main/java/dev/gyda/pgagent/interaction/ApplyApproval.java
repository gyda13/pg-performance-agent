package dev.gyda.pgagent.interaction;

import org.springframework.stereotype.Component;

/**
 * Human-in-the-loop gate for anything that writes to the database (Phase 3). Every index or
 * ANALYZE is shown — together with its HypoPG <em>estimated</em> improvement, i.e. a preview
 * produced without touching the database — and must be explicitly approved before it runs.
 *
 * This is unconditional by design: a write to the database always requires a person to see the
 * exact statement and say yes. If no console is attached (no input available), it denies — the
 * safe default is to leave the database unchanged rather than apply unattended.
 */
@Component
public class ApplyApproval {

    private final Console console;

    public ApplyApproval(Console console) {
        this.console = console;
    }

    /**
     * @param action          short label, e.g. "index" or "ANALYZE"
     * @param statement       the exact statement that would run (shown to the user verbatim)
     * @param estimatedSpeedup HypoPG estimate to show as a preview, or null if none
     * @return true only if the user approved
     */
    public boolean approve(String action, String statement, Double estimatedSpeedup) {
        console.print("\n──────────── DATABASE WRITE — APPROVAL REQUIRED ────────────");
        console.print("The agent wants to run this " + action + " against the database:");
        console.print("    " + statement);
        if (estimatedSpeedup != null) {
            console.print(String.format(
                    "Preview — HypoPG estimates %.1fx faster (estimated only; nothing applied yet).",
                    estimatedSpeedup));
        }
        boolean ok = console.confirm("Execute this statement now? [y/N] ");
        console.print(ok ? "→ Approved — executing.\n" : "→ Declined — leaving the database unchanged.\n");
        return ok;
    }
}
