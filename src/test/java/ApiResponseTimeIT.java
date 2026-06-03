import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import utils.ExtentManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a Django REST Framework project for API routes,
 * tests each API response time and status code,
 * generates Extent Report, sends email report,
 * and blocks merge if any API fails.
 *
 * Run with: mvn verify
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiResponseTimeIT {

    static ApiConfig config;
    static List<ApiResult> results = new ArrayList<>();
    static ExtentReports extent;

    @BeforeAll
    static void loadConfigAndScan() throws Exception {
        config = ApiConfig.load();
        extent = ExtentManager.getInstance();

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       API RESPONSE TIME TESTER (Auto-Scan)       ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("🌐 Base URL : " + config.baseUrl);
        System.out.println("⏱  Threshold: " + config.thresholdMs + "ms");
        System.out.println("──────────────────────────────────────────────────");

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

    @Test
    @Order(1)
    public void testAllApiResponseTimes() {

        boolean anyFailed = false;

        for (ApiConfig.ApiDefinition api : config.apis) {

            ExtentTest test = extent.createTest(api.method + " " + api.endpoint)
                    .assignCategory(api.method);

            test.info("API Name: " + api.name);
            test.info("Base URL: " + config.baseUrl);
            test.info("Endpoint: " + api.endpoint);
            test.info("Method: " + api.method);
            test.info("Expected Status Code: " + api.expectedStatusCode);
            test.info("Threshold: " + config.thresholdMs + " ms");

            RequestSpecification request = RestAssured
                    .given()
                    .baseUri(config.baseUrl);

            if (config.authToken != null && !config.authToken.trim().isEmpty()) {
                request.header("Authorization", config.authToken);
            }

            if (api.headers != null) {
                api.headers.forEach(request::header);
                test.info("Headers: " + api.headers);
            }

            if (api.body != null) {
                request.body(api.body);
                test.info("Request Body: " + api.body);
            }

            long start = System.currentTimeMillis();
            Response response;
            String errorMessage = null;

            try {
                response = request.when().request(api.method, api.endpoint);
            } catch (Exception e) {
                errorMessage = e.getMessage();

                ApiResult result = new ApiResult(
                        api.name, api.method, api.endpoint,
                        -1, -1, api.expectedStatusCode,
                        false, false, errorMessage, null
                );
                results.add(result);

                test.fail("API UNREACHABLE");
                test.fail("Error Message: " + errorMessage);

                System.out.printf("[ERROR] %-40s | %s %-30s | UNREACHABLE%n",
                        api.name, api.method, api.endpoint);

                anyFailed = true;
                continue;
            }

            long responseTime = System.currentTimeMillis() - start;
            int actualStatus = response.getStatusCode();
            String responseBody = response.getBody() != null ? response.getBody().asPrettyString() : "";

            boolean timePassed = responseTime <= config.thresholdMs;
            boolean statusPassed = actualStatus == api.expectedStatusCode;
            boolean passed = timePassed && statusPassed;

            ApiResult result = new ApiResult(
                    api.name, api.method, api.endpoint,
                    responseTime, actualStatus, api.expectedStatusCode,
                    timePassed, statusPassed, null, responseBody
            );
            results.add(result);

            test.info("Actual Status Code: " + actualStatus);
            test.info("Response Time: " + responseTime + " ms");

            if (responseBody != null && !responseBody.isBlank()) {
                String bodyForReport = responseBody.length() > 3000
                        ? responseBody.substring(0, 3000) + "... [truncated]"
                        : responseBody;
                test.info("Response Body: <pre>" + escapeHtml(bodyForReport) + "</pre>");
            }

            if (passed) {
                test.pass("API PASSED");
            } else {
                test.fail("API FAILED");

                if (!timePassed) {
                    test.fail("Slow API: " + responseTime + " ms exceeded threshold " + config.thresholdMs + " ms");
                }

                if (!statusPassed) {
                    test.fail("Wrong Status Code: expected " + api.expectedStatusCode + " but got " + actualStatus);
                }
            }

            System.out.printf("[%s] %-40s | %s %-30s | %4dms | HTTP %d (expected %d)%s%s%n",
                    passed ? "PASS" : "FAIL",
                    api.name,
                    api.method,
                    api.endpoint,
                    responseTime,
                    actualStatus,
                    api.expectedStatusCode,
                    timePassed ? "" : " ⚠ SLOW",
                    statusPassed ? "" : " ⚠ WRONG STATUS"
            );

            if (!passed) {
                anyFailed = true;
            }
        }

        long passed = results.stream().filter(ApiResult::passed).count();
        long failed = results.size() - passed;
        long slow = results.stream().filter(r -> !r.timePassed).count();
        long badStatus = results.stream().filter(r -> !r.statusPassed).count();

        ExtentTest summary = extent.createTest("Execution Summary")
                .assignCategory("Summary");
        summary.info("Total APIs: " + results.size());
        summary.pass("Passed APIs: " + passed);

        if (failed > 0) {
            summary.fail("Failed APIs: " + failed);
        } else {
            summary.pass("Failed APIs: 0");
        }

        if (slow > 0) {
            summary.warning("Slow APIs: " + slow + " exceeded " + config.thresholdMs + " ms");
        } else {
            summary.pass("Slow APIs: 0");
        }

        if (badStatus > 0) {
            summary.fail("Wrong Status APIs: " + badStatus);
        } else {
            summary.pass("Wrong Status APIs: 0");
        }

        System.out.println("──────────────────────────────────────────────────");
        System.out.println("📊 RESULTS : Total=" + results.size()
                + " | Passed=" + passed + " | Failed=" + failed);
        System.out.println("   Slow (>" + config.thresholdMs + "ms): " + slow
                + " | Wrong Status: " + badStatus);
        System.out.println("──────────────────────────────────────────────────");

        try {
            // Generate Extent HTML Report
            extent.flush();

// Verify report exists
            File report = new File("target/ExtentReport.html");
            System.out.println("Report Exists = " + report.exists());
            System.out.println("Report Path = " + report.getAbsolutePath());

// Send email
            EmailReporter.send(config, results);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("⚠ Email sending failed: " + e.getMessage());
        }

        extent.flush();

        if (anyFailed) {
            Assertions.fail(
                    "❌ Merge Blocked! " + failed + " API(s) failed. " +
                            slow + " exceeded " + config.thresholdMs + "ms, " +
                            badStatus + " returned wrong status code. Check ExtentReport.html for details."
            );
        } else {
            System.out.println("✅ All APIs passed! Merge is allowed.");
        }
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static class ApiResult {
        public final String name;
        public final String method;
        public final String endpoint;
        public final long responseTime;
        public final int actualStatus;
        public final int expectedStatus;
        public final boolean timePassed;
        public final boolean statusPassed;
        public final String errorMessage;
        public final String responseBody;

        public ApiResult(String name, String method, String endpoint,
                         long responseTime, int actualStatus, int expectedStatus,
                         boolean timePassed, boolean statusPassed,
                         String errorMessage, String responseBody) {
            this.name = name;
            this.method = method;
            this.endpoint = endpoint;
            this.responseTime = responseTime;
            this.actualStatus = actualStatus;
            this.expectedStatus = expectedStatus;
            this.timePassed = timePassed;
            this.statusPassed = statusPassed;
            this.errorMessage = errorMessage;
            this.responseBody = responseBody;
        }

        public boolean passed() {
            return timePassed && statusPassed && errorMessage == null;
        }
    }
}
