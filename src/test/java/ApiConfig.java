import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads and maps apis.json into Java objects.
 * Add new APIs by editing apis.json only — no code changes needed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiConfig {

    public String baseUrl;
    public String authToken;
    public long thresholdMs;
    public EmailConfig emailReport;
    public List<ApiDefinition> apis;

    /**
     * Reads src/test/resources/apis.json automatically.
     */
    public static ApiConfig load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = ApiConfig.class
                .getClassLoader()
                .getResourceAsStream("apis.json");

        if (is == null) {
            throw new RuntimeException(
                "apis.json not found in src/test/resources/. " +
                "Please create it with your API definitions."
            );
        }

        return mapper.readValue(is, ApiConfig.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiDefinition {
        public String name;
        public String method;
        public String endpoint;
        public Map<String, String> headers;
        public Map<String, Object> body;
        public int expectedStatusCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailConfig {
        public String from;
        public String to;
        public String smtpHost;
        public int smtpPort;
        public String smtpUser;
        public String smtpPassword;
    }
}
