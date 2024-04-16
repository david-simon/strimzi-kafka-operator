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

package com.cloudera.operator.cluster.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.zjsonpatch.internal.guava.Preconditions;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Model class for Cloudera license
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class License {
    private LocalDate deactivationDate;
    private LocalDate expirationDate;
    private LocalDate startDate;
    private Set<String> features;
    private String name;
    private UUID uuid;
    private int version;

    /**
     * No args constructor
     */
    public License() {
    }

    /**
     * All args constructor
     * @param deactivationDate  the deactivation date of the license
     * @param expirationDate    the expiration date of the license
     * @param startDate         the start date of the license
     * @param features          the features of the license
     * @param name              the name of the license
     * @param uuid              the uuid of the license
     * @param version           the version of the license
     */
    public License(LocalDate deactivationDate, LocalDate expirationDate, LocalDate startDate, Set<String> features,
                   String name, UUID uuid, int version) {
        this.deactivationDate = deactivationDate;
        this.expirationDate = expirationDate;
        this.startDate = startDate;
        this.features = features;
        this.name = name;
        this.uuid = uuid;
        this.version = version;
    }

    /**
     * @return deactivation date
     */
    public LocalDate getDeactivationDate() {
        return deactivationDate;
    }

    /**
     * @param deactivationDate the deactivationDate to set
     */
    public void setDeactivationDate(LocalDate deactivationDate) {
        this.deactivationDate = deactivationDate;
    }

    /**
     * @return expiration date
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * @param expirationDate the expirationDate to set
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * @return start date
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * @param startDate the startDate to set
     */
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    /**
     * @return start date
     */
    public Set<String> getFeatures() {
        return features;
    }

    /**
     * @param features the features collection to set
     */
    public void setFeatures(Set<String> features) {
        if (features != null) {
            this.features = Collections.unmodifiableSet(features);
        }
    }

    /**
     * Method to check whether the license has the specifid feature
     * @param feature   the specified feature
     * @return true if the feature is present in the license feature list
     */
    public boolean hasFeature(String feature) {
        Preconditions.checkNotNull(feature, "Invalid feature.");
        return
                features != null
                && features
                        .stream()
                        .anyMatch(f -> f.contains(feature + ':') || f.equalsIgnoreCase(feature));
    }

    /**
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * @param uuid the uuid to set
     */
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * @return version
     */
    public int getVersion() {
        return version;
    }

    /**
     * @param version the version to set
     */
    public void setVersion(int version) {
        this.version = version;
    }
}
