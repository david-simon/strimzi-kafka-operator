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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LicenseUtilsTest {
    private static final String VALID_FEATURE = "K8S_STREAM_CCU:200";
    private static final String INVALID_FEATURE = "K8S_NOT_VALID_FEATURE:400";
    private static final LocalDate NOW = LocalDate.of(2024, 2, 3);
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
    private LicenseUtils licenseUtils;

    private static Stream<Arguments> provideLicenseDatesAndExpectedState() {
        return Stream.of(
                arguments("Dates are not provided", null, null, null, LicenseState.DATE_MISSING),
                arguments("Start date is missing", null, NOW, NOW, LicenseState.DATE_MISSING),
                arguments("Expiration date is missing", NOW, null, NOW, LicenseState.DATE_MISSING),
                arguments("Every dates selected as now", NOW, NOW, NOW, LicenseState.ACTIVE),
                arguments("Start and expiration dates selected as now, deactivation date is missing", NOW, NOW, null, LicenseState.ACTIVE),
                arguments("Start date is in the past", NOW.minusDays(1), NOW, NOW, LicenseState.ACTIVE),
                arguments("Start date is in the future", NOW.plusDays(1), NOW, NOW, LicenseState.INACTIVE),
                arguments("Expired due to expiration date", NOW, NOW.minusMonths(2), null, LicenseState.INACTIVE),
                arguments("Expired due to deactivation date", NOW, NOW.minusMonths(2), NOW.minusDays(1), LicenseState.INACTIVE),
                arguments("Expired due to expiration and deactivation dates", NOW, NOW.minusMonths(2), NOW.minusMonths(2), LicenseState.INACTIVE),
                arguments("Grace period due to expiration date", NOW, NOW.minusDays(1), null, LicenseState.GRACE_PERIOD),
                arguments("Grace period due to expiration date, when grace period end equals to now", NOW, NOW.minusMonths(1), null, LicenseState.GRACE_PERIOD),
                arguments("Grace period due to deactivation date", NOW, NOW.minusMonths(3), NOW.plusDays(1), LicenseState.GRACE_PERIOD),
                arguments("Grace period due to deactivation date, when grace period end equals to now", NOW, NOW.minusMonths(3), NOW, LicenseState.GRACE_PERIOD)
        );
    }

    @BeforeEach
    public void init() {
        var mockDateSupplier = (Supplier<LocalDate>) mock(Supplier.class);
        when(mockDateSupplier.get()).thenReturn(NOW);
        licenseUtils = new LicenseUtils(mockDateSupplier);
    }

    @Test
    public void testLicenseNotActiveWhenNoLicense() {
        var state = licenseUtils.checkLicenseState(null);

        assertEquals(LicenseState.MISSING, state, "License state should be missing, when license is not present.");
    }

    @Test
    public void testLicenseNotActiveWhenFeatureIsMissing() {
        var license = createLicense(INVALID_FEATURE);
        var state = licenseUtils.checkLicenseState(license);

        assertEquals(LicenseState.FEATURE_MISSING, state, "License state should be feature is missing.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideLicenseDatesAndExpectedState")
    public void testCheckLicenseState(String displayName, LocalDate startDate, LocalDate expDate, LocalDate deactDate, LicenseState expectedState) {
        var license = createLicense();
        license.setStartDate(startDate);
        license.setExpirationDate(expDate);
        license.setDeactivationDate(deactDate);

        var state = licenseUtils.checkLicenseState(license);

        assertEquals(expectedState, state);
    }

    @Test
    public void testVerifyLicense() {
        var realLicenseUtils = new LicenseUtils();
        var licenseString = getValidLicenseString();

        var license = realLicenseUtils.getVerifiedLicense(licenseString.getBytes(StandardCharsets.UTF_8));

        assertTrue(licenseString.contains("\"name\" : \"%s\"".formatted(license.getName())));
        assertTrue(licenseString.contains("\"uuid\" : \"%s\"".formatted(license.getUuid().toString())));
        assertTrue(licenseString.contains("\"version\" : %d".formatted(license.getVersion())));
        assertEquals(LicenseState.ACTIVE, realLicenseUtils.checkLicenseState(license), "License should be active.");
    }

    @Test
    public void testVerifyLicenseWhenLicenseIsInvalid() {
        assertThrows(RuntimeException.class, () -> licenseUtils.getVerifiedLicense(INVALID_SIGNED_DATA.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testVerifyLicenseWhenNoLicense() {
        assertThrows(RuntimeException.class, () -> licenseUtils.getVerifiedLicense(null));
    }

    @Test
    public void testVerifyLicenseWhenLicenseIsEmpty() {
        byte[] licenseBytes = {};

        assertThrows(RuntimeException.class, () -> licenseUtils.getVerifiedLicense(licenseBytes));
    }

    private License createLicense(String... features) {
        var featuresToAdd = features.length == 0 ? Set.of(VALID_FEATURE, INVALID_FEATURE) : Set.of(features);
        var license = new License();
        license.setFeatures(featuresToAdd);
        return license;
    }

    private String getValidLicenseString() {
        try (var is = getClass().getResourceAsStream("/license/valid_license.pgp")) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
