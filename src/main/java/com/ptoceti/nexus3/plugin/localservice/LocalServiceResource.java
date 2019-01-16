package com.ptoceti.nexus3.plugin.localservice;

import com.google.common.base.Supplier;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.collect.DetachingMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.sonatype.nexus.repository.search.SearchService;
import org.sonatype.nexus.repository.storage.*;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.rest.Resource;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;
import org.sonatype.nexus.repository.maven.internal.Attributes;

import static org.sonatype.nexus.common.text.Strings2.isBlank;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

@Named("LocalService")
@Singleton
@Path(LocalServiceResource.RESOURCE_URI)
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public class LocalServiceResource  extends ComponentSupport
        implements Resource {

    public static final String RESOURCE_URI = "/servicelocal/artifact/maven/";

    private static final Response NOT_FOUND = Response.status(404).build();

    private final RepositoryManager repositoryManager;
    private final SearchService searchService;

    Metadata componentMetaData;
    Metadata versionMetaData;

    @Inject
    LocalServiceResource(SearchService searchService, RepositoryManager repositoryManager) {
        this.searchService = checkNotNull(searchService);
        this.repositoryManager = checkNotNull(repositoryManager);
    }

    /**
     * Attempt to mock https://github.com/sonatype/nexus-public/blob/release-2.14.7-01/plugins/restlet1x/nexus-restlet1x-plugin/src/main/java/org/sonatype/nexus/rest/artifact/ArtifactResolvePlexusResource.java
     *
     * @param repositoryName
     * @param groupId
     * @param artifactId
     * @param version
     * @param classifier
     * @param extension
     * @param packaging
     * @return
     */
    @GET
    @Path("resolve")
    @Produces(MediaType.APPLICATION_XML)
    public Response resolve(
            @QueryParam("r") String repositoryName,
            @QueryParam("g") String groupId,
            @QueryParam("a") String artifactId,
            @QueryParam("v") String version,
            @QueryParam("c") String classifier,
            @QueryParam("e") @DefaultValue("jar") String extension,
            @QueryParam("p") String packaging
    ) {

        log.debug("Resolve: received request for r={} g={} a={} v={} c={} e={} p={}", repositoryName, groupId, artifactId, version, classifier, extension, packaging);
        // check params
        if (isBlank(repositoryName) || isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            log.debug("Resolve: params r - g - a - v  cannot be null or blank");
            return NOT_FOUND;
        }

        Repository repository = repositoryManager.get(repositoryName);

        // be sure it is a maven repo
        if (null == repository || !repository.getFormat().getValue().equals("maven2")) {
            log.debug("Resolve: repository {} not found or not Maven2", repositoryName);
            return NOT_FOUND;
        }

        String resolvedVersion = version;

        try {
            if (resolvedVersion.equals("LATEST")) {
                componentMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));
                if (componentMetaData != null) {
                    resolvedVersion = getLatestVersionFromMetaData(repository, componentMetaData);
                }
                if (resolvedVersion == null) {
                    log.debug("Resolve: not latest version found");
                }
            } else if (resolvedVersion.equals("RELEASE")) {
                componentMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));
                if (componentMetaData != null) {
                    resolvedVersion = getReleaseVersionFromMetaData(repository, componentMetaData);
                }
                if (resolvedVersion == null) {
                    log.debug("Resolve: not release version found");
                }
            }

            if (resolvedVersion == null) {
                return NOT_FOUND;
            }
            versionMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, resolvedVersion));
            if (versionMetaData == null) {
                return NOT_FOUND;
            }
            if (resolvedVersion.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
                resolvedVersion = getSnapshotVersionFromMetaData(repository, versionMetaData, resolvedVersion);
            }

        } catch (XmlPullParserException ex) {
            log.debug("Resolve: could not get metadata");
            return NOT_FOUND;
        } catch (IOException ex) {
            log.debug("Resolve: could not get metadatak");
            return NOT_FOUND;
        }

        if (resolvedVersion == null) {
            return NOT_FOUND;
        }

        ArtifactResolution artifact = null;

        if (repository.getType().getValue().equals(HostedType.NAME)) {
            artifact = searchAssetLocal(repository, groupId, artifactId, classifier, extension, packaging, resolvedVersion);
        } else if ( repository.getType().getValue().equals(GroupType.NAME)){
            GroupFacet groupFacet = repository.facet(GroupFacet.class);
            List<Repository> repositories = groupFacet.leafMembers();
            for(Repository nextRepository : repositories ){
                if( nextRepository.getType().getValue().equals(HostedType.NAME) ||
                        nextRepository.getType().getValue().equals(ProxyType.NAME)){
                    artifact = searchAssetLocal(nextRepository, groupId, artifactId, classifier, extension, packaging, resolvedVersion);
                    if( artifact != null) {break;}
                }
            }
            if(artifact == null) {
                artifact = searchAssetRemote(repository, groupId, artifactId, classifier, extension, packaging, resolvedVersion);
            }
            if( artifact == null) {
                artifact = makeArtifactFromMetaData(groupId, artifactId, classifier, extension, resolvedVersion, version, versionMetaData);
            }
        } else if ( repository.getType().getValue().equals(ProxyType.NAME )){
            artifact = searchAssetLocal(repository, groupId, artifactId, classifier, extension, packaging, resolvedVersion);
            if( artifact == null) {
                artifact = makeArtifactFromMetaData(groupId, artifactId, classifier, extension, resolvedVersion, version, versionMetaData);
            }
        }

        if (artifact != null) {
            return Response.ok(artifact).build();
        }

        log.debug("Resolve: not asset found");
        return NOT_FOUND;
    }

    /**
     * Attempt to mock https://github.com/sonatype/nexus-public/blob/2.14.8-01/plugins/restlet1x/nexus-restlet1x-plugin/src/main/java/org/sonatype/nexus/rest/artifact/ArtifactContentPlexusResource.java
     * @param repositoryName
     * @param groupId
     * @param artifactId
     * @param version
     * @param classifier
     * @param extension
     * @param packaging
     * @return
     */
    @GET
    @Path("content")
    public Response content(
            @QueryParam("r") String repositoryName,
            @QueryParam("g") String groupId,
            @QueryParam("a") String artifactId,
            @QueryParam("v") String version,
            @QueryParam("c") String classifier,
            @QueryParam("e") @DefaultValue("jar") String extension,
            @QueryParam("p") String packaging
    ) {
        log.debug("Content: received request for r={} g={} a={} v={} c={} e={} p={}", repositoryName, groupId, artifactId, version, classifier, extension, packaging);
        // check params
        if (isBlank(repositoryName) || isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            log.debug("Content: params r - g - a - v  cannot be null or blank");
            return NOT_FOUND;
        }

        Repository repository = repositoryManager.get(repositoryName);

        // be sure it is a maven repo
        if (null == repository || !repository.getFormat().getValue().equals("maven2")) {
            log.debug("Content: repository {} not found or not Maven2", repositoryName);
            return NOT_FOUND;
        }

        String resolvedVersion = version;

        try {
            if (resolvedVersion.equals("LATEST")) {
                componentMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));
                if (componentMetaData != null) {
                    resolvedVersion = getLatestVersionFromMetaData(repository, componentMetaData);
                }
                if (resolvedVersion == null) {
                    log.debug("Content: not latest version found");
                }
            } else if (resolvedVersion.equals("RELEASE")) {
                componentMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));
                if (componentMetaData != null) {
                    resolvedVersion = getReleaseVersionFromMetaData(repository, componentMetaData);
                }
                if (resolvedVersion == null) {
                    log.debug("Content: not release version found");
                }
            }
            if (resolvedVersion == null) {
                return NOT_FOUND;
            }

            versionMetaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, resolvedVersion));
            if (versionMetaData == null) {
                return NOT_FOUND;
            }
            if (resolvedVersion.endsWith(SNAPSHOT_VERSION_SUFFIX) && versionMetaData != null) {
                resolvedVersion = getSnapshotVersionFromMetaData(repository, versionMetaData, resolvedVersion);
            }

        } catch (XmlPullParserException ex) {
            log.debug("Content: could not get metadata");
            return NOT_FOUND;
        } catch (IOException ex) {
            log.debug("Content: could not get metadatak");
            return NOT_FOUND;
        }

        Response response = null;

        if (repository.getType().getValue().equals(HostedType.NAME)) {
            response =  getContentLocaly(repository, groupId, artifactId, resolvedVersion, classifier, extension, packaging);
        } else if ( repository.getType().getValue().equals(GroupType.NAME)) {
            GroupFacet groupFacet = repository.facet(GroupFacet.class);
            List<Repository> repositories = groupFacet.leafMembers();
            for (Repository nextRepository : repositories) {
                if (nextRepository.getType().getValue().equals(HostedType.NAME) ||
                        nextRepository.getType().getValue().equals(ProxyType.NAME)) {
                    response =  getContentLocaly(repository, groupId, artifactId, resolvedVersion, classifier, extension, packaging);
                    if ( response != null) {
                        break;
                    }
                }
            }
            if( response == null){
                response  = getContentRemote(repository, groupId, artifactId, version, resolvedVersion, classifier, extension, packaging);
            }
        } else if ( repository.getType().getValue().equals(ProxyType.NAME )){
            response =  getContentLocaly(repository, groupId, artifactId, resolvedVersion, classifier, extension, packaging);
            if( response == null){
                response  = getContentRemote(repository, groupId, artifactId, version, resolvedVersion, classifier, extension, packaging);
            }
        }

        if (response != null) {
            return response;
        }
        return NOT_FOUND;
    }


    protected Integer getBuildNumberForMetadataMaven3Value(final String valueString) {
        try {
            final int lastIdx = valueString.lastIndexOf('-');

            if (lastIdx > -1) {
                return Integer.valueOf(valueString.substring(lastIdx + 1));
            } else {
                return 0;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected String getReleaseVersionFromMetaData(Repository repository, Metadata metaData) {
        String releaseVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);
        if (VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy())) {
            return null;
        }


        if (metaData != null && metaData.getVersioning() != null) {
            releaseVersion = metaData.getVersioning().getRelease();
            List<String> versions = metaData.getVersioning().getVersions();
            if (StringUtils.isEmpty(releaseVersion) && versions.size() > 0) {
                for (int i = versions.size() - 1; i >= 0; i--) {
                    String version = versions.get(i);
                    if (!version.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
                        releaseVersion = version;
                        break;
                    }
                }
            }
        } else {
            log.debug("getLatestVersionFromMetaData : no metadata or no metadata versionning.");
        }

        log.debug("getReleaseVersionFromMetaData : found release version : {}", releaseVersion);
        return releaseVersion;
    }

    protected String getLatestVersionFromMetaData(Repository repository, Metadata metaData) {

        String latestVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);

        if (metaData != null && metaData.getVersioning() != null) {
            latestVersion = metaData.getVersioning().getLatest();

            List<String> versions = metaData.getVersioning().getVersions();
            if (StringUtils.isEmpty(latestVersion) && versions.size() > 0) {
                for (int i = versions.size() - 1; i >= 0; i--) {
                    String version = versions.get(i);
                    if (VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy()) && version.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
                        latestVersion = version;
                        break;
                    } else if (VersionPolicy.RELEASE.equals(facet.getVersionPolicy()) && !version.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
                        latestVersion = version;
                        break;
                    } else {
                        latestVersion = version;
                        break;
                    }
                }
            }
        } else {
            log.debug("getLatestVersionFromMetaData : no metadata or no metadata versionning.");
        }

        log.debug("getLatestVersionFromMetaData : found latest version : {}", latestVersion);
        return latestVersion;
    }

    protected String getSnapshotVersionFromMetaData(Repository repository, Metadata metaData, String baseVersion) {

        String snapshotVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);
        if (!VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy()) && !VersionPolicy.MIXED.equals(facet.getVersionPolicy())) {
            return null;
        }

        if (metaData != null && metaData.getVersioning() != null) {

            Snapshot snapshot = metaData.getVersioning().getSnapshot();
            if (snapshot != null && StringUtils.isNotBlank(snapshot.getTimestamp()) && (snapshot.getBuildNumber() > 0)) {
                snapshotVersion = baseVersion;
                snapshotVersion = snapshotVersion.replace(SNAPSHOT_VERSION_SUFFIX, snapshot.getTimestamp() + "-" + snapshot.getBuildNumber());
            }

        } else {
            log.debug("getSnapshotVersionFromMetaData : no metadata or no metadata versionning.");
        }


        log.debug("getSnapshotVersionFromMetaData : found snapshot version : {}", snapshotVersion);
        return snapshotVersion;
    }

    public ArtifactResolution searchAssetLocal(Repository repository, String groupId, String artifactId, String classifier, String extension, String packaging, String resolvedVersion) {

        StorageFacet facet = repository.facet(StorageFacet.class);
        Supplier<StorageTx> storageTxSupplier = facet.txSupplier();

        List<Asset> allAssets = new ArrayList<Asset>();
        List<Repository> repositories = new ArrayList();
        repositories.add(repository);

        final StorageTx tx2 = storageTxSupplier.get();
        try {
            tx2.begin();
            Query.Builder qb = Query.builder().where("component.group").eq(groupId)
                    .and("component.name").eq(artifactId)
                    .and("component.attributes.maven2.version").eq(resolvedVersion);
            if (!isBlank(classifier)) {
                qb = qb.and("attributes.maven2.classifier").eq(classifier);
            }
            if (!isBlank(extension)) {
                qb = qb.and("attributes.maven2.extension").eq(extension);
            }
            if (!isBlank(packaging)) {
                qb = qb.and("attributes.maven2.packaging").eq(packaging);
            }

            Query q = qb.suffix("order by last_updated desc").build();

            log.debug("Resolve query: where {} {} parsm: {}", q.getWhere(), q.getQuerySuffix(), q.getParameters());
            Iterable<Asset> assets = tx2.findAssets(q, repositories);
            assets.forEach(it -> {
                allAssets.add(it);
            });
        } finally {
            tx2.close();
        }

        if (allAssets.size() > 0) {
            Asset asset = allAssets.get(0);
            DetachingMap mavenProps = (DetachingMap) asset.attributes().get("maven2");
            if (mavenProps != null) {

                ArtifactResolution artifactResolution = new ArtifactResolution();
                artifactResolution.data = new Data();
                artifactResolution.data.presentLocally = "true";
                artifactResolution.data.groupId = (String) mavenProps.get(Attributes.P_GROUP_ID);
                artifactResolution.data.artifactId = (String) mavenProps.get(Attributes.P_ARTIFACT_ID);
                artifactResolution.data.version = (String) mavenProps.get(Attributes.P_VERSION);
                artifactResolution.data.extension = (String) mavenProps.get(Attributes.P_EXTENSION);
                artifactResolution.data.classifier = (String) mavenProps.get(Attributes.P_CLASSIFIER);

                String baseVersion = (String) mavenProps.get(Attributes.P_BASE_VERSION);
                boolean isSnapshot = baseVersion != null && baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
                if (isSnapshot) {
                    artifactResolution.data.baseVersion = baseVersion;

                    Integer buildNumber = getBuildNumberForMetadataMaven3Value(artifactResolution.data.version);
                    if (buildNumber != null) {

                        Map<String, Object> content = (Map<String, Object>) allAssets.get(0).attributes().get("content");
                        Date snapshotTimeStamp = (Date) content.get("last_modified");
                        if (snapshotTimeStamp != null) {
                            artifactResolution.data.snapshotTimeStamp = Long.toString(snapshotTimeStamp.getTime());
                        }
                        artifactResolution.data.snapshotBuildNumber = buildNumber.toString();
                    }
                }

                artifactResolution.data.snapshot = isSnapshot ? "true" : "false";

                Map<String, Object> checksum = (Map<String, Object>) allAssets.get(0).attributes().get("checksum");
                artifactResolution.data.sha1 = (String) checksum.get("sha1");
                artifactResolution.data.repositoryPath = asset.name();

                return artifactResolution;
            }
        }

        return null;
    }

    public ArtifactResolution searchAssetRemote(Repository repository, String groupId, String artifactId, String classifier, String extension, String packaging, String version) {

        ArtifactResolution artifactResolution = null;

        BoolQueryBuilder query = boolQuery();
        query.filter(termQuery("format", "maven2"));

        //if (!isBlank(repository)) {
        //    query.filter(termQuery("repository_name", repository));
        //}
        //if (!isBlank(groupId)) {
        query.filter(termQuery("attributes.maven2.groupId", groupId));
        //}
        //if (!isBlank(artifactId)) {
        query.filter(termQuery("attributes.maven2.artifactId", artifactId));
        //}
        if (!isBlank(classifier)) {
            query.filter(termQuery("assets.attributes.maven2.classifier", classifier));
        }
        if (!isBlank(extension)) {
            query.filter(termQuery("assets.attributes.maven2.extension", extension));
        }
        if (!isBlank(packaging)) {
            query.filter(termQuery("assets.attributes.maven2.packaging", packaging));
        }
        //if( !isBlank(version)) {
        query.filter(termQuery("assets.attributes.maven2.version", version));
        //}

        SearchResponse result = searchService.search(query, null, 0, 10);

        SearchHit[] hits = result.getHits().hits();
        if (hits.length > 0) {
            SearchHit hit = hits[0];
            List<Map<String, Object>> assets = (List<Map<String, Object>>) hit.getSource().get("assets");
            //Map<String, Object> attributes = (Map<String, Object>) assets.get(0).get("attributes");
            //Map<String, Object> content = (Map<String, Object>) attributes.get("content");

            for (Map<String, Object> asset : assets) {
                Map<String, Object> attributes = (Map<String, Object>) asset.get("attributes");
                Map<String, Object> maven2 = (Map<String, Object>) attributes.get("maven2");
                Map<String, Object> content = (Map<String, Object>) attributes.get("content");
                Map<String, Object> checksum = (Map<String, Object>) attributes.get("checksum");

                if (maven2.get("extension").equals(extension)
                        && (isBlank(classifier) ? maven2.get("classifier") == null : maven2.get("classifier").equals(classifier))) {

                    artifactResolution = new ArtifactResolution();
                    artifactResolution.data = new Data();
                    artifactResolution.data.presentLocally = "false";
                    artifactResolution.data.groupId = groupId;
                    artifactResolution.data.artifactId = artifactId;
                    artifactResolution.data.version = version;
                    artifactResolution.data.extension = extension;
                    if (maven2.get("classifier") != null) {
                        artifactResolution.data.classifier = (String) maven2.get("classifier");
                    }
                    String baseVersion = (String) maven2.get("baseVersion");
                    boolean isSnapshot = baseVersion != null && baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
                    if (isSnapshot) {
                        artifactResolution.data.baseVersion = baseVersion;

                        Integer buildNumber = getBuildNumberForMetadataMaven3Value(artifactResolution.data.version);
                        if (buildNumber != null) {

                            Long snapshotTimeStamp = (Long) content.get("last_modified");
                            if (snapshotTimeStamp != null) {
                                artifactResolution.data.snapshotTimeStamp = Long.toString(snapshotTimeStamp);
                            }
                            artifactResolution.data.snapshotBuildNumber = buildNumber.toString();
                        }
                    }
                    artifactResolution.data.snapshot = isSnapshot ? "true" : "false";


                    artifactResolution.data.sha1 = (String) checksum.get("sha1");
                    artifactResolution.data.repositoryPath = (String) asset.get("name");
                    break;
                }
            }

        }


        return artifactResolution;
    }

    public ArtifactResolution makeArtifactFromMetaData( String groupId, String artifactId, String classifier, String extension, String version, String baseVersion, Metadata metadata) {

        ArtifactResolution artifactResolution = new ArtifactResolution();

        artifactResolution.data = new Data();
        artifactResolution.data.presentLocally = "false";
        artifactResolution.data.groupId = groupId;
        artifactResolution.data.artifactId = artifactId;
        artifactResolution.data.version = version;
        artifactResolution.data.extension = extension;
        artifactResolution.data.classifier = classifier;

        boolean isSnapshot = baseVersion != null && baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
        if (isSnapshot) {

            Snapshot snapshot = metadata.getVersioning().getSnapshot();
            if (snapshot != null && StringUtils.isNotBlank(snapshot.getTimestamp()) && (snapshot.getBuildNumber() > 0)) {

                artifactResolution.data.snapshotTimeStamp = snapshot.getTimestamp();
                artifactResolution.data.snapshotBuildNumber = Integer.toString(snapshot.getBuildNumber());

            }
            artifactResolution.data.baseVersion = baseVersion;
        }
        artifactResolution.data.snapshot = isSnapshot ? "true" : "false";

        artifactResolution.data.repositoryPath = PathUtils.calculatePath(groupId, artifactId, baseVersion, version, classifier, extension);

        return artifactResolution;
    }


    Response getContentRemote(Repository repository, String groupId, String artifactId, String baseVersion, String resolvedVersion, String classifier, String extension, String packaging){
        Response response = null;

        String url = repository.getUrl();
        String artifactPath = PathUtils.calculatePath( groupId, artifactId, baseVersion, resolvedVersion, classifier, extension);

        HttpClient client = HttpClients.custom().build();
        HttpUriRequest request = RequestBuilder.get()
                .setUri(url + "/" +artifactPath)
                .build();
        try {
            HttpResponse httpClientResponse = client.execute(request);
            int status = httpClientResponse.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                HttpEntity entity = httpClientResponse.getEntity();
                InputStream instream = entity.getContent();

                Header[] headers = httpClientResponse.getAllHeaders();

                Response.ResponseBuilder responseBuilder = Response.ok(instream);

                for( Header header : headers){
                    if( header.getName().equals("Content-Type")) {
                        responseBuilder.header(header.getName(), header.getValue());
                        break;
                    }
                }
                responseBuilder.header("Content-Disposition", "attachment;filename=\"" + artifactPath + "\"");
                response = responseBuilder.build();
            }

        } catch (ClientProtocolException ex ){

        } catch (IOException ex) {

        }
        return response;
    }

    Response getContentLocaly(Repository repository, String groupId, String artifactId, String resolvedVersion, String classifier, String extension, String packaging){
        StorageFacet facet = repository.facet(StorageFacet.class);
        Supplier<StorageTx> storageTxSupplier = facet.txSupplier();

        List<Asset> allAssets = new ArrayList<Asset>();
        List<Repository> repositories = new ArrayList();
        repositories.add(repository);

        Response response = null;

        final StorageTx tx = storageTxSupplier.get();
        try {
            tx.begin();
            Query.Builder qb = Query.builder().where("component.group").eq(groupId)
                    .and("component.name").eq(artifactId)
                    .and("component.attributes.maven2.version").eq(resolvedVersion);
            if (!isBlank(classifier)) {
                qb = qb.and("attributes.maven2.classifier").eq(classifier);
            }
            if (!isBlank(extension)) {
                qb = qb.and("attributes.maven2.extension").eq(extension);
            }
            if (!isBlank(packaging)) {
                qb = qb.and("attributes.maven2.packaging").eq(packaging);
            }

            Query q = qb.suffix("order by last_updated desc").build();

            log.debug("Content query: where {} {} parsm: {}", q.getWhere(), q.getQuerySuffix(), q.getParameters());
            Iterable<Asset> assets = tx.findAssets(q, repositories);
            assets.forEach(it -> {
                allAssets.add(it);
            });

            if (allAssets.size() > 0) {
                Asset asset = allAssets.get(0);
                asset.markAsDownloaded();
                tx.saveAsset(asset);
                Blob blob = tx.requireBlob(asset.requireBlobRef());
                String filename = asset.name().substring(asset.name().lastIndexOf("/") + 1);
                Response.ResponseBuilder responseBuilder = Response.ok(blob.getInputStream());
                responseBuilder.header("Content-Type", blob.getHeaders().get("BlobStore.content-type"));
                responseBuilder.header("Content-Disposition", "attachment;filename=\"" + filename + "\"");
                if (tx.isActive()) {
                    tx.commit();
                }
                response = responseBuilder.build();
            }
        } finally {
            tx.close();
        }

        return response;
    }
}