package com.ptoceti.nexus3.plugin.localservice;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
public class Data {
    @XmlElement(name = "presentLocally")
    String presentLocally;
    @XmlElement(name = "groupId")
    String groupId;
    @XmlElement(name = "artifactId")
    String artifactId;
    @XmlElement(name = "version")
    String version;
    @XmlElement(name = "baseVersion")
    String baseVersion;
    @XmlElement(name = "classifier")
    String classifier;
    @XmlElement(name = "extension")
    String extension;
    @XmlElement(name = "snapshot")
    String snapshot;
    @XmlElement(name = "snapshotBuildNumber")
    String snapshotBuildNumber;
    @XmlElement(name = "snapshotTimeStamp")
    String snapshotTimeStamp;
    @XmlElement(name = "repositoryPath")
    String repositoryPath;
    @XmlElement(name = "sha1")
    String sha1;
}
