/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.datadomain.restapi.model;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.annotate.JsonRootName;

/**
 * Created by zeldib on 2/10/14.
 */
@JsonRootName(value="mtree_create")
public class DDMTreeCreate {

	// full path for the mtree
    private String name;

    private String tenant;

    @SerializedName("tenant_unit")
    @JsonProperty(value="tenant_unit")
    private String tenantUnit;

    @SerializedName("quota_config")
    @JsonProperty(value="quota_config")
    private DDQuotaConfig quota;

    public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTenant() {
		return tenant;
	}

	public void setTenant(String tenant) {
		this.tenant = tenant;
	}

	public String getTenantUnit() {
		return tenantUnit;
	}

	public void setTenantUnit(String tenantUnit) {
		this.tenantUnit = tenantUnit;
	}

	public DDQuotaConfig getQuota() {
		return quota;
	}

	public void setQuota(DDQuotaConfig quota) {
		this.quota = quota;
	}

	public String toString() {
        return new Gson().toJson(this).toString();
    }

}
