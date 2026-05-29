import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads apis.json. SMTP credentials are overridden by environment variables
 * when running in GitHub Actions so they never need to be hardcoded.
 *
 *  SMTP_USER      → emailReport.smtpUser  + emailReport.from
 *  SMTP_PASSWORD  → emailReport.smtpPassword
 *  EMAIL_TO       → emailReport.to
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiConfig {

    public String              baseUrl;
    public String              authToken;
    public long                thresholdMs;
    public ScanConfig          scanConfig;
    public EmailConfig         emailReport;

    // Populated at runtime by ApiScanner — not in JSON
    public List<ApiDefinition> apis;

    public static ApiConfig load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = ApiConfig.class
                .getClassLoader()
                .getResourceAsStream("apis.json");

        if (is == null) {
            throw new RuntimeException(
                "apis.json not found in src/test/resources/."
            );
        }

        ApiConfig config = mapper.readValue(is, ApiConfig.class);

        // ── Override email config from environment variables (GitHub Secrets) ──
        if (config.emailReport == null) config.emailReport = new EmailConfig();

        String smtpUser = System.getenv("SMTP_USER");
        String smtpPass = System.getenv("SMTP_PASSWORD");
        String emailTo  = System.getenv("EMAIL_TO");

        if (smtpUser != null && !smtpUser.isBlank()) {
            config.emailReport.smtpUser = smtpUser;
            config.emailReport.from     = smtpUser;   // from must match authenticated user
        }
        if (smtpPass != null && !smtpPass.isBlank()) {
            config.emailReport.smtpPassword = smtpPass;
        }
        if (emailTo != null && !emailTo.isBlank()) {
            config.emailReport.to = emailTo;
        }

        return config;
    }

    // ── Nested config classes ─────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScanConfig {
        public String              projectPath;
        public String              urlsFile;
        public Map<String, Integer> defaultExpectedStatus;
        public Map<String, String>  pathParamDefaults;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiDefinition {
        public String              name;
        public String              method;
        public String              endpoint;
        public Map<String, String> headers;
        public Map<String, Object> body;
        public int                 expectedStatusCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailConfig {
        public String from;
        public String to;
        public String smtpHost;
        public int    smtpPort;
        public String smtpUser;
        public String smtpPassword;
    }
}
