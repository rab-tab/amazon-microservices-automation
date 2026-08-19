package com.amazon.tests.reports;

import com.amazon.tests.config.ConfigManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TestReporterFactory {

    public static TestReporter create() {
        String configured = ConfigManager.getInstance().getReporters();
        List<TestReporter> active = Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(TestReporterFactory::resolve)
                .collect(Collectors.toList());

        return active.size() == 1 ? active.get(0) : new CompositeReporter(active);
    }

    private static TestReporter resolve(String name) {
        return switch (name.toLowerCase()) {
            case "extent" -> new ExtentReporterAdapter();
            case "allure" -> new AllureReporterAdapter();
            case "slf4j"  -> new Slf4jReporterAdapter();
            default -> throw new IllegalArgumentException("Unknown reporter: " + name);
        };
    }
}