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

package io.strimzi.operator.cluster;

import com.cloudera.operator.cluster.LicenseExpirationWatcher;
import io.strimzi.operator.cluster.operator.assembly.KafkaAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaBridgeAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaConnectAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaMirrorMaker2AssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaMirrorMakerAssemblyOperator;
import io.strimzi.operator.cluster.operator.assembly.KafkaRebalanceAssemblyOperator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClusterOperatorReconcileAllTest {
    private static Stream<Arguments> provideLicenseValidity() {
        return Stream.of(
                arguments("Valid license", true),
                arguments("Invalid license", false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideLicenseValidity")
    public void testClusterOperatorReconcileAll(String displayName, boolean isLicenseValid) {
        var callTimes = isLicenseValid ? times(1) : never();
        var mockConfig = mock(ClusterOperatorConfig.class);
        var mockKafkaAssemblyOperator = mock(KafkaAssemblyOperator.class);
        var mockKafkaConnectAssemblyOperator = mock(KafkaConnectAssemblyOperator.class);
        var mockKafkaMirrorMakerAssemblyOperator = mock(KafkaMirrorMakerAssemblyOperator.class);
        var mockKafkaMirrorMaker2AssemblyOperator = mock(KafkaMirrorMaker2AssemblyOperator.class);
        var mockKafkaBridgeAssemblyOperator = mock(KafkaBridgeAssemblyOperator.class);
        var mockKafkaRebalanceAssemblyOperator = mock(KafkaRebalanceAssemblyOperator.class);
        var mockLicenseExpirationWatcher = mock(LicenseExpirationWatcher.class);
        when(mockConfig.isPodSetReconciliationOnly()).thenReturn(false);
        when(mockLicenseExpirationWatcher.isLicenseActive()).thenReturn(isLicenseValid);
        var clusterOperator = new ClusterOperator(
                "",
                mockConfig,
                mockKafkaAssemblyOperator,
                mockKafkaConnectAssemblyOperator,
                mockKafkaMirrorMakerAssemblyOperator,
                mockKafkaMirrorMaker2AssemblyOperator,
                mockKafkaBridgeAssemblyOperator,
                mockKafkaRebalanceAssemblyOperator,
                null,
                mockLicenseExpirationWatcher
        );

        clusterOperator.reconcileAll("");

        verify(mockLicenseExpirationWatcher, times(1)).isLicenseActive();
        verify(mockConfig, callTimes).isPodSetReconciliationOnly();
        verify(mockKafkaAssemblyOperator, callTimes).reconcileAll(any(), any(), any());
        verify(mockKafkaConnectAssemblyOperator, callTimes).reconcileAll(any(), any(), any());
        verify(mockKafkaMirrorMakerAssemblyOperator, callTimes).reconcileAll(any(), any(), any());
        verify(mockKafkaMirrorMaker2AssemblyOperator, callTimes).reconcileAll(any(), any(), any());
        verify(mockKafkaBridgeAssemblyOperator, callTimes).reconcileAll(any(), any(), any());
        verify(mockKafkaRebalanceAssemblyOperator, callTimes).reconcileAll(any(), any(), any());
    }
}
