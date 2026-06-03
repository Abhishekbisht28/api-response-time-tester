import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.File;
import java.util.List;
import java.util.Properties;

/**
 * Sends API execution email with ExtentReport.html attached.
 */
public class EmailReporter {

    private static final String EXTENT_REPORT_PATH = "target/ExtentReport.html";

    public static void send(ApiConfig config, List<ApiResponseTimeIT.ApiResult> results) throws Exception {

        if (config.emailReport == null ||
                config.emailReport.smtpUser == null ||
                config.emailReport.smtpPassword == null ||
                config.emailReport.to == null) {
            System.out.println("Email config missing. Skipping email report.");
            return;
        }

        long total = results.size();
        long passed = results.stream().filter(ApiResponseTimeIT.ApiResult::passed).count();
        long failed = total - passed;
        long slow = results.stream()
                .filter(r -> r.errorMessage == null)
                .filter(r -> !r.timePassed)
                .count();
        long wrongStatus = results.stream()
                .filter(r -> r.errorMessage == null)
                .filter(r -> !r.statusPassed)
                .count();
        long unreachable = results.stream()
                .filter(r -> r.errorMessage != null)
                .count();

        String subject = "API Test Report [" + (failed > 0 ? "FAILED" : "PASSED") + "]";

        String htmlBody =
                "<html><body>" +
                        "<h2>API Response Time Test Report</h2>" +
                        "<table border='1' cellpadding='8' cellspacing='0'>" +
                        "<tr><th>Total APIs</th><th>Passed</th><th>Failed</th><th>Slow</th><th>Wrong Status</th><th>Unreachable</th></tr>" +
                        "<tr>" +
                        "<td>" + total + "</td>" +
                        "<td style='color:green'>" + passed + "</td>" +
                        "<td style='color:red'>" + failed + "</td>" +
                        "<td>" + slow + "</td>" +
                        "<td>" + wrongStatus + "</td>" +
                        "<td>" + unreachable + "</td>" +
                        "</tr>" +
                        "</table>" +
                        "<br>" +
                        "<p>Please find the attached <b>ExtentReport.html</b> for full API execution details.</p>" +
                        "</body></html>";

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", config.emailReport.smtpHost);
        props.put("mail.smtp.port", String.valueOf(config.emailReport.smtpPort));

        Session session = Session.getInstance(props);

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.emailReport.from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.emailReport.to));
        message.setSubject(subject);

        MimeMultipart multipart = new MimeMultipart();

        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent(htmlBody, "text/html; charset=utf-8");
        multipart.addBodyPart(bodyPart);

        File reportFile = new File(EXTENT_REPORT_PATH);
        if (reportFile.exists()) {
            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(reportFile);
            attachmentPart.setFileName("ExtentReport.html");
            multipart.addBodyPart(attachmentPart);
        } else {
            System.out.println("Extent report not found at: " + reportFile.getAbsolutePath());
        }

        message.setContent(multipart);

        Transport transport = session.getTransport("smtp");
        try {
            transport.connect(
                    config.emailReport.smtpHost,
                    config.emailReport.smtpPort,
                    config.emailReport.smtpUser,
                    config.emailReport.smtpPassword
            );
            transport.sendMessage(message, message.getAllRecipients());
            System.out.println("Email report sent with ExtentReport attachment to: " + config.emailReport.to);
        } finally {
            transport.close();
        }
    }
}
