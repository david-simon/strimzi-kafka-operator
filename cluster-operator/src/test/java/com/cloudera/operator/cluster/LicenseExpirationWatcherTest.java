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
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LicenseExpirationWatcherTest extends LicenseExpirationWatcherTestBase {
    @Test
    public void testLicenseExpirationWatcherWhenNoSecretProvided() {
        when(secretsInNamespace.withName(LicenseConfig.DEFAULT_LICENSE_SECRET_NAME)).thenReturn(null);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherWhenNoLicenseSecretResourceCallThrowsException() {
        var secretResource = mock(Resource.class);
        var secret = mock(Secret.class);
        when(secretsInNamespace.withName(LicenseConfig.DEFAULT_LICENSE_SECRET_NAME)).thenReturn(secretResource);
        when(secretResource.get()).thenThrow(new RuntimeException("test exception"));

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherWhenNoLicenseSecretDataProvided() {
        var secretResource = mock(Resource.class);
        var secret = mock(Secret.class);
        when(secretsInNamespace.withName(LicenseConfig.DEFAULT_LICENSE_SECRET_NAME)).thenReturn(secretResource);
        when(secretResource.get()).thenReturn(secret);
        when(secret.getData()).thenReturn(null);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherWhenNoLicenseProvidedInLicenseSecret() {
        mockLicenseIntoSecret("");

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvided() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.ACTIVE);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseActive(false);
        verify(eventResource, never()).create();
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedButLicenseMessageIsNull() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(null, LicenseState.MISSING);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedButLicenseMessageHasNoFeatures() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.FEATURE_MISSING);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotActiveAndEventCreated();
        assertLicenseState(LicenseState.FEATURE_MISSING);
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedButLicenseMessageHasNoDates() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.DATE_MISSING);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotActiveAndEventCreated();
        assertLicenseState(LicenseState.DATE_MISSING);
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedButLicenseStartDateNotReached() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.INACTIVE);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotActiveAndEventCreated();
        assertLicenseState(LicenseState.INACTIVE);
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedAndLicenseIsInGracePeriod() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.GRACE_PERIOD);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseActive(true);
        verify(eventResource).create();
        assertLicenseState(LicenseState.GRACE_PERIOD);
    }

    @Test
    public void testLicenseExpirationWatcherWhenValidLicenseProvidedAndLicenseHasExpired() {
        mockLicenseIntoSecret("license");
        setLicenseUtilsMockReturnState(new License(), LicenseState.INACTIVE);

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotActiveAndEventCreated();
        assertLicenseState(LicenseState.INACTIVE);
    }
}
