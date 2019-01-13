package com.ptoceti.nexus3.plugin.localservice;

import com.google.common.base.Supplier;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.collect.DetachingMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.sonatype.nexus.repository.search.SearchService;
import org.sonatype.nexus.repository.storage.*;
import org.sonatype.nexus.rest.Resource;

import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;
import org.sonatype.nexus.repository.maven.internal.Attributes;
import org.sonatype.nexus.transaction.UnitOfWork;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.sonatype.nexus.common.text.Strings2.isBlank;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.IOException;
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

    @Inject
    LocalServiceResource(SearchService searchService, RepositoryManager repositoryManager){
        this.searchService = checkNotNull(searchService);
        this.repositoryManager = checkNotNull(repositoryManager);
    }

    /**
     * Attempt to mock https://github.com/sonatype/nexus-public/blob/release-2.14.7-01/plugins/restlet1x/nexus-restlet1x-plugin/src/main/java/org/sonatype/nexus/rest/artifact/ArtifactResolvePlexusResource.java
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
        if (isBlank(repositoryName) || isBlank(groupId) || isBlank(artifactId) || isBlank(version) ) {
            log.debug("Resolve: params r - g - a - v  cannot be null or blank");
            return NOT_FOUND;
        }

        Repository repository = repositoryManager.get(repositoryName);
        // be sure it is a maven repo
        if (null == repository || !repository.getFormat().getValue().equals("maven2")) {
            log.debug("Resolve: repository {} not found or not Maven2", repositoryName );
            return NOT_FOUND;
        }

        String resolvedVersion = version;

        StorageFacet facet = repository.facet(StorageFacet.class);
        Supplier<StorageTx> storageTxSupplier = facet.txSupplier();

        if (resolvedVersion.equals("LATEST")) {
            resolvedVersion = getLatestVersionFromMetaData(repository, groupId, artifactId);
            if (resolvedVersion == null) {
                log.debug("Resolve: not latest version found");
            }
        } else if (resolvedVersion.equals("RELEASE")) {
            resolvedVersion = getReleaseVersionFromMetaData(repository, groupId, artifactId);
            if (resolvedVersion == null) {
                log.debug("Resolve: not release version found");
            }
        }

        if( resolvedVersion == null ){
            return NOT_FOUND;
        }

        if(resolvedVersion.endsWith(SNAPSHOT_VERSION_SUFFIX)){
            resolvedVersion = getSnapshotVersionFromMetaData(repository, groupId, artifactId, resolvedVersion);
        }


        List<Asset> allAssets = new ArrayList<Asset>();
        List<Repository> repositories = new ArrayList();
        repositories.add(repository);


        final StorageTx tx2 = storageTxSupplier.get();
        try {
            tx2.begin();
            Query.Builder qb  = Query.builder().where("component.group").eq(groupId)
                    .and("component.name").eq(artifactId)
                    .and("component.attributes.maven2.version").eq(resolvedVersion);
            if( !isBlank(classifier)) {
                    qb = qb.and("attributes.maven2.classifier").eq(classifier);
            }
            if( !isBlank(extension)) {
                    qb = qb.and("attributes.maven2.extension").eq(extension);
            }
            if( !isBlank(packaging)) {
                qb = qb.and("attributes.maven2.packaging").eq(packaging);
            }

            Query q =  qb.suffix("order by last_updated desc").build();

            log.debug("Resolve query: where {} {} parsm: {}", q.getWhere(), q.getQuerySuffix(), q.getParameters());
            Iterable<Asset> assets = tx2.findAssets(q, repositories);
            assets.forEach( it -> {allAssets.add(it);});
        }
        finally {
            tx2.close();
        }

        if( allAssets.size() > 0){
            Asset asset = allAssets.get(0);
            DetachingMap mavenProps = (DetachingMap)asset.attributes().get("maven2");
            if (mavenProps != null) {

                ArtifactResolution artifactResolution = new ArtifactResolution();
                artifactResolution.data = new Data();
                artifactResolution.data.presentLocally = "false";
                artifactResolution.data.groupId = (String)mavenProps.get(Attributes.P_GROUP_ID);
                artifactResolution.data.artifactId = (String)mavenProps.get(Attributes.P_ARTIFACT_ID);
                artifactResolution.data.version = (String)mavenProps.get(Attributes.P_VERSION);
                artifactResolution.data.extension = (String)mavenProps.get(Attributes.P_EXTENSION);
                artifactResolution.data.classifier = (String)mavenProps.get(Attributes.P_CLASSIFIER);

                String baseVersion = (String)mavenProps.get(Attributes.P_BASE_VERSION);
                boolean isSnapshot = baseVersion != null && baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
                if (isSnapshot) {
                    artifactResolution.data.baseVersion = baseVersion;

                    Integer buildNumber = getBuildNumberForMetadataMaven3Value(artifactResolution.data.version);
                    if (buildNumber != null) {

                        Map<String, Object> content = (Map<String, Object>)allAssets.get(0).attributes().get("content");
                        Date snapshotTimeStamp = (Date)content.get("last_modified");
                        if( snapshotTimeStamp != null ){
                            artifactResolution.data.snapshotTimeStamp = Long.toString(snapshotTimeStamp.getTime());
                        }
                        artifactResolution.data.snapshotBuildNumber = buildNumber.toString();
                    }
                }

                artifactResolution.data.snapshot = isSnapshot ? "true" : "false";

                Map<String, Object> checksum = (Map<String, Object>)allAssets.get(0).attributes().get("checksum");
                artifactResolution.data.sha1 = (String)checksum.get("sha1");
                artifactResolution.data.repositoryPath = asset.name();

                return Response.ok(artifactResolution).build();
            }
        }

        log.debug("Resolve: not asset found");
        return NOT_FOUND;
    }

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
        if (isBlank(repositoryName) || isBlank(groupId) || isBlank(artifactId) || isBlank(version) ) {
            log.debug("Content: params r - g - a - v  cannot be null or blank");
            return NOT_FOUND;
        }

        Repository repository = repositoryManager.get(repositoryName);
        // be sure it is a maven repo
        if (null == repository || !repository.getFormat().getValue().equals("maven2")) {
            log.debug("Content: repository {} not found or not Maven2", repositoryName );
            return NOT_FOUND;
        }

        String resolvedVersion = version;

        StorageFacet facet = repository.facet(StorageFacet.class);
        Supplier<StorageTx> storageTxSupplier = facet.txSupplier();

        if (resolvedVersion.equals("LATEST")) {
            resolvedVersion = getLatestVersionFromMetaData(repository, groupId, artifactId);
            if (resolvedVersion == null) {
                log.debug("Resolve: not latest version found");
            }
        } else if (resolvedVersion.equals("RELEASE")) {
            resolvedVersion = getReleaseVersionFromMetaData(repository, groupId, artifactId);
            if (resolvedVersion == null) {
                log.debug("Resolve: not release version found");
            }
        }

        if( resolvedVersion == null ){
            return NOT_FOUND;
        }

        if(resolvedVersion.endsWith(SNAPSHOT_VERSION_SUFFIX)){
            resolvedVersion = getSnapshotVersionFromMetaData(repository, groupId, artifactId, resolvedVersion);
        }


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
                responseBuilder.header("Content-Disposition", "attachment;filename=\"" + filename+ "\"");
                if (tx.isActive()) {
                    tx.commit();
                }
                response = responseBuilder.build();
            }
        } finally {
            tx.close();
        }

        if(response != null){
            return response;
        }
        return NOT_FOUND;
    }



    protected  Integer getBuildNumberForMetadataMaven3Value(final String valueString) {
        try {
            final int lastIdx = valueString.lastIndexOf('-');

            if (lastIdx > -1) {
                return Integer.valueOf(valueString.substring(lastIdx + 1));
            }
            else {
                return 0;
            }
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    protected String getReleaseVersionFromMetaData(Repository repository, String groupId, String artifactId) {
        String releaseVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);
        if (VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy())){
            return null;
        }

        StorageFacet storagefacet = repository.facet(StorageFacet.class);
        UnitOfWork.begin(storagefacet.txSupplier());

        try {
            Metadata metaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));

            if( metaData != null && metaData.getVersioning() != null ){
                releaseVersion = metaData.getVersioning().getRelease();
                List<String> versions = metaData.getVersioning().getVersions();
                if(StringUtils.isEmpty(releaseVersion) && versions.size() > 0){
                    for( int i = versions.size() - 1; i >= 0 ; i -- ){
                        String version = versions.get(i);
                        if ( !version.endsWith(SNAPSHOT_VERSION_SUFFIX)){
                            releaseVersion = version;
                            break;
                        }
                    }
                }
            }  else {
                log.debug("getLatestVersionFromMetaData : no metadata or no metadata versionning.");
            }
        }catch (IOException ex){
            log.debug("getLatestVersionFromMetaData : no meta data found");
        } catch (XmlPullParserException ex){
            log.debug("getLatestVersionFromMetaData : error reading metadata");
        } finally {
            UnitOfWork.end();
        }

        log.debug("getReleaseVersionFromMetaData : found release version : {}", releaseVersion);
        return releaseVersion;
    }

    protected String getLatestVersionFromMetaData(Repository repository, String groupId, String artifactId) {

        String latestVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);

        StorageFacet storagefacet = repository.facet(StorageFacet.class);
        UnitOfWork.begin(storagefacet.txSupplier());

        try {
            Metadata metaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, null));

            if(metaData != null &&  metaData.getVersioning() != null ){
                latestVersion = metaData.getVersioning().getLatest();

                List<String> versions = metaData.getVersioning().getVersions();
                if(StringUtils.isEmpty(latestVersion) && versions.size() > 0){
                    for( int i = versions.size() - 1; i >= 0 ; i -- ){
                        String version = versions.get(i);
                        if (VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy()) && version.endsWith(SNAPSHOT_VERSION_SUFFIX)) {
                            latestVersion = version;
                            break;
                        } else if ( VersionPolicy.RELEASE.equals(facet.getVersionPolicy())&& !version.endsWith(SNAPSHOT_VERSION_SUFFIX)){
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
        } catch (IOException ex){
            log.debug("getLatestVersionFromMetaData : no meta data found");
        } catch (XmlPullParserException ex){
            log.debug("getLatestVersionFromMetaData : error reading metadata");
        } finally {
            UnitOfWork.end();
        }

        log.debug("getLatestVersionFromMetaData : found latest version : {}", latestVersion);
        return latestVersion;
    }

    protected String getSnapshotVersionFromMetaData(Repository repository, String groupId, String artifactId, String baseVersion) {

        String snapshotVersion = null;
        MavenFacet facet = repository.facet(MavenFacet.class);
        if (!VersionPolicy.SNAPSHOT.equals(facet.getVersionPolicy())){
            return null;
        }


        StorageFacet storagefacet = repository.facet(StorageFacet.class);
        UnitOfWork.begin(storagefacet.txSupplier());

        try {
            Metadata metaData = MetaDataHelper.read(repository, MetaDataHelper.metadataPath(groupId, artifactId, baseVersion));

            if(metaData != null &&  metaData.getVersioning() != null ){

                Snapshot snapshot = metaData.getVersioning().getSnapshot();
                if (snapshot != null && StringUtils.isNotBlank(snapshot.getTimestamp()) && (snapshot.getBuildNumber() > 0)) {
                    snapshotVersion = baseVersion;

                    snapshotVersion = snapshotVersion.replace(SNAPSHOT_VERSION_SUFFIX, snapshot.getTimestamp() + "-" + snapshot.getBuildNumber());

                }

            } else {
                log.debug("getSnapshotVersionFromMetaData : no metadata or no metadata versionning.");
            }
        } catch (IOException ex){
            log.debug("getSnapshotVersionFromMetaData : no meta data found");
        } catch (XmlPullParserException ex){
            log.debug("getSnapshotVersionFromMetaData : error reading metadata");
        } finally {
            UnitOfWork.end();
        }

        log.debug("getSnapshotVersionFromMetaData : found snapshot version : {}", snapshotVersion);
        return snapshotVersion;
    }

}
