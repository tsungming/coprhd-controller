/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.model.protection;

import java.util.Set;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;

import org.codehaus.jackson.annotate.JsonProperty;

public class RPClusterVirtualArrayResourceUpdateParam {

	private Set<RPClusterVirtualArrayAssignmentChanges> varrayAssignmentChanges;

	public RPClusterVirtualArrayResourceUpdateParam() {}

	public RPClusterVirtualArrayResourceUpdateParam(Set<RPClusterVirtualArrayAssignmentChanges> varrayChanges) {
		varrayAssignmentChanges = varrayChanges;
	}

	/**
	 * The list of virtual arrays to be added to or removed from the resource.
	 * 
	 * @valid none
	 */
	@XmlElementWrapper(name = "varray_assignment_changes")
	@XmlElement(name = "varray_assignment_change")
	@JsonProperty("varray_assignment_changes")
	public Set<RPClusterVirtualArrayAssignmentChanges> getVarrayChanges() {
		return varrayAssignmentChanges;
	}

	public void setVarrayChanges(Set<RPClusterVirtualArrayAssignmentChanges> varrayChanges) {
		varrayAssignmentChanges = varrayChanges;
	}
}
