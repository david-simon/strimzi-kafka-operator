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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Utility class for Cloudera licensing
 */
public class LicenseUtils {
    /**
     * Name of the Streaming feature which is required in the valid licenses
     */
    public static final String STREAMING_FEATURE = "K8S_STREAM_CCU";
    private static final int DEFAULT_GRACE_PERIOD_MONTH = 1;
    private static final String ENTERPRISE_LICENSE_PUB_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----
            Version: GnuPG v1.4.11 (GNU/Linux)

            mQINBE5nn7MBEADCOdc1mB2eXj0UYNfHJ4KExHYPK8Wak8JITh/L/+ghUNtrseRl
            C8ukF9N/cfvM4bMl4R0GohYCPiwUkbzqq+NgxZnjObRdQhXxvVMK50e75vZH5a2b
            QOrPYPOgwZoNtj/iFXv8VrVm5jWxZBWIyNoc4EnbNb98g/T6znxn22fKe5MjH/6+
            OGHo64uqswGNqXyiQavc9GRahjaf9j4LB/qmVj9QDyoPrRN8w9FXb9yqP+0pGyfs
            jqFEQweaoHCHN+fEw3GWLxdfUK/sFvfSbDPXuZIlFSk/QW/Y2RfRCsgvSvva/oPs
            AMQbnXh/T2p/vNIDnEr562JtOS/GNQV3zaV8kVcpXIQ+In16KQNagd/o5Gy+vi/d
            ivepAw4s1qOG0U6Kx5Fueqsqd6cM5x3Thf2Elmf6aqE1ouXtk2QYb+UrSg4XmA8t
            9Vw/b9EmhwB5Sy0199Cdt1+YinwTLVSsjqqOTIKsGs+adThzzVZb3m9BwErP/3+i
            hvhF20no0nW2VQlQ3fyjfwpJR/QwVGs1t1qViGKoAmi7gVg0ozeZ34/Y4NmBLEut
            1xnhMSLUpchOUbrPChDQtQ+rtX2RNRLhgJp5FIjCRMyFOvdNPkVftLWN7Nq1YLE6
            urVQ5+xr2pkgZYnplYIfwjIy+D/CXlsmsDwtJuIEg/rDkhGG1hClEiMl6QARAQAB
            tEtDbG91ZGVyYSBVbml0IFRlc3RzIChUaGlzIGlzIGEga2V5IGZvciB0ZXN0aW5n
            IHB1cnBvc2VzKSA8ZW5nQGNsb3VkZXJhLmNvbT6JAjgEEwECACIFAk5nn7MCGwMG
            CwkIBwMCBhUIAgkKCwQWAgMBAh4BAheAAAoJEFrC2ATt5goQtRUP/1s1WtmnRn0T
            j77eaZ2r6Oyxg5G8rwMoT8T2E69oDC9Vzn9PSQKWoBn7cG6SQTtYhwVkWGbAm+M8
            vzy4KVqnlm3DSLLEhvtdST+qG1XakEyjOJzongbWwrPSbu/qT0UBla6OOFEZALQO
            hl6sfnWdGwvc2j+DcHTzJbx33pGmE5aJf1LqNWt0BleWdRlg4nU9yR2DRmt8nw41
            LorvS5tX5Jazjo1OiJllJOAFkKya9sW2tvApM5AIItxQ7wnGpcPzkq+3osXETVt5
            RzqNdgcz3d3AZaYaOYb10uWbwEQBuOJ8DqzN7Z0Ui5JkCwS7d+sUJfVxrpycEmGw
            MLqTEsYWZPsOx8kDWsn9rfQWJSCMccT3UmroUozZZU+g5ivn8/IuBzazk55qc0L0
            5IUh3rkTTHMFev/0DqoecdbUVlIkttMVXuCV0klSpO30splLewvtDpoabDWteFto
            HXOOEBGifckGsE+SjjzTbbf9dyixbALw+YQM4MvFhwFlEa/tehAanFfqoLn6SCFy
            u6TmRsCyVi7ewV5tsd2esD5F6JMvNogdVlWOmaOVksC8Pn4DQLB7s50sFe9Ks1BH
            Sxnxp0iBAhz4W2+5jQ29hCExvGXGF6gftYllu4VWh66wkT+dLkgIvggpHByONbqm
            z1U9dpCSSrB+TaH23yCtlDyMlr4ybxzquQINBE5nn7MBEADDSIbGBXJLOQVvUDly
            dSwvhypg5mouCXieLuf8xbRESDgXVpf14pAHemaXYFQtHGGZWKAKpsZyZjPYrX3m
            Sm2Ro9r5Qdm80bM+78ZpWerVS7SxdoazGw8sVlR58ybcExJWGZ2I1ROKpFdjowgF
            Yc46FAN9uqZk+1Bqx297EAeZb/K8ebXb0PvRqkq5kyGVsqCTiXaS9EwWPB3gYnn/
            YYsEdl9nmBeIhkg7ta/yHRkndG7vxpSNAPT54yYcONoSkwtK99/h6k3Nqu0Y5ubG
            DOJDXbpggMe2TmY7NI71weRNCFERPzpis+Tz0LePlA6AB/xxsVsuxCSciqcuCPJw
            iJHvp4fNz4pF7C6M/OLAHUIE1mebBS0Hb2BAabjwW+dKDgirdlVURn4D/g61eRN2
            YSO6YKdJYO7/liCd9+p1kv7Q854MqdK0csn7/Uen1ypFEf5kPeeh9bIk49W0YaSh
            8UbpG7uf3kfbKkgpsew/Zy1tUTSrP5lRQG4sqCPXSkqMhc+Kt9TLkcx6vzRuh3YK
            qKf03HcmHFTQVGLx4QzUUTS0W1DIztc7dOSS115qyNLWD4Wi0ewNsXMKcKyEFo8x
            udJV91Pxfp+1UqiFuTdiImMQKTaKumaAdzl2WvrYTNKqFmth/Q2H4l+McTuJeI1R
            edxKyDe8PyCPTpbe6A0Y91y1RwARAQABiQIfBBgBAgAJBQJOZ5+zAhsMAAoJEFrC
            2ATt5goQ3jMP/08alXMmA4OsrKuDTa4jB2d0c5I3sbqoD0HoW30ZQq4j4ruNbyAH
            tP5nlg+CfT1SDoj9GdNn/3agn+rI0dP4+EmQYXrQhykGawr5hU+jtrd7HoiDYyRO
            FJ2J49HnbVibPVgDuHeu0nrsyQrcrSBb9Rxoo0kliVKR52b0yLOhzdug2/B2H9N2
            Gj/Pfh89AHsNoAkppOAJ5qa2yhcCq/zZU8q23w3gXjfyoB0bKROV3ogS+7X7kP3Z
            hSGt6VoukPAJIHsujVonsZXTou4F0HeItvF8tmHs82iG0jU13H35UcNgpWpiuivp
            46oXY4rqNvMNczDMUimMawzANCQqjPVfzXGMcuxK1xN7PwuRw652x6Te263NyFy0
            fsZ56BDS0ga6jj+CeBDfIjs4W0z1l8EWc82bK2Nz5raEzajL2IYf3q2T3No9/ExG
            wY2hydqjA5nlDB/RBOKhOya0cRnWvbaQBHubG9pok63NuCNE+HOUMJ2RjrSTolex
            B5AbnwI/kf27gJnVnmrKMYClbYiCeURsvmlqo0XGZJfWmF4aARUA6bfQ1B/sgZzB
            rnqRSz6lKGFzauX55ylWOvEPP2VpQVr6IYzXcZH4+PHub6MSt867jJAg9ny+uejt
            9CDOBx3hc7LUppcUzbMpIQWW+gKPqX4t6YdL7FHaxtW6MKVkpZOMEtp0mI0EToSh
            dwEEAJ4ETWCNu0fbAWIl/vkXKf9M7MgX/pX2RPOq9uqkDqyP5kwpJXSgYKrd1VVN
            CdAzndI87EC+AZIhO776287r8d+KS4Hf/EsnCtVco/L6cKY5cpqR8a0AIJ48fZpR
            DfJcioQ4JFRpZw4mulIehFdIPhjXaWxYP3LlZ4AMBl7jkRWfABEBAAG0QUNsb3Vk
            ZXJhIExpY2Vuc2luZyAoTGljZW5zZSBTaWduaW5nIEtleSkgPGxpY2Vuc2luZ0Bj
            bG91ZGVyYS5jb20+iLgEEwECACIFAk6EoXcCGy8GCwkIBwMCBhUIAgkKCwQWAgMB
            Ah4BAheAAAoJEPLZ8LCLzgVJFrsD+wSLCtW9mmv4a/subnXMG7Bs7EWCDXEYfLac
            ELzEryhdEUuyGonv7S3Ul+2m6fOkEXV1hQeG9gnFnEPhP/S4f6PjWrXtwZV0EJ7g
            RA88W/gYtDeXXV1AneF81Sm9mqAeBXVDio9WuajlRWy63n8fDmV6UGDKwIZzrlaj
            l+CyqpyimQINBE6WZT4BEAC6SQUZIV6DqbpiX9YHYy2VrwDi88RWTzMq7+KX6jEf
            AJCEnYC4/Ae/73fQj15zTnwunOQF6wi970uvlfjoDgtmMgCrs3iyhPP4MTNhA0Cp
            3jIutzBo6sxRrG3NnBduu8TVT4QnR42rxH6uCRHMC0W+oTBdI0k4joF7XSOdc5M/
            KhvLCU0Ey77W6vb/uL45Nbn9RD7/1BL2zQUvBd940luoqyeV+nlDgMTz4tUzaUaU
            nyz94JCTD8kcaE7c1Vj2oBCe6qMU7efBr++XEe4Z0d7mGdGoPA0MBxTY3rAI8wud
            SThWBr8KsQGQM6iyPqzUl4xppPENXuWw2S4qUyxyuwOQDiR5nmlSW960uHYAbM8m
            s5J7wtxIwJvjRqJirMtfViPWDVkxGP4QuXBDDwOS9R8S69kdjRLvL7hruASdicFs
            G8tRqZbILg6xA5UeocrYUJAqLNbsL3c66GZ5rwWJSZLVzPHVCpl/7hk2JF1CI2JF
            4AqfU8ixnpPyG+MWPGeC2Ce6YaJaMC4+G9zUNrEWXi7mTq30GrHiDX8H58alIE1F
            5m61K14GnKg8J3zvKXNdSfYWHlVqM9HeiqXu/H7uLpVTMjk31Shk+xp1/Kxv7Fj5
            ee418SxSOpC9XOwi4esX7L8d+dnjf7nxWhz0E3T1zZ0GDZpul9ys0dfepN96GJ0T
            wQARAQABtFdDbG91ZGVyYSBFbnRlcnByaXNlIExpY2Vuc2luZyAoRW50ZXJwcmlz
            ZSBMaWNlbnNlIFNpZ25pbmcgS2V5KSA8bGljZW5zaW5nQGNsb3VkZXJhLmNvbT6J
            AjYEEwECACAFAk6WZT4CGwMGCwkIBwMCBBUCCAMEFgIDAQIeAQIXgAAKCRAl8EVy
            5h/LZavID/0UfpBpJ9DuRW9Rn5wpObKBXU25suhJfiYVQomlMKdixd09DcBNP1aO
            6cVOHtpe1DQ6DXwCvkCc/FPDwG2LGGYfxyuxJVu3x/GBITCOIxqhojvWPGKRsGQH
            2ciEZyzsSsDxU/smsrFjuYPe3yA1k4Vlb+fLCHd/urZMjmC5zLcLmTbDvIi1FxEA
            l4VJG2kLNUV2RSU/1BAPDag4MFE96vNIOUmsKWA4Rgc/MWUkCiRqBGvFq8fChcT7
            eFPOjhzd+1sCsMION3ngl+JMswT/8PyipPBjobfWk9BlpT8Ci85yy94qF0+gfHUD
            Wm4AnqmAmxww1n6wv/bo3RaN+hIb4bhTqz+QEODI2R4fcjpZj+YPORIK8YZORi5H
            CEjs2cV0qwiZ/9UgPPf/tO+FARV3swZ/WJB8gaUKD55oqgZHiP/CEz8ENjSjo459
            uAhJOohUdJhLylQHGo0tZBzI04uWhhI3zCixZ9gUIxr/42w1Y3MGXugqvO1zUhnf
            A3lKopJAFXNRcpciLXhU6edXTxLUF+Sr+qyyW5DqFD1t1Js3OmsjP25wUpGs/GmS
            dV5+mOd4dslW1phozkh+CMLD79lRTHs6OKS+/McR0omCtucJDEkx5z9BrEtRQDC4
            5NMoVQRvH6X/IfTt58hF66fuaqyEX32uS6uLdGGAJ//V6dLyQxWujw==
            =TmBA
            -----END PGP PUBLIC KEY BLOCK-----""";
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.findAndRegisterModules();
    }

    private final Supplier<LocalDate> currentDateSupplier;

    /**
     * Constructor to create LicenseUtils object
     */
    public LicenseUtils() {
        this(() -> LocalDate.now(ZoneOffset.UTC));
    }

    LicenseUtils(Supplier<LocalDate> currentDateSupplier) {
        this.currentDateSupplier = currentDateSupplier;
    }

    /**
     * Check license if it is active, so period started and not expired yet (even with the grace period).
     * The method ensures that a license was provided.
     *
     * @param license the license object
     * @return the status of the license
     */
    public LicenseState checkLicenseState(License license) {
        if (license == null) {
            return LicenseState.MISSING;
        }

        if (!license.hasFeature(STREAMING_FEATURE)) {
            return LicenseState.FEATURE_MISSING;
        }

        var startDate = license.getStartDate();
        var expirationDate = license.getExpirationDate();
        if (startDate == null || expirationDate == null) {
            return LicenseState.DATE_MISSING;
        }

        var now = currentDateSupplier.get();
        if (now.isBefore(startDate)) {
            return LicenseState.INACTIVE;
        }

        if (now.isBefore(expirationDate.plusDays(1))) {
            return LicenseState.ACTIVE;
        }

        var gracePeriodEndDate = getGracePeriodEndDate(license);
        if (now.isBefore(gracePeriodEndDate.plusDays(1))) {
            return LicenseState.GRACE_PERIOD;
        }

        return LicenseState.INACTIVE;
    }

    /**
     * Verify license bytes and returns a license object.
     *
     * @param licenseBytes the license key data in bytes
     * @return the license object after verification
     */
    public License getVerifiedLicense(byte[] licenseBytes) {
        if (licenseBytes == null || licenseBytes.length == 0) {
            throw new RuntimeException("No license key was provided");
        }

        var licenseJson = getVerifiedLicenseJson(licenseBytes);
        try {
            var license = MAPPER.readValue(licenseJson, License.class);
            if (license == null) {
                throw new RuntimeException("Decoded license was empty.");
            }
            return license;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to decode license after signature verification.", e);
        }
    }

    private LocalDate getGracePeriodEndDate(License license) {
        var gracePeriodEndDateCandidate = license.getExpirationDate().plusMonths(DEFAULT_GRACE_PERIOD_MONTH);
        var deactivationDate = license.getDeactivationDate();

        if (deactivationDate == null) {
            return gracePeriodEndDateCandidate;
        }

        return Collections.max(List.of(gracePeriodEndDateCandidate, deactivationDate));
    }

    private String getVerifiedLicenseJson(byte[] licenseBytes) {
        try {
            var keyIn = new ArmoredInputStream(
                    new ByteArrayInputStream(ENTERPRISE_LICENSE_PUB_KEY.getBytes(StandardCharsets.UTF_8)));
            Security.addProvider(new BouncyCastleProvider());
            return getVerifiedData(licenseBytes, keyIn);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read license Verification key. This should never happen.", e);
        }
    }

    private String getVerifiedData(byte[] licenseBytes, InputStream keyIn) {
        var dataOut = new ByteArrayOutputStream();
        var in = new ByteArrayInputStream(licenseBytes);
        if (!isVerified(keyIn, dataOut, in)) {
            throw new RuntimeException("License signature verification failed.");
        }
        return dataOut.toString(StandardCharsets.UTF_8);
    }

    private static boolean isVerified(InputStream keyIn, ByteArrayOutputStream dataOut, ByteArrayInputStream in) {
        try {
            var fileSignature = ClearSignedFileProcessor.getFileSignature(in, keyIn, dataOut);
            return fileSignature.verify();
        } catch (Exception e) {
            throw new RuntimeException("Failed to complete verification of license key.", e);
        }
    }
}
