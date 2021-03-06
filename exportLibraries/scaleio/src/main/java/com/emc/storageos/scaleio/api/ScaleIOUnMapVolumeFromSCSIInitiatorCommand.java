/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/*
 * Copyright (c) 2014 EMC Corporation
 *
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.scaleio.api;

import com.iwave.ext.command.CommandException;

import java.util.List;

public class ScaleIOUnMapVolumeFromSCSIInitiatorCommand extends AbstractScaleIOQueryCommand<ScaleIOUnMapVolumeFromSCSIInitiatorResult> {

    private static final String UNMAPPED_SUCCESSFULLY = "UnmappedSuccessfully";
    // Successfully unmapped volume with ID e9e51f7c00000002 from SCSI Initiator with ID 19be524400000000
    private final static ParsePattern[] PARSING_CONFIG = new ParsePattern[]{
            new ParsePattern("Successfully unmapped volume with ID \\w+ from SCSI Initiator with IQN .*", UNMAPPED_SUCCESSFULLY)
    };

    public ScaleIOUnMapVolumeFromSCSIInitiatorCommand(ScaleIOCommandSemantics commandSemantics, String volumeId, String iqn) {
        addArgument("--unmap_volume_from_scsi_initiator");
        addArgument(String.format("--volume_id %s", volumeId));
        addArgument(String.format("--iqn %s", iqn));
        if (commandSemantics != ScaleIOCommandSemantics.SIO1_2X) {
            // SIO 1.30+ prompts the user for certain operations; this argument gets past the prompt
            addArgument("--i_am_sure");
        }
        results = new ScaleIOUnMapVolumeFromSCSIInitiatorResult();
        results.setIsSuccess(false);
    }

    @Override
    ParsePattern[] getOutputPatternSpecification() {
        return PARSING_CONFIG;
    }

    @Override
    protected void processError() throws CommandException {
        results.setErrorString(getErrorMessage());
        results.setIsSuccess(false);
    }

    @Override
    void beforeProcessing() {

    }

    @Override
    void processMatch(ParsePattern spec, List<String> capturedStrings) {
        if (spec.getPropertyName().equals(UNMAPPED_SUCCESSFULLY)) {
            results.setIsSuccess(true);
        }
    }
}
