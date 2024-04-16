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

import com.cloudera.operator.cluster.model.License;
import com.cloudera.operator.cluster.model.LicenseState;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.EventingAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1EventingAPIGroupDSL;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Base class for tests of the LicenseExpirationWatcher
 */
public abstract class LicenseExpirationWatcherTestBase {
    private static final String LICENSE_KEY = "license";
    private static final String NAMESPACE = "ns";
    protected final NonNamespaceOperation secretsInNamespace = mock(NonNamespaceOperation.class);
    protected final Resource eventResource = mock(Resource.class);
    protected final LicenseUtils licenseUtils = mock(LicenseUtils.class);
    private final KubernetesClient client = mock(KubernetesClient.class);
    private LicenseExpirationWatcher licenseExpirationWatcher;
    private ArgumentCaptor<Event> capturedEvent;

    @BeforeEach
    protected void init() {
        reset(client, secretsInNamespace, eventResource, licenseUtils);
        capturedEvent = ArgumentCaptor.forClass(Event.class);

        var secrets = mock(MixedOperation.class);
        when(client.secrets()).thenReturn(secrets);
        when(secrets.inNamespace(NAMESPACE)).thenReturn(secretsInNamespace);

        var eventsDSL = mock(EventingAPIGroupDSL.class);
        var eventV1 = mock(V1EventingAPIGroupDSL.class);
        var events = mock(MixedOperation.class);
        var eventsInNamespace = mock(MixedOperation.class);
        when(client.events()).thenReturn(eventsDSL);
        when(eventsDSL.v1()).thenReturn(eventV1);
        when(eventV1.events()).thenReturn(events);
        when(events.inNamespace(NAMESPACE)).thenReturn(eventsInNamespace);
        when(eventsInNamespace.resource(capturedEvent.capture())).thenReturn(eventResource);

        licenseExpirationWatcher = new LicenseExpirationWatcher(client, NAMESPACE, LicenseConfig.DEFAULT_LICENSE_SECRET_NAME, licenseUtils);
    }

    protected LicenseExpirationWatcher getLicenseExpirationWatcher() {
        return licenseExpirationWatcher;
    }

    protected void mockLicenseIntoSecret(String licenseToMock) {
        var secretResource = mock(Resource.class);
        var secret = mock(Secret.class);
        var encodedData = Base64.getEncoder().encodeToString(licenseToMock.getBytes(StandardCharsets.UTF_8));
        var secretMap = Map.of(LICENSE_KEY, encodedData);

        when(secretsInNamespace.withName(LicenseConfig.DEFAULT_LICENSE_SECRET_NAME)).thenReturn(secretResource);
        when(secretResource.get()).thenReturn(secret);
        when(secret.getData()).thenReturn(secretMap);
    }

    protected void setLicenseUtilsMockReturnState(License licenseToReturn, LicenseState stateToReturn) {
        when(licenseUtils.getVerifiedLicense(any())).thenReturn(licenseToReturn);
        when(licenseUtils.checkLicenseState(any())).thenReturn(stateToReturn);
    }

    protected void assertLicenseActive(boolean isEventCaptured) {
        assertTrue(licenseExpirationWatcher.isLicenseActive());
        assertEquals(isEventCaptured, !capturedEvent.getAllValues().isEmpty());
    }

    protected void assertLicenseNotFound() {
        assertLicenseNotActiveAndEventCreated();
        assertLicenseState(LicenseState.MISSING);
    }

    protected void assertLicenseNotActiveAndEventCreated() {
        assertFalse(licenseExpirationWatcher.isLicenseActive());
        assertFalse(capturedEvent.getAllValues().isEmpty());
        verify(eventResource).create();
    }

    protected void assertLicenseState(LicenseState state) {
        assertEquals(
                state.toString(),
                capturedEvent
                        .getValue()
                        .getMetadata()
                        .getAnnotations()
                        .get(LicenseExpirationWatcher.LICENSE_ANNOTATION_KEY)
        );
    }
}
