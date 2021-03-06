/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 * Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.volumecontroller.impl.smis.vmax;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.NamedElementQueryResultList;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.exceptions.DeviceControllerExceptions;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerServiceImpl;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.job.QueueJob;
import com.emc.storageos.volumecontroller.impl.providerfinders.FindProviderFactory;
import com.emc.storageos.volumecontroller.impl.smis.AbstractSnapshotOperations;
import com.emc.storageos.volumecontroller.impl.smis.CIMPropertyFactory;
import com.emc.storageos.volumecontroller.impl.smis.ReplicationUtils;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.smis.SmisException;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisBlockCreateCGSnapshotJob;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisBlockCreateSnapshotJob;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisBlockRestoreSnapshotJob;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisBlockResumeSnapshotJob;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisCreateVmaxCGTargetVolumesJob;
import com.emc.storageos.volumecontroller.impl.smis.job.SmisDeleteVmaxCGTargetVolumesJob;
import com.google.common.base.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cim.CIMArgument;
import javax.cim.CIMInstance;
import javax.cim.CIMObjectPath;
import javax.wbem.CloseableIterator;
import javax.wbem.WBEMException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.emc.storageos.volumecontroller.impl.smis.ReplicationUtils.callEMCRefreshIfRequired;
import static com.emc.storageos.volumecontroller.impl.smis.SmisConstants.*;
import static com.google.common.collect.Collections2.filter;
import static java.text.MessageFormat.format;

public class VmaxSnapshotOperations extends AbstractSnapshotOperations {
    private static final Logger _log = LoggerFactory.getLogger(VmaxSnapshotOperations.class);
    private static final String[] PL_ONLY_EMC_UNIQUE_ID = new String[]{CP_EMC_UNIQUE_ID};
    private static final String[] PL_STORAGE_EXTENT_INITIAL_USAGE = new String[]{CP_STORAGE_EXTENT_INITIAL_USAGE};
    private static final String VMAX10K = "VMAX10K";

    private FindProviderFactory findProviderFactory;
    
    public void setFindProviderFactory(final FindProviderFactory findProviderFactory) {
        this.findProviderFactory = findProviderFactory;
    }

    /**
     * This interface is for the snapshot active. The createSnapshot may have done
     * whatever is necessary to setup the snapshot for this call. The goal is to
     * make this a quick operation and the create operation has already done a lot
     * of the "heavy lifting".
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void activateSingleVolumeSnapshot(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            BlockSnapshot snapshotObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            if (snapshotObj.getIsSyncActive()) {
                _log.warn("Trying to activate snapshot, which is already active",
                        snapshotObj.getId().toString());
                return;
            }
            _log.info("activateSingleVolumeSnapshot operation START");
            CIMArgument[] inArgs = _helper.getActivateSnapshotInputArguments(storage, snapshotObj);
            CIMArgument[] outArgs = new CIMArgument[5];
            _helper.callModifyReplica(storage, inArgs, outArgs);
            setIsSyncActive(snapshotObj, true);
            snapshotObj.setRefreshRequired(true);
            _dbClient.persistObject(snapshotObj);
            // Success -- Update status
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _log.info("Problem making SMI-S call: ", e);
            ServiceError error = DeviceControllerErrors.smis.unableToCallStorageProvider(e.getMessage());
            taskCompleter.error(_dbClient, error);
        } finally {
            _log.info("activateSingleVolumeSnapshot operation END");
        }
    }

    /**
     * This interface is for the snapshot active. The createSnapshot may have done
     * whatever is necessary to setup the snapshot for this call. The goal is to
     * make this a quick operation and the create operation has already done a lot
     * of the "heavy lifting".
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void activateGroupSnapshots(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            _log.info("activateGroupSnapshots operation START");
            BlockSnapshot snapshotObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            if (snapshotObj.getIsSyncActive()) {
                _log.warn("Trying to activate CG snapshot, which is already active",
                        snapshotObj.getId().toString());
                return;
            }
            
            // Check if the consistency group exists
            String groupName = _helper.getConsistencyGroupName(snapshotObj, storage);
            storage = findProviderFactory.withGroup(storage, groupName).find();

            if (storage == null) {
                ServiceError error = DeviceControllerErrors.smis.noConsistencyGroupWithGivenName();
                taskCompleter.error(_dbClient, error);
                return;
            }
            
            Volume sourceVolume = _dbClient.queryObject(Volume.class, snapshotObj.getParent().getURI());
            boolean isSuccess = VmaxGroupOperationsUtils.activateGroupReplicas(storage, sourceVolume, snapshotObj,
                    SYNC_TYPE.SNAPSHOT, taskCompleter, _dbClient, _helper, _cimPath);
            if (isSuccess) {
	            List<BlockSnapshot> snapshots = ControllerUtils.getBlockSnapshotsBySnapsetLabelForProject(snapshotObj, _dbClient);
	            setIsSyncActive(snapshots, true);
                for (BlockSnapshot it : snapshots) {
                    it.setRefreshRequired(true);
                }
                _dbClient.persistObject(snapshots);
                taskCompleter.ready(_dbClient);
            }
        } catch (Exception e) {
            _log.info("Problem making SMI-S call: ", e);
            ServiceError error = DeviceControllerErrors.smis.unableToCallStorageProvider(e.getMessage());
            taskCompleter.error(_dbClient, error);
        }  finally {
            _log.info("activateGroupSnapshots operation END");
        }
    }

    /**
     * Should implement deletion of single volume snapshot. That is, deleting a snap that was
     * created independent of other volumes.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void deleteSingleVolumeSnapshot(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            callEMCRefreshIfRequired(_dbClient, _helper, storage, Arrays.asList(snapshot));
            BlockSnapshot snap = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            CIMObjectPath syncObjectPath = _cimPath.getSyncObject(storage, snap);
            if (_helper.checkExists(storage, syncObjectPath, false, false) != null) {
                deactivateSnapshot(storage, snap, syncObjectPath);
                if (storage.checkIfVmax3()) {
                    _helper.removeVolumeFromParkingSLOStorageGroup(storage, snap.getNativeId(), false);
                    _log.info("Done invoking remove volume {} from parking SLO storage group", snap.getNativeId());
                }
                CIMArgument[] outArgs = new CIMArgument[5];
                _helper.callModifyReplica(storage, _helper.getDeleteSnapshotSynchronousInputArguments(syncObjectPath), outArgs);
                snap.setInactive(true);
                snap.setIsSyncActive(false);
                _dbClient.persistObject(snap);
                taskCompleter.ready(_dbClient);
            } else {
                // Perhaps, it's already been deleted or was deleted on the array.
                // In that case, we'll just say all is well, so that this operation
                // is idempotent.
                snap.setInactive(true);
                snap.setIsSyncActive(false);
                _dbClient.persistObject(snap);
                taskCompleter.ready(_dbClient);
            }
        } catch (WBEMException e) {
            String message = String.format("Error encountered during delete snapshot %s on array %s",
                    snapshot.toString(), storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.unableToCallStorageProvider(e.getMessage());
            taskCompleter.error(_dbClient, error);
        } catch (Exception e) {
            String message = String.format("Generic exception when trying to delete snapshot %s on array %s",
                    snapshot.toString(), storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.methodFailed("deleteSingleVolumeSnapshot", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }

    /**
     * Should implement creation of a single volume snapshot. That is a volume that
     * is not in any consistency group.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void createSingleVolumeSnapshot(StorageSystem storage, URI snapshot, Boolean createInactive, TaskCompleter taskCompleter) throws DeviceControllerException {
        // List of target device ids
        List<String> targetDeviceIds = null;
        try {
            BlockSnapshot snapshotObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            _log.info("createSingleVolumeSnapshot operation START");
            Volume volume = _dbClient.queryObject(Volume.class, snapshotObj.getParent());
            // Need to terminate an restore sessions, so that we can
            // restore from the same snapshot multiple times
            terminateAnyRestoreSessionsForVolume(storage, volume, taskCompleter);
            TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, volume.getTenant().getURI());
            String tenantName = tenant.getLabel();
            String snapLabelToUse =
                    _nameGenerator.generate(tenantName, snapshotObj.getLabel(),
                            snapshot.toString(), '-', storage.getUsingSmis80()? SmisConstants.MAX_SMI80_SNAPSHOT_NAME_LENGTH : SmisConstants.MAX_SNAPSHOT_NAME_LENGTH);
            CIMObjectPath replicationSvcPath = _cimPath.getControllerReplicationSvcPath(storage);
            CIMArgument[] inArgs = null;
            CIMArgument[] outArgs = new CIMArgument[5];
            if (storage.checkIfVmax3()) {
                CIMObjectPath volumeGroupPath = _helper.getVolumeGroupPath(storage, volume, null);
                CIMObjectPath poolPath = findSnapStoragePoolOrNull(storage);
                targetDeviceIds = createTargetDevices(storage, poolPath, volumeGroupPath, null, "SingleSnapshot", snapLabelToUse,
                        createInactive, 1, volume.getCapacity(), taskCompleter);
                CIMInstance replicaSettingData = _helper.getReplicationSettingData(storage, targetDeviceIds.get(0), false);
                inArgs = _helper.getCreateElementReplicaSnapInputArgumentsWithTargetAndSetting(storage, volume, targetDeviceIds.get(0), replicaSettingData, createInactive, snapLabelToUse);
            }
            else {
                if (volume.getThinlyProvisioned()) {
                    CIMInstance replicationSetting = ReplicationUtils.getVPSnapReplicationSetting(storage, _helper, _cimPath);

                    inArgs = _helper.getCreateElementReplicaVPSnapInputArguments(storage, volume, createInactive,
                            snapLabelToUse, replicationSetting);
                } else {
                    inArgs = _helper.getCreateElementReplicaSnapInputArguments(storage, volume, createInactive,
                            snapLabelToUse);
                }
            }
            _helper.invokeMethod(storage, replicationSvcPath, SmisConstants.CREATE_ELEMENT_REPLICA, inArgs, outArgs);
            CIMObjectPath job = _cimPath.getCimObjectPathFromOutputArgs(outArgs, SmisConstants.JOB);
            if (job != null) {
                ControllerServiceImpl.enqueueJob(
                        new QueueJob(new SmisBlockCreateSnapshotJob(job,
                                storage.getId(), !createInactive, taskCompleter)));
            }
        } catch (Exception e) {
            _log.info("Problem making SMI-S call: ", e);
            ServiceError error = DeviceControllerErrors.smis.unableToCallStorageProvider(e.getMessage());
            taskCompleter.error(_dbClient, error);
            setInactive(snapshot, true);
            // Roll back changes
            if (storage.checkIfVmax3()) {
                rollbackCreateSnapshot(storage, null, targetDeviceIds, taskCompleter);
            }
        }
    }

    /**
     * Should implement create of a snapshot from a source volume that is part of a
     * consistency group.
     *
     * Implementation note: In this method we will kick of the asynchronous creation
     * of the target devices required for the CG snaps. Upon the successful
     * device creation, the post operations will take place, which will include the
     * creation of the target group and the group snapshot operation.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshotList      [required] - BlockSnapshot URI list representing the previously created
     *                      snap for the volume
     * @param createInactive  whether the snapshot needs to to be created with sync_active=true/false                      
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     * @throws DeviceControllerException 
     */
    @Override
    public void createGroupSnapshots(StorageSystem storage, List<URI> snapshotList,
                                     Boolean createInactive, TaskCompleter taskCompleter) throws DeviceControllerException {

    	// Target group CIM Path
    	CIMObjectPath targetGroupPath = null;
    	
    	// List of target device ids
    	List<String> targetDeviceIds = null;
    	
    	// The source consistency group name
    	String sourceGroupName = null;
    	
    	try {
        	final BlockSnapshot first = _dbClient.queryObject(BlockSnapshot.class, snapshotList.get(0));
        	sourceGroupName = _helper.getConsistencyGroupName(first, storage);
            Volume snapVolume = _dbClient.queryObject(Volume.class, first.getParent());
            boolean thinProvisioning = snapVolume.getThinlyProvisioned() != null && snapVolume.getThinlyProvisioned();
            TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, snapVolume.getTenant().getURI());
            String tenantName = tenant.getLabel();
            final String snapLabelToUse = _nameGenerator.generate(tenantName, first.getLabel(),
                    snapshotList.get(0).toString(), '-', storage.getUsingSmis80()? SmisConstants.MAX_SMI80_SNAPSHOT_NAME_LENGTH : SmisConstants.MAX_SNAPSHOT_NAME_LENGTH);
        	
            int snapshotsCount = ControllerUtils.getBlockSnapshotsBySnapsetLabelForProject(first, _dbClient).size();

            // CTRL-5640: ReplicationGroup may not be accessible after provider fail-over.
            ReplicationUtils.checkReplicationGroupAccessibleOrFail(storage, first, _dbClient, _helper, _cimPath);
            
            if(snapshotsCount <= 0){
            	final String errMsg = String.format("The number of block snapshots with snapset label %s is %d; need to have more than 0 to CG snaps.",
            			first.getSnapsetLabel(), snapshotsCount);
                _log.error(errMsg);
                ServiceError error = DeviceControllerErrors.smis.noBlockSnapshotsFound();
                taskCompleter.error(_dbClient, error);
            }
            
            final Map<String,List<Volume>> volumesBySizeMap = new HashMap<String,List<Volume>>();
            
            // Group volumes by pool and size
            for (URI uri : snapshotList) {
            	final BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, uri);
            	final Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent().getURI());
            	
            	final String key = volume.getPool() + "-" + volume.getCapacity();
            	
            	final List<Volume> currentVolumes = volumesBySizeMap.containsKey(key) ? volumesBySizeMap.get(key) : new ArrayList<Volume>();
            	currentVolumes.add(volume);
            	volumesBySizeMap.put(key, currentVolumes);
			}
            
            // For 8.0 providers, no need to create target devices and
            // target group separately for volumes in CG.
            // They will be created as part of 'CreateGroupReplica' call.
            // For 4.6 providers, target devices and target group will be
            // created separately before 'CreateGroupReplica' call.
            if (!storage.getUsingSmis80()) {
	            targetDeviceIds = new ArrayList<String>();
	            CIMObjectPath volumeGroupPath = _helper.getVolumeGroupPath(storage, snapVolume, null);
	            for (Entry<String, List<Volume>> entry : volumesBySizeMap.entrySet()) {
	            	final List<Volume> volumes = entry.getValue();
	            	final Volume volume = volumes.get(0);
	            	final URI poolId = volume.getPool();
	            	
	            	// Create target devices based on the array model
	                final List<String> newDeviceIds = kickOffTargetDevicesCreation(storage, volumeGroupPath,
	                        sourceGroupName, snapLabelToUse, createInactive, thinProvisioning, volumes.size(), poolId,
	                		volume.getCapacity(), taskCompleter);
	                
	                targetDeviceIds.addAll(newDeviceIds);
				}
	            
	            // Create target device group
	            targetGroupPath = ReplicationUtils.createTargetDeviceGroup(storage, sourceGroupName, targetDeviceIds, taskCompleter, _dbClient, _helper, _cimPath,
	                                                            SYNC_TYPE.SNAPSHOT);
            }
            // Create CG snapshot
            
            CIMObjectPath job = VmaxGroupOperationsUtils.internalCreateGroupReplica(storage, sourceGroupName,
                    snapLabelToUse, targetGroupPath, createInactive, thinProvisioning,
                    taskCompleter, SYNC_TYPE.SNAPSHOT, _dbClient, _helper, _cimPath);

            if (job != null) {
                ControllerServiceImpl.enqueueJob(
                        new QueueJob(new SmisBlockCreateCGSnapshotJob(job,
                                storage.getId(), !createInactive, sourceGroupName, taskCompleter)));
            }
            
            
        } catch (Exception e) {
            final String errMsg = format("An exception occurred when trying to create snapshots for consistency group {0} on storage system {1}",
                    sourceGroupName, storage.getId());
            _log.error(errMsg, e);
            ServiceError error = DeviceControllerErrors.smis.methodFailed("createGroupSnapshots", e.getMessage());
            taskCompleter.error(_dbClient, error);

            // Roll back changes
            rollbackCreateSnapshot(storage, targetGroupPath, targetDeviceIds, taskCompleter);
            
            throw new SmisException(errMsg, e);
        }
    }
    
 	/**
     * Should implement clean up of all the snapshots in a volume consistency
     * group 'snap-set'. The 'snap-set' is a set of block snapshots created for a
     * set of volumes in a consistency group.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot object representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void deleteGroupSnapshots(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            callEMCRefreshIfRequired(_dbClient, _helper, storage, Arrays.asList(snapshot));
            List<BlockSnapshot> snapshots = _dbClient.queryObject(BlockSnapshot.class, Arrays.asList(snapshot));
            BlockSnapshot snapshotObj = snapshots.get(0);
            // Check if the consistency group exists
            String consistencyGroupName = _helper.getConsistencyGroupName(snapshotObj, storage);
            StorageSystem newStorage = findProviderFactory.withGroup(storage, consistencyGroupName).find();

            if (newStorage == null) {
                _log.warn("Replication Group {} not found.", consistencyGroupName);
                // Don't return, let the below code do its clean-up.
            } // else - storage will have right Provider to use.


            String snapshotGroupName = snapshotObj.getReplicationGroupInstance();
            if (snapshotGroupName.contains("+")) {
                //quick fix for correcting 000198700406+EMC_SMI_RG1430326858108 snapshotGroupName in 803+VMAX
                if (storage.getUsingSmis80() && !storage.checkIfVmax3()) {
                    snapshotGroupName = snapshotGroupName.substring(snapshotGroupName.indexOf("+") + 1);
                } else { // array is not managed by SMI-S 8.0 (It can't be VMAX3 because only SMI-S 8.0 managed VMAX3 arrays)
                    snapshotGroupName = snapshotGroupName.substring(0, snapshotGroupName.indexOf("+"));
                }
            }
            List<BlockSnapshot> snapshotList = ControllerUtils.getBlockSnapshotsBySnapsetLabelForProject(snapshotObj, _dbClient);
            CIMArgument[] outArgs = new CIMArgument[5];
            CIMObjectPath groupSynchronized = _cimPath.getGroupSynchronizedPath(storage, consistencyGroupName, snapshotGroupName);
            if (_helper.checkExists(storage, groupSynchronized, false, false) != null) {
                
                // remove targets from parking SLO group
                if (storage.checkIfVmax3()) {
                    Iterator<BlockSnapshot> iter = snapshotList.iterator();
                    while (iter.hasNext()) {
                        BlockSnapshot blockSnapshot = iter.next();
                        _helper.removeVolumeFromParkingSLOStorageGroup(storage, blockSnapshot.getNativeId(), false);
                        _log.info("Done invoking remove volume {} from parking SLO storage group", blockSnapshot.getNativeId());
                    }
                }

                CIMArgument[] deleteCGSnapInput = _helper.getDeleteSnapshotSynchronousInputArguments(groupSynchronized);
                _helper.callModifyReplica(storage, deleteCGSnapInput, outArgs);
            } 
            // Set inactive=true for all snapshots in the snaps set
            Iterator<BlockSnapshot> snapshotIter = snapshotList.iterator();
            while (snapshotIter.hasNext()) {
                BlockSnapshot it = snapshotIter.next();
                it.setInactive(true);
                it.setIsSyncActive(false);
                _dbClient.persistObject(it);
            }
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            String message =
                    String.format("Generic exception when trying to delete snapshots from consistency group array %s",
                            storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.methodFailed("deleteGroupSnapshots", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }

    /**
     * Implementation for restoring of a single volume snapshot restore. That is, this
     * volume is independent of other volumes and a snapshot was taken previously, and
     * now we want to restore that snap to the original volume.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param volume        [required] - Volume URI for the volume to be restored
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void restoreSingleVolumeSnapshot(StorageSystem storage, URI volume, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            callEMCRefreshIfRequired(_dbClient, _helper, storage, Arrays.asList(snapshot));
            BlockSnapshot from = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            CIMObjectPath syncObjectPath = _cimPath.getSyncObject(storage, from);
            if (_helper.checkExists(storage, syncObjectPath, false, false) != null) {
                terminateAnyRestoreSessions(storage, from, volume, taskCompleter);
            }

            CIMObjectPath cimJob;
            if (storage.checkIfVmax3()) {
                Volume to = _dbClient.queryObject(Volume.class, volume);
                cimJob = _helper.callModifySettingsDefineState(storage, _helper.getRestoreFromSnapshotInputArguments(storage, to, from));
            }
            else {
                cimJob = _helper.callModifyReplica(storage, _helper.getRestoreFromReplicaInputArguments(syncObjectPath));
            }
            ControllerServiceImpl.enqueueJob(new QueueJob(new SmisBlockRestoreSnapshotJob(cimJob, storage.getId(), taskCompleter)));
        } catch (WBEMException e) {
            String message = String.format("Error encountered when trying to restore from snapshot %s on array %s",
                    snapshot.toString(), storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.unableToCallStorageProvider(e.getMessage());
            taskCompleter.error(_dbClient, error);
        } catch (Exception e) {
            String message = String.format("Generic exception when trying to restore from snapshot %s on array %s",
                    snapshot.toString(), storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.methodFailed("restoreSingleVolumeSnapshot", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }

    /**
     * Implementation should restore the set of snapshots that were taken for a set of
     * volumes in a consistency group. That is, at some time there was a consistency
     * group of volumes created and snapshot was taken of these; these snapshots would
     * belong to a "snap-set". This restore operation, will restore the volumes in the
     * consistency group from this snap-set. Any snapshot from the snap-set can be
     * provided to restore the whole snap-set.
     *
     * @param storage       [required] - StorageSystem object representing the array
     * @param snapshot      [required] - BlockSnapshot URI representing the previously created
     *                      snap for the volume
     * @param taskCompleter - TaskCompleter object used for the updating operation status.
     */
    @Override
    public void restoreGroupSnapshots(StorageSystem storage, URI volume, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            callEMCRefreshIfRequired(_dbClient, _helper, storage, Arrays.asList(snapshot));
            BlockSnapshot snapshotObj = _dbClient.queryObject(BlockSnapshot.class, snapshot);
            // Check if the consistency group exists
            String consistencyGroupName = _helper.getConsistencyGroupName(snapshotObj, storage);
            storage = findProviderFactory.withGroup(storage, consistencyGroupName).find();

            if (storage == null) {
                ServiceError error = DeviceControllerErrors.smis.noConsistencyGroupWithGivenName();
                taskCompleter.error(_dbClient, error);
                return;
            }
            String snapshotGroupName = snapshotObj.getReplicationGroupInstance();
            CIMObjectPath groupSynchronized = _cimPath.getGroupSynchronizedPath(storage, consistencyGroupName, snapshotGroupName);
            if (_helper.checkExists(storage, groupSynchronized, false, false) != null) {
                CIMObjectPath cimJob = null;
                if (storage.checkIfVmax3()) {
                    if (snapshotObj.getSettingsInstance() == null) {
                        throw DeviceControllerException.exceptions.snapSettingsInstanceNull(snapshotObj.getSnapsetLabel(), snapshotObj.getId().toString());
                    }
                    // there could only be one restored snapshot per device at a time
                    // terminate any pre-existing one in favor of the new one
                    terminateAnyRestoreSessions(storage, snapshotObj, snapshot, taskCompleter);

                    CIMObjectPath settingsPath = _cimPath.getGroupSynchronizedSettingsPath(storage, consistencyGroupName, snapshotObj.getSettingsInstance());
                    cimJob = _helper.callModifySettingsDefineState(storage, _helper.getRestoreFromSettingsStateInputArguments(settingsPath));
                }
                else {
                    CIMArgument[] restoreCGSnapInput = _helper.getRestoreFromReplicaInputArguments(groupSynchronized);
                    cimJob = _helper.callModifyReplica(storage, restoreCGSnapInput);
                }
                ControllerServiceImpl.enqueueJob(new QueueJob(new SmisBlockRestoreSnapshotJob(cimJob, storage.getId(), taskCompleter)));
            } else {
                ServiceError error = DeviceControllerErrors.smis.unableToFindSynchPath(consistencyGroupName);
                taskCompleter.error(_dbClient, error);
            }
        } catch (Exception e) {
            String message =
                    String.format("Generic exception when trying to restoring snapshots from consistency group on array %s",
                            storage.getSerialNumber());
            _log.error(message, e);
            ServiceError error = DeviceControllerErrors.smis.methodFailed("restoreGroupSnapshots", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }
   
	private void rollbackCreateSnapshot(final StorageSystem storage,
			final CIMObjectPath targetGroupPath,
			final List<String> targetDeviceIds,
			final TaskCompleter taskCompleter) throws DeviceControllerException {
		
		_log.info(format( "Rolling back snapshot creation on storage system {0}", storage.getId()));
    	
    	try {
			// Remove target group
			if(targetGroupPath != null){
				deleteTargetDeviceGroup(storage, targetGroupPath);
			}
			
			// Remove target devices
			if(targetDeviceIds != null && !targetDeviceIds.isEmpty()){
				deleteTargetDevices(storage, targetDeviceIds.toArray(new String[targetDeviceIds.size()]), taskCompleter);				
			}
			
		} catch (DeviceControllerException e) {
			final String errMsg = format("Unable to rollback snapshot creation on storage system {0}", storage.getId());
			_log.error(errMsg, e);
			throw new SmisException(errMsg, e);
		}
    }
    
   /**
    *
    * 1) Create Storage Setting:
    *    -- Invoke CreateSetting() method on StorageCapabilities to create a default Setting.
    *       -- Needs to be against the pool that the VDEVs will be created against
    *    -- Then modify this setting via ModifyInstance() to set StorageExtentInitialUsage to "Delta Replica Target"
    *       -- "Delta Replica Target" = 12
    *       -- For client.modifyInstance(CIMInstance, String[] propertyList)
    *
    * 2) Use this setting as a Goal on CreateOrModifyStoragePool(). This should create you VDEVs.
    *    -- This will be an asynchronous operation!!!
    *    -- Which pool to use?
    *       -- We could have volumes (based on placement) on different pools
    *       -- Select one of the volume pools?
    *       -- Do we need to do placement for this?
    *       -- Do we select some default pool?
    *
    *
    * @param storage       - StorageSystem where the pool and snapshot exist
    * @param sourceGroupName - Name of source group
    * @param label - Name to be applied to each snapshot volume
    * @param createInactive  whether the snapshot needs to to be created with sync_active=true/false
    * @param thinlyProvisioned
    *@param count         - Number of VDEVs to create
    * @param capacity      - Size of the VDEVs to create
    * @param taskCompleter - Completer object used for task status update    @return
 * @throws DeviceControllerException 
    */
   private List<String> kickOffTargetDevicesCreation(StorageSystem storage, CIMObjectPath volumeGroupPath, String sourceGroupName,
                                                     String label, Boolean createInactive, boolean thinlyProvisioned, int count,
                                                     URI storagePoolUri, long capacity,
                                                     TaskCompleter taskCompleter) throws Exception {
       if (storage.checkIfVmax3()) {
           CIMObjectPath poolPath = findSnapStoragePoolOrThrow(storage);
           return createTargetDevices(storage, poolPath, volumeGroupPath, null, sourceGroupName, null, createInactive,
                   count, capacity, taskCompleter);
       } else if (thinlyProvisioned) {
           return ReplicationUtils.createTargetDevices(storage, sourceGroupName, label, createInactive, count, storagePoolUri,
                   capacity, true, null, taskCompleter, _dbClient, _helper, _cimPath);
       } else {
           CIMObjectPath poolPath = findSnapStoragePoolOrThrow(storage);

           CIMInstance storageSetting = createStorageSetting(storage, poolPath);
           if (storageSetting == null) {
               final String errMsg = String.format("Unable to find StoragePoolSetting for SnapStoragePool %s when creating target devices on array %s",
                       poolPath.toString(), storage.getSerialNumber());
               _log.error(errMsg);
               throw DeviceControllerExceptions.smis.unableToFindStoragePoolSetting();
           }

           return createTargetDevices(storage, poolPath, null, storageSetting, sourceGroupName, label, createInactive,
                   count, capacity, taskCompleter);
       }
  }
   
   /**
    * Method will search through the snap pools for the specified array
    * and return the CIMObjectPath for it. This will be the pool where
    * the target devices will be created for snapshot operation.
    *
    * @param storage    - StorageSystem representing the array
    * @return CIMObjectPath - CIM Reference to SnapStoragePool to be used for
    * the target devices.
    * @throws WBEMException
    */
   private CIMObjectPath findSnapStoragePoolOrNull(StorageSystem storage) throws WBEMException {
       CIMObjectPath snapPoolPath = null;
       CloseableIterator<CIMObjectPath> snapPools = null;
       try {
           CIMObjectPath systemPath = _cimPath.getStorageSystem(storage);
           snapPools = _helper.getAssociatorNames(storage, systemPath, null,
                   storage.checkIfVmax3()? StoragePool.PoolClassNames.Symm_SRPStoragePool.name()
                           : SYMM_SNAP_STORAGE_POOL, null, null);

           // TODO: Get the first one for now, but could there be more than one? If so, what to do?
           if (snapPools.hasNext()) {
               snapPoolPath = snapPools.next();
               _log.info(String.format("Found Symm_SnapStoragePool to use -> %s",
                       snapPoolPath.toString()));
           }
       } finally {
           if (snapPools != null) {
               snapPools.close();
           }
       }
       return snapPoolPath;
   }

    private CIMObjectPath findSnapStoragePoolOrThrow(StorageSystem storage) throws SmisException, WBEMException {
        CIMObjectPath objectPath = findSnapStoragePoolOrNull(storage);
        if (objectPath == null) {
            String msg = String.format("Failed to find an instance of Symm_SnapStoragePool for system %s",
                    storage.getSerialNumber());
            throw DeviceControllerExceptions.smis.noStoragePoolInstances(msg, null);
        }
        return objectPath;
    }

   /**
    * In order to create VDEV targets for snapshots, we need to have a StorageSetting
    * with a parameter set to enable it. This method will create an instance of this.
    * We will try to maintain a single StorageSetting for this purpose, per pool. To
    * that end, we'll first check for the existence of the setting (based on a
    * special name).
    *
    * @param storage       - StorageSystem where the pool exists
    * @param poolPath      - CIMObject representing the pool to allocate targets from
    * @return CIMInstance  - null => error. Otherwise, will be the StorageSetting
    *                        instance for creating VDEVs against the pool.
 * @throws DeviceControllerException
    */
   private CIMInstance createStorageSetting(StorageSystem storage,
                                            CIMObjectPath poolPath) throws Exception {
       CloseableIterator<CIMObjectPath> capabilities = null;
       CloseableIterator<CIMObjectPath> poolSettings = null;
       CIMInstance instance = null;
       try {
           // From the storage pool, get its capability (assuming there is only
           // one per pool). Get associated storage settings from the capability,
           // loop through each and find one that has the special name. If it
           // doesn't exist, create it and modify the StorageExtentInitialUsage
           // and ElementName.
           capabilities = _helper.getAssociatorNames(storage, poolPath, null, SYMM_STORAGE_POOL_CAPABILITIES, null, null);
           if (capabilities != null && capabilities.hasNext()) {
               CIMObjectPath poolCapabilities = capabilities.next();
               poolSettings = _helper.getAssociatorNames(storage, poolCapabilities, null, SYMM_STORAGE_POOL_SETTING, null, null);
               CIMInstance foundVdevSettingForThisPool = null;
               while (poolSettings != null && poolSettings.hasNext()) {
                   CIMInstance it = _helper.getInstance(storage, poolSettings.next(), false, false, PL_STORAGE_EXTENT_INITIAL_USAGE);
                   int storageExtentInitialUsage = Integer.valueOf(CIMPropertyFactory.getPropertyValue(it, CP_STORAGE_EXTENT_INITIAL_USAGE));
                   if (storageExtentInitialUsage == DELTA_REPLICA_TARGET_VALUE) {
                       // We found a setting that has the "Delta Replica Target" value
                       // for the StorageExtentInitialUsage attribute
                       foundVdevSettingForThisPool = it;
                       break;
                   }
               }
               if (foundVdevSettingForThisPool != null) {
                   instance = foundVdevSettingForThisPool;
                   _log.info(String.format("Found existing StorageSetting for VDEV %s", instance.toString()));
               } else {
                   // It wasn't found ==> create it
                   // TODO: How do we prevent concurrent operations from creating duplicates?
                   _log.info("Could not find existing StorageSetting for VDEV, going to create it and modify it...");
                   CIMArgument[] inArgs = _helper.getCreateDefaultStoragePoolSettingsArguments();
                   CIMArgument[] outArgs = new CIMArgument[5];
                   _helper.invokeMethod(storage, poolCapabilities, CREATE_SETTING, inArgs, outArgs);
                   CIMObjectPath newSetting = _cimPath.getCimObjectPathFromOutputArgs(outArgs, NEW_SETTING);
                   instance = _cimPath.getStoragePoolVdevSettings(newSetting);
                   _helper.modifyInstance(storage, instance, PL_STORAGE_EXTENT_INITIAL_USAGE);
                   //  Get the unique ID for display
                   CIMInstance newSettingInstance = _helper.getInstance(storage, newSetting, false, false, PL_ONLY_EMC_UNIQUE_ID);
                   String emcUniqueId = CIMPropertyFactory.getPropertyValue(newSettingInstance, CP_EMC_UNIQUE_ID);
                   _log.info(String.format("Created StorageSetting for VDEV %s (EMCUniqueID = %s)", instance.toString(), emcUniqueId));
               }
           } else {
               String message = String.format("Could not find any %s instances for StoragePool %s. Will not be able to create a StorageSetting.",
                       SYMM_STORAGE_POOL_CAPABILITIES, poolPath.toString());
               _log.error(message);
               throw DeviceControllerExceptions.smis.noStoragePoolInstances(message, null);
           }
       } finally {
           if (capabilities != null) {
               capabilities.close();
           }
           if (poolSettings != null) {
               poolSettings.close();
           }
       }
       return instance;
   }

   /**
    * Method will invoke the SMI-S operation to create 'count' number of VDEVs of the
    * specified capacity.
    *
    * If any errors, taskCompleter will be updated.
    *
    * Note; This method will kick off of an asynchronous job. The SmisCreateVmaxCGTargetVolumesJob
    * encapsulates this. When the task completes successfully, it will continue the
    * work of completing the snapshot operation.
    *
    * @param storage        - StorageSystem where VDEVs will be created
    * @param poolPath       - CIMObject representing the pool to allocate targets from
    * @param storageSetting - Setting that allows for VDEV creation
    * @param label          - Name to appl to each VDEV
    * @param createInactive - whether the snapshot needs to to be created with sync_active=true/false 
    * @param count          - Number of VDEVs
    * @param capacity       - Size of each VDEV
    * @param taskCompleter  - Completer object used for task status update
    * @throws DeviceControllerException
    * 
    * @return - List of native Ids 
    */
   private List<String> createTargetDevices(StorageSystem storage, CIMObjectPath poolPath, CIMObjectPath volumeGroupPath,
                                    CIMInstance storageSetting, String sourceGroupName,
                                    String label, Boolean createInactive, int count, long capacity,
                                    TaskCompleter taskCompleter) throws DeviceControllerException {
       
		_log.info(format("Creating target devices: Storage System: {0}, Consistency Group: {1}, Pool: {2}, Count: {3}",
				storage.getId(), sourceGroupName, poolPath, count));
       
       try {
           CIMObjectPath configSvcPath = _cimPath.getConfigSvcPath(storage);
           
           CIMArgument[] inArgs = null;
           if (storage.checkIfVmax3()) {
               inArgs = _helper.getCreateVolumesBasedOnVolumeGroupInputArguments(storage, poolPath, volumeGroupPath, label, count, capacity);
           }
           else {
               inArgs = _helper.getCreateVolumesBasedOnSettingInputArguments(storage, poolPath, storageSetting, label, count, capacity);
           }

           CIMArgument[] outArgs = new CIMArgument[5];
           
           SmisCreateVmaxCGTargetVolumesJob job = new SmisCreateVmaxCGTargetVolumesJob(null, storage.getId(), sourceGroupName,
                          label, createInactive, taskCompleter);
           
           _helper.invokeMethodSynchronously(storage, configSvcPath, CREATE_OR_MODIFY_ELEMENT_FROM_STORAGE_POOL, inArgs, outArgs, job);
           
           return job.getTargetDeviceIds();
       } catch (Exception e) {
    	   final String errMsg = format("An error occurred when creating target devices on storage system {0}", storage.getId());
           _log.error(errMsg, e);
           taskCompleter.error(_dbClient, SmisException.errors.methodFailed(CREATE_OR_MODIFY_ELEMENT_FROM_STORAGE_POOL, e.getMessage()));
           throw new SmisException(errMsg, e);
       }
       
   }


 	/**
 	 * Method will invoke the SMI-S operation to return the Volumes represented by the native ids to the storage pool  
 	 * 
 	 * @param storageSystem  - StorageSystem where the pool and volume exist
 	 * @param deviceIds      - List of native Ids representing the elements to be returned to the pool
 	 * @param taskCompleter  - Completer object used for task status update
 	 * 
 	 * @throws DeviceControllerException
 	 */
	private void deleteTargetDevices(final StorageSystem storageSystem, final String[] deviceIds, final TaskCompleter taskCompleter) {
		
		_log.info(format("Removing target devices {0} from storage system {1}", deviceIds, storageSystem.getId()));
		
		try {
		    if (storageSystem.checkIfVmax3()) {
		        for (String deviceId : deviceIds) {
		            _helper.removeVolumeFromParkingSLOStorageGroup(storageSystem, deviceId, false);
		            _log.info("Done invoking remove volume {} from parking SLO storage group", deviceId);
		        }
		    }

			final CIMObjectPath configSvcPath = _cimPath.getConfigSvcPath(storageSystem);
			
			final CIMObjectPath[] theElements = _cimPath.getVolumePaths(storageSystem, deviceIds);
			
			final CIMArgument[] inArgs = _helper.getReturnElementsToStoragePoolArguments(storageSystem, theElements, 2);
			final CIMArgument[] outArgs = new CIMArgument[5];

			final SmisDeleteVmaxCGTargetVolumesJob job = new SmisDeleteVmaxCGTargetVolumesJob(
					null, storageSystem.getId(), deviceIds, taskCompleter);

			_helper.invokeMethodSynchronously(storageSystem, configSvcPath, RETURN_ELEMENTS_TO_STORAGE_POOL, inArgs, outArgs, job);

		} catch (Exception e) {
			_log.error(format("An error occurred when removing target devices {0} from storage system {1}", deviceIds, storageSystem.getId()), e);
		}
	}
   
   
   /**
    * Deletes a target group represented by the given target group path 
    * 
    * @param storage			- StorageSystem where the target group is
    * @param targetGroupPath	- Path representing target group to be deleted
    * 
    * @throws DeviceControllerException
    */
   private void deleteTargetDeviceGroup(final StorageSystem storage, final CIMObjectPath targetGroupPath) {
	   
	   _log.info(format("Removing target device group {0} from storage system {1}", targetGroupPath, storage.getId()));
	   
       try {
           CIMObjectPath replicationSvc = _cimPath.getControllerReplicationSvcPath(storage);
           CIMArgument[] outArgs = new CIMArgument[5];
           CIMArgument[] inArgs = _helper.getDeleteReplicationGroupInputArguments(storage, targetGroupPath, true);
           
           _helper.invokeMethod(storage, replicationSvc, DELETE_GROUP, inArgs, outArgs);
       } catch (Exception e) {
    	   _log.error(format("An error occurred when removing target device group {0} from storage system {1}", targetGroupPath, storage.getId()), e);
       }
   }

    /**
     * Routine will call SMI-S ModifyReplicaSynchronization to resume a synchronization instance.
     * The operation is a counter-intuitive; it will terminate a RESTORE session between
     * target and source. This was the suggestion based on input from SMI-S team.
     * See OPT 443785.
     *
     * @param storage       [in] - StorageSystem object representing the array
     * @param from          [in] - Should be the snapshot object
     * @param blockObject   [in] - Should be the volume object
     * @param syncObject   [in] - A CIMObjectPath representing the SMI-S synchronization object tying
     *                      from and blockObject together, along with other related consistency group members
     * @param taskCompleter [in] - TaskCompleter used for updating status of operation
     * @return true if the resume operation was successfully performed on the synchronization
     * @throws WBEMException
     */
    private boolean resumeSnapshot(StorageSystem storage, BlockObject from, BlockObject blockObject,
                                   CIMObjectPath syncObject, TaskCompleter taskCompleter)
            throws WBEMException {
        boolean wasResumed = false;
        SmisBlockResumeSnapshotJob job =
                new SmisBlockResumeSnapshotJob(null, storage.getId(),
                        new TaskCompleter() {
                            @Override
                            protected void
                            complete(DbClient dbClient,
                                     Operation.Status status,
                                     ServiceCoded coded) throws DeviceControllerException {

                            }
                        });
        CIMArgument[] result = new CIMArgument[5];

        try {
            if (storage.checkIfVmax3()) {
                _helper.invokeMethodSynchronously(storage,
                        _cimPath.getControllerReplicationSvcPath(storage),
                        SmisConstants.MODIFY_SETTINGS_DEFINE_STATE,
                        _helper.getEMCResumeInputArguments(syncObject),
                        result, job);
            }
            else if(storage.getUsingSmis80()){
            	/**
            	 * VMAX2 managed by 8.* SMIS
            	 * We need to pass Operation = 16
            	 */
                _helper.invokeMethodSynchronously(storage,
                        _cimPath.getControllerReplicationSvcPath(storage),
                        SmisConstants.MODIFY_REPLICA_SYNCHRONIZATION,
                        _helper.getResumeSnapshotSynchronizationInputArguments(syncObject),
                        result, job);
            } else{
            	/**
            	 * VMAX2 managed by 4.6.2 SMI provider
            	 * We need to pass Operation = 14 
            	 */
            	 _helper.invokeMethodSynchronously(storage,
                         _cimPath.getControllerReplicationSvcPath(storage),
                         SmisConstants.MODIFY_REPLICA_SYNCHRONIZATION,
                         _helper.getResumeSynchronizationInputArguments(syncObject),
                         result, job);
            }
        } catch (Exception e) {
            /*
             * May be ignored if message is about invalid device state, since when
             * dealing with multiple GroupSynchronized instances, we attempt to resume
             * all of them.
            */
            _log.info("Encountered exception which may be ignored: {}", e.getMessage());
        }

        if (job.isSuccess()) {
            _log.info("Synchronization was successfully resumed: {}", syncObject);
            wasResumed = true;
        } else {
            _log.info("Synchronization was not resumed and can be ignored: {}", syncObject);
        }
        return wasResumed;
    }

    /**
     * Helper to return a list of sync objects for a given volume that are in the 'Restored' state.
     * These objects represent the volume-to-snapshot relationship.
     *
     * NOTE: Multiple GroupSynchronized instances that are in the 'Mixed' state represent scenarios where
     * a source has multiple CG snapshots and only one may have been restored (the others could be "synchronized",
     * hence the mixed states).  In this case, we simply treat Mixed state synchronizations as Restored
     * as we want to run ResyncReplica against at least one of the GroupSynchronized instances.
     *
     * @param storage       [in] - StorageSystem object representing the array
     * @param blockObject   [in] - Source block object
     * @return List of CIMObjectPaths representing Synchronization paths.
     * @throws Exception
     */
    private Collection<CIMObjectPath> getRestoredOrMixedStateSyncObjects(StorageSystem storage, BlockObject blockObject)
            throws Exception {
        List<CIMObjectPath> syncObjects = null;

        if (blockObject.hasConsistencyGroup()) {
            syncObjects = getAllGroupSyncObjects(storage, blockObject);
            // TODO For any GroupSynchronized instances that have Mixed state, determine which ones
            // have underlying StorageSynchronized instances in state Restored and use only those.
        } else {
            syncObjects = getAllStorageSyncObjects(storage, blockObject);
        }

        return filter(syncObjects, hasRestoredOrMixedStatePredicate(storage));
    }

    /**
     * Acquire StorageSynchronized instances that reference the given source block object.
     *
     * @param storage       StorageSystem representing the array
     * @param blockObject   BlockObject representing the source of replication.
     * @return A List of CIMObjectPath's for each StorageSynchronized instance.
     */
    private List<CIMObjectPath> getAllStorageSyncObjects(StorageSystem storage, BlockObject blockObject) {
        List<CIMObjectPath> result = new ArrayList<>();
        CloseableIterator<CIMObjectPath> it = null;
        try {
            it = _cimPath.getSyncObjects(storage, blockObject);
            while (it.hasNext()) {
                result.add(it.next());
            }
        } finally {
            if (it != null) {
                it.close();
            }
        }
        return result;
    }

    /**
     * Acquires ReplicationGroup's associated with the block object, then further acquires the GroupSynchonized
     * instances that reference them.
     *
     * @param storage       StorageSystem that holds the GroupSynchronized instances.
     * @param blockObject   BlockObject representing the source of replication.
     * @return              A List of CIMObjectPath's for each GroupSynchronized instance.
     * @throws WBEMException
     */
    private List<CIMObjectPath> getAllGroupSyncObjects(StorageSystem storage, BlockObject blockObject)
            throws WBEMException {
        List<CIMObjectPath> groupSyncPaths = new ArrayList<>();
        CIMObjectPath blockObjectPath = _cimPath.getBlockObjectPath(storage, blockObject);
        CloseableIterator<CIMObjectPath> associatorNames = null;
        CloseableIterator<CIMObjectPath> groupSyncRefs = null;

        try {
            associatorNames = _helper.getAssociatorNames(storage, blockObjectPath, null, SE_REPLICATION_GROUP,
                    null, null);
            while (associatorNames.hasNext()) {
                CIMObjectPath repGrpPath = associatorNames.next();
                _log.info(String.format("Source %s has associated ReplicationGroup %s", blockObjectPath, repGrpPath));
                groupSyncRefs = _helper.getReference(storage, repGrpPath, SE_GROUP_SYNCHRONIZED_RG_RG, null);
                while (groupSyncRefs.hasNext()) {
                    CIMObjectPath grpSyncPath = groupSyncRefs.next();
                    _log.info(String.format("ReplicationGroup %s is referenced by %s", repGrpPath, grpSyncPath));
                    groupSyncPaths.add(grpSyncPath);
                }
            }
        } finally {
            if (associatorNames != null) {
                associatorNames.close();
            }
            if (groupSyncRefs != null) {
                groupSyncRefs.close();
            }
        }

        return groupSyncPaths;
    }

    private Predicate<CIMObjectPath> hasRestoredOrMixedStatePredicate(final StorageSystem storage) {
        return new Predicate<CIMObjectPath>() {
            @Override
            public boolean apply(CIMObjectPath path) {
                boolean result = false;
                try {
                    CIMInstance instance = _helper.getInstance(storage, path, false, false, PS_COPY_STATE_AND_DESC);
                    String copyState = instance.getPropertyValue(SmisConstants.CP_COPY_STATE).toString();

                    // GroupSynchronized does not have the EMCCopyStateDesc property
                    Object descProperty = instance.getPropertyValue(SmisConstants.EMC_COPY_STATE_DESC);
                    String copyStateDesc = (descProperty == null ? "null" : descProperty.toString());

                    _log.info(String.format("Sync %s has copyState %s (%s)", path.toString(), copyState, copyStateDesc));
                    result = copyState.equals(COPY_STATE_RESTORED_INT_VALUE) ||
                            copyState.equals(COPY_STATE_MIXED_INT_VALUE);
                } catch (Exception e) {
                    String msg = String.format("Failed to acquire sync instance %s as part of Restore State check", path);
                    _log.warn(msg, e);
                }
                return result;
            }
        };
    }

    /**
     * Given a snapshot and a URI of its parent volume, look up any existing restore sessions and
     * terminate them.
     *
     * @param storage       [in] - StorageSystem object representing the array
     * @param from          [in] - Should be the snapshot object
     * @param volume        [in] - Should be the volume URI
     * @param taskCompleter [in] - TaskCompleter used for updating status of operation
     * @throws Exception
     */
    @Override
    public void terminateAnyRestoreSessions(StorageSystem storage, BlockObject from, URI volume,
                                             TaskCompleter taskCompleter)
            throws Exception {
        BlockObject blockObject = BlockObject.fetch(_dbClient, volume);
        Collection<CIMObjectPath> syncObjects = storage.checkIfVmax3() ? _helper.getSettingsDefineStatePaths(storage, blockObject, (BlockSnapshot) from) :
            getRestoredOrMixedStateSyncObjects(storage, blockObject);

        for (CIMObjectPath syncObject : syncObjects) {
            resumeSnapshot(storage, from, blockObject, syncObject, taskCompleter);
        }
    }

    /**
     * Look up any snapshot objects associated with the give volume. For each, can to terminate
     * any existing restore sessions.
     *
     * @param storage       [in] - StorageSystem object representing the array
     * @param volume        [in] - Volume to use for lookup
     * @param taskCompleter [in] - TaskCompleter used for updating status of operation
     * @throws Exception
     */
    private void terminateAnyRestoreSessionsForVolume(StorageSystem storage, Volume volume,
                                                      TaskCompleter taskCompleter) throws Exception {
        if (storage.checkIfVmax3()) {
            terminateAnyRestoreSessions(storage, null, volume.getId(), taskCompleter);
            return;
        }
        NamedElementQueryResultList snapshots = new NamedElementQueryResultList();
        _dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getVolumeSnapshotConstraint(volume.getId()), snapshots);
        for (NamedElementQueryResultList.NamedElement ne : snapshots) {
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, ne.id);
            if (snapshot != null && !snapshot.getInactive()) {
                CIMObjectPath syncObjectPath = _cimPath.getSyncObject(storage, snapshot);
                if (_helper.checkExists(storage, syncObjectPath, false, false) != null) {
                    terminateAnyRestoreSessions(storage, volume, snapshot.getId(), taskCompleter);
                }
            }
        }
    }
}
