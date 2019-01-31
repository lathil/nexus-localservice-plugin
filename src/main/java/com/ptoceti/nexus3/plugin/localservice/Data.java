package com.ptoceti.nexus3.plugin.localservice;

import com.fasterxml.jackson.annotation.JsonRootName;
import org.jboss.resteasy.annotations.providers.NoJackson;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
@JsonRootName(value = "data")
public class Data {
    @XmlElement(name = "presentLocally")
    public String presentLocally;
    @XmlElement(name = "groupId")
    public String groupId;
    @XmlElement(name = "artifactId")
    public String artifactId;
    @XmlElement(name = "version")
    public String version;
    @XmlElement(name = "baseVersion")
    public String baseVersion;
    @XmlElement(name = "classifier")
    public String classifier;
    @XmlElement(name = "extension")
    public String extension;
    @XmlElement(name = "snapshot")
    public String snapshot;
    @XmlElement(name = "snapshotBuildNumber")
    public String snapshotBuildNumber;
    @XmlElement(name = "snapshotTimeStamp")
    public String snapshotTimeStamp;
    @XmlElement(name = "repositoryPath")
    public String repositoryPath;
    @XmlElement(name = "sha1")
    public String sha1;
}
