package com.ptoceti.nexus3.plugin.localservice;



import org.jboss.resteasy.annotations.providers.NoJackson;
import org.jboss.resteasy.annotations.providers.jackson.Formatted;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "artifact-resolution")
@XmlAccessorType(XmlAccessType.FIELD)
@Formatted
public class ArtifactResolution {
    @XmlElement(name = "data")
    public Data data;
}