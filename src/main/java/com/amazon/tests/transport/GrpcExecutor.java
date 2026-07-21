package com.amazon.tests.transport;

public class GrpcExecutor implements RequestExecutor {

   // private final ManagedChannel channel;

    @Override
    public ServiceResponse execute(ServiceRequest request) {
        // request.getOperation() maps to a stub method, e.g. "OrderService/CreateOrder"
        // no UnsupportedOperationException needed anymore —
        // gRPC genuinely CAN execute any operation, just via a different mechanism
      //  Object grpcResult = invokeStub(request.getOperation(), request.getPayload());
       // return new ServiceResponse(mapGrpcStatus(grpcResult), grpcResult, Map.of());
        return null;
    }
}
