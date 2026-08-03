package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService.IngressResponse;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService.TenantIngressEvidence;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService.CallbackRouteRejectedException;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService.RouteLookup;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService.RouteType;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.ProviderIngressEvidence;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.CallbackKind;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.FormatBucket;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.RouteKind;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;
import com.google.common.net.InetAddresses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;

/** The sole Taku callback-key classification and dispatch boundary. */
@Service
public class SkitTakuCallbackIngressDispatcherImpl implements SkitTakuCallbackIngressDispatcher {

    private static final String PROVIDER = "TAKU";

    private final SkitCallbackRouteRegistryService registryService;
    private final SkitCallbackRoutingService routingService;
    private final SkitCallbackIngressService tenantIngressService;
    private final SkitProviderConnectionService providerConnectionService;
    private final SkitProviderImpressionWireParser wireParser;
    private final SkitProviderImpressionCaptureService captureService;
    private final SkitCallbackRateLimiter rateLimiter;
    private final SkitProviderConnectionCapacityGuard capacityGuard;
    private final SkitProviderCallbackAuditFactory auditFactory;
    private final SkitProviderImpressionCaptureObservation observation;
    private final Clock clock;
    private final ZoneId tenantDatabaseZone;

    @Autowired
    public SkitTakuCallbackIngressDispatcherImpl(
            SkitCallbackRouteRegistryService registryService,
            SkitCallbackRoutingService routingService,
            SkitCallbackIngressService tenantIngressService,
            SkitProviderConnectionService providerConnectionService,
            SkitProviderImpressionWireParser wireParser,
            SkitProviderImpressionCaptureService captureService,
            SkitCallbackRateLimiter rateLimiter,
            SkitProviderConnectionCapacityGuard capacityGuard,
            SkitProviderCallbackAuditFactory auditFactory,
            SkitProviderImpressionCaptureObservation observation) {
        this(registryService, routingService, tenantIngressService, providerConnectionService,
                wireParser, captureService, rateLimiter, capacityGuard, auditFactory,
                observation, Clock.systemUTC(), ZoneId.systemDefault());
    }

    SkitTakuCallbackIngressDispatcherImpl(
            SkitCallbackRouteRegistryService registryService,
            SkitCallbackRoutingService routingService,
            SkitCallbackIngressService tenantIngressService,
            SkitProviderConnectionService providerConnectionService,
            SkitProviderImpressionWireParser wireParser,
            SkitProviderImpressionCaptureService captureService,
            SkitCallbackRateLimiter rateLimiter,
            SkitProviderConnectionCapacityGuard capacityGuard,
            SkitProviderCallbackAuditFactory auditFactory,
            SkitProviderImpressionCaptureObservation observation,
            Clock clock) {
        this(registryService, routingService, tenantIngressService, providerConnectionService,
                wireParser, captureService, rateLimiter, capacityGuard, auditFactory,
                observation, clock, ZoneOffset.UTC);
    }

    SkitTakuCallbackIngressDispatcherImpl(
            SkitCallbackRouteRegistryService registryService,
            SkitCallbackRoutingService routingService,
            SkitCallbackIngressService tenantIngressService,
            SkitProviderConnectionService providerConnectionService,
            SkitProviderImpressionWireParser wireParser,
            SkitProviderImpressionCaptureService captureService,
            SkitCallbackRateLimiter rateLimiter,
            SkitProviderConnectionCapacityGuard capacityGuard,
            SkitProviderCallbackAuditFactory auditFactory,
            SkitProviderImpressionCaptureObservation observation,
            Clock clock,
            ZoneId tenantDatabaseZone) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.tenantIngressService = Objects.requireNonNull(tenantIngressService,
                "tenantIngressService");
        this.providerConnectionService = Objects.requireNonNull(providerConnectionService,
                "providerConnectionService");
        this.wireParser = Objects.requireNonNull(wireParser, "wireParser");
        this.captureService = Objects.requireNonNull(captureService, "captureService");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.capacityGuard = Objects.requireNonNull(capacityGuard, "capacityGuard");
        this.auditFactory = Objects.requireNonNull(auditFactory, "auditFactory");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantDatabaseZone = Objects.requireNonNull(
                tenantDatabaseZone, "tenantDatabaseZone");
    }

    @Override
    public DispatchResponse dispatch(CallbackType callbackType, String callbackKey,
                                     String rawQuery, SkitCallbackRequestMetadata requestMetadata) {
        Instant receivedInstant = clock.instant();
        LocalDateTime providerReceivedAt = LocalDateTime
                .ofInstant(receivedInstant, ZoneOffset.UTC).withNano(0);
        LocalDateTime tenantReceivedAt = LocalDateTime
                .ofInstant(receivedInstant, tenantDatabaseZone).withNano(0);
        ObservationScope scope = new ObservationScope(callbackKind(callbackType));
        observe(() -> observation.recordRequest(scope.route, scope.callback));
        DispatchResponse response = dispatchOnce(callbackType, callbackKey, rawQuery,
                requestMetadata, providerReceivedAt, tenantReceivedAt, scope);
        recordResponse(response, scope, providerReceivedAt);
        return response;
    }

    private DispatchResponse dispatchOnce(
            CallbackType callbackType, String callbackKey, String rawQuery,
            SkitCallbackRequestMetadata requestMetadata, LocalDateTime providerReceivedAt,
            LocalDateTime tenantReceivedAt,
            ObservationScope scope) {
        byte[] keyBytes = null;
        byte[] keyHash = null;
        byte[] packedClientAddress = null;
        SkitProviderConnectionCapacityGuard.Permit emergencyPermit = null;
        try {
            if (callbackType == null || requestMetadata == null || !validKey(callbackKey)) {
                return DispatchResponse.REJECT_602;
            }
            keyBytes = callbackKey.getBytes(StandardCharsets.US_ASCII);
            keyHash = sha256(keyBytes);
            packedClientAddress = requestMetadata.getPackedClientAddress();

            RateGateOutcome globalAddressGate = globalAddressRateGate(packedClientAddress);
            if (globalAddressGate == RateGateOutcome.EXCEEDED) {
                return DispatchResponse.FAILURE_503;
            }
            if (globalAddressGate == RateGateOutcome.UNAVAILABLE) {
                emergencyPermit = capacityGuard.tryAcquireEmergency();
                if (emergencyPermit == null) {
                    recordRedisDegradation(globalAddressGate, scope);
                    observe(() -> observation.recordCapacityReject(scope.format));
                    return DispatchResponse.FAILURE_503;
                }
            }
            RouteLookup lookup;
            try {
                // Existing tenant callback DATETIME values use the database/host local zone.
                lookup = registryService.lookup(keyHash, providerReceivedAt, tenantReceivedAt);
            } catch (CallbackRouteRejectedException rejected) {
                recordRedisDegradation(globalAddressGate, scope);
                return globalAddressGate == RateGateOutcome.PASSED
                        ? DispatchResponse.REJECT_602 : DispatchResponse.FAILURE_503;
            } catch (RuntimeException infrastructureFailure) {
                recordRedisDegradation(globalAddressGate, scope);
                return DispatchResponse.FAILURE_503;
            }
            scope.route = routeKind(lookup.getRouteType());
            recordRedisDegradation(globalAddressGate, scope);

            if (lookup.getRouteType() == RouteType.TENANT_CALLBACK_KEY) {
                if (globalAddressGate != RateGateOutcome.PASSED) {
                    return DispatchResponse.FAILURE_503;
                }
                byte[] tenantKeyHash = tenantCallbackKeyHash(keyBytes);
                RateGateOutcome businessGate;
                try {
                    businessGate = businessKeyRateGate(callbackType, tenantKeyHash);
                } finally {
                    wipe(tenantKeyHash);
                }
                recordRedisDegradation(businessGate, scope);
                if (businessGate != RateGateOutcome.PASSED) {
                    return DispatchResponse.FAILURE_503;
                }
                return dispatchTenant(callbackType, lookup, rawQuery, keyBytes,
                        packedClientAddress, tenantReceivedAt);
            }
            if (lookup.getRouteType() != RouteType.PROVIDER_CALLBACK_ROUTE) {
                return globalAddressGate == RateGateOutcome.PASSED
                        ? DispatchResponse.REJECT_602 : DispatchResponse.FAILURE_503;
            }
            if (callbackType == CallbackType.REWARD) {
                return DispatchResponse.REJECT_602;
            }
            return dispatchProviderImpression(lookup, rawQuery, requestMetadata,
                    globalAddressGate, providerReceivedAt, scope);
        } catch (RuntimeException infrastructureFailure) {
            return DispatchResponse.FAILURE_503;
        } finally {
            wipe(keyBytes);
            wipe(keyHash);
            wipe(packedClientAddress);
            close(emergencyPermit);
            close(requestMetadata);
        }
    }

    private DispatchResponse dispatchTenant(
            CallbackType callbackType, RouteLookup lookup, String rawQuery, byte[] keyBytes,
            byte[] packedClientAddress, LocalDateTime receivedAt) {
        final SkitCallbackRoutingService.CallbackRoute route;
        try {
            route = routingService.resolveTenantReward(lookup, receivedAt);
        } catch (CallbackRouteRejectedException rejected) {
            return DispatchResponse.REJECT_602;
        }
        byte[] callbackKeyHash = tenantCallbackKeyHash(keyBytes);
        byte[] clientIpHash = tenantClientIpHash(keyBytes, packedClientAddress);
        try (TenantIngressEvidence evidence = TenantIngressEvidence.of(
                callbackKeyHash, clientIpHash)) {
            IngressResponse response = callbackType == CallbackType.REWARD
                    ? tenantIngressService.receiveReward(route, rawQuery, evidence, receivedAt)
                    : tenantIngressService.receiveImpression(route, rawQuery, evidence, receivedAt);
            return tenantResponse(response);
        } finally {
            wipe(callbackKeyHash);
            wipe(clientIpHash);
        }
    }

    private DispatchResponse dispatchProviderImpression(
            RouteLookup lookup, String rawQuery, SkitCallbackRequestMetadata requestMetadata,
            RateGateOutcome rateGate, LocalDateTime receivedAt, ObservationScope scope) {
        final ProviderRouteResolution route;
        try {
            route = providerConnectionService.resolveProviderImpression(lookup, receivedAt);
        } catch (RuntimeException infrastructureFailure) {
            return DispatchResponse.FAILURE_503;
        }
        if (route == null || !route.isAccepting()) {
            return rateGate == RateGateOutcome.PASSED
                    ? DispatchResponse.REJECT_602 : DispatchResponse.FAILURE_503;
        }
        if (rateGate == RateGateOutcome.EXCEEDED) {
            return DispatchResponse.FAILURE_503;
        }

        SkitProviderConnectionCapacityGuard.Permit permit =
                capacityGuard.tryAcquire(route.getProviderConnectionId());
        if (permit == null) {
            observe(() -> observation.recordCapacityReject(scope.format));
            return DispatchResponse.FAILURE_503;
        }
        SkitProviderImpressionWireParser.WirePayload wirePayload = null;
        ProviderIngressEvidence evidence = null;
        try {
            try {
                wirePayload = wireParser.parseBounded(rawQuery);
            } catch (SkitProviderImpressionWireParser.WireBoundaryException boundaryFailure) {
                return DispatchResponse.REJECT_602;
            } catch (RuntimeException parsingFailure) {
                return DispatchResponse.FAILURE_503;
            }
            scope.format = FormatBucket.fromWirePayload(wirePayload);
            evidence = auditFactory.create(requestMetadata, receivedAt);
            CaptureDecision decision = captureService.capture(
                    route, wirePayload, evidence, receivedAt);
            return captureResponse(decision);
        } finally {
            close(evidence);
            close(wirePayload);
            close(permit);
        }
    }

    private RateGateOutcome globalAddressRateGate(byte[] packedClientAddress) {
        try {
            rateLimiter.checkGlobalAddressHashed(packedClientAddress);
            return RateGateOutcome.PASSED;
        } catch (SkitCallbackRateLimiter.RateLimitExceededException exceeded) {
            return RateGateOutcome.EXCEEDED;
        } catch (RuntimeException redisUnavailable) {
            return RateGateOutcome.UNAVAILABLE;
        }
    }

    private RateGateOutcome businessKeyRateGate(CallbackType callbackType, byte[] keyHash) {
        try {
            rateLimiter.checkBusinessKeyHashed(PROVIDER, keyHash, callbackType.name());
            return RateGateOutcome.PASSED;
        } catch (SkitCallbackRateLimiter.RateLimitExceededException exceeded) {
            return RateGateOutcome.EXCEEDED;
        } catch (RuntimeException redisUnavailable) {
            return RateGateOutcome.UNAVAILABLE;
        }
    }

    private void recordRedisDegradation(RateGateOutcome outcome, ObservationScope scope) {
        if (outcome == RateGateOutcome.UNAVAILABLE) {
            observe(() -> observation.recordRedisDegradation(scope.route, scope.callback));
        }
    }

    private void recordResponse(DispatchResponse response, ObservationScope scope,
                                LocalDateTime receivedAt) {
        if (response == DispatchResponse.ACK_200
                && scope.route == RouteKind.PROVIDER
                && scope.callback == CallbackKind.IMPRESSION) {
            observe(() -> observation.recordAccepted200AfterCommit(scope.format, receivedAt));
        } else if (response == DispatchResponse.REJECT_602) {
            observe(() -> observation.recordRejected602(
                    scope.route, scope.callback, scope.format));
        } else if (response == DispatchResponse.FAILURE_503) {
            observe(() -> observation.recordFailure503(
                    scope.route, scope.callback, scope.format));
        }
    }

    private static CallbackKind callbackKind(CallbackType callbackType) {
        if (callbackType == CallbackType.IMPRESSION) {
            return CallbackKind.IMPRESSION;
        }
        if (callbackType == CallbackType.REWARD) {
            return CallbackKind.REWARD;
        }
        return CallbackKind.UNKNOWN;
    }

    private static RouteKind routeKind(RouteType routeType) {
        if (routeType == RouteType.PROVIDER_CALLBACK_ROUTE) {
            return RouteKind.PROVIDER;
        }
        if (routeType == RouteType.TENANT_CALLBACK_KEY) {
            return RouteKind.TENANT;
        }
        return RouteKind.UNKNOWN;
    }

    private static void observe(Runnable signal) {
        try {
            signal.run();
        } catch (RuntimeException ignored) {
            // Telemetry failure must not alter callback transport behavior.
        }
    }

    private static DispatchResponse tenantResponse(IngressResponse response) {
        if (response == IngressResponse.OK) {
            return DispatchResponse.ACK_200;
        }
        if (response == IngressResponse.INVALID_SIGNATURE) {
            return DispatchResponse.INVALID_SIGNATURE_601;
        }
        if (response == IngressResponse.REJECTED) {
            return DispatchResponse.REJECT_602;
        }
        return DispatchResponse.FAILURE_503;
    }

    private static DispatchResponse captureResponse(CaptureDecision decision) {
        if (decision == CaptureDecision.ACK_200) {
            return DispatchResponse.ACK_200;
        }
        if (decision == CaptureDecision.REJECT_602) {
            return DispatchResponse.REJECT_602;
        }
        return DispatchResponse.FAILURE_503;
    }

    private static boolean validKey(String value) {
        if (value == null || value.length() != 43) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!((current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '_' || current == '-')) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tenantClientIpHash(byte[] callbackKey, byte[] packedClientAddress) {
        try {
            String canonicalAddress = InetAddresses.toAddrString(
                    InetAddress.getByAddress(packedClientAddress));
            byte[] addressBytes = canonicalAddress.getBytes(StandardCharsets.UTF_8);
            byte[] domain = "client-ip\0".getBytes(StandardCharsets.US_ASCII);
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(callbackKey, "HmacSHA256"));
                mac.update(domain);
                return mac.doFinal(addressBytes);
            } finally {
                wipe(addressBytes);
                wipe(domain);
            }
        } catch (UnknownHostException invalidAddress) {
            throw new IllegalArgumentException("Callback client address is invalid");
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    private static byte[] tenantCallbackKeyHash(byte[] callbackKey) {
        byte[] domain = "callback-key\0".getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            return digest.digest(callbackKey);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        } finally {
            wipe(domain);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void close(AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // All ingress cleanup types are non-throwing and cleanup must not change the response.
        }
    }

    private enum RateGateOutcome {
        PASSED,
        EXCEEDED,
        UNAVAILABLE
    }

    private static final class ObservationScope {
        private final CallbackKind callback;
        private RouteKind route = RouteKind.UNKNOWN;
        private FormatBucket format = FormatBucket.MISSING;

        private ObservationScope(CallbackKind callback) {
            this.callback = callback;
        }
    }
}
