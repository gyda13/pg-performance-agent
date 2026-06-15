package dev.gyda.pgagent;

import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.model.AgentRunResult;
import dev.gyda.pgagent.model.Classification;
import dev.gyda.pgagent.model.Confidence;
import dev.gyda.pgagent.model.Finding;
import dev.gyda.pgagent.model.QueryTrend;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EmailReporter {

    private static final Logger log = LoggerFactory.getLogger(EmailReporter.class);

    private final JavaMailSender mailSender;
    private final AgentProperties props;
    private final String senderEmail;

    // JavaMailSender is Optional so the app starts even when mail is not configured.
    public EmailReporter(Optional<JavaMailSender> mailSender,
                         AgentProperties props,
                         @Value("${spring.mail.username:}") String senderEmail) {
        this.mailSender = mailSender.orElse(null);
        this.props = props;
        this.senderEmail = senderEmail;
    }

    public void send(AgentRunResult result) {
        String recipient = props.getReport().getRecipientEmail();
        if (!StringUtils.hasText(recipient)) {
            log.info("REPORT_RECIPIENT_EMAIL not set — skipping email report.");
            return;
        }
        if (mailSender == null || !StringUtils.hasText(senderEmail)) {
            log.warn("Mail not configured (MAIL_USERNAME / MAIL_PASSWORD) — skipping email report.");
            return;
        }

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(senderEmail);
            helper.setTo(recipient);
            helper.setSubject(buildSubject(result.findings()));
            helper.setText(buildPlainText(result), buildHtml(result));
            mailSender.send(msg);
            log.info("Report sent to {}.", recipient);
        } catch (Exception e) {
            log.warn("Failed to send report: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ subject

    private static String buildSubject(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "Postgres Performance Report — no findings — " + LocalDate.now();
        }
        long db    = count(findings, Classification.DB_PROBLEM);
        long app   = count(findings, Classification.APP_PROBLEM);
        long mixed = count(findings, Classification.MIXED);
        return "Postgres Performance Report — " + findings.size() + " finding"
                + (findings.size() == 1 ? "" : "s")
                + " (" + db + " DB / " + app + " app / " + mixed + " mixed) — " + LocalDate.now();
    }

    // ------------------------------------------------------------------ HTML body

    private static String buildHtml(AgentRunResult result) {
        List<Finding> findings = result.findings();
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style=\"margin:0;padding:0;background:#f3f4f6;\">")
          .append("<div style=\"font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;")
          .append("color:#1f2937;max-width:860px;margin:0 auto;padding:24px 16px;\">");

        sb.append("<h1 style=\"font-size:20px;margin:0 0 4px;\">Postgres Performance Report</h1>")
          .append("<p style=\"color:#6b7280;margin:0 0 20px;font-size:13px;\">Generated ")
          .append(LocalDate.now());
        if (result.windowStart() != null) {
            sb.append(" · window: activity since <strong>").append(result.windowStart()).append("</strong>");
        }
        sb.append(" — all numbers measured from pg_stat_statements and EXPLAIN ANALYZE, never estimated by the LLM.</p>");

        if (findings.isEmpty()) {
            sb.append("<p style=\"font-size:14px;\">No queries above the configured thresholds")
              .append(result.windowStart() != null ? " in this window." : ".")
              .append(" Nothing to fix.</p>");
            appendResolvedHtml(sb, result.resolvedQueries());
            sb.append("</div></body></html>");
            return sb.toString();
        }

        appendCounters(sb, findings);
        appendSummaryTable(sb, findings, result.trends());

        appendSection(sb, "Fix in the database",
                "DDL or configuration changes, verifiable by the agent.",
                "#1d4ed8", filter(findings, Classification.DB_PROBLEM), result.trends());
        appendSection(sb, "Fix in your code",
                "ORM / application changes — the database is doing what it was asked.",
                "#b45309", filter(findings, Classification.APP_PROBLEM), result.trends());
        appendSection(sb, "Fix in both layers",
                "Findings that need a database change and an application change.",
                "#7c3aed", filter(findings, Classification.MIXED), result.trends());

        appendResolvedHtml(sb, result.resolvedQueries());

        sb.append("<p style=\"color:#9ca3af;font-size:12px;margin-top:24px;\">")
          .append("Application-side findings are reported with evidence but marked unverified — ")
          .append("code changes cannot be benchmarked by this agent.</p>")
          .append("</div></body></html>");
        return sb.toString();
    }

    private static void appendResolvedHtml(StringBuilder sb, List<String> resolved) {
        if (resolved.isEmpty()) return;
        sb.append("<h2 style=\"font-size:16px;margin:28px 0 2px;color:#15803d;\">Resolved since last run (")
          .append(resolved.size()).append(")</h2>")
          .append("<p style=\"color:#6b7280;font-size:12px;margin:0 0 12px;\">")
          .append("Previous findings no longer above the thresholds in this window — fixed, or not executed.</p>")
          .append("<ul style=\"margin:0;padding-left:18px;\">");
        for (String q : resolved) {
            sb.append("<li style=\"font-family:ui-monospace,Menlo,Consolas,monospace;")
              .append("font-size:12px;color:#4b5563;margin-bottom:4px;\">")
              .append(escape(abbreviate(q, 100))).append("</li>");
        }
        sb.append("</ul>");
    }

    private static void appendCounters(StringBuilder sb, List<Finding> findings) {
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;margin-bottom:20px;\"><tr>");
        counterCell(sb, String.valueOf(findings.size()), "total findings", "#1f2937");
        counterCell(sb, String.valueOf(count(findings, Classification.DB_PROBLEM)), "database", "#1d4ed8");
        counterCell(sb, String.valueOf(count(findings, Classification.APP_PROBLEM)), "application", "#b45309");
        counterCell(sb, String.valueOf(count(findings, Classification.MIXED)), "mixed", "#7c3aed");
        counterCell(sb, String.valueOf(findings.stream().filter(Finding::verified).count()), "verified", "#15803d");
        sb.append("</tr></table>");
    }

    private static void counterCell(StringBuilder sb, String number, String label, String color) {
        sb.append("<td style=\"background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;")
          .append("padding:10px 6px;text-align:center;width:20%;\">")
          .append("<div style=\"font-size:22px;font-weight:700;color:").append(color).append(";\">")
          .append(number).append("</div>")
          .append("<div style=\"font-size:11px;color:#6b7280;text-transform:uppercase;\">")
          .append(label).append("</div></td><td style=\"width:8px;\"></td>");
    }

    private static void appendSummaryTable(StringBuilder sb, List<Finding> findings,
                                           Map<String, QueryTrend> trends) {
        sb.append("<h2 style=\"font-size:15px;margin:0 0 8px;\">Summary</h2>")
          .append("<table cellpadding=\"6\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;")
          .append("background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;font-size:12px;margin-bottom:24px;\">")
          .append("<tr style=\"background:#f9fafb;color:#374151;text-align:left;\">")
          .append("<th style=\"padding:8px;\">#</th><th>Pathology</th><th>Layer</th><th>Confidence</th><th>Trend</th><th>Query</th>")
          .append("<th style=\"text-align:right;\">Calls</th><th style=\"text-align:right;\">Mean&nbsp;ms</th>")
          .append("<th style=\"text-align:right;\">Total&nbsp;ms</th><th>Status</th></tr>");

        int i = 0;
        for (Finding f : findings) {
            i++;
            sb.append("<tr style=\"border-top:1px solid #e5e7eb;\">")
              .append("<td style=\"padding:8px;color:#9ca3af;\">").append(i).append("</td>")
              .append("<td>").append(badge(f.pathology().name(), "#374151", "#e5e7eb")).append("</td>")
              .append("<td>").append(classificationBadge(f.classification())).append("</td>")
              .append("<td>").append(confidenceBadge(f.confidence())).append("</td>")
              .append("<td>").append(trendBadge(trends.get(f.query().queryText()))).append("</td>")
              .append("<td style=\"font-family:ui-monospace,Menlo,Consolas,monospace;color:#4b5563;\">")
              .append(escape(abbreviate(f.query().queryText(), 60))).append("</td>")
              .append("<td style=\"text-align:right;\">").append(f.query().calls()).append("</td>")
              .append("<td style=\"text-align:right;\">").append(fmt(f.query().meanTimeMs())).append("</td>")
              .append("<td style=\"text-align:right;\">").append(fmt(f.query().totalTimeMs())).append("</td>")
              .append("<td>").append(statusHtml(f)).append("</td>")
              .append("</tr>");
        }
        sb.append("</table>");
    }

    private static void appendSection(StringBuilder sb, String title, String subtitle,
                                      String color, List<Finding> group,
                                      Map<String, QueryTrend> trends) {
        if (group.isEmpty()) return;

        sb.append("<h2 style=\"font-size:16px;margin:28px 0 2px;color:").append(color).append(";\">")
          .append(escape(title)).append(" (").append(group.size()).append(")</h2>")
          .append("<p style=\"color:#6b7280;font-size:12px;margin:0 0 12px;\">").append(escape(subtitle)).append("</p>");

        for (Finding f : group) {
            sb.append("<div style=\"background:#ffffff;border:1px solid #e5e7eb;border-left:4px solid ")
              .append(color).append(";border-radius:8px;padding:14px 16px;margin-bottom:14px;\">");

            // Title row: pathology + trend + per-query metrics
            sb.append("<div style=\"margin-bottom:8px;\">")
              .append(badge(f.pathology().name(), "#ffffff", color))
              .append(" ").append(confidenceBadge(f.confidence()));
            String trend = trendBadge(trends.get(f.query().queryText()));
            if (!trend.isEmpty()) {
                sb.append(" ").append(trend);
            }
            sb.append("<span style=\"color:#6b7280;font-size:12px;margin-left:8px;\">")
              .append("calls ").append(f.query().calls())
              .append(" · mean ").append(fmt(f.query().meanTimeMs())).append(" ms")
              .append(" · total ").append(fmt(f.query().totalTimeMs())).append(" ms")
              .append(" · rows/call ").append(fmt(rowsPerCall(f)))
              .append("</span></div>");

            // Query
            sb.append("<pre style=\"background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;")
              .append("padding:10px;font-size:12px;white-space:pre-wrap;word-break:break-word;margin:0 0 10px;\">")
              .append(escape(oneLine(f.query().queryText()))).append("</pre>");

            field(sb, "Root cause", f.rootCause());
            field(sb, "Evidence", f.evidence());
            field(sb, "Proposed fix", f.proposedFix());
            field(sb, "Tradeoffs", f.tradeoffs());

            // Verification block
            sb.append("<div style=\"background:").append(f.verified() ? "#f0fdf4" : "#f9fafb")
              .append(";border-radius:6px;padding:8px 10px;font-size:12px;margin-top:10px;\">")
              .append("<strong>").append(statusHtml(f)).append("</strong>");
            if (f.hypoTest() != null) {
                sb.append("<br>HypoPG estimate: plan cost ").append(fmt(f.hypoTest().costBefore()))
                  .append(" → ").append(fmt(f.hypoTest().costAfter()))
                  .append(" (").append(fmt(f.hypoTest().estimatedSpeedup())).append("× estimated)");
            }
            if (f.delta() != null) {
                sb.append("<br>Measured: ").append(fmt(f.beforeMs())).append(" ms → ")
                  .append(fmt(f.afterMs())).append(" ms (delta ").append(fmt(f.delta())).append(" ms");
                if (f.afterMs() != null && f.afterMs() > 0) {
                    sb.append(", ").append(fmt(f.beforeMs() / f.afterMs())).append("× speedup");
                }
                sb.append(")");
            }
            sb.append("</div></div>");
        }
    }

    private static void field(StringBuilder sb, String label, String value) {
        if (!StringUtils.hasText(value)) return;
        sb.append("<p style=\"font-size:13px;margin:0 0 6px;\"><strong style=\"color:#374151;\">")
          .append(label).append(":</strong> ").append(escape(value)).append("</p>");
    }

    private static String trendBadge(QueryTrend trend) {
        if (trend == null) return "";
        return switch (trend) {
            case NEW       -> badge("NEW", "#ffffff", "#15803d");
            case RECURRING -> badge("RECURRING", "#ffffff", "#6b7280");
        };
    }

    private static String confidenceBadge(Confidence c) {
        if (c == null) return "";
        return switch (c) {
            case HIGH   -> badge("HIGH", "#ffffff", "#15803d");
            case MEDIUM -> badge("MEDIUM", "#ffffff", "#b45309");
            case LOW    -> badge("LOW", "#ffffff", "#9ca3af");
        };
    }

    private static String classificationBadge(Classification c) {
        return switch (c) {
            case DB_PROBLEM  -> badge("DB", "#ffffff", "#1d4ed8");
            case APP_PROBLEM -> badge("APP", "#ffffff", "#b45309");
            case MIXED       -> badge("MIXED", "#ffffff", "#7c3aed");
        };
    }

    private static String badge(String text, String fg, String bg) {
        return "<span style=\"background:" + bg + ";color:" + fg
                + ";border-radius:4px;padding:2px 6px;font-size:11px;font-weight:600;\">"
                + escape(text) + "</span>";
    }

    private static String statusHtml(Finding f) {
        if (f.verified() && f.delta() != null) {
            return "✅ Verified — measured improvement";
        }
        if (f.hypoTest() != null) {
            return "Estimated " + fmt(f.hypoTest().estimatedSpeedup()) + "× (HypoPG, not yet applied)";
        }
        if (f.classification() == Classification.APP_PROBLEM) {
            return "Not applicable — fix is application-side";
        }
        return "Not verified";
    }

    // ------------------------------------------------------------------ plain-text fallback

    private static String buildPlainText(AgentRunResult result) {
        List<Finding> findings = result.findings();
        StringBuilder sb = new StringBuilder();
        sb.append("Postgres Performance Agent Report — ").append(LocalDate.now()).append("\n");
        if (result.windowStart() != null) {
            sb.append("Window: activity since ").append(result.windowStart()).append("\n");
        }
        sb.append("\n");

        if (findings.isEmpty()) {
            sb.append("No queries above the configured thresholds")
              .append(result.windowStart() != null ? " in this window" : "").append(".\n");
            appendPlainResolved(sb, result.resolvedQueries());
            return sb.toString();
        }

        sb.append("Findings: ").append(findings.size())
          .append(" (").append(count(findings, Classification.DB_PROBLEM)).append(" database, ")
          .append(count(findings, Classification.APP_PROBLEM)).append(" application, ")
          .append(count(findings, Classification.MIXED)).append(" mixed)\n");

        appendPlainGroup(sb, "FIX IN THE DATABASE", filter(findings, Classification.DB_PROBLEM), result.trends());
        appendPlainGroup(sb, "FIX IN YOUR CODE", filter(findings, Classification.APP_PROBLEM), result.trends());
        appendPlainGroup(sb, "FIX IN BOTH LAYERS", filter(findings, Classification.MIXED), result.trends());
        appendPlainResolved(sb, result.resolvedQueries());
        return sb.toString();
    }

    private static void appendPlainResolved(StringBuilder sb, List<String> resolved) {
        if (resolved.isEmpty()) return;
        sb.append("\n").append("=".repeat(60)).append("\n")
          .append("RESOLVED SINCE LAST RUN (").append(resolved.size()).append(")\n")
          .append("=".repeat(60)).append("\n");
        for (String q : resolved) {
            sb.append("  ").append(abbreviate(q, 100)).append("\n");
        }
    }

    private static void appendPlainGroup(StringBuilder sb, String header, List<Finding> group,
                                         Map<String, QueryTrend> trends) {
        if (group.isEmpty()) return;

        sb.append("\n").append("=".repeat(60)).append("\n")
          .append(header).append(" (").append(group.size()).append(")\n")
          .append("=".repeat(60)).append("\n");

        int i = 0;
        for (Finding f : group) {
            i++;
            QueryTrend trend = trends.get(f.query().queryText());
            sb.append("\n[").append(i).append("] ").append(f.pathology())
              .append(f.confidence() != null ? " (" + f.confidence() + " confidence)" : "")
              .append(trend != null ? " [" + trend + "]" : "")
              .append(" — ").append(abbreviate(f.query().queryText(), 80)).append("\n")
              .append("    calls=").append(f.query().calls())
              .append("  mean=").append(fmt(f.query().meanTimeMs())).append(" ms")
              .append("  total=").append(fmt(f.query().totalTimeMs())).append(" ms")
              .append("  rows/call=").append(fmt(rowsPerCall(f))).append("\n")
              .append("    Root cause:   ").append(f.rootCause()).append("\n")
              .append("    Evidence:     ").append(f.evidence()).append("\n")
              .append("    Proposed fix: ").append(f.proposedFix()).append("\n")
              .append("    Tradeoffs:    ").append(f.tradeoffs()).append("\n");
            if (f.hypoTest() != null) {
                sb.append("    HypoPG:       cost ").append(fmt(f.hypoTest().costBefore()))
                  .append(" -> ").append(fmt(f.hypoTest().costAfter()))
                  .append(" (est. ").append(fmt(f.hypoTest().estimatedSpeedup())).append("x)\n");
            }
            if (f.delta() != null) {
                sb.append("    Measured:     ").append(fmt(f.beforeMs())).append(" ms -> ")
                  .append(fmt(f.afterMs())).append(" ms (delta ").append(fmt(f.delta())).append(" ms)\n");
            }
            sb.append("    Verified:     ").append(f.verified()).append("\n");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<Finding> filter(List<Finding> all, Classification c) {
        return all.stream().filter(f -> f.classification() == c).toList();
    }

    private static long count(List<Finding> all, Classification c) {
        return all.stream().filter(f -> f.classification() == c).count();
    }

    private static double rowsPerCall(Finding f) {
        return f.query().calls() > 0 ? (double) f.query().rows() / f.query().calls() : 0;
    }

    private static String escape(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String fmt(Double d) {
        return d == null ? "-" : String.format("%.2f", d);
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String s, int max) {
        String oneLine = oneLine(s);
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
