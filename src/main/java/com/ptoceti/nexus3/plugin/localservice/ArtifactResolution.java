package com.ptoceti.nexus3.plugin.localservice;



import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "artifact-resolution")
@XmlAccessorType(XmlAccessType.FIELD)
public class ArtifactResolution {
    @XmlElement(name = "data")
    Data data;
}