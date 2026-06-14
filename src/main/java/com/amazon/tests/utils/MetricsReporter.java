package com.amazon.tests.utils;



import io.micrometer.core.instrument.*;

public class MetricsReporter {

    public static void print() {

        MeterRegistry registry =
                MetricsManager.registry();

        registry.getMeters()
                .forEach(meter -> {

                    Meter.Id id =
                            meter.getId();

                    System.out.println(
                            "Metric="
                                    + id.getName()
                                    + " tags="
                                    + id.getTags());
                });
    }
}