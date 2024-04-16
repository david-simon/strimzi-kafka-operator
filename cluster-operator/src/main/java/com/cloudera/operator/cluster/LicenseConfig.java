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

import io.strimzi.operator.common.operator.resource.ConfigParameter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.strimzi.operator.common.operator.resource.ConfigParameterParser.NON_EMPTY_STRING;

/**
 * Configuration class for the license
 */
public class LicenseConfig {

    private static final Map<String, ConfigParameter<?>> CONFIG_VALUES = new HashMap<>();

    /**
     * Default name of the license secret
     */
    public static final String DEFAULT_LICENSE_SECRET_NAME = "csm-op-license";

    /**
     * Name of the license secret
     */
    public static final ConfigParameter<String> ENV_VAR_LICENSE_SECRET_NAME = new ConfigParameter<>(
            "LICENSE_SECRET_NAME",
            NON_EMPTY_STRING,
            DEFAULT_LICENSE_SECRET_NAME,
            CONFIG_VALUES
    );

    /**
     * Creates the license configuration from Map with environment variables
     * @param map   Map with environment variables
     * @return  Instance of LicenseConfig
     */
    public static LicenseConfig fromMap(Map<String, String> map) {
        Map<String, String> envMap = new HashMap<>(map);
        envMap.keySet().retainAll(keyNames());
        Map<String, Object> generatedMap = ConfigParameter.define(envMap, CONFIG_VALUES);
        return new LicenseConfig(generatedMap);

    }

    /**
     * Creates the license configuration from existing map
     * @param map   Map with environment variables
     * @return  Instance of LicenseConfig
     */
    public static LicenseConfig buildFromExistingMap(Map<String, Object> map) {
        Map<String, Object> existingMap = new HashMap<>(map);
        existingMap.keySet().retainAll(keyNames());
        return new LicenseConfig(existingMap);
    }

    private final Map<String, Object> map;

    /**
     * Constructor
     * @param map Map containing configurations and their respective values
     */
    private LicenseConfig(Map<String, Object> map) {
        this.map = map;
    }

    /**
     * @return Set of configuration key/names
     */
    public static Set<String> keyNames() {
        return Collections.unmodifiableSet(CONFIG_VALUES.keySet());
    }

    /**
     * @return  Configuration values map
     */
    public static Map<String, ConfigParameter<?>> configValues() {
        return Collections.unmodifiableMap(CONFIG_VALUES);
    }

    @SuppressWarnings("unchecked")
    private  <T> T get(ConfigParameter<T> value) {
        return (T) map.get(value.key());
    }

    /**
     * @return  Returns the name of the license secret
     */
    public String getLicenseSecretName() {
        return get(ENV_VAR_LICENSE_SECRET_NAME);
    }

    @Override
    public String toString() {
        return "LicenseConfig{\n\tlicenseSecretName='" + getLicenseSecretName() + "'\n}";
    }
}
