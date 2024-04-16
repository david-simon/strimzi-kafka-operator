/*
Copyright 2024 Cloudera Inc.

This code is provided to you pursuant to your written agreement with Cloudera, which may be the terms of the
Affero General Public License version 3 (AGPLv3), or pursuant to a written agreement with a third party authorized
to distribute this code.  If you do not have a written agreement with Cloudera or with an authorized and
properly licensed third party, you do not have any rights to this code.

If this code is provided to you under the terms of the AGPLv3:
(A) CLOUDERA PROVIDES THIS CODE TO YOU WITHOUT WARRANTIES OF ANY KIND;
(B) CLOUDERA DISCLAIMS ANY AND ALL EXPRESS AND IMPLIED WARRANTIES WITH RESPECT TO THIS CODE, INCLUDING BUT NOT
  LIMITED TO IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE;
(C) CLOUDERA IS NOT LIABLE TO YOU, AND WILL NOT DEFEND, INDEMNIFY, OR HOLD YOU HARMLESS FOR ANY CLAIMS ARISING
  FROM OR RELATED TO THE CODE; AND
(D) WITH RESPECT TO YOUR EXERCISE OF ANY RIGHTS GRANTED TO YOU FOR THE CODE, CLOUDERA IS NOT LIABLE FOR ANY
  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, PUNITIVE OR CONSEQUENTIAL DAMAGES INCLUDING, BUT NOT LIMITED TO,
  DAMAGES RELATED TO LOST REVENUE, LOST PROFITS, LOSS OF INCOME, LOSS OF BUSINESS ADVANTAGE OR UNAVAILABILITY,
  OR LOSS OR CORRUPTION OF DATA.
*/

package com.cloudera.operator.cluster;

import com.cloudera.operator.cluster.model.LicenseState;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.operator.cluster.model.AbstractModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * LicenseExpirationWatcher monitors the license if it is valid or not
 */
public class LicenseExpirationWatcher {
    /**
     * The key of the annotations in license related events
     */
    public static final String LICENSE_ANNOTATION_KEY = "csm/license-state";
    private static final Logger LOGGER = LogManager.getLogger(LicenseExpirationWatcher.class);
    private static final DateTimeFormatter K8S_MICROTIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.'SSSSSSXXX");
    private static final String CONTROLLER = "strimzi.io/cluster-operator";
    private static final String LICENSE_SECRET_KEY = "license";
    private static final String EVENT_ACTION = "LicenseCheck";
    private static final String EVENT_TYPE = "Warning";
    private final KubernetesClient client;
    private final String namespace;
    private final String secretName;
    private final ScheduledExecutorService execSvc;
    private final LicenseUtils licenseUtils;
    private ScheduledFuture<?> licenseWatcherTask;
    private volatile boolean licenseActive;

    /**
     * Constructor to create LicenseExpirationWatcher object
     * @param client     the kubernetes client object
     * @param namespace  the namespace of the cluster-operator
     * @param secretName the name of the license secret
     */
    public LicenseExpirationWatcher(KubernetesClient client, String namespace, String secretName) {
        this(client, namespace, secretName, new LicenseUtils());
    }

    LicenseExpirationWatcher(KubernetesClient client, String namespace, String secretName, LicenseUtils licenseUtils) {
        this.client = client;
        this.namespace = namespace;
        this.secretName = secretName;
        this.licenseUtils = licenseUtils;
        execSvc = Executors.newScheduledThreadPool(1);
        licenseActive = false;
    }

    /**
     * Returns if the license is active or not.
     * It is active, when it was verified, it has the proper features, furthermore it is started and not expired yet.
     *
     * @return true, if the License is active, otherwise false
     */
    public boolean isLicenseActive() {
        return licenseActive;
    }

    /**
     * Verifies the license secret, set the proper value for {@link #licenseActive} field, furthermore create logs and
     * events if there is a licensing problem. Secret will be queried multiple times to avoid transient failures
     */
    public void doLicenseExpirationWatching() {
        doLicenseExpirationWatching(true);
    }

    /**
     * Verifies the license secret, set the proper value for {@link #licenseActive} field, furthermore create logs and
     * events if there is a licensing problem.
     * @param isRetryAllowed  if the license secret should be queried multiple times to avoid transient failures
     */
    public void doLicenseExpirationWatching(boolean isRetryAllowed) {
        try {
            var data = getLicenseSecretData(isRetryAllowed);
            if (data.isEmpty()) {
                licenseActive = false;
                LOGGER.error("No license found. Please provide a valid license in the license secret.");
                publishEvent(LicenseState.MISSING, "No license found.", "Please provide a valid license in the license secret.");
                return;
            }

            try {
                var license = licenseUtils.getVerifiedLicense(data.get());
                var licenseState = licenseUtils.checkLicenseState(license);

                licenseActive = LicenseState.ACTIVE.equals(licenseState) || LicenseState.GRACE_PERIOD.equals(licenseState);
                createEventAndLogAboutLicenseState(licenseState);
            } catch (Exception e) {
                licenseActive = false;
                LOGGER.error("License verification failed. Please provide a valid license in the license secret.", e);
                publishEvent(LicenseState.MISSING, "License verification failed.", "Please provide a valid license in the license secret.");
            }
        } catch (InterruptedException ignored) {
            licenseActive = false;
        } catch (Exception e) {
            licenseActive = false;
            LOGGER.error("Unexpected error during license check.", e);
        }
    }

    /**
     * Starts the license checking
     */
    public synchronized void start() {
        LOGGER.info("Start license checking");
        licenseWatcherTask = execSvc.scheduleWithFixedDelay(
                this::doLicenseExpirationWatching,
                0,
                10,
                TimeUnit.MINUTES
        );
    }

    /**
     * Stops the license checking
     */
    public synchronized void stop() {
        LOGGER.info("Stop license checking");
        if (licenseWatcherTask != null) {
            licenseWatcherTask.cancel(true);
        }
        execSvc.shutdown();
        try {
            if (!execSvc.awaitTermination(5, TimeUnit.SECONDS)) {
                execSvc.shutdownNow();
            }
        } catch (InterruptedException e) {
            execSvc.shutdownNow();
        }
    }

    private Optional<byte[]> getLicenseSecretData(boolean isRetryAllowed) throws InterruptedException {
        var count = isRetryAllowed ? 1 : 3;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                Thread.sleep(3000);
            }

            var secret = getSecretWithApiCall();
            if (secret == null) {
                continue;
            }

            var data = secret.getData();
            if (data == null || data.get(LICENSE_SECRET_KEY) == null || data.get(LICENSE_SECRET_KEY).isEmpty()) {
                LOGGER.debug("Secret is empty or not contains key '{}' with the license in it.", LICENSE_SECRET_KEY);
                continue;
            }

            var decodedData = Base64.getDecoder().decode(data.get(LICENSE_SECRET_KEY).getBytes(StandardCharsets.UTF_8));
            return Optional.of(decodedData);
        }

        return Optional.empty();
    }

    private Secret getSecretWithApiCall() {
        try {
            return client.secrets().inNamespace(namespace).withName(secretName).get();
        } catch (Exception e) {
            LOGGER.debug("License secret not found in namespace '{}' with name '{}'.", namespace, secretName, e);
            return null;
        }
    }

    private void createEventAndLogAboutLicenseState(LicenseState state) {
        switch (state) {
            case ACTIVE -> { }
            case GRACE_PERIOD -> {
                LOGGER.warn("License is in the grace period. Please provide a newer license in the license secret.");
                publishEvent(state, "License is in grace period.", "Please provide a newer License in the License secret.");
            }
            case MISSING -> {
                LOGGER.error("License is missing. Please provide a valid License in the License secret.");
                publishEvent(state, "No License found.", "Please provide a valid License in the License secret.");
            }
            case FEATURE_MISSING -> {
                LOGGER.error(
                        "License has no streaming feature ({}). Please provide a License with streaming feature.",
                        LicenseUtils.STREAMING_FEATURE
                );
                publishEvent(
                        state,
                        "License has no streaming feature (%s).".formatted(LicenseUtils.STREAMING_FEATURE),
                        "Please provide a License with streaming feature."
                );
            }
            case DATE_MISSING -> {
                LOGGER.error("License has no start/expiration date. Please provide a valid License with proper dates.");
                publishEvent(state, "License has no start/expiration date.", "Please provide a valid License with proper dates.");
            }
            case INACTIVE -> {
                LOGGER.error("License is inactive. Please provide a valid License in the License secret.");
                publishEvent(state, "License is inactive.", "Please provide a valid License in the License secret.");
            }
            default -> {
                LOGGER.error("License handling failed. It is considered as inactive. License state: {}", state);
                publishEvent(state, "License handling failed.", "Please provide a valid License in the License secret.");
            }
        }
    }

    private void publishEvent(LicenseState state, String reason, String note) {
        var eventTime = new MicroTime(K8S_MICROTIME.format(ZonedDateTime.now(Clock.systemDefaultZone())));
        var event = new EventBuilder()
                .withNewMetadata()
                .withGenerateName("license-event-" + UUID.randomUUID())
                .withAnnotations(Map.of(LICENSE_ANNOTATION_KEY, state.toString()))
                .endMetadata()
                .withAction(EVENT_ACTION)
                .withRegarding(createClusterOperatorReference())
                .withReportingController(CONTROLLER)
                .withReportingInstance(AbstractModel.STRIMZI_CLUSTER_OPERATOR_NAME)
                .withReason(reason)
                .withType(EVENT_TYPE)
                .withEventTime(eventTime)
                .withNote(note)
                .build();
        client.events().v1().events().inNamespace(namespace).resource(event).create();
    }

    private ObjectReference createClusterOperatorReference() {
        return new ObjectReferenceBuilder().withKind("Deployment")
                .withNamespace(namespace)
                .withName(AbstractModel.STRIMZI_CLUSTER_OPERATOR_NAME)
                .build();
    }
}
