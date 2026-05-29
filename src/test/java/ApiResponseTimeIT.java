import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads all APIs from apis.json, tests each one's response time,
 * sends an email report, and blocks merge if any API fails.
 *
 * Run with: mvn verify
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiResponseTimeIT {

    static ApiConfig config;
    static List<ApiResult> results = new ArrayList<>();

    // ─── Load config once before all tests ───────────────────────────────────
    @BeforeAll
    static void loadConfig() throws Exception {
        config = ApiConfig.load();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║          API RESPONSE TIME TESTER                ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("📋 Loaded   : " + config.apis.size() + " APIs from apis.json");
        System.out.println("🌐 Base URL : " + config.baseUrl);
        System.out.println("⏱  Threshold: " + config.thresholdMs + "ms");
        System.out.println("──────────────────────────────────────────────────");
    }

    // ─── Main test: runs every API from apis.json ─────────────────────────────
    @Test
    @Order(1)
    public void testAllApiResponseTimes() {

        boolean anyFailed = false;

        for (ApiConfig.ApiDefinition api : config.apis) {

            // Build request
            RequestSpecification request = RestAssured
                    .given()
                        .baseUri(config.baseUrl)
                        .header("Authorization", config.authToken); // global auth token

            // Add per-API headers
            if (api.headers != null) {
                api.headers.forEach(request::header);
            }

            // Add request body if present
            if (api.body != null) {
                request.body(api.body);
            }

            // Execute and measure time
            long start = System.currentTimeMillis();
            Response response;
            String errorMessage = null;

            try {
                response = request.when().request(api.method, api.endpoint);
            } catch (Exception e) {
                // API unreachable or threw exception
                errorMessage = e.getMessage();
                results.add(new ApiResult(
                        api.name, api.method, api.endpoint,
                        -1, -1, api.expectedStatusCode,
                        false, false, errorMessage
                ));
                System.out.printf("[ERROR] %-30s | %s %-25s | UNREACHABLE: %s%n",
                        api.name, api.method, api.endpoint, errorMessage);
                anyFailed = true;
                continue;
            }

            long responseTime = System.currentTimeMillis() - start;
            int actualStatus  = response.getStatusCode();

            boolean timePassed   = responseTime <= config.thresholdMs;
            boolean statusPassed = actualStatus == api.expectedStatusCode;
            boolean passed       = timePassed && statusPassed;

            results.add(new ApiResult(
                    api.name, api.method, api.endpoint,
                    responseTime, actualStatus, api.expectedStatusCode,
                    timePassed, statusPassed, null
            ));

            // Console output
            System.out.printf("[%s] %-30s | %s %-25s | %4dms | HTTP %d (expected %d)%s%s%n",
                    passed       ? "PASS" : "FAIL",
                    api.name,
                    api.method,
                    api.endpoint,
                    responseTime,
                    actualStatus,
                    api.expectedStatusCode,
                    timePassed   ? "" : " ⚠ SLOW",
                    statusPassed ? "" : " ⚠ WRONG STATUS"
            );

            if (!passed) anyFailed = true;
        }

        // ── Summary ──────────────────────────────────────────────────────────
        long passed    = results.stream().filter(ApiResult::passed).count();
        long failed    = results.size() - passed;
        long slow      = results.stream().filter(r -> !r.timePassed).count();
        long badStatus = results.stream().filter(r -> !r.statusPassed).count();

        System.out.println("──────────────────────────────────────────────────");
        System.out.println("📊 RESULTS: Total=" + results.size()
                + " | Passed=" + passed + " | Failed=" + failed);
        System.out.println("   Slow APIs (>" + config.thresholdMs + "ms): " + slow
                + " | Wrong Status: " + badStatus);
        System.out.println("──────────────────────────────────────────────────");

        // ── Send email report (always, pass or fail) ──────────────────────────
        try {
            EmailReporter.send(config, results);
        } catch (Exception e) {
            System.err.println("⚠ Email sending failed: " + e.getMessage());
        }

        // ── Block merge if any API failed ─────────────────────────────────────
        if (anyFailed) {
            Assertions.fail(
                "❌ Merge Blocked! " + failed + " API(s) failed. " +
                slow + " exceeded " + config.thresholdMs + "ms, " +
                badStatus + " returned wrong status code. Check email report for details."
            );
        } else {
            System.out.println("✅ All APIs passed! Merge is allowed.");
        }
    }

    // ─── Result Model ─────────────────────────────────────────────────────────
    public static class ApiResult {
        public final String name;
        public final String method;
        public final String endpoint;
        public final long   responseTime;
        public final int    actualStatus;
        public final int    expectedStatus;
        public final boolean timePassed;
        public final boolean statusPassed;
        public final String errorMessage;

        public ApiResult(String name, String method, String endpoint,
                         long responseTime, int actualStatus, int expectedStatus,
                         boolean timePassed, boolean statusPassed, String errorMessage) {
            this.name           = name;
            this.method         = method;
            this.endpoint       = endpoint;
            this.responseTime   = responseTime;
            this.actualStatus   = actualStatus;
            this.expectedStatus = expectedStatus;
            this.timePassed     = timePassed;
            this.statusPassed   = statusPassed;
            this.errorMessage   = errorMessage;
        }

        public boolean passed() {
            return timePassed && statusPassed && errorMessage == null;
        }
    }
}
