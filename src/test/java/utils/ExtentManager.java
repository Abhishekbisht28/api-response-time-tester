package utils;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExtentManager {

    private static ExtentReports extent;

    public static ExtentReports getInstance() {
        if (extent == null) {
            try {
                Path reportPath = Paths.get(System.getProperty("user.dir"), "target", "ExtentReport.html");
                Files.createDirectories(reportPath.getParent());

                ExtentSparkReporter spark = new ExtentSparkReporter(reportPath.toString());
                spark.config().setDocumentTitle("API Response Report");
                spark.config().setReportName("API Performance Report");

                extent = new ExtentReports();
                extent.attachReporter(spark);
                extent.setSystemInfo("Framework", "REST Assured + JUnit 5");
                extent.setSystemInfo("Report Path", reportPath.toAbsolutePath().toString());
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize Extent Report", e);
            }
        }
        return extent;
    }
}
