package com.amazon.tests.reports;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

public class ReportingFilter implements Filter {
    @Override
    public Response filter(FilterableRequestSpecification req,
                           FilterableResponseSpecification res,
                           FilterContext ctx) {
        Response response = ctx.next(req, res);
        if (response.getStatusCode() >= 400) {
            ExtentReportManager.getInstance().logInfo(
                    "Request: " + req.getMethod() + " " + req.getURI() +
                            "\nResponse (" + response.getStatusCode() + "): " + response.getBody().asString());
        }
        return response;
    }
    private static final ThreadLocal<Response> lastResponse = new ThreadLocal<>();

    public static Response getLastResponse() {
        return lastResponse.get();
    }

    public static void clearLastResponse() {   // ADD THIS
        lastResponse.remove();
    }
}
