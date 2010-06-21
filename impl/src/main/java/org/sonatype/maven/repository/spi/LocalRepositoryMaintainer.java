package org.sonatype.maven.repository.spi;

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

/**
 * Performs housekeeping tasks in response to updates to the local repository. This provides an extension point to
 * integrators to performs things like updating indexes.
 * 
 * @author Benjamin Bentmann
 */
public interface LocalRepositoryMaintainer
{

    /**
     * Notifies the maintainer of the addition of an artifact to the local repository by a local build.
     * 
     * @param event The event that holds details about the artifact, must not be {@code null}.
     */
    void artifactInstalled( LocalRepositoryEvent event );

    /**
     * Notifies the maintainer of the addition of an artifact to the local repository by download from a remote
     * repository.
     * 
     * @param event The event that holds details about the artifact, must not be {@code null}.
     */
    void artifactDownloaded( LocalRepositoryEvent event );

}