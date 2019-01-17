package com.ptoceti.nexus3.plugin.localservice;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.transaction.UnitOfWork;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

public class MetaDataHelper {

    public static Metadata read(final Repository repository, final MavenPath mavenPath) throws IOException, XmlPullParserException {
        Metadata metadata = null;
        if (repository.getType().getValue().equals(HostedType.NAME)) {
            metadata =  MetaDataHelper.readLocal(repository, mavenPath);
        } else if ( repository.getType().getValue().equals(GroupType.NAME))  {

            GroupFacet groupFacet = repository.facet(GroupFacet.class);
            List<Repository> repositories = groupFacet.leafMembers();
            for (Repository nextRepository : repositories) {
                if (nextRepository.getType().getValue().equals(HostedType.NAME) ||
                        nextRepository.getType().getValue().equals(ProxyType.NAME)) {
                    metadata = MetaDataHelper.readLocal(repository, mavenPath);
                    if ( metadata != null) {
                        break;
                    }
                }
            }
            if( metadata == null) {
                metadata = MetaDataHelper.readRemote(repository, mavenPath);
            }
        } else if ( repository.getType().getValue().equals(ProxyType.NAME )){
            metadata =  MetaDataHelper.readLocal(repository, mavenPath);
            if( metadata == null) {
                metadata = MetaDataHelper.readRemote(repository, mavenPath);
            }
        }

        return metadata;
    }

    public static Metadata readLocal(final Repository repository, final MavenPath mavenPath) throws IOException, XmlPullParserException {

        StorageFacet storagefacet = repository.facet(StorageFacet.class);
        UnitOfWork.begin(storagefacet.txSupplier());

        try {
            Content content = repository.facet(MavenFacet.class).get(mavenPath);

            if (content == null) {
                return null;
            } else {
                MetadataXpp3Reader reader = new MetadataXpp3Reader();
                Metadata metadata = reader.read(content.openInputStream(), false);
                return metadata;
            }
        } finally {
                UnitOfWork.end();
        }
    }

    public static Metadata readRemote( final Repository repository, final MavenPath mavenPath) {

        String url = repository.getUrl();
        String metaDataRespnse = null;

        HttpClient client = HttpClients.custom().build();
        HttpUriRequest request = RequestBuilder.get()
                .setUri(url + "/" + mavenPath.getPath())
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/xml")
                .build();

        HttpResponse response = null;

        try {
            response = client.execute(request);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    metaDataRespnse = EntityUtils.toString(entity);
                    MetadataXpp3Reader reader = new MetadataXpp3Reader();
                    Metadata metadata = reader.read(new StringReader(metaDataRespnse), false);
                    return metadata;
                }
            } else {
                EntityUtils.consume(response.getEntity());
            }
        } catch (IOException | XmlPullParserException ex) {
            if( response!= null){
                try {
                    EntityUtils.consume(response.getEntity());
                } catch (IOException excp) {}
            }
        }
        return null;
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
