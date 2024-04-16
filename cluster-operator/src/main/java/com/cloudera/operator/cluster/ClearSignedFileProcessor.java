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
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A utility class that creates clear signed files and verifies them.
 */
public class ClearSignedFileProcessor {
    /**
     * Get the contents and PGPSignature from a clear text signed file
     *
     * @param in            the incoming data as inputstream
     * @param keyIn         the key as inputstream
     * @param dataOut       the outputstream to write the message
     * @throws IOException  if input/output read/wirte fails, then exception will be thrown
     * @throws PGPException if any error happens with the PGP, then exception will be thrown
     * @return the license object after verification
     */
    public static PGPSignature getFileSignature(InputStream in, InputStream keyIn, OutputStream dataOut)
            throws IOException, PGPException {
        var aIn = new ArmoredInputStream(in);
        var out = new ByteArrayOutputStream();

        var lineOut = new ByteArrayOutputStream();
        var lookAhead = readInputLine(lineOut, aIn);
        var lineSep = getLineSeparator();

        if (lookAhead != -1 && aIn.isClearText()) {
            var line = lineOut.toByteArray();
            out.write(line, 0, getLengthWithoutSeperator(line));
            out.write(lineSep);

            while (lookAhead != -1 && aIn.isClearText()) {
                lookAhead = readInputLine(lineOut, lookAhead, aIn);

                line = lineOut.toByteArray();
                out.write(line, 0, getLengthWithoutSeperator(line));
                out.write(lineSep);
            }
        }

        out.close();
        var outBytes = out.toByteArray();
        dataOut.write(outBytes);

        var pgpRings = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(keyIn), new JcaKeyFingerprintCalculator());
        var pgpFact = new JcaPGPObjectFactory(aIn);
        var p3 = (PGPSignatureList) pgpFact.nextObject();
        var sig = p3.get(0);
        var key = pgpRings.getPublicKey(sig.getKeyID());

        sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
        var sigIn = new ByteArrayInputStream(outBytes);
        lookAhead = readInputLine(lineOut, sigIn);
        processLine(sig, lineOut.toByteArray());

        if (lookAhead != -1) {
            do {
                lookAhead = readInputLine(lineOut, lookAhead, sigIn);
                sig.update((byte) '\r');
                sig.update((byte) '\n');
                processLine(sig, lineOut.toByteArray());
            } while (lookAhead != -1);
        }

        return sig;
    }

    private static int readInputLine(ByteArrayOutputStream bOut, InputStream fIn) throws IOException {
        bOut.reset();
        var lookAhead = -1;
        int ch;

        while ((ch = fIn.read()) >= 0) {
            bOut.write(ch);
            if (ch == '\r' || ch == '\n') {
                lookAhead = readPassedEOL(bOut, ch, fIn);
                break;
            }
        }

        return lookAhead;
    }

    private static int readInputLine(ByteArrayOutputStream bOut, int lookAhead, InputStream fIn) throws IOException {
        bOut.reset();
        var ch = lookAhead;

        do {
            bOut.write(ch);
            if (ch == '\r' || ch == '\n') {
                lookAhead = readPassedEOL(bOut, ch, fIn);
                break;
            }
        } while ((ch = fIn.read()) >= 0);

        return lookAhead;
    }

    private static int readPassedEOL(ByteArrayOutputStream bOut, int lastCh, InputStream fIn) throws IOException {
        var lookAhead = fIn.read();
        if (lastCh == '\r' && lookAhead == '\n') {
            bOut.write(lookAhead);
            lookAhead = fIn.read();
        }
        return lookAhead;
    }

    private static void processLine(PGPSignature sig, byte[] line) {
        var length = getLengthWithoutWhiteSpace(line);
        if (length > 0) {
            sig.update(line, 0, length);
        }
    }

    private static int getLengthWithoutWhiteSpace(byte[] line) {
        var end = line.length - 1;
        while (end >= 0 && isWhiteSpace(line[end])) {
            end--;
        }
        return end + 1;
    }

    private static int getLengthWithoutSeperator(byte[] line) {
        var end = line.length - 1;
        while (end >= 0 && isLineEnding(line[end])) {
            end--;
        }
        return end + 1;
    }

    private static byte[] getLineSeparator() {
        var nl = System.getProperty("line.separator");
        var nlBytes = new byte[nl.length()];
        for (int i = 0; i != nlBytes.length; i++) {
            nlBytes[i] = (byte) nl.charAt(i);
        }
        return nlBytes;
    }

    private static boolean isWhiteSpace(byte b) {
        return b == '\r' || b == '\n' || b == '\t' || b == ' ';
    }

    private static boolean isLineEnding(byte b) {
        return b == '\r' || b == '\n';
    }
}
