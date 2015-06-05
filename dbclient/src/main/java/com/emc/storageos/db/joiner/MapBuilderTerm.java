/**
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
package com.emc.storageos.db.joiner;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Map "term" represents a single push operation.
 * @author watson
 *
 */
class MapBuilderTerm {
    MapBuilderTermType type;
    JClass jclass;
    String alias;
    List<JClass> joinPath;
    Map<URI, Set<URI>> duples;
    Map<URI, Map> map;
}