import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds and sends an HTML email report after all API tests complete.
 * Email is always sent regardless of pass/fail outcome.
 */
public class EmailReporter {

    public static void send(ApiConfig config, List<ApiResponseTimeIT.ApiResult> results) {

        long totalPassed  = results.stream().filter(ApiResponseTimeIT.ApiResult::passed).count();
        long totalFailed  = results.size() - totalPassed;
        long slowCount    = results.stream().filter(r -> !r.timePassed).count();
        long badStatus    = results.stream().filter(r -> !r.statusPassed).count();
        long errorCount   = results.stream().filter(r -> r.errorMessage != null).count();

        boolean allPassed = totalFailed == 0;
        String overallStatus = allPassed
                ? "✅ PASSED — Merge Allowed"
                : "❌ FAILED — Merge Blocked";

        String runTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // ── Build HTML ────────────────────────────────────────────────────────
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>")
            .append("<style>")
            .append("  body { font-family: Arial, sans-serif; background: #f4f4f4; padding: 20px; }")
            .append("  .container { background: white; border-radius: 8px; padding: 24px; max-width: 900px; margin: auto; }")
            .append("  h2 { margin-top: 0; }")
            .append("  .badge { display:inline-block; padding:6px 16px; border-radius:20px; font-weight:bold; font-size:15px; }")
            .append("  .pass  { background:#d4edda; color:#155724; }")
            .append("  .fail  { background:#f8d7da; color:#721c24; }")
            .append("  .summary { display:flex; gap:16px; margin:16px 0; flex-wrap:wrap; }")
            .append("  .stat { background:#f0f0f0; border-radius:8px; padding:12px 20px; text-align:center; }")
            .append("  .stat .num { font-size:28px; font-weight:bold; }")
            .append("  .stat .lbl { font-size:12px; color:#666; }")
            .append("  table { width:100%; border-collapse:collapse; margin-top:16px; }")
            .append("  th { background:#2d2d2d; color:white; padding:10px 12px; text-align:left; font-size:13px; }")
            .append("  td { padding:9px 12px; border-bottom:1px solid #eee; font-size:13px; }")
            .append("  tr:hover td { background:#fafafa; }")
            .append("  .tag { padding:3px 10px; border-radius:12px; font-size:12px; font-weight:bold; }")
            .append("  .tag-pass   { background:#d4edda; color:#155724; }")
            .append("  .tag-fail   { background:#f8d7da; color:#721c24; }")
            .append("  .tag-slow   { background:#fff3cd; color:#856404; }")
            .append("  .tag-error  { background:#cce5ff; color:#004085; }")
            .append("  .footer { margin-top:20px; font-size:12px; color:#999; text-align:center; }")
            .append("</style></head><body><div class='container'>");

        // Header
        html.append("<h2>🔍 API Response Time Test Report</h2>");
        html.append("<p>Run at: <b>").append(runTime).append("</b> &nbsp;|&nbsp; ")
            .append("Base URL: <b>").append(config.baseUrl).append("</b> &nbsp;|&nbsp; ")
            .append("Threshold: <b>").append(config.thresholdMs).append("ms</b></p>");

        // Overall status badge
        html.append("<span class='badge ").append(allPassed ? "pass" : "fail").append("'>")
            .append(overallStatus).append("</span>");

        // Summary stats
        html.append("<div class='summary'>")
            .append(stat(String.valueOf(results.size()), "Total APIs", "#2d2d2d"))
            .append(stat(String.valueOf(totalPassed),    "Passed",     "#155724"))
            .append(stat(String.valueOf(totalFailed),    "Failed",     "#721c24"))
            .append(stat(String.valueOf(slowCount),      "Slow (>" + config.thresholdMs + "ms)", "#856404"))
            .append(stat(String.valueOf(badStatus),      "Wrong Status", "#004085"))
            .append(stat(String.valueOf(errorCount),     "Unreachable", "#6c757d"))
            .append("</div>");

        // Results table
        html.append("<table>")
            .append("<tr>")
            .append("<th>#</th>")
            .append("<th>API Name</th>")
            .append("<th>Method</th>")
            .append("<th>Endpoint</th>")
            .append("<th>Response Time</th>")
            .append("<th>Expected Status</th>")
            .append("<th>Actual Status</th>")
            .append("<th>Result</th>")
            .append("</tr>");

        int index = 1;
        for (ApiResponseTimeIT.ApiResult r : results) {

            String timeDisplay, timeStyle;
            if (r.errorMessage != null) {
                timeDisplay = "N/A";
                timeStyle   = "color:#6c757d";
            } else if (!r.timePassed) {
                timeDisplay = r.responseTime + "ms ⚠";
                timeStyle   = "color:#856404;font-weight:bold";
            } else {
                timeDisplay = r.responseTime + "ms";
                timeStyle   = "color:#155724";
            }

            String statusDisplay = r.errorMessage != null
                    ? "N/A"
                    : String.valueOf(r.actualStatus);
            String statusStyle = r.statusPassed
                    ? "color:#155724"
                    : "color:#721c24;font-weight:bold";

            String resultTag;
            if (r.errorMessage != null) {
                resultTag = tag("ERROR", "tag-error");
            } else if (r.passed()) {
                resultTag = tag("PASS", "tag-pass");
            } else if (!r.timePassed && !r.statusPassed) {
                resultTag = tag("SLOW + BAD STATUS", "tag-fail");
            } else if (!r.timePassed) {
                resultTag = tag("SLOW", "tag-slow");
            } else {
                resultTag = tag("BAD STATUS", "tag-fail");
            }

            String rowBg = r.passed() ? "" : "background:#fff8f8";

            html.append("<tr style='").append(rowBg).append("'>")
                .append("<td>").append(index++).append("</td>")
                .append("<td><b>").append(r.name).append("</b></td>")
                .append("<td>").append(r.method).append("</td>")
                .append("<td style='font-family:monospace'>").append(r.endpoint).append("</td>")
                .append("<td style='").append(timeStyle).append("'>").append(timeDisplay).append("</td>")
                .append("<td>").append(r.expectedStatus).append("</td>")
                .append("<td style='").append(statusStyle).append("'>").append(statusDisplay).append("</td>")
                .append("<td>").append(resultTag).append("</td>")
                .append("</tr>");

            // Show error message if unreachable
            if (r.errorMessage != null) {
                html.append("<tr style='background:#e8f4fd'>")
                    .append("<td></td>")
                    .append("<td colspan='7' style='color:#004085;font-size:12px'>")
                    .append("⚠ Error: ").append(r.errorMessage)
                    .append("</td></tr>");
            }
        }
        html.append("</table>");

        // Footer
        html.append("<div class='footer'>")
            .append("Generated by API Response Time Tester &nbsp;|&nbsp; ")
            .append("Powered by REST Assured + Maven Failsafe")
            .append("</div>");

        html.append("</div></body></html>");

        // ── Send Email ────────────────────────────────────────────────────────
        Email email = EmailBuilder.startingBlank()
                .from(config.emailReport.from)
                .to(config.emailReport.to)
                .withSubject("API Test Report [" + (allPassed ? "PASSED" : "FAILED") + "] — " + runTime)
                .withHTMLText(html.toString())
                .buildEmail();

        Mailer mailer = MailerBuilder
                .withSMTPServer(
                        config.emailReport.smtpHost,
                        config.emailReport.smtpPort,
                        config.emailReport.smtpUser,
                        config.emailReport.smtpPassword
                )
                .withTransportStrategy(TransportStrategy.SMTP_TLS)
                .buildMailer();

        mailer.sendMail(email);
        System.out.println("📧 Email report sent to: " + config.emailReport.to);
    }

    private static String stat(String number, String label, String color) {
        return "<div class='stat'>"
                + "<div class='num' style='color:" + color + "'>" + number + "</div>"
                + "<div class='lbl'>" + label + "</div>"
                + "</div>";
    }

    private static String tag(String text, String cssClass) {
        return "<span class='tag " + cssClass + "'>" + text + "</span>";
    }
}
