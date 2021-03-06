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
package com.emc.storageos.db.client.upgrade.callbacks;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.DiscoveredDataObject.DiscoveryStatus;
import com.emc.storageos.db.client.upgrade.BaseCustomMigrationCallback;

public class StoragePortDiscoveryStatusMigration extends
        BaseCustomMigrationCallback {
    
    private static final Logger log = LoggerFactory.getLogger(StoragePortDiscoveryStatusMigration.class);

    @Override
    public void process() {
        DbClient dbClient = getDbClient();
        List<URI> portIds = dbClient.queryByType(StoragePort.class, true);
        Iterator<StoragePort> ports = dbClient.queryIterativeObjects(StoragePort.class, portIds);
        List<StoragePort> modifiedPorts = new ArrayList<StoragePort>();
        while (ports.hasNext()) {
            // set default value of DiscoveryStatus to VISIBLE
            StoragePort port = ports.next();
            log.info("Setting discovery status of " + port.getId() +
                    " to " + DiscoveryStatus.VISIBLE);
            port.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
            modifiedPorts.add(port);
        }        
        dbClient.persistObject(modifiedPorts);
    }
}
