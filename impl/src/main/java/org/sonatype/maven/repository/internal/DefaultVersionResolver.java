package org.sonatype.maven.repository.internal;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.maven.repository.Artifact;
import org.sonatype.maven.repository.ArtifactRepository;
import org.sonatype.maven.repository.DefaultMetadata;
import org.sonatype.maven.repository.LocalRepositoryManager;
import org.sonatype.maven.repository.Metadata;
import org.sonatype.maven.repository.MetadataRequest;
import org.sonatype.maven.repository.MetadataResult;
import org.sonatype.maven.repository.RemoteRepository;
import org.sonatype.maven.repository.RepositoryCache;
import org.sonatype.maven.repository.RepositoryListener;
import org.sonatype.maven.repository.RepositorySystemSession;
import org.sonatype.maven.repository.VersionRequest;
import org.sonatype.maven.repository.VersionResolutionException;
import org.sonatype.maven.repository.VersionResult;
import org.sonatype.maven.repository.WorkspaceReader;
import org.sonatype.maven.repository.WorkspaceRepository;
import org.sonatype.maven.repository.internal.metadata.Snapshot;
import org.sonatype.maven.repository.internal.metadata.SnapshotVersion;
import org.sonatype.maven.repository.internal.metadata.Versioning;
import org.sonatype.maven.repository.internal.metadata.io.xpp3.MetadataXpp3Reader;
import org.sonatype.maven.repository.spi.Logger;
import org.sonatype.maven.repository.spi.MetadataResolver;
import org.sonatype.maven.repository.spi.NullLogger;
import org.sonatype.maven.repository.spi.VersionResolver;
import org.sonatype.maven.repository.util.DefaultRepositoryEvent;

/**
 * @author Benjamin Bentmann
 */
@Component( role = VersionResolver.class )
public class DefaultVersionResolver
    implements VersionResolver
{

    private static final String MAVEN_METADATA_XML = "maven-metadata.xml";

    private static final String RELEASE = "RELEASE";

    private static final String LATEST = "LATEST";

    private static final String SNAPSHOT = "SNAPSHOT";

    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    @Requirement
    private MetadataResolver metadataResolver;

    public DefaultVersionResolver setLogger( Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
        return this;
    }

    public DefaultVersionResolver setMetadataResolver( MetadataResolver metadataResolver )
    {
        if ( metadataResolver == null )
        {
            throw new IllegalArgumentException( "metadata resolver has not been specified" );
        }
        this.metadataResolver = metadataResolver;
        return this;
    }

    public VersionResult resolveVersion( RepositorySystemSession session, VersionRequest request )
        throws VersionResolutionException
    {
        Artifact artifact = request.getArtifact();

        String version = artifact.getVersion();

        VersionResult result = new VersionResult( request );

        Key cacheKey = null;
        RepositoryCache cache = session.getCache();
        if ( cache != null )
        {
            cacheKey = new Key( session, request );

            Object obj = cache.get( session, cacheKey );
            if ( obj instanceof Record )
            {
                Record record = (Record) obj;
                result.setVersion( record.version );
                result.setRepository( CacheUtils.getRepository( session, request.getRepositories(), record.repoClass,
                                                                record.repoId ) );
                return result;
            }
        }

        Metadata metadata;

        if ( RELEASE.equals( version ) )
        {
            metadata =
                new DefaultMetadata( artifact.getGroupId(), artifact.getArtifactId(), MAVEN_METADATA_XML,
                                     Metadata.Nature.RELEASE );
        }
        else if ( LATEST.equals( version ) )
        {
            metadata =
                new DefaultMetadata( artifact.getGroupId(), artifact.getArtifactId(), MAVEN_METADATA_XML,
                                     Metadata.Nature.RELEASE_OR_SNAPSHOT );
        }
        else if ( version.endsWith( SNAPSHOT ) )
        {
            WorkspaceReader workspace = session.getWorkspaceReader();
            if ( workspace != null && workspace.findVersions( artifact ).contains( version ) )
            {
                metadata = null;
                result.setRepository( workspace.getRepository() );
            }
            else
            {
                metadata =
                    new DefaultMetadata( artifact.getGroupId(), artifact.getArtifactId(), version, MAVEN_METADATA_XML,
                                         Metadata.Nature.SNAPSHOT );
            }
        }
        else
        {
            metadata = null;
        }

        if ( metadata == null )
        {
            result.setVersion( version );
        }
        else
        {
            List<MetadataRequest> metadataRequests = new ArrayList<MetadataRequest>( request.getRepositories().size() );
            for ( RemoteRepository repository : request.getRepositories() )
            {
                MetadataRequest metadataRequest =
                    new MetadataRequest( metadata, repository, request.getRequestContext() );
                metadataRequest.setDeleteLocalCopyIfMissing( true );
                metadataRequest.setFavorLocalRepository( true );
                metadataRequests.add( metadataRequest );
            }
            List<MetadataResult> metadataResults = metadataResolver.resolveMetadata( session, metadataRequests );

            LocalRepositoryManager lrm = session.getLocalRepositoryManager();
            File localMetadataFile =
                new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( metadata ) );
            if ( localMetadataFile.isFile() )
            {
                metadata = metadata.setFile( localMetadataFile );
            }

            Map<String, VersionInfo> infos = new HashMap<String, VersionInfo>();
            merge( artifact, infos, readVersions( session, metadata, result ),
                   session.getLocalRepositoryManager().getRepository() );

            for ( MetadataResult metadataResult : metadataResults )
            {
                result.addException( metadataResult.getException() );
                merge( artifact, infos, readVersions( session, metadataResult.getMetadata(), result ),
                       metadataResult.getRequest().getRepository() );
            }

            if ( RELEASE.equals( version ) )
            {
                resolve( result, infos, RELEASE );
            }
            else if ( LATEST.equals( version ) )
            {
                if ( !resolve( result, infos, LATEST ) )
                {
                    resolve( result, infos, RELEASE );
                }

                if ( result.getVersion() != null && result.getVersion().endsWith( SNAPSHOT ) )
                {
                    VersionRequest subRequest = new VersionRequest();
                    subRequest.setArtifact( artifact.setVersion( result.getVersion() ) );
                    if ( result.getRepository() instanceof RemoteRepository )
                    {
                        subRequest.setRepositories( Collections.singletonList( (RemoteRepository) result.getRepository() ) );
                    }
                    else
                    {
                        subRequest.setRepositories( request.getRepositories() );
                    }
                    VersionResult subResult = resolveVersion( session, subRequest );
                    result.setVersion( subResult.getVersion() );
                    result.setRepository( subResult.getRepository() );
                    for ( Exception exception : subResult.getExceptions() )
                    {
                        result.addException( exception );
                    }
                }
            }
            else
            {
                if ( !resolve( result, infos, SNAPSHOT + artifact.getClassifier() )
                    && !resolve( result, infos, SNAPSHOT ) )
                {
                    result.setVersion( version );
                }
            }

            if ( StringUtils.isEmpty( result.getVersion() ) )
            {
                throw new VersionResolutionException( result );
            }
        }

        if ( cacheKey != null && metadata != null )
        {
            cache.put( session, cacheKey, new Record( result.getVersion(), result.getRepository() ) );
        }

        return result;
    }

    private boolean resolve( VersionResult result, Map<String, VersionInfo> infos, String key )
    {
        VersionInfo info = infos.get( key );
        if ( info != null )
        {
            result.setVersion( info.version );
            result.setRepository( info.repository );
        }
        return info != null;
    }

    private Versioning readVersions( RepositorySystemSession session, Metadata metadata, VersionResult result )
    {
        Versioning versioning = null;

        FileInputStream fis = null;
        try
        {
            if ( metadata != null && metadata.getFile() != null )
            {
                fis = new FileInputStream( metadata.getFile() );
                org.sonatype.maven.repository.internal.metadata.Metadata m = new MetadataXpp3Reader().read( fis, false );
                versioning = m.getVersioning();
            }
        }
        catch ( FileNotFoundException e )
        {
            // tolerable
        }
        catch ( Exception e )
        {
            invalidMetadata( session, metadata, e );
            result.addException( e );
        }
        finally
        {
            IOUtil.close( fis );
        }

        return ( versioning != null ) ? versioning : new Versioning();
    }

    private void invalidMetadata( RepositorySystemSession session, Metadata metadata, Exception exception )
    {
        RepositoryListener listener = session.getRepositoryListener();
        if ( listener != null )
        {
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( session, metadata );
            event.setException( exception );
            listener.metadataInvalid( event );
        }
    }

    private void merge( Artifact artifact, Map<String, VersionInfo> infos, Versioning versioning,
                        ArtifactRepository repository )
    {
        if ( StringUtils.isNotEmpty( versioning.getRelease() ) )
        {
            merge( RELEASE, infos, versioning.getLastUpdated(), versioning.getRelease(), repository );
        }

        if ( StringUtils.isNotEmpty( versioning.getLatest() ) )
        {
            merge( LATEST, infos, versioning.getLastUpdated(), versioning.getLatest(), repository );
        }

        boolean mainSnapshot = false;
        for ( SnapshotVersion sv : versioning.getSnapshotVersions() )
        {
            if ( StringUtils.isNotEmpty( sv.getVersion() ) )
            {
                mainSnapshot |= sv.getClassifier().length() <= 0;
                merge( SNAPSHOT + sv.getClassifier(), infos, sv.getUpdated(), sv.getVersion(), repository );
            }
        }

        Snapshot snapshot = versioning.getSnapshot();
        if ( !mainSnapshot && snapshot != null )
        {
            String version = artifact.getVersion();
            if ( snapshot.getTimestamp() != null && snapshot.getBuildNumber() > 0 )
            {
                String qualifier = snapshot.getTimestamp() + '-' + snapshot.getBuildNumber();
                version = version.substring( 0, version.length() - SNAPSHOT.length() ) + qualifier;
            }
            merge( SNAPSHOT, infos, versioning.getLastUpdated(), version, repository );
        }
    }

    private void merge( String key, Map<String, VersionInfo> infos, String timestamp, String version,
                        ArtifactRepository repository )
    {
        VersionInfo info = infos.get( key );
        if ( info == null )
        {
            info = new VersionInfo( timestamp, version, repository );
            infos.put( key, info );
        }
        else if ( info.isOutdated( timestamp ) )
        {
            info.version = version;
            info.repository = repository;
        }
    }

    private static class VersionInfo
    {

        String timestamp;

        String version;

        ArtifactRepository repository;

        public VersionInfo( String timestamp, String version, ArtifactRepository repository )
        {
            this.timestamp = ( timestamp != null ) ? timestamp : "";
            this.version = version;
            this.repository = repository;
        }

        public boolean isOutdated( String timestamp )
        {
            return timestamp != null && timestamp.compareTo( this.timestamp ) > 0;
        }

    }

    private static class Key
    {

        private final String groupId;

        private final String artifactId;

        private final String version;

        private final String context;

        private final File localRepo;

        private final WorkspaceRepository workspace;

        private final List<RemoteRepository> repositories;

        private final int hashCode;

        public Key( RepositorySystemSession session, VersionRequest request )
        {
            groupId = request.getArtifact().getGroupId();
            artifactId = request.getArtifact().getArtifactId();
            version = request.getArtifact().getVersion();
            context = request.getRequestContext();
            localRepo = session.getLocalRepository().getBasedir();
            workspace = CacheUtils.getWorkspace( session );
            repositories = new ArrayList<RemoteRepository>( request.getRepositories().size() );
            for ( RemoteRepository repository : request.getRepositories() )
            {
                if ( repository.isRepositoryManager() )
                {
                    repositories.addAll( repository.getMirroredRepositories() );
                }
                else
                {
                    repositories.add( repository );
                }
            }

            int hash = 17;
            hash = hash * 31 + groupId.hashCode();
            hash = hash * 31 + artifactId.hashCode();
            hash = hash * 31 + version.hashCode();
            hash = hash * 31 + localRepo.hashCode();
            hash = hash * 31 + CacheUtils.repositoriesHashCode( repositories );
            hashCode = hash;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( obj == this )
            {
                return true;
            }
            else if ( obj == null || !getClass().equals( obj.getClass() ) )
            {
                return false;
            }

            Key that = (Key) obj;
            return artifactId.equals( that.artifactId ) && groupId.equals( that.groupId )
                && version.equals( that.version ) && context.equals( that.context )
                && localRepo.equals( that.localRepo ) && CacheUtils.eq( workspace, that.workspace )
                && CacheUtils.repositoriesEquals( repositories, that.repositories );
        }

        @Override
        public int hashCode()
        {
            return hashCode;
        }

    }

    private static class Record
    {
        final String version;

        final String repoId;

        final Class<?> repoClass;

        public Record( String version, ArtifactRepository repository )
        {
            this.version = version;
            if ( repository != null )
            {
                repoId = repository.getId();
                repoClass = repository.getClass();
            }
            else
            {
                repoId = null;
                repoClass = null;
            }
        }
    }

}
