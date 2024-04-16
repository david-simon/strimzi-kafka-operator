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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class LicenseExpirationWatcherIT extends LicenseExpirationWatcherTestBase {
    private static final String INVALID_SIGNED_DATA = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            {
              "version"        : 1,
              "name"           : "foobar",
              "uuid"           : "12c8052f-d78f-4a8e-bba4-a55a2d141fc8",
              "expirationDate" : "1981-3-2"
            }
            -----BEGIN PGP SIGNATURE-----
            Version: BCPG v1.46

            WRONG SIGNATURE
            -----END PGP SIGNATURE-----""";
    private static final String LICENSE = getValidLicenseString();

    private static String getValidLicenseString() {
        try (var is = LicenseExpirationWatcherIT.class.getResourceAsStream("/license/valid_license.pgp")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                throw new RuntimeException("valid_license.pgp is not reachable");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testLicenseExpirationWatcherWhenInvalidLicenseProvided() {
        mockLicenseIntoSecret(INVALID_SIGNED_DATA);
        when(licenseUtils.getVerifiedLicense(any())).thenCallRealMethod();

        getLicenseExpirationWatcher().doLicenseExpirationWatching();

        assertLicenseNotFound();
    }

    @Test
    public void testLicenseExpirationWatcherStartStop() {
        mockLicenseIntoSecret(LICENSE);
        when(licenseUtils.getVerifiedLicense(any())).thenCallRealMethod();
        when(licenseUtils.checkLicenseState(any())).thenReturn(LicenseState.ACTIVE);

        getLicenseExpirationWatcher().start();
        Awaitility
                .await("Wait for LicenseExpirationWatcher to mark License valid")
                .timeout(Duration.ofSeconds(60))
                .pollInterval(5, TimeUnit.SECONDS)
                .ignoreExceptions()
                .untilAsserted(() -> assertTrue(getLicenseExpirationWatcher().isLicenseActive()));
        getLicenseExpirationWatcher().stop();
    }
}
