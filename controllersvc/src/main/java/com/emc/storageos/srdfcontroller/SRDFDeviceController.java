/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.srdfcontroller;

import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.RemoteDirectorGroup;
import com.emc.storageos.db.client.model.RemoteDirectorGroup.SupportedCopyModes;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.volumecontroller.AsyncTask;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.RemoteMirroring;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.block.BlockDeviceController;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.*;
import com.emc.storageos.volumecontroller.impl.smis.SRDFOperations.Mode;
import com.emc.storageos.volumecontroller.impl.smis.srdf.SRDFUtils;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.Workflow.Method;
import com.emc.storageos.workflow.WorkflowService;
import com.emc.storageos.workflow.WorkflowStepCompleter;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;

import static com.emc.storageos.db.client.constraint.ContainmentConstraint.Factory.getVolumesByConsistencyGroup;
import static com.emc.storageos.db.client.util.CommonTransformerFunctions.FCTN_STRING_TO_URI;
import static com.google.common.collect.Collections2.transform;
import static java.util.Arrays.asList;

/**
 * SRDF-specific Controller implementation with support for block orchestration.
 */
public class SRDFDeviceController implements SRDFController, BlockOrchestrationInterface {
    private static final Logger log = LoggerFactory.getLogger(SRDFDeviceController.class);
    private static final String ADD_SYNC_VOLUME_PAIRS_METHOD = "addVolumePairsToCgMethodStep";
    private static final String ROLLBACK_ADD_SYNC_VOLUME_PAIR_METHOD = "rollbackAddSyncVolumePairStep";
    private static final String CREATE_SRDF_VOLUME_PAIR = "createSRDFVolumePairStep";
    private static final String CREATE_SRDF_ASYNC_MIRROR_METHOD = "createSrdfCgPairsStep";
    private static final String ROLLBACK_SRDF_LINKS_METHOD = "rollbackSRDFLinksStep";
    private static final String SUSPEND_SRDF_LINK_METHOD = "suspendSRDFLinkStep";
    private static final String SPLIT_SRDF_LINK_METHOD = "splitSRDFLinkStep";
    private static final String REMOVE_DEVICE_GROUPS_METHOD = "removeDeviceGroupsStep";
    private static final String CREATE_SRDF_RESYNC_PAIR_METHOD = "reSyncSRDFLinkStep";
    private static final String CREATE_SRDF_MIRRORS_STEP_GROUP = "CREATE_SRDF_MIRRORS_STEP_GROUP";
    private static final String CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP = "CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP";
    private static final String CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_DESC = "Synchronize source/target pairs";
    private static final String DELETE_SRDF_MIRRORS_STEP_GROUP = "DELETE_SRDF_MIRRORS_STEP_GROUP";
    private static final String CREATE_SRDF_MIRRORS_STEP_DESC = "Create SRDF Link";
    private static final String SUSPEND_SRDF_MIRRORS_STEP_DESC = "Suspend SRDF Link";
    public  static final String SPLIT_SRDF_MIRRORS_STEP_DESC = "Split SRDF Link ";
    private static final String DETACH_SRDF_MIRRORS_STEP_DESC = "Detach SRDF Link";
    private static final String UPDATE_SRDF_PAIRING_STEP_GROUP = "UPDATE_SRDF_PAIRING_STEP_GROUP";
    private static final String UPDATE_SRDF_PAIRING = "updateSRDFPairingStep";
    private static final String ROLLBACK_METHOD_NULL = "rollbackMethodNull";

    private static final String REMOVE_ASYNC_PAIR_METHOD = "removePairFromGroup";
    private static final String DETACH_SRDF_PAIR_METHOD = "detachVolumePairStep";
    private static final String REMOVE_SRDF_PAIR_STEP_DESC = "Remove %1$s pair from %1$s cg";
    private static final String SUSPEND_SRDF_PAIR_STEP_DESC = "Suspend %1$s pair removed from %1$s cg";
    private static final String DETACH_SRDF_PAIR_STEP_DESC = "Detach %1$s pair removed from %1$s cg";
    private static final String REMOVE_DEVICE_GROUPS_STEP_DESC = "Removing volume from replication group";
    private static final String DETACH_SRDF_MIRRORS_STEP_GROUP = "DETACH_SRDF_MIRRORS_STEP_GROUP";
    public static final String SPLIT_SRDF_MIRRORS_STEP_GROUP = "SPLIT_SRDF_MIRRORS_STEP_GROUP";
    private static final String RESYNC_SRDF_MIRRORS_STEP_GROUP = "RESYNC_SRDF_MIRRORS_STEP_GROUP";
    private static final String RESYNC_SRDF_MIRRORS_STEP_DESC = "Reestablishing SRDF Relationship again";
    private static final String STEP_VOLUME_EXPAND = "EXPAND_VOLUME";
    private static final String CREATE_SRDF_RESUME_PAIR_METHOD = "resumeSyncPairStep";
    private static final String CHANGE_SRDF_TO_NONSRDF_STEP_DESC = "Converting SRDF Devices to Non Srdf devices";
    private static final String CONVERT_TO_NONSRDF_DEVICES_METHOD = "convertToNonSrdfDevicesMethodStep";

    private WorkflowService workflowService;
    private DbClient dbClient;
    private Map<String, BlockStorageDevice> devices;
    private SRDFUtils utils;

    public WorkflowService getWorkflowService() {
        return workflowService;
    }

    public void setWorkflowService(final WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public DbClient getDbClient() {
        return dbClient;
    }

    public void setDbClient(final DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public Map<String, BlockStorageDevice> getDevices() {
        return devices;
    }

    public void setDevices(final Map<String, BlockStorageDevice> devices) {
        this.devices = devices;
    }

    @Override
    public String addStepsForCreateVolumes(final Workflow workflow, String waitFor,
                                           final List<VolumeDescriptor> volumeDescriptors, final String taskId)
            throws InternalException {
        List<VolumeDescriptor> srdfDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                new VolumeDescriptor.Type[]{VolumeDescriptor.Type.SRDF_SOURCE,
                        VolumeDescriptor.Type.SRDF_EXISTING_SOURCE,
                        VolumeDescriptor.Type.SRDF_TARGET}, new VolumeDescriptor.Type[]{});
        if (srdfDescriptors.isEmpty()) {
            log.info("No SRDF Steps required");
            return waitFor;
        }
        log.info("Adding SRDF steps for create volumes");
        // Create SRDF relationships
        waitFor = createElementReplicaSteps(workflow, waitFor, srdfDescriptors);
        return waitFor;
    }

    @Override
    public String addStepsForDeleteVolumes(final Workflow workflow, String waitFor,
                                           final List<VolumeDescriptor> volumeDescriptors, final String taskId)
            throws InternalException {
        List<VolumeDescriptor> sourceDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                VolumeDescriptor.Type.SRDF_SOURCE);
        if (sourceDescriptors.isEmpty()) {
            return waitFor;
        }

        Map<URI, Volume> volumeMap = queryVolumes(sourceDescriptors);
        //a rare roll back scenario, where target volume deletion failed due to Sym lock
        //as of multiple targets not supported
        //TODO make this roll back work for multiple targets
        for (Volume source : volumeMap.values()) {
            StringSet targets = source.getSrdfTargets();

            if (targets == null) {
                return waitFor;
            }

            for (String target : targets) {
                Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));

                if (null == targetVolume) {
                    return waitFor;
                }

                if (Mode.ASYNCHRONOUS.toString().equalsIgnoreCase(targetVolume.getSrdfCopyMode()) &&
                        targetVolume.hasConsistencyGroup()) {
                    //if replication Group Name is not set, we end up in delete errors, preventing.
                    RemoteDirectorGroup group = dbClient.queryObject(RemoteDirectorGroup.class,
                            targetVolume.getSrdfGroup());
                    if (!NullColumnValueGetter.isNotNullValue(group.getSourceReplicationGroupName()) ||
                            !NullColumnValueGetter.isNotNullValue(group.getTargetReplicationGroupName()) ) {
                        log.warn("Consistency Groups of RDF {} still not updated in ViPR DB. If async pair is created minutes back and tried delete immediately,please wait and try again",
                                group.getNativeGuid());
                        throw DeviceControllerException.exceptions.srdfAsyncStepDeletionfailed(group.getNativeGuid());
                    }
                }

            }
        }

        waitFor = deleteSRDFMirrorSteps(workflow, waitFor, sourceDescriptors);

        return waitFor;
    }

    private String createElementReplicaSteps(final Workflow workflow, String waitFor,
                                             final List<VolumeDescriptor> volumeDescriptors) {
        log.info("START create element replica steps");
        List<VolumeDescriptor> sourceDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                VolumeDescriptor.Type.SRDF_SOURCE, VolumeDescriptor.Type.SRDF_EXISTING_SOURCE);
        List<VolumeDescriptor> targetDescriptors = VolumeDescriptor.filterByType(volumeDescriptors,
                VolumeDescriptor.Type.SRDF_TARGET);
        Map<URI, Volume> uriVolumeMap = queryVolumes(volumeDescriptors);

        /**
         * If copy Mode synchronous, then always run createElementReplica, irrespective of
         * whether existing volumes are present in RA Group or not. Consistency parameter
         * doesn't have any effect , hence creating replication groups for each volume
         * doesn't make sense. Moreover, SMI-S has done rigorous testing of
         * Pause,resume,fail over,fail back on StorageSynchronized rather than on Groups.
         */
        boolean volumePartOfCG = isVolumePartOfCG(targetDescriptors, uriVolumeMap);

        if (!volumePartOfCG) {
            createNonCGSRDFVolumes(workflow, waitFor, sourceDescriptors, uriVolumeMap);
        } else {
            createCGSRDFVolumes(workflow, waitFor, sourceDescriptors, targetDescriptors,uriVolumeMap);
        }
        waitFor = CREATE_SRDF_MIRRORS_STEP_GROUP;

        if (volumePartOfCG && sourceDescriptors.size() > 1) {
            waitFor = updateSourceAndTargetPairings(workflow, waitFor,
                    sourceDescriptors, targetDescriptors, uriVolumeMap);
        }

        return waitFor;
    }

    private String updateSourceAndTargetPairings(Workflow workflow, String waitFor,
                                                 List<VolumeDescriptor> sourceDescriptors,
                                                 List<VolumeDescriptor> targetDescriptors,
                                                 Map<URI, Volume> uriVolumeMap) {

        log.info("Creating step to update source and target pairings");
        List<URI> sourceURIs = VolumeDescriptor.getVolumeURIs(sourceDescriptors);
        List<URI> targetURIs = VolumeDescriptor.getVolumeURIs(targetDescriptors);

        Volume firstSource = dbClient.queryObject(Volume.class, sourceURIs.get(0));
        StorageSystem sourceSystem = dbClient.queryObject(StorageSystem.class, firstSource.getStorageController());

        Method method = updateSourceAndTargetPairingsMethod(sourceURIs, targetURIs);
        workflow.createStep(UPDATE_SRDF_PAIRING_STEP_GROUP, UPDATE_SRDF_PAIRING_STEP_GROUP, waitFor,
                sourceSystem.getId(), sourceSystem.getSystemType(), getClass(), method, null, null);

        return UPDATE_SRDF_PAIRING_STEP_GROUP;
    }

    private Method updateSourceAndTargetPairingsMethod(List<URI> sourceURIs, List<URI> targetURIs) {
        return new Workflow.Method(UPDATE_SRDF_PAIRING, sourceURIs, targetURIs);
    }

    public boolean updateSRDFPairingStep(List<URI> sourceURIs, List<URI> targetURIs, String opId) {
        log.info("Updating SRDF pairings");

        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            completer = new SRDFTaskCompleter(sourceURIs, opId);
            getRemoteMirrorDevice().doUpdateSourceAndTargetPairings(sourceURIs, targetURIs);
            completer.ready(dbClient);
        } catch (Exception e) {
            log.error("Failed to update SRDF pairings", e);
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    protected void createNonCGSRDFVolumes(Workflow workflow, String waitFor, List<VolumeDescriptor> sourceDescriptors,
                                          Map<URI, Volume> uriVolumeMap) {
        for (VolumeDescriptor sourceDescriptor : sourceDescriptors) {
            Volume source = uriVolumeMap.get(sourceDescriptor.getVolumeURI());
            //this will be null for normal use cases except vpool change
            URI vpoolChangeUri = getVirtualPoolChangeVolume(sourceDescriptors);
            log.info("VPoolChange URI {}",vpoolChangeUri);
            StringSet srdfTargets = source.getSrdfTargets();
            for (String targetStr : srdfTargets) {
                URI targetURI = URI.create(targetStr);
                Volume target = uriVolumeMap.get(targetURI);
                RemoteDirectorGroup group = dbClient.queryObject(RemoteDirectorGroup.class,
                        target.getSrdfGroup());
                StorageSystem system = dbClient.queryObject(StorageSystem.class,
                        group.getSourceStorageSystemUri());

                Workflow.Method createMethod = createSRDFVolumePairMethod(system.getId(),
                        source.getId(), targetURI, vpoolChangeUri);
                Workflow.Method rollbackMethod = rollbackSRDFLinkMethod(system.getId(),
                        source.getId(), targetURI, false);
                // Ensure CreateElementReplica steps are executed sequentially (CQ613404)
                waitFor = workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP,
                        CREATE_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                        system.getSystemType(), getClass(), createMethod, rollbackMethod, null);
            }
        }
    }

    protected void createSyncSteps(Workflow workflow, String waitFor, Volume source) {
        StringSet srdfTargets = source.getSrdfTargets();
        for (String targetStr : srdfTargets) {
            URI targetURI = URI.create(targetStr);
            StorageSystem system = dbClient.queryObject(StorageSystem.class, source.getStorageController());

            Workflow.Method createMethod = createSRDFVolumePairMethod(system.getId(),
                    source.getId(), targetURI, null);
            Workflow.Method rollbackMethod = rollbackSRDFLinkMethod(system.getId(),
                    source.getId(), targetURI, false);
            // Ensure CreateElementReplica steps are executed sequentially (CQ613404)
            waitFor = workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP,
                    CREATE_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                    system.getSystemType(), getClass(), createMethod, rollbackMethod, null);
        }
    }

    @SuppressWarnings("unchecked")
    protected void createCGSRDFVolumes(Workflow workflow, String waitFor, List<VolumeDescriptor> sourceDescriptors,
                                       List<VolumeDescriptor> targetDescriptors, Map<URI, Volume> uriVolumeMap) {
        RemoteDirectorGroup group = getRAGroup(targetDescriptors, uriVolumeMap);
        StorageSystem system = dbClient.queryObject(StorageSystem.class, group.getSourceStorageSystemUri());
        //finding actual volumes from Provider
        Set<String> volumes = findVolumesPartOfRDFGroups(system, group);

        if (group.getVolumes() == null) {
            group.setVolumes(new StringSet());
        }
        // RDF Groups must be in sync with Array, to be able to make the right
        // decision for Async Groups.
        Set diff = new HashSet<String>();
        Sets.difference(volumes, new HashSet<String>(group.getVolumes()))
                .copyInto(diff);
        if (diff.size() > 0) {
            // throw Exception rediscover source and target arrays.
            log.warn("RDF Group {} out of sync with Array", group.getNativeGuid());
            List<URI> sourceURIs = VolumeDescriptor.getVolumeURIs(sourceDescriptors);
            List<URI> targetURIs = VolumeDescriptor.getVolumeURIs(targetDescriptors);
            URI vpoolChangeUri = getVirtualPoolChangeVolume(sourceDescriptors);
            // Clear source and target

            for (URI sourceUri : sourceURIs) {
                Volume sourceVolume = dbClient.queryObject(Volume.class, sourceUri);
                if (null != sourceVolume) {
                    log.info("Clearing source volume {}-->{}", sourceVolume.getNativeGuid(),
                            sourceVolume.getId());
                    if (null == vpoolChangeUri) {
                        // clear everything if not vpool change
                        sourceVolume.setPersonality(NullColumnValueGetter.getNullStr());
                        sourceVolume.setAccessState(Volume.VolumeAccessState.READWRITE.name());

                        sourceVolume.setInactive(true);
                        sourceVolume.setConsistencyGroup(NullColumnValueGetter.getNullURI());
                    }
                    if (null != sourceVolume.getSrdfTargets()) {
                        sourceVolume.getSrdfTargets().clear();
                    }
                    dbClient.updateAndReindexObject(sourceVolume);
                }

            }

            for (URI targetUri : targetURIs) {
                Volume targetVolume = dbClient.queryObject(Volume.class, targetUri);
                if (null != targetVolume) {
                    log.info("Clearing target volume {}-->{}", targetVolume.getNativeGuid(),
                            targetVolume.getId());
                    targetVolume.setPersonality(NullColumnValueGetter.getNullStr());
                    targetVolume.setAccessState(Volume.VolumeAccessState.READWRITE.name());
                    targetVolume.setSrdfParent(new NamedURI(NullColumnValueGetter.getNullURI(),
                            NullColumnValueGetter.getNullStr()));
                    targetVolume.setSrdfCopyMode(NullColumnValueGetter.getNullStr());
                    targetVolume.setSrdfGroup(NullColumnValueGetter.getNullURI());
                    targetVolume.setConsistencyGroup(NullColumnValueGetter.getNullURI());
                    targetVolume.setInactive(true);
                    dbClient.updateAndReindexObject(targetVolume);
                }

            }
            throw DeviceControllerException.exceptions.srdfAsyncStepCreationfailed(group
                    .getNativeGuid());

        }
        group.getVolumes().replace(volumes);
        dbClient.persistObject(group);

        if (volumes.isEmpty() && SupportedCopyModes.ALL.toString().equalsIgnoreCase(group.getSupportedCopyMode())) {
            log.info("RA Group {} was empty", group.getId());
            createSrdfCgPairStepsOnEmptyGroup(sourceDescriptors, targetDescriptors, group, waitFor, workflow);
        } else {
            log.info("RA Group {} not empty", group.getId());
            createSrdfCGPairStepsOnPopulatedGroup(sourceDescriptors, group, uriVolumeMap, waitFor, workflow);
        }
    }

    private void createSrdfCgPairStepsOnEmptyGroup(List<VolumeDescriptor> sourceDescriptors,
                                                   List<VolumeDescriptor> targetDescriptors, RemoteDirectorGroup group,
                                                   String waitFor, Workflow workflow) {

        StorageSystem system = dbClient.queryObject(StorageSystem.class, group.getSourceStorageSystemUri());
        URI vpoolChangeUri = getVirtualPoolChangeVolume(sourceDescriptors);
        log.info("VPoolChange URI {}",vpoolChangeUri);
        List<URI> sourceURIs = VolumeDescriptor.getVolumeURIs(sourceDescriptors);
        List<URI> targetURIs = VolumeDescriptor.getVolumeURIs(targetDescriptors);

        Workflow.Method createGroupsMethod = createSrdfCgPairsMethod(system.getId(), sourceURIs, targetURIs, vpoolChangeUri);
        Workflow.Method rollbackGroupsMethod = rollbackSRDFLinksMethod(system.getId(), sourceURIs, targetURIs, true);
        workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP, CREATE_SRDF_MIRRORS_STEP_DESC, waitFor,
                system.getId(), system.getSystemType(), getClass(), createGroupsMethod, rollbackGroupsMethod, null);
    }

    private void createSrdfCGPairStepsOnPopulatedGroup(List<VolumeDescriptor> sourceDescriptors,
                                                       RemoteDirectorGroup group, Map<URI, Volume> uriVolumeMap,
                                                       String waitFor, Workflow workflow) {

        List<URI> sourceURIs = VolumeDescriptor.getVolumeURIs(sourceDescriptors);
        StorageSystem system = dbClient.queryObject(StorageSystem.class, group.getSourceStorageSystemUri());
        URI vpoolChangeUri = getVirtualPoolChangeVolume(sourceDescriptors);
        log.info("VPoolChange URI {}",vpoolChangeUri);
        String stepId = waitFor;
        List<URI> targetURIs = new ArrayList<URI>();
        
        for (URI sourceURI : sourceURIs) {
            Volume source = uriVolumeMap.get(sourceURI);
            StringSet srdfTargets = source.getSrdfTargets();
            for (String targetStr : srdfTargets) {
                /*
                 * 1. Create Element Replicas for each source/target pairing
                 */
                URI targetURI = URI.create(targetStr);
                targetURIs.add(targetURI);
                Workflow.Method createMethod = createSRDFVolumePairMethod(
                        system.getId(), source.getId(), targetURI, null);
                Workflow.Method rollbackMethod = rollbackSRDFLinkMethod(system.getId(),
                        source.getId(), targetURI, false);
                stepId = workflow.createStep(CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP,
                        CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_DESC, stepId, system.getId(),
                        system.getSystemType(), getClass(), createMethod, rollbackMethod,
                        null);
            }
        }
        /*
         * 2. Invoke AddSyncpair with the created StorageSynchronized from Step 1
         */

        Workflow.Method addMethod = addVolumePairsToCgMethod(system.getId(), sourceURIs, group.getId(), vpoolChangeUri);
        Workflow.Method rollbackAddMethod = rollbackAddSyncVolumePairMethod(system.getId(), sourceURIs, targetURIs, false);
        workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP,
                CREATE_SRDF_MIRRORS_STEP_DESC, CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP, system.getId(),
                system.getSystemType(), getClass(), addMethod, rollbackAddMethod,
                null);
    }

    private void createSrdfCGPairStepsOnPopulatedGroup(Volume source,
                                                       String waitFor, Workflow workflow) {
        List<URI> sourceURIs = new ArrayList<URI>();
        sourceURIs.add(source.getId());
        StorageSystem system = dbClient.queryObject(StorageSystem.class, source.getStorageController());
        String stepId = waitFor;
        RemoteDirectorGroup group = null;
        StringSet srdfTargets = source.getSrdfTargets();
        if (null == srdfTargets)
            return;
        List<URI> targetURIS = new ArrayList<URI>();
        for (String targetStr : srdfTargets) {
            /* 1. Create Element Replicas for each source/target pairing */
            URI targetURI = URI.create(targetStr);
            targetURIS.add(targetURI);
            Volume target = dbClient.queryObject(Volume.class, targetURI);
            group = dbClient.queryObject(RemoteDirectorGroup.class,
                    target.getSrdfGroup());
            Workflow.Method createMethod = createSRDFVolumePairMethod(
                    system.getId(), source.getId(), targetURI, null);
            Workflow.Method rollbackMethod = rollbackSRDFLinkMethod(system.getId(),
                    source.getId(), targetURI, false);
            stepId = workflow.createStep(CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP,
                    CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_DESC, stepId, system.getId(),
                    system.getSystemType(), getClass(), createMethod, rollbackMethod,
                    null);
        }
        /* 2. Invoke AddSyncpair with the created StorageSynchronized from Step 1 */
        Workflow.Method addMethod = addVolumePairsToCgMethod(system.getId(), sourceURIs, group.getId(), null);
        Workflow.Method rollbackAddMethod = rollbackAddSyncVolumePairMethod(system.getId(), sourceURIs, targetURIS, false);
        workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP,
                CREATE_SRDF_MIRRORS_STEP_DESC, CREATE_SRDF_SYNC_VOLUME_PAIR_STEP_GROUP, system.getId(),
                system.getSystemType(), getClass(), addMethod, rollbackAddMethod,
                null);
    }

    private RemoteDirectorGroup getRAGroup(List<VolumeDescriptor> descriptors, Map<URI, Volume> uriVolumeMap) {
        Volume firstTarget = getFirstTarget(descriptors, uriVolumeMap);
        return dbClient.queryObject(RemoteDirectorGroup.class, firstTarget.getSrdfGroup());
    }

    private Mode getSRDFMode(List<VolumeDescriptor> sourceDescriptors, Map<URI, Volume> uriVolumeMap) {
        Volume firstTarget = getFirstTarget(sourceDescriptors, uriVolumeMap);
        return Mode.valueOf(firstTarget.getSrdfCopyMode());
    }

    private boolean isVolumePartOfCG(List<VolumeDescriptor> sourceDescriptors, Map<URI, Volume> uriVolumeMap) {
        Volume firstTarget = getFirstTarget(sourceDescriptors, uriVolumeMap);
        return  (firstTarget.getConsistencyGroup() != null);
    }

    private Volume getFirstTarget(List<VolumeDescriptor> descriptors, Map<URI, Volume> uriVolumeMap) {
        for (VolumeDescriptor volumeDescriptor : descriptors) {
            if (VolumeDescriptor.Type.SRDF_TARGET.equals(volumeDescriptor.getType())) {
                return uriVolumeMap.get(volumeDescriptor.getVolumeURI());
            }
        }
        throw new IllegalStateException("Excepted a target descriptor to exist");
    }

    private Volume getFirstTarget(Volume sourceVolume) {
        StringSet targets = sourceVolume.getSrdfTargets();

        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException("Source has no targets");
        }

        return dbClient.queryObject(Volume.class, URI.create(targets.iterator().next()));
    }

    private boolean canRemoveSrdfCg(Map<URI, Volume> volumeMap) {
        Volume targetVol = null;
        boolean volumePartOfCG = false;
        for (Volume source : volumeMap.values()) {
            volumePartOfCG = null != source.getConsistencyGroup();
            StringSet targets = source.getSrdfTargets();
            if (targets == null)
                return false;
            for (String target : targets) {
                targetVol = dbClient.queryObject(Volume.class, URI.create(target));
                break;
            }
            break;
        }
        RemoteDirectorGroup group = dbClient.queryObject(RemoteDirectorGroup.class,
                targetVol.getSrdfGroup());
        if (null == group) return true;
        StorageSystem system = getStorageSystem(group.getSourceStorageSystemUri());
        Set<String> volumes = findVolumesPartOfRDFGroups(system, group);
        if (group.getVolumes() == null) {
            group.setVolumes(new StringSet());
        }
        group.getVolumes().replace(volumes);
        log.info("# volumes : {}  in RDF Group {} after refresh",Joiner.on(",").join(group.getVolumes()), group.getNativeGuid());
        dbClient.persistObject(group);

        if (null != volumes && volumes.size() == volumeMap.size() && volumePartOfCG) {
            log.info("Deleting all the volumes  {} in CG  in one attempt",Joiner.on(",").join(volumeMap.keySet()));
            return true;
        }
        return false;

    }

    /**
     * Delete All SRDF Volumes in CG in one attempt.
     *
     * @param sourcesVolumeMap
     * @param workflow
     * @param waitFor
     * @return
     */
    private String deleteAllSrdfVolumesInCG(Map<URI, Volume> sourcesVolumeMap, final Workflow workflow,
                                            String waitFor, final List<VolumeDescriptor> sourceDescriptors) {

        // TODO Improve this logic
        Volume sourceVolume = sourcesVolumeMap.get(sourceDescriptors.get(0).getVolumeURI());
        Volume targetVolume = getFirstTarget(sourceVolume);
        if (targetVolume == null) {
            log.info("No target volume available for source {}", sourceVolume.getId());
            return waitFor;
        }
        StorageSystem sourceSystem = dbClient.queryObject(StorageSystem.class, sourceVolume.getStorageController());
        StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class, targetVolume.getStorageController());

        // Suspend all members in the group
        Method method = suspendSRDFLinkMethod(targetSystem.getId(), sourceVolume.getId(), targetVolume.getId(), false);
        String splitStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, targetSystem.getId(), targetSystem.getSystemType(), getClass(),
                method, null, null);

        // Second we detach the group...
        Workflow.Method detachMethod = detachGroupPairsMethod(targetSystem.getId(), sourceVolume.getId(),
                targetVolume.getId());
        String detachMirrorStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                DETACH_SRDF_MIRRORS_STEP_DESC, splitStep, targetSystem.getId(), targetSystem.getSystemType(),
                getClass(), detachMethod, null, null);

        waitFor = detachMirrorStep;

        for (Volume source : sourcesVolumeMap.values()) {
            StringSet srdfTargets = source.getSrdfTargets();
            for (String srdfTarget : srdfTargets) {
                log.info("suspend and detach: source:{}, target:{}", source.getId(), srdfTarget);
                URI targetURI = URI.create(srdfTarget);
                Volume target = dbClient.queryObject(Volume.class, targetURI);
                if (null == target) {
                    log.warn("Target volume {} not available for SRDF source vol {}",source.getId(),targetURI);
                    return DELETE_SRDF_MIRRORS_STEP_GROUP;
                }
                log.info("target Volume {} with srdf group {}", target.getNativeGuid(), target.getSrdfGroup());
                // Third we remove the device groups, a defensive step to remove
                // members from deviceGroups if it exists.
                Workflow.Method removeGroupsMethod = removeDeviceGroupsMethod(sourceSystem.getId(), source.getId(),
                        targetURI);
                waitFor = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP, REMOVE_DEVICE_GROUPS_STEP_DESC,
                        waitFor, sourceSystem.getId(), sourceSystem.getSystemType(), getClass(), removeGroupsMethod, null,
                        null);

            }
        }
        return DELETE_SRDF_MIRRORS_STEP_GROUP;
    }

    /**
     * Deletion of SRDF Volumes with/without CGs.
     *
     * @param workflow
     * @param waitFor
     * @param sourceDescriptors
     * @return
     */
    private String deleteSRDFMirrorSteps(final Workflow workflow, String waitFor,
                                         final List<VolumeDescriptor> sourceDescriptors) {
        log.info("START delete SRDF mirrors workflow");
        Map<URI, Volume> sourcesVolumeMap = queryVolumes(sourceDescriptors);
        if (canRemoveSrdfCg(sourcesVolumeMap)) {
            //invoke workflow to delete CG
            log.info("Invoking SRDF Consistency Group Deletion with all its volumes");
            return deleteAllSrdfVolumesInCG(sourcesVolumeMap, workflow, waitFor, sourceDescriptors);
        }
        //invoke deletion of volume within CG
        for (Volume source : sourcesVolumeMap.values()) {
            StorageSystem system = getStorageSystem(source.getStorageController());
            StringSet srdfTargets = source.getSrdfTargets();
            for (String srdfTarget : srdfTargets) {
                log.info("suspend and detach: source:{}, target:{}", source.getId(), srdfTarget);
                URI targetURI = URI.create(srdfTarget);
                Volume target = dbClient.queryObject(Volume.class, targetURI);
                if (null == target) {
                    log.warn("Target volume {} not available for SRDF source vol {}",source.getId(),targetURI);
                    //We need to proceed with the operation, as it could be because of a left over from last operation.
                    return waitFor;
                }
                log.info("target Volume {} with srdf group {}", target.getNativeGuid(),
                        target.getSrdfGroup());

                if (!source.hasConsistencyGroup()) {
                    // No CG, so suspend single link (cons_exempt used in case of Asynchronous)
                    Workflow.Method suspendMethod = suspendSRDFLinkMethod(system.getId(),
                            source.getId(), targetURI, true);
                    String suspendStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                            SUSPEND_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                            system.getSystemType(), getClass(), suspendMethod, null, null);
                    // Second we detach the mirrors...
                    Workflow.Method detachMethod = detachVolumePairMethod(system.getId(), source.getId(), targetURI);
                    String detachStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                            DETACH_SRDF_MIRRORS_STEP_DESC, suspendStep, system.getId(),
                            system.getSystemType(), getClass(), detachMethod, null, null);
                    waitFor = detachStep;

                } else {

                    // Defensive steps to prevent orphaned SRDF Volumes, which cannot be deleted.
                    // First we remove the sync pair from Async CG...
                    Workflow.Method removePairFromGroupMethod = removePairFromGroup(system.getId(),
                            source.getId(), targetURI, true);
                    String removePairFromGroupWorkflowDesc = String.format(REMOVE_SRDF_PAIR_STEP_DESC, target.getSrdfCopyMode());
                    String detachVolumePairWorkflowDesc = String.format(DETACH_SRDF_PAIR_STEP_DESC, target.getSrdfCopyMode());
                    
                    String removePairFromGroupStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                            removePairFromGroupWorkflowDesc, waitFor, system.getId(),
                            system.getSystemType(), getClass(), removePairFromGroupMethod, null, null);
                    // suspend the removed async pair
                    Workflow.Method suspendPairMethod = suspendSRDFLinkMethod(system.getId(),
                            source.getId(), targetURI, true);
                    String suspendPairStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                            SUSPEND_SRDF_MIRRORS_STEP_DESC, removePairFromGroupStep, system.getId(),
                            system.getSystemType(), getClass(), suspendPairMethod, null, null);
                    // Finally we detach the removed async pair...
                    // don't proceed if detach fails, earlier we were allowing the delete operation
                    // to proceed even if there is a failure on detach.
                    Workflow.Method detachPairMethod = detachVolumePairMethod(system.getId(),
                            source.getId(), targetURI);
                    waitFor = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                            detachVolumePairWorkflowDesc, suspendPairStep, system.getId(),
                            system.getSystemType(), getClass(), detachPairMethod, null, null);


                }
            }
        }
        return DELETE_SRDF_MIRRORS_STEP_GROUP;
    }



    private Workflow.Method convertToNonSrdfDevicesMethod(final URI systemURI, final URI sourceURI,
                                                          final URI targetURI, final boolean rollback) {
        return new Workflow.Method(CONVERT_TO_NONSRDF_DEVICES_METHOD, systemURI, sourceURI, targetURI,
                rollback);
    }


    public boolean convertToNonSrdfDevicesMethodStep(final URI systemURI, final URI sourceURI,
                                                     final URI targetURI, final boolean rollback, final String opId) {
        log.info("START conversion of srdf to non srdf devices");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            Volume source = dbClient.queryObject(Volume.class, sourceURI);
            Volume target = dbClient.queryObject(Volume.class, targetURI);
            //Change source and target RDF devices to non-srdf devices in DB
            source.setPersonality(NullColumnValueGetter.getNullStr());
            source.setAccessState(Volume.VolumeAccessState.READWRITE.name());
            source.getSrdfTargets().clear();
            target.setPersonality(NullColumnValueGetter.getNullStr());
            target.setAccessState(Volume.VolumeAccessState.READWRITE.name());
            target.setSrdfParent(new NamedURI(NullColumnValueGetter.getNullURI(), NullColumnValueGetter.getNullStr()));
            target.setSrdfCopyMode(NullColumnValueGetter.getNullStr());
            target.setSrdfGroup(NullColumnValueGetter.getNullURI());
            dbClient.persistObject(source);
            dbClient.persistObject(target);log.info("SRDF Devices source {} and target {} converted to non srdf devices",source.getId(),target.getId());
            completer = new SRDFTaskCompleter(sourceURI, targetURI, opId);
            completer.ready(dbClient);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    private String reSyncSRDFMirrorSteps(final Workflow workflow, final String waitFor,
                                         final Volume source) {
        log.info("START resync SRDF mirrors workflow");
        StorageSystem system = getStorageSystem(source.getStorageController());
        StringSet srdfTargets = source.getSrdfTargets();
        for (String srdfTarget : srdfTargets) {
            URI targetURI = URI.create(srdfTarget);
            Volume target = dbClient.queryObject(Volume.class, targetURI);
            if (null == target)
                return waitFor;
            log.info("target Volume {} with srdf group {}", target.getNativeGuid(),
                    target.getSrdfGroup());
            Workflow.Method reSyncMethod = reSyncSRDFLinkMethod(system.getId(),
                    source.getId(), targetURI);
            String reSyncStep = workflow.createStep(RESYNC_SRDF_MIRRORS_STEP_GROUP,
                    RESYNC_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                    system.getSystemType(), getClass(), reSyncMethod, null, null);
        }
        return RESYNC_SRDF_MIRRORS_STEP_GROUP;
    }

    private Method detachVolumePairMethod(URI systemURI, URI sourceURI, URI targetURI) {
        return new Workflow.Method(DETACH_SRDF_PAIR_METHOD, systemURI, sourceURI, targetURI, false);
    }

    private Method detachGroupPairsMethod(URI systemURI, URI sourceURI, URI targetURI) {
        return new Workflow.Method(DETACH_SRDF_PAIR_METHOD, systemURI, sourceURI, targetURI, true);
    }

    public boolean detachVolumePairStep(final URI systemURI, final URI sourceURI,
                                        final URI targetURI, final boolean onGroup, final String opId) {
        log.info("START Detach Pair onGroup={}", onGroup);
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            completer = new SRDFTaskCompleter(sourceURI, targetURI, opId);
            getRemoteMirrorDevice().doDetachLink(system, sourceURI, targetURI, onGroup, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    private Method removePairFromGroup(URI systemURI, URI sourceURI, URI targetURI,final boolean rollback) {
        return new Workflow.Method(REMOVE_ASYNC_PAIR_METHOD, systemURI, sourceURI, targetURI,rollback);
    }

    public boolean removePairFromGroup(final URI systemURI, final URI sourceURI,
                                       final URI targetURI, final boolean rollback, final String opId) {
        log.info("START Remove Pair from Group");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            dbClient.queryObject(Volume.class, targetURI);
            completer = new SRDFTaskCompleter(sourceURI, targetURI, opId);
            getRemoteMirrorDevice().doRemoveVolumePair(system, sourceURI, targetURI, rollback,
                    completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    public boolean resumeSyncPairStep(final URI systemURI, final URI sourceURI,
                                      final URI targetURI, final String opId) {
        log.info("START Resume Sync Pair");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            Volume targetVolume = dbClient.queryObject(Volume.class, targetURI);
            completer = new SRDFTaskCompleter(sourceURI, targetURI, opId);
            getRemoteMirrorDevice().doResumeLink(system, targetVolume, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    public Method resumeSyncPairMethod(final URI systemURI, final URI sourceURI,
                                        final URI targetURI) {
        return new Workflow.Method(CREATE_SRDF_RESUME_PAIR_METHOD, systemURI, sourceURI, targetURI);
    }

    private Method reSyncSRDFLinkMethod(final URI systemURI, final URI sourceURI,
                                        final URI targetURI) {
        return new Workflow.Method(CREATE_SRDF_RESYNC_PAIR_METHOD, systemURI, sourceURI, targetURI);
    }

    public boolean reSyncSRDFLinkStep(final URI systemURI, final URI sourceURI,
                                      final URI targetURI, final String opId) {
        log.info("START ReSync SRDF Links");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            completer = new SRDFTaskCompleter(sourceURI, targetURI, opId);
            getRemoteMirrorDevice().doResyncLink(system, sourceURI, targetURI, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    private Workflow.Method rollbackSRDFLinksMethod(final URI systemURI, final List<URI> sourceURIs,
                                                    final List<URI> targetURIs, final boolean isGroupRollback) {
        return new Workflow.Method(ROLLBACK_SRDF_LINKS_METHOD, systemURI, sourceURIs, targetURIs, isGroupRollback);
    }

    // Convenience method for singular usage of #rollbackSRDFLinksMethod
    private Workflow.Method rollbackSRDFLinkMethod(final URI systemURI, final URI sourceURI,
                                                   final URI targetURI, final boolean isGroupRollback) {
        return rollbackSRDFLinksMethod(systemURI, asList(sourceURI), asList(targetURI), isGroupRollback);
    }

	public boolean rollbackSRDFLinksStep(URI systemURI, List<URI> sourceURIs,
			List<URI> targetURIs, boolean isGroupRollback, String opId) {
        log.info("START rollback multiple SRDF links");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            completer = new SRDFMirrorRollbackCompleter(sourceURIs, opId);
            getRemoteMirrorDevice().doRollbackLinks(system, sourceURIs, targetURIs, isGroupRollback, completer);
        } catch (Exception e) {
            log.error("Ignoring exception while rolling back SRDF sources: {}", sourceURIs, e);
            // Succeed here, to allow other rollbacks to run
            if (null != completer) {
                completer.ready(dbClient);
            }
            WorkflowStepCompleter.stepSucceded(opId);
            return false;
        }
        return true;
    }
    
    private Workflow.Method createSRDFVolumePairMethod(final URI systemURI,
                                                       final URI sourceURI, final URI targetURI, final URI vpoolChangeUri) {
        return new Workflow.Method(CREATE_SRDF_VOLUME_PAIR, systemURI, sourceURI, targetURI, vpoolChangeUri);
    }

    public boolean createSRDFVolumePairStep(final URI systemURI, final URI sourceURI,
                                            final URI targetURI, final URI vpoolChangeUri, final String opId) {
        log.info("START Add srdf volume pair");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            completer = new SRDFMirrorCreateCompleter(sourceURI, targetURI,vpoolChangeUri, opId);
            getRemoteMirrorDevice().doCreateLink(system, sourceURI, targetURI, completer);
            log.info("Source: {}", sourceURI);
            log.info("Target: {}", targetURI);
            log.info("OpId: {}", opId);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

	private Method rollbackAddSyncVolumePairMethod(final URI systemURI,
			final List<URI> sourceURIs, final List<URI> targetURIs,
			final boolean isGroupRollback) {
		return new Workflow.Method(ROLLBACK_ADD_SYNC_VOLUME_PAIR_METHOD,
				systemURI, sourceURIs, targetURIs, isGroupRollback);
	}

	public boolean rollbackAddSyncVolumePairStep(final URI systemURI,
			final List<URI> sourceURIs, final List<URI> targetURIs,
			final boolean isGroupRollback, final String opId) {
		log.info("START rollback srdf volume pair");
		TaskCompleter completer = new SRDFMirrorRollbackCompleter(sourceURIs,
				opId);
		try {
			// removePairFromGroup step not required as addPair failed
			completer.ready(dbClient);
			WorkflowStepCompleter.stepSucceded(opId);
		} catch (Exception e) {
			log.warn("Error during rollback for adding sync pairs", e);
		}
		return true;
	}

    private Method addVolumePairsToCgMethod(URI systemURI, List<URI> sourceURIs, URI remoteDirectorGroupURI, URI vpoolChangeUri) {
        return new Workflow.Method(ADD_SYNC_VOLUME_PAIRS_METHOD, systemURI, sourceURIs, remoteDirectorGroupURI, vpoolChangeUri);
    }

    public boolean addVolumePairsToCgMethodStep(URI systemURI, List<URI> sourceURIs, URI remoteDirectorGroupURI, URI vpoolChangeUri, String opId) {
        log.info("START Add VolumePair to CG");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            completer = new SRDFAddPairToGroupCompleter(sourceURIs,vpoolChangeUri,opId);
            getRemoteMirrorDevice().doAddVolumePairsToCg(system, sourceURIs, remoteDirectorGroupURI, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    private Workflow.Method suspendSRDFLinkMethod(URI systemURI, URI sourceURI, URI targetURI, boolean consExempt) {
        return new Workflow.Method(SUSPEND_SRDF_LINK_METHOD, systemURI, sourceURI, targetURI, consExempt);
    }

    public boolean suspendSRDFLinkStep(URI systemURI, URI sourceURI, URI targetURI, boolean consExempt, String opId) {
        log.info("START Suspend SRDF link");
        TaskCompleter completer = null;

        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            Volume target = dbClient.queryObject(Volume.class, targetURI);
            List<URI> combined = Arrays.asList(sourceURI, targetURI);
            completer = new SRDFLinkPauseCompleter(combined, opId);
            getRemoteMirrorDevice().doSuspendLink(system, target, consExempt, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }

        return true;
    }

    public Workflow.Method splitSRDFLinkMethod(URI systemURI, URI sourceURI, URI targetURI, boolean rollback) {
        return new Workflow.Method(SPLIT_SRDF_LINK_METHOD, systemURI, sourceURI, targetURI, rollback);
    }

    public boolean splitSRDFLinkStep(URI systemURI, URI sourceURI, URI targetURI,
                                     boolean rollback, String opId) {
        log.info("START Split SRDF link");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            Volume targetVolume = dbClient.queryObject(Volume.class, targetURI);
            List<URI> combined = Arrays.asList(sourceURI, targetURI);
            completer = new SRDFLinkPauseCompleter(combined, opId);
            getRemoteMirrorDevice().doSplitLink(system, targetVolume, rollback, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return true;
    }

    private Workflow.Method removeDeviceGroupsMethod(final URI systemURI, final URI sourceURI,
                                                     final URI targetURI) {
        return new Workflow.Method(REMOVE_DEVICE_GROUPS_METHOD, systemURI, sourceURI, targetURI);
    }

    public boolean removeDeviceGroupsStep(final URI systemURI, final URI sourceURI,
                                          final URI targetURI, final String opId) {
        log.info("START remove device groups");
        TaskCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            List<URI> combined = Arrays.asList(sourceURI, targetURI);
            completer = new SRDFRemoveDeviceGroupsCompleter(combined, opId);
            getRemoteMirrorDevice().doRemoveDeviceGroups(system, sourceURI, targetURI, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return false;
    }

    private Method createSrdfCgPairsMethod(URI system, List<URI> sourceURIs, List<URI> targetURIs, URI vpoolChangeUri) {
        return new Method(CREATE_SRDF_ASYNC_MIRROR_METHOD, system, sourceURIs, targetURIs, vpoolChangeUri);
    }

    public boolean createSrdfCgPairsStep(URI systemURI, List<URI> sourceURIs, List<URI> targetURIs, URI vpoolChangeUri, String opId) {
        log.info("START creating SRDF Pairs in CGs");
        SRDFMirrorCreateCompleter completer = null;
        try {
            WorkflowStepCompleter.stepExecuting(opId);
            StorageSystem system = getStorageSystem(systemURI);
            List<URI> combined = new ArrayList<URI>(sourceURIs);
            combined.addAll(targetURIs);
            completer = new SRDFMirrorCreateCompleter(combined, vpoolChangeUri, opId);
            getRemoteMirrorDevice().doCreateCgPairs(system, sourceURIs, targetURIs, completer);
        } catch (Exception e) {
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
            WorkflowStepCompleter.stepFailed(opId, error);
            return false;
        }
        return false;
    }

    /**
     * Convenience method to build a Map of URI's to their respective Volumes based on a List of
     * VolumeDescriptor.
     *
     * @param volumeDescriptors List of volume descriptors
     * @return Map of URI to Volume
     */
    private Map<URI, Volume> queryVolumes(final List<VolumeDescriptor> volumeDescriptors) {
        List<URI> volumeURIs = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
        List<Volume> volumes = dbClient.queryObject(Volume.class, volumeURIs);
        Map<URI, Volume> volumeMap = new HashMap<URI, Volume>();
        for (Volume volume : volumes) {
            volumeMap.put(volume.getId(), volume);
        }
        return volumeMap;
    }

    private Set<String> findVolumesPartOfRDFGroups(StorageSystem system, RemoteDirectorGroup rdfGroup) {
        return getRemoteMirrorDevice().findVolumesPartOfRemoteGroup(system, rdfGroup);
    }

    private RemoteMirroring getRemoteMirrorDevice() {
        return (RemoteMirroring) devices.get(StorageSystem.Type.vmax.toString());
    }

    private StorageSystem getStorageSystem(final URI systemURI) {
        return dbClient.queryObject(StorageSystem.class, systemURI);
    }

    @Override
    public void connect(final URI protection) throws InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void disconnect(final URI protection) throws InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void discover(final AsyncTask[] tasks) throws InternalException {
        // TODO Auto-generated method stub
    }

    @Override
    public void performProtectionOperation(final URI systemUri, final URI id,
                                           final String op, final String task) throws InternalException {
        TaskCompleter completer = null;
        try {
            URI sourceVolumeUri = null;
            StorageSystem system = dbClient.queryObject(StorageSystem.class, systemUri);
            Volume volume = dbClient.queryObject(Volume.class, id);
            List<String> targetVolumeUris = new ArrayList<String>();
            List<URI> combined = new ArrayList<URI>();
            if (PersonalityTypes.SOURCE.toString().equalsIgnoreCase(volume.getPersonality())) {
                targetVolumeUris.addAll(volume.getSrdfTargets());
                sourceVolumeUri = volume.getId();
                combined.add(sourceVolumeUri);
                combined.addAll(transform(volume.getSrdfTargets(), FCTN_STRING_TO_URI));
            } else {
                sourceVolumeUri = volume.getSrdfParent().getURI();
                targetVolumeUris.add(volume.getId().toString());
                combined.add(sourceVolumeUri);
                combined.add(volume.getId());
            }
            
            /**
             * Async WITHOUT CG
             * SRDF operations will be happening for all volumes available on ra group.
             * Hence adding the missing source volume ids in the taskCompleter to change the accessState and linkStatus field.
             */
            Volume targetVol,sourceVol = null;
            sourceVol = dbClient.queryObject(Volume.class, sourceVolumeUri);
            Iterator<String> taregtVolumeUrisIterator = targetVolumeUris.iterator();
            if( taregtVolumeUrisIterator.hasNext()){
            	targetVol = dbClient.queryObject(Volume.class, URI.create(taregtVolumeUrisIterator.next()));
            	if(targetVol!=null && Mode.ASYNCHRONOUS.toString().equalsIgnoreCase(targetVol.getSrdfCopyMode())
            			&& !targetVol.hasConsistencyGroup()){
                	List<Volume> associatedSourceVolumeList = utils.getRemainingSourceVolumesForAsyncRAGroup(sourceVol, targetVol);
                    
                    for(Volume vol:associatedSourceVolumeList){
                    	if(!combined.contains(vol.getId())){
                    		combined.add(vol.getId());
                    	}
                    }
                }
            }
            /**
             * Needs to add all SRDF source volumes id to change the linkStatus and accessState
             * for Sync/Async with CG
             */
            if(sourceVol!=null && sourceVol.hasConsistencyGroup()){
            	 List<URI> srcVolumeUris =   dbClient.queryByConstraint(
                         getVolumesByConsistencyGroup(sourceVol.getConsistencyGroup()));
            	 for(URI uri:srcVolumeUris){
            		 if(!combined.contains(uri)){
            			 combined.add(uri);
            		 }
            	 }
            }
            log.info("Combined ids : {}", Joiner.on("\t").join(combined));
            if (op.equalsIgnoreCase("failover")) {
                completer = new SRDFLinkFailOverCompleter(combined, task);
                getRemoteMirrorDevice().doFailoverLink(system, volume, completer);
            } else if (op.equalsIgnoreCase("failover-cancel")) {
                completer = new SRDFLinkFailOverCancelCompleter(combined, task);
                getRemoteMirrorDevice().doFailoverCancelLink(system, volume, completer);
            } else if (op.equalsIgnoreCase("swap")) {
                completer = new SRDFSwapCompleter(combined, task);
                getRemoteMirrorDevice().doSwapVolumePair(system, volume, completer);
            } else if (op.equalsIgnoreCase("pause")) {
                completer = new SRDFLinkPauseCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doSplitLink(targetSystem, targetVolume, false, completer);
                }
            } else if (op.equalsIgnoreCase("suspend")) {
                completer = new SRDFLinkSuspendCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doSuspendLink(targetSystem, targetVolume, false, completer);
                }
            } else if (op.equalsIgnoreCase("resume")) {
                completer = new SRDFLinkResumeCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doResumeLink(targetSystem, targetVolume, completer);
                }
            } else if (op.equalsIgnoreCase("start")) {
                completer = new SRDFLinkStartCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doStartLink(targetSystem, targetVolume, completer);
                }
            } else if (op.equalsIgnoreCase("sync")) {
                completer = new SRDFLinkSyncCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doSyncLink(targetSystem, targetVolume, completer);
                }
            } else if (op.equalsIgnoreCase("stop")) {
                completer = new SRDFLinkStopCompleter(combined, task);
                for (String target : targetVolumeUris) {
                    Volume targetVolume = dbClient.queryObject(Volume.class, URI.create(target));
                    StorageSystem targetSystem = dbClient.queryObject(StorageSystem.class,
                            targetVolume.getStorageController());
                    getRemoteMirrorDevice().doStopLink(targetSystem, targetVolume, completer);
                }
            }
        } catch (Exception e) {
            log.error("Failed operation {}", op, e);
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
        }
    }

    private String addExpandBlockVolumeSteps(Workflow workflow, String waitFor, URI pool, URI sourceVolumeUri, Long size, String token)
            throws InternalException {
        Volume sourceVolume = dbClient.queryObject(Volume.class, sourceVolumeUri);
        //add step to expand the source
        createExpandStep(workflow, waitFor, size, sourceVolume.getId().toString(), "Source volume expand subtask: ");
        //add steps to expand the targets
        StringSet targets = sourceVolume.getSrdfTargets();
        for (String target : targets) {
            createExpandStep(workflow, waitFor, size, target, "Target volume expand subtask: ");
        }
        return STEP_VOLUME_EXPAND;
    }
    
    private void createExpandStep(Workflow workflow, String waitFor, Long size, String volumeURI, String description) {
        Volume volume = dbClient.queryObject(Volume.class, URI.create(volumeURI));
        if (volume != null) {        
            StorageSystem system = dbClient.queryObject(StorageSystem.class, volume.getStorageController());
            String createStepId = workflow.createStepId();
            workflow.createStep(STEP_VOLUME_EXPAND, description.concat(volume.getLabel()),
                    waitFor, system.getId(), system.getSystemType(), BlockDeviceController.class,
                    BlockDeviceController.expandVolumesMethod(system.getId(), volume.getPool(), volume.getId(), size),
                    BlockDeviceController.rollbackExpandVolumeMethod(system.getId(), volume.getId(), createStepId), createStepId);
        }
        
    }


    @Override
    public void expandVolume(URI storage, URI pool, URI volumeId, Long size, String task) throws InternalException {
        TaskCompleter completer = null;
        Workflow workflow = workflowService.getNewWorkflow(this, "expandVolume", true, task);
        String waitFor = null;
        try {
            Volume source = dbClient.queryObject(Volume.class, volumeId);
            StorageSystem system = getStorageSystem(source.getStorageController());
            StringSet targets = source.getSrdfTargets();
            List<URI> combined = Lists.newArrayList();

            combined.add(source.getId());
            combined.addAll(transform(targets, FCTN_STRING_TO_URI));
            completer = new SRDFExpandCompleter(combined, task);

            if (null != targets) {
                for (String targetURI : targets) {
                    Volume target = dbClient.queryObject(Volume.class, URI.create(targetURI));
                    log.info("target Volume {} with srdf group {}", target.getNativeGuid(),
                            target.getSrdfGroup());
                    RemoteDirectorGroup group =  dbClient.queryObject(RemoteDirectorGroup.class, target.getSrdfGroup());
                    Set<String> volumes = findVolumesPartOfRDFGroups(system, group);
                    if (group.getVolumes() == null) {
                        group.setVolumes(new StringSet());
                    }
                    group.getVolumes().replace(volumes);
                    dbClient.persistObject(group);

                    if (!source.hasConsistencyGroup()) {
                        // First we suspend the mirror...
                        Workflow.Method suspendMethod = suspendSRDFLinkMethod(system.getId(),
                                source.getId(), target.getId(), true);
                        // TODO Belongs as a rollback for the detach step
                        Workflow.Method rollbackMethod = createSRDFVolumePairMethod(system.getId(),
                                source.getId(), target.getId(), null);
                        String suspendStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                                system.getSystemType(), getClass(), suspendMethod, rollbackMethod, null);

                        // Second we detach the mirror...
                        Workflow.Method detachMethod = detachVolumePairMethod(system.getId(),
                                source.getId(), target.getId());
                        String detachStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                DETACH_SRDF_MIRRORS_STEP_DESC, suspendStep, system.getId(),
                                system.getSystemType(), getClass(), detachMethod, null, null);

                        //Expand the source and target Volumes
                        String expandStep = addExpandBlockVolumeSteps(workflow, detachStep, pool, volumeId, size, task);

                        //resync source and target again
                        createSyncSteps(workflow, expandStep, source);
                    } else {

                        if (volumes.size() == 1) {

                            // split all members the group
                            Workflow.Method splitMethod = splitSRDFLinkMethod(system.getId(),
                                    source.getId(), target.getId(), false);
                            String splitStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                    SPLIT_SRDF_MIRRORS_STEP_DESC, waitFor, system.getId(),
                                    system.getSystemType(), getClass(), splitMethod, null, null);

                            // Second we detach the group...
                            Workflow.Method detachMethod = detachGroupPairsMethod(system.getId(),
                                    source.getId(), target.getId());
                            Workflow.Method resumeSyncPairMethod = resumeSyncPairMethod(system.getId(),
                                    source.getId(), target.getId());
                            String detachMirrorStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                    DETACH_SRDF_MIRRORS_STEP_DESC, splitStep, system.getId(),
                                    system.getSystemType(), getClass(), detachMethod, resumeSyncPairMethod, null);

                            //Expand the source and target Volumes
                            String expandStep = addExpandBlockVolumeSteps(workflow, detachMirrorStep, pool, volumeId, size, task);

                            //re-establish again
                            List<URI> sourceURIs = new ArrayList<URI>();
                            sourceURIs.add(source.getId());
                            List<URI> targetURIs = new ArrayList<URI>();
                            targetURIs.add(target.getId());

                            Workflow.Method createGroupsMethod = createSrdfCgPairsMethod(system.getId(), sourceURIs, targetURIs, null);
                            workflow.createStep(CREATE_SRDF_MIRRORS_STEP_GROUP, CREATE_SRDF_MIRRORS_STEP_DESC, expandStep,
                                    system.getId(), system.getSystemType(), getClass(), createGroupsMethod, null, null);


                        } else {

                            // First we remove the sync pair from Async CG...
                            Workflow.Method removeAsyncPairMethod = removePairFromGroup(system.getId(),
                                    source.getId(), target.getId(), true);
                            List<URI> sourceUris = new ArrayList<URI>();
                            sourceUris.add(system.getId());
                            
                            String removePairFromGroupWorkflowDesc = String.format(REMOVE_SRDF_PAIR_STEP_DESC, target.getSrdfCopyMode());
                            String detachVolumePairWorkflowDesc = String.format(DETACH_SRDF_PAIR_STEP_DESC, target.getSrdfCopyMode());
                            
                            Workflow.Method addSyncPairMethod = addVolumePairsToCgMethod(system.getId(),
                                    sourceUris, group.getId(), null);

                            String removeAsyncPairStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                    removePairFromGroupWorkflowDesc, waitFor, system.getId(),
                                    system.getSystemType(), getClass(), removeAsyncPairMethod, addSyncPairMethod, null);

                            // split the removed async pair
                            Workflow.Method suspend = suspendSRDFLinkMethod(system.getId(),
                                    source.getId(), target.getId(), true);
                            Workflow.Method resumeSyncPairMethod = resumeSyncPairMethod(system.getId(),
                                    source.getId(), target.getId());
                            String suspendStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                    SPLIT_SRDF_MIRRORS_STEP_DESC, removeAsyncPairStep, system.getId(),
                                    system.getSystemType(), getClass(), suspend, resumeSyncPairMethod, null);

                            // Finally we detach the removed async pair...
                            Workflow.Method detachAsyncPairMethod = detachVolumePairMethod(system.getId(),
                                    source.getId(), target.getId());
                            Workflow.Method createSyncPairMethod = createSRDFVolumePairMethod(system.getId(),
                                    source.getId(), target.getId(), null);
                            String detachStep = workflow.createStep(DELETE_SRDF_MIRRORS_STEP_GROUP,
                                    detachVolumePairWorkflowDesc, suspendStep, system.getId(),
                                    system.getSystemType(), getClass(), detachAsyncPairMethod, createSyncPairMethod, null);

                            //Expand the source and target Volumes
                            String expandStep = addExpandBlockVolumeSteps(workflow, detachStep, pool, volumeId, size, task);

                            //create Relationship again
                            createSrdfCGPairStepsOnPopulatedGroup(source, expandStep, workflow) ;
                        }
                    }

                }
            }
            String successMessage = String.format("Workflow of SRDF Expand Volume %s successfully created",
                    volumeId);
            workflow.executePlan(completer, successMessage);
        } catch (Exception e) {
            log.error("Failed SRDF Expand Volume operation ",  e);
            ServiceError error = DeviceControllerException.errors.jobFailed(e);
            if (null != completer) {
                completer.error(dbClient, error);
            }
        }
    }

    private URI getVirtualPoolChangeVolume(List<VolumeDescriptor> volumeDescriptors) {
        for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
            if (volumeDescriptor.getParameters() != null) {
                if (volumeDescriptor.getParameters().get(
                        VolumeDescriptor.PARAM_VPOOL_CHANGE_VPOOL_ID) != null) {
                    return (URI) volumeDescriptor.getParameters().get(
                            VolumeDescriptor.PARAM_VPOOL_CHANGE_VPOOL_ID);
                }
            }
        }
        return null;
    }

    @Override
    public String addStepsForPostDeleteVolumes(Workflow workflow, String waitFor,
                                               List<VolumeDescriptor> volumes, String taskId,
                                               VolumeWorkflowCompleter completer) {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForExpandVolume(Workflow workflow, String waitFor,
                                          List<VolumeDescriptor> volumeDescriptors, String taskId)
            throws InternalException {
        //TODO : JIRA CTRL-5335 SRDF expand needs to go via BlockOrchestrationController. Implement expand here.
        return null;
    }

    @Override
    public String addStepsForChangeVirtualPool(Workflow workflow,
                                               String waitFor, List<VolumeDescriptor> volumes, String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForChangeVirtualArray(Workflow workflow, String waitFor,
                                                List<VolumeDescriptor> volumes, String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

	public void setUtils(SRDFUtils utils) {
		this.utils = utils;
	}
	
	/**
     * Creates a rollback workflow method that does nothing, but allows rollback
     * to continue to prior steps back up the workflow chain.
     * 
     * @return A workflow method
     */
    private Workflow.Method rollbackMethodNullMethod() {
        return new Workflow.Method(ROLLBACK_METHOD_NULL);
    }
}
