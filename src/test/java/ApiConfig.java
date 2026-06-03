import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads apis.json.
 *
 * SMTP credentials should come from environment variables / GitHub Secrets:
 * SMTP_USER, SMTP_PASSWORD, EMAIL_TO
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiConfig {

    public String              baseUrl;
    public String              authToken;
    public long                thresholdMs;
    public ScanConfig          scanConfig;
    public EmailConfig         emailReport;

    // Populated at runtime by ApiScanner
    public List<ApiDefinition> apis;

    public static ApiConfig load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = ApiConfig.class
                .getClassLoader()
                .getResourceAsStream("apis.json");

        if (is == null) {
            throw new RuntimeException("apis.json not found in src/test/resources.");
        }

        ApiConfig config = mapper.readValue(is, ApiConfig.class);

        if (config.emailReport == null) config.emailReport = new EmailConfig();

        String smtpUser = System.getenv("SMTP_USER");
        String smtpPass = System.getenv("SMTP_PASSWORD");
        String emailTo  = System.getenv("EMAIL_TO");

        if (smtpUser != null && !smtpUser.isBlank()) {
            config.emailReport.smtpUser = smtpUser;
            config.emailReport.from = smtpUser;
        }
        if (smtpPass != null && !smtpPass.isBlank()) {
            config.emailReport.smtpPassword = smtpPass;
        }
        if (emailTo != null && !emailTo.isBlank()) {
            config.emailReport.to = emailTo;
        }

        if (config.thresholdMs <= 0) config.thresholdMs = 1500;

        return config;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScanConfig {
        public String               projectPath;
        public String               urlsFile;
        public Boolean              changedOnly;
        public String               baseBranch;
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
