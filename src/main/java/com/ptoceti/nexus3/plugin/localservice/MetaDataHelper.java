package com.ptoceti.nexus3.plugin.localservice;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.view.Content;

import java.io.IOException;

public class MetaDataHelper {

    public static Metadata read(final Repository repository, final MavenPath mavenPath) throws IOException, XmlPullParserException {
        final Content content = repository.facet(MavenFacet.class).get(mavenPath);
        if (content == null) {
            return null;
        }
        else {
            MetadataXpp3Reader reader =  new MetadataXpp3Reader();
            Metadata metadata = reader.read(content.openInputStream(), false);
            return metadata;
        }
    }

    public static MavenPath metadataPath(String groupId, String artifactId, String baseVersion){

        StringBuilder sb = new StringBuilder();
        sb.append( groupId.replace('.', '/'));
        if(StringUtils.isNotEmpty( artifactId)){
            sb.append('/');
            sb.append( artifactId);
            if(StringUtils.isNotEmpty(baseVersion)){
                sb.append('/');
                sb.append(baseVersion);
            }
        }
        sb.append('/').append("maven-metadata.xml");
        return new MavenPath(sb.toString(), null);
    }
}
