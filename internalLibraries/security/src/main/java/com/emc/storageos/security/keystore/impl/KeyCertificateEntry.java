/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.security.keystore.impl;

import java.io.Serializable;
import java.security.Key;
import java.security.cert.Certificate;
import java.util.Date;

import org.apache.commons.codec.binary.Base64;

/**
 * a class representing a key and it's matching certificate's entry in persistence
 */
public class KeyCertificateEntry implements Serializable {

    private static final long serialVersionUID = 9217286234984340287L;
    private byte[] key;
    private Certificate[] certificateChain;
    private Date creationDate;

    public KeyCertificateEntry(byte[] key, Certificate[] certificateChain) {
        this.key = Base64.encodeBase64(key);
        this.certificateChain = certificateChain;
    }

    public KeyCertificateEntry(Key key, Certificate[] certificateChain) {
        this(key.getEncoded(), certificateChain);
    }

    public KeyCertificateEntry(Key key, Certificate[] certificateChain, Date creationDate) {
        this(key, certificateChain);
        this.creationDate = creationDate;
    }

    public KeyCertificateEntry(byte[] key, Certificate[] certificateChain,
            Date creationDate) {
        this(key, certificateChain);
        this.creationDate = creationDate;
    }

    /**
     * @return the key
     */
    public byte[] getKey() {
        return Base64.decodeBase64(key);
    }

    /**
     * @param key
     *            the key to set
     */
    public void setKey(Key key) {
        this.key = Base64.encodeBase64(key.getEncoded());
    }

    /**
     * @param key
     *            the key to set
     */
    public void setKey(byte[] key) {
        this.key = Base64.encodeBase64(key);
    }

    /**
     * @return the certificateChain
     */
    public Certificate[] getCertificateChain() {
        return certificateChain;
    }

    /**
     * @param certificateChain
     *            the certificateChain to set
     */
    public void setCertificateChain(Certificate[] certificateChain) {
        this.certificateChain = certificateChain;
    }

    /**
     * @return the creationDate
     */
    public Date getCreationDate() {
        return creationDate;
    }

    /**
     * @param creationDate
     *            the creationDate to set
     */
    public void setCreationDate(Date creationDate) {
        this.creationDate = creationDate;
    }

}
