import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a Django REST Framework project for API routes,
 * tests each one's response time and status code,
 * sends an HTML email report, and blocks the merge if any API fails.
 *
 * Run with: mvn verify
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiResponseTimeIT {

    static ApiConfig        config;
    static List<ApiResult>  results = new ArrayList<>();

    // ── Load config + scan Django project ────────────────────────────────────
    @BeforeAll
    static void loadConfigAndScan() throws Exception {
        config = ApiConfig.load();

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       API RESPONSE TIME TESTER (Auto-Scan)       ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("🌐 Base URL : " + config.baseUrl);
        System.out.println("⏱  Threshold: " + config.thresholdMs + "ms");
        System.out.println("──────────────────────────────────────────────────");

        // Auto-discover all routes from Django source code
        config.apis = ApiScanner.scan(config);

        if (config.apis.isEmpty()) {
            throw new RuntimeException(
                "No APIs discovered! Check 'scanConfig.projectPath' in apis.json " +
                "and make sure it points to your Django project root."
            );
        }

        System.out.println("📋 APIs to test: " + config.apis.size());
        System.out.println("──────────────────────────────────────────────────");
    }

    // ── Main test ─────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    public void testAllApiResponseTimes() {

        boolean anyFailed = false;

        for (ApiConfig.ApiDefinition api : config.apis) {

            RequestSpecification request = RestAssured
                    .given()
                        .baseUri(config.baseUrl)
                        .header("Authorization", config.authToken);

            if (api.headers != null) {
                api.headers.forEach(request::header);
            }
            if (api.body != null) {
                request.body(api.body);
            }

            long     start        = System.currentTimeMillis();
            Response response;
            String   errorMessage = null;

            try {
                response = request.when().request(api.method, api.endpoint);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                results.add(new ApiResult(
                        api.name, api.method, api.endpoint,
                        -1, -1, api.expectedStatusCode,
                        false, false, errorMessage
                ));
                System.out.printf("[ERROR] %-40s | %s %-30s | UNREACHABLE%n",
                        api.name, api.method, api.endpoint);
                anyFailed = true;
                continue;
            }

            long    responseTime = System.currentTimeMillis() - start;
            int     actualStatus = response.getStatusCode();
            boolean timePassed   = responseTime <= config.thresholdMs;
            boolean statusPassed = actualStatus == api.expectedStatusCode;
            boolean passed       = timePassed && statusPassed;

            results.add(new ApiResult(
                    api.name, api.method, api.endpoint,
                    responseTime, actualStatus, api.expectedStatusCode,
                    timePassed, statusPassed, null
            ));

            System.out.printf("[%s] %-40s | %s %-30s | %4dms | HTTP %d (expected %d)%s%s%n",
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

        // ── Summary ───────────────────────────────────────────────────────────
        long passed    = results.stream().filter(ApiResult::passed).count();
        long failed    = results.size() - passed;
        long slow      = results.stream().filter(r -> !r.timePassed).count();
        long badStatus = results.stream().filter(r -> !r.statusPassed).count();

        System.out.println("──────────────────────────────────────────────────");
        System.out.println("📊 RESULTS : Total=" + results.size()
                + " | Passed=" + passed + " | Failed=" + failed);
        System.out.println("   Slow (>" + config.thresholdMs + "ms): " + slow
                + " | Wrong Status: " + badStatus);
        System.out.println("──────────────────────────────────────────────────");

        // ── Email report ──────────────────────────────────────────────────────
        try {
            EmailReporter.send(config, results);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("⚠ Email sending failed: " + e.getMessage());
        }

        // ── Block merge if any API failed ──────────────────────────────────────
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

    // ── Result model ──────────────────────────────────────────────────────────
    public static class ApiResult {
        public final String  name;
        public final String  method;
        public final String  endpoint;
        public final long    responseTime;
        public final int     actualStatus;
        public final int     expectedStatus;
        public final boolean timePassed;
        public final boolean statusPassed;
        public final String  errorMessage;

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
