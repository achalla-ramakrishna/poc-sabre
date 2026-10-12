package com.rewardsiq.grpc;

import com.rewardsiq.common.config.RewardsIqProperties;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * gRPC's equivalent of the servlet security chain (NFR-S4): DPE must present a service JWT with
 * the pricing scope. Also carries the caller's correlation id into the call context.
 */
@Component
public class ServiceAuthInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> CORRELATION =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final Context.Key<String> CORRELATION_ID = Context.key("correlationId");
    public static final Context.Key<String> CALLER = Context.key("caller");

    private final JwtDecoder decoder;
    private final String requiredScope;

    public ServiceAuthInterceptor(JwtDecoder decoder, RewardsIqProperties props) {
        this.decoder = decoder;
        this.requiredScope = props.security().pricingScope();
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String header = headers.get(AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("service bearer token required"));
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(header.substring(7));
        } catch (JwtException e) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("invalid service token"));
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope == null || Arrays.stream(scope.split(" ")).noneMatch(requiredScope::equals)) {
            return reject(call, Status.PERMISSION_DENIED.withDescription("scope " + requiredScope + " required"));
        }
        String correlationId = headers.get(CORRELATION);
        Context ctx = Context.current()
                .withValue(CALLER, jwt.getSubject())
                .withValue(CORRELATION_ID, correlationId == null ? UUID.randomUUID().toString() : correlationId);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private static <Q, R> ServerCall.Listener<Q> reject(ServerCall<Q, R> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() { };
    }
}
