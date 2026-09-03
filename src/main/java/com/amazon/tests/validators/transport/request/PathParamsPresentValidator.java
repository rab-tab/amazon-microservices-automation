package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.RequestAttributes;
import com.amazon.tests.transport.ServiceRequest;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches the exact bug that took multiple messages to diagnose earlier
 * this session: an endpoint template like "/api/orders/{id}" was sent
 * with no corresponding PATH_PARAMS attribute, so RestAssured sent the
 * literal string "{id}" to the server. The failure only ever surfaced as
 * a confusing backend error, several layers removed from the real cause.
 */
public class PathParamsPresentValidator extends AbstractRequestValidationHandler {

    private static final Pattern PATH_TEMPLATE_VAR = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    @Override
    protected void check(ServiceRequest request) {
        Matcher matcher = PATH_TEMPLATE_VAR.matcher(request.getEndpoint());
        if (!matcher.find()) {
            return; // no {placeholder} in this endpoint, nothing to check
        }

        Map<String, Object> pathParams =
                request.getAttribute(RequestAttributes.PATH_PARAMS, Map.class, Map.of());

        if (pathParams.isEmpty()) {
            throw new InvalidServiceRequestException(
                    "Endpoint '" + request.getEndpoint() + "' contains a path template placeholder "
                            + "but no PATH_PARAMS attribute was set on the ServiceRequest. "
                            + "The literal placeholder text would be sent to the server as-is.");
        }

        // Also confirm every {placeholder} actually has a matching key —
        // catches a partially-populated PATH_PARAMS map, not just an empty one.
        matcher.reset();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (!pathParams.containsKey(placeholder)) {
                throw new InvalidServiceRequestException(
                        "Endpoint '" + request.getEndpoint() + "' expects a path param named '"
                                + placeholder + "' but PATH_PARAMS only contains: " + pathParams.keySet());
            }
        }
    }
}
