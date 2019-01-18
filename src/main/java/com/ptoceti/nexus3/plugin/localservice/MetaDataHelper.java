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
import org.sonatype.goodies.common.ComponentSupport;
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
import java.net.URL;
import java.util.List;

public class MetaDataHelper extends ComponentSupport  {

    public Metadata read(final Repository repository, final MavenPath mavenPath) throws IOException, XmlPullParserException {
        Metadata metadata = null;

        if (repository.getType().getValue().equals(HostedType.NAME)) {
            metadata = readLocal(repository, mavenPath);
        } else {
            // alwayz try to get latest metadata, this way we get aggregation
            metadata = readRemote(repository, mavenPath);
        }

        return metadata;
    }

    public Metadata readLocal(final Repository repository, final MavenPath mavenPath) throws IOException, XmlPullParserException {

        StorageFacet storagefacet = repository.facet(StorageFacet.class);
        UnitOfWork.begin(storagefacet.txSupplier());

        try {
            Content content = repository.facet(MavenFacet.class).get(mavenPath);

            if (content == null) {
                return null;
            } else {
                log.debug("readLocal : loading metadata from repository: {}", repository.getName());
                MetadataXpp3Reader reader = new MetadataXpp3Reader();
                Metadata metadata = reader.read(content.openInputStream(), false);
                return metadata;
            }
        } finally {
                UnitOfWork.end();
        }
    }

    public Metadata readRemote( final Repository repository, final MavenPath mavenPath) {


        String metaDataRespnse = null;
        String url = repository.getUrl();
        String applicationPort = System.getProperty("application-port");

        HttpResponse response = null;

        try {
            URL repoUrl = new URL(url);
            URL localRepoUrl = new URL("http", "localhost", Integer.parseInt(applicationPort), repoUrl.getFile());
            HttpClient client = HttpClients.custom().build();
            log.debug("readRemote : loading metatada at {}", localRepoUrl);
            HttpUriRequest request = RequestBuilder.get()
                    .setUri(localRepoUrl + "/" + mavenPath.getPath())
                    .setHeader(HttpHeaders.CONTENT_TYPE, "application/xml")
                    .build();

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
            log.debug("readRemote : error loading metatada at {}, ex: {}", url + "/" + mavenPath.getPath(), ex.getMessage());
            if( response!= null){
                try {
                    EntityUtils.consume(response.getEntity());
                } catch (IOException excp) {}
            }
        }
        return null;
    }

    public MavenPath metadataPath(String groupId, String artifactId, String baseVersion){

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
