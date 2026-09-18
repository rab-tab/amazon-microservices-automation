package com.amazon.tests.reports;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.List;

public class ReportingFilter implements Filter {
    // ReportingFilter.java — add alongside the existing ThreadLocal<Response>
        private static final ThreadLocal<Response> lastResponse = new ThreadLocal<>();
        private static final ThreadLocal<List<Response>> captureGroup = new ThreadLocal<>();

        @Override
        public Response filter(FilterableRequestSpecification req,
                               FilterableResponseSpecification res,
                               FilterContext ctx) {
            Response response = ctx.next(req, res);
            lastResponse.set(response);

            List<Response> group = captureGroup.get();
            if (group != null) {
                group.add(response);   // shared, synchronized list — safe across threads
            }
            return response;
        }

        public static Response getLastResponse() { return lastResponse.get(); }
        public static void clearLastResponse() { lastResponse.remove(); }

        // NEW — concurrency-test opt-in
        public static void attachCaptureGroup(List<Response> sharedGroup) {
            captureGroup.set(sharedGroup);
        }
        public static void clearCaptureGroup() {
            captureGroup.remove();
        }
    }

