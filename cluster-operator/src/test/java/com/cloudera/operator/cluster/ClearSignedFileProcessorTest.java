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

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.Security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClearSignedFileProcessorTest {
    private static final String DATA = """
            {
              "version"        : 1,
              "name"           : "foobar",
              "uuid"           : "12c8052f-d78f-4a8e-bba4-a55a2d141fc8",
              "expirationDate" : [1981,3,2]
            }
            """;

    private static final String SIGNED_DATA = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            {
              "version"        : 1,
              "name"           : "foobar",
              "uuid"           : "12c8052f-d78f-4a8e-bba4-a55a2d141fc8",
              "expirationDate" : [1981,3,2]
            }
            -----BEGIN PGP SIGNATURE-----
            Version: BCPG v1.46

            iQJpBAEBCgBTBQJOZ84vTBxDbG91ZGVyYSBVbml0IFRlc3RzIChUaGlzIGlzIGEg
            a2V5IGZvciB0ZXN0aW5nIHB1cnBvc2VzKSA8ZW5nQGNsb3VkZXJhLmNvbT4ACgkQ
            WsLYBO3mChBgSRAAqzXy1DN/oxeSf/bhblPduLXq3uIFmAO2VSvBaigrwkJXX9JK
            6tw6QZNcMpqBBf0p8AhQ6zLkZuUqoMYLWKdLLOtOyMwFv/npahifDvS/CIfpKR2A
            jYvq7XZLSI01lhkxRhIzf92tHS83utPAN/f13op7zjhPS4TVMeyvGsZ7bZB+k2Yn
            zBRD2zuJoJvZlVVXGPqmZyOb7CxpJzUKZG+Q8r3xm5hTnbep3qnOdWQfCeEiZ079
            sa1YwDEH7DTnzjsVENo7Qjtymbeds8doAZ8zJK0NQPQzfs9PQlFcpPPJVM4ChJRe
            Jn2gSv5HLetjsaEt6shUAf8HNyJ78f9x73/NNsHvjxmf8pvqadOCyK6y//sn3XdB
            CxAkQ9E1vwAAHkWFuV1eCGoLO93CEOBvl035adwp2ePVZ8Fmt6JtJJE6pfuP9XaU
            nVfmVbDUb7wVpoRpWjS+rOOVqnrC+Hg7aA+EGZgl9bnHXPKWGBYniMlsTNdbLz7E
            d1NIXn+1wKP/nvXUNFk1A3hI0MTa8zu3cte1+V7wRAz0qDzAEvY/k+EyxiGTItV6
            E02L8fQZOvexhoh9Y/+4oT6c7PNdb6u8KDg3DvLwJZaN6YmhSI1irPUAz211gJGO
            k1UGvaH/I1RLF6aMG7LqOMocqZTj30+lnsNOtSH9DSYuZU2u2DPDf427C8c=
            =/aH9
            -----END PGP SIGNATURE-----""";

    private static final String INVALID_SIGNED_DATA = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            {
              "version"        : 1,
              "name"           : "foobar",
              "uuid"           : "12c8052f-d78f-4a8e-bba4-a55a2d141fc8",
              "expirationDate" : [1981,3,2]
            }
            -----BEGIN PGP SIGNATURE-----
            Version: BCPG v1.46

            WRONG SIGNATURE
            -----END PGP SIGNATURE-----""";

    private static final String PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----
            Version: GnuPG v1.4.11 (GNU/Linux)

            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
            =MYPL
            -----END PGP PUBLIC KEY BLOCK-----""";

    private static final String PRIVATE_PASSWORD = "cloudera";

    @Test
    public void testVerify() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        ByteArrayInputStream signedIn = new ByteArrayInputStream(SIGNED_DATA.getBytes());
        ArmoredInputStream aKeyIn = new ArmoredInputStream(new ByteArrayInputStream(PUBLIC_KEY.getBytes()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        boolean verified = ClearSignedFileProcessor.getFileSignature(signedIn, aKeyIn, out).verify();

        assertTrue(verified);
        assertEquals(DATA, out.toString());
    }

    @Test
    public void testVerifyWhenDataIsInvalid() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        ByteArrayInputStream signedIn = new ByteArrayInputStream(INVALID_SIGNED_DATA.getBytes());
        ArmoredInputStream aKeyIn = new ArmoredInputStream(new ByteArrayInputStream(PUBLIC_KEY.getBytes()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(IOException.class, () -> ClearSignedFileProcessor.getFileSignature(signedIn, aKeyIn, out));
    }
}
