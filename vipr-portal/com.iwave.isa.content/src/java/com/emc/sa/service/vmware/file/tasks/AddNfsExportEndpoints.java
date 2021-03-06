/*
 * Copyright 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vmware.file.tasks;

import java.net.URI;
import java.util.Set;

import org.apache.commons.lang.StringUtils;

import com.emc.sa.service.vipr.tasks.WaitForTask;
import com.emc.storageos.model.file.FileExportUpdateParam;
import com.emc.storageos.model.file.FileShareRestRep;
import com.emc.storageos.model.file.FileSystemExportParam;
import com.emc.vipr.client.Task;
import com.google.common.collect.Lists;

public class AddNfsExportEndpoints extends WaitForTask<FileShareRestRep> {
    private URI fileSystemId;
    private FileSystemExportParam export;
    private Set<String> endpoints;

    public AddNfsExportEndpoints(URI fileSystemId, FileSystemExportParam export, Set<String> endpoints) {
        this.fileSystemId = fileSystemId;
        this.export = export;
        this.endpoints = endpoints;
        provideDetailArgs(StringUtils.join(endpoints, ", "), export.getMountPoint(), fileSystemId);
    }

    @Override
    protected Task<FileShareRestRep> doExecute() throws Exception {
        String protocol = export.getProtocol();
        String securityType = export.getSecurityType();
        String permissions = export.getPermissions();
        String rootMapping = export.getRootUserMapping();

        FileExportUpdateParam update = new FileExportUpdateParam();
        update.setAdd(Lists.newArrayList(endpoints));

        return getClient().fileSystems().updateExport(fileSystemId, protocol, securityType, permissions, rootMapping,
                update);
    }
}
