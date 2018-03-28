package org.ops4j.pax.url.mvn.internal;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ops4j.pax.url.mvn.ServiceConstants;
import org.ops4j.pax.url.mvn.UnitHelp;
import org.ops4j.pax.url.mvn.internal.config.MavenConfiguration;

public class ProxyTest {

    private static final String TEST_PID = "org.ops4j.pax.url.mvn";

    private Server server;

    private String repoPath;

    private File localRepo;

    @Before
    public void startHttp() throws Exception {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(8778);
        server.addConnector(connector);

        ResourceHandler resource_handler = new ResourceHandler();
        resource_handler.setDirectoriesListed(false);
        resource_handler.setWelcomeFiles(new String[] {});

        resource_handler.setResourceBase("target/test-classes/repo2");

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { resource_handler, new DefaultHandler() });
        server.setHandler(handlers);

        server.start();

        repoPath = "target/localrepo_" + UUID.randomUUID();
        localRepo = new File(repoPath);
        // you must exist.
        localRepo.mkdirs();
    }

    @After
    public void stopHttp() throws Exception {
        server.stop();

        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("https.proxyUser");
        System.clearProperty("https.proxyPassword");
        
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("http.proxyUser");
        System.clearProperty("http.proxyPassword");
    }

    @Test
    public void proxyHttp() throws Exception {
        File file = new File("target/test-classes/settings-proxy1.xml");

        Properties settings = new Properties();
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_LOCAL_REPOSITORY, repoPath);
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_REPOSITORIES, "http://qfdqfqfqf.fra@id=fake");
        MavenConfiguration config = UnitHelp.getConfig(file, settings);

        Connection c =
                new Connection(new URL(null, "mvn:ant/ant/1.5.1", new org.ops4j.pax.url.mvn.Handler()),
                        new AetherBasedResolver(config));
        c.getInputStream();

        assertEquals("the artifact must be downloaded", true,
                new File(localRepo, "ant/ant/1.5.1/ant-1.5.1.jar").exists());

        // test for PAXURL-209
        assertThat(System.getProperty("http.proxyHost"), is(nullValue()));
    }

    @Test
    public void javaProxy() throws Exception {
        System.setProperty("http.proxyHost", "localhost");
        System.setProperty("http.proxyPort", "8778");

        File file = new File("target/test-classes/settings-no-mirror.xml");

        Properties settings = new Properties();
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_LOCAL_REPOSITORY, repoPath);
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_REPOSITORIES, "http://qfdqfqfqf.fra@id=fake");
        MavenConfiguration config = UnitHelp.getConfig(file, settings);

        Connection c =
                new Connection(new URL(null, "mvn:ant/ant/1.5.1", new org.ops4j.pax.url.mvn.Handler()),
                        new AetherBasedResolver(config));
        c.getInputStream();

        assertEquals("the artifact must be downloaded", true,
                new File(localRepo, "ant/ant/1.5.1/ant-1.5.1.jar").exists());
    }

    private AetherBasedResolver createResolver(String schema) {
        File file = new File("target/test-classes/settings-no-mirror.xml");

        Properties settings = new Properties();
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_LOCAL_REPOSITORY, repoPath);
        settings.setProperty(TEST_PID + "." + ServiceConstants.PROPERTY_REPOSITORIES, schema
                + "://qfdqfqfqf.fra@id=fake");

        MavenConfiguration config = UnitHelp.getConfig(file, settings);
        AetherBasedResolver resolver = new AetherBasedResolver(config);
        return resolver;
    }

    /**
     * Http proxy work well for http URL
     */
    @Test
    public void javaHttpProxyForHttpURL() throws Exception {
        System.setProperty("http.proxyHost", "localhost");
        System.setProperty("http.proxyPort", "8778");

        AetherBasedResolver resolver = createResolver("http");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("http", remoteRepository.getProtocol());
        assertEquals("http://qfdqfqfqf.fra/", remoteRepository.getUrl());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8778, proxy.getPort());
        assertEquals("http", proxy.getType());
        
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    /**
     * Http proxy with default proxy port work well for http URL
     */
    @Test
    public void javaHttpProxyWithouPortForHttpURL() throws Exception {
        System.setProperty("http.proxyHost", "localhost");
        // System.setProperty("http.proxyPort", "8778");

        AetherBasedResolver resolver = createResolver("http");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("http", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8080, proxy.getPort());
        assertEquals("http", proxy.getType());
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    @Test
    public void javaHttpProxyWithAuthForHttpURL() throws Exception {
        System.setProperty("http.proxyHost", "localhost");
        System.setProperty("http.proxyPort", "8778");
        System.setProperty("http.proxyUser", "foo");
        System.setProperty("http.proxyPassword", "bar");

        AetherBasedResolver resolver = createResolver("http");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("http", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8778, proxy.getPort());
        assertEquals("http", proxy.getType());

        final Authentication authentication = proxy.getAuthentication();
        assertNotNull(authentication);
    }

    /**
     * Http proxy work well for https URL also
     */
    @Test
    public void javaHttpProxyForHttpsURL() throws Exception {
        System.setProperty("http.proxyHost", "localhost");
        System.setProperty("http.proxyPort", "8778");

        AetherBasedResolver resolver = createResolver("https");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("https", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8778, proxy.getPort());
        assertEquals("http", proxy.getType());
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    /**
     * Https proxy work well for https URL
     */
    @Test
    public void javaHttpsProxyForHttpsURL() throws Exception {
        System.setProperty("https.proxyHost", "localhost");
        System.setProperty("https.proxyPort", "8778");

        AetherBasedResolver resolver = createResolver("https");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("https", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8778, proxy.getPort());
        assertEquals("https", proxy.getType());
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    /**
     * Http proxy work for http URL always, ignore https proxy
     */
    @Test
    public void javaHttpAndHttpsProxyForHttpURL() throws Exception {
        System.setProperty("https.proxyHost", "localhost");
        System.setProperty("https.proxyPort", "8778");
        System.setProperty("http.proxyHost", "abc.com");

        AetherBasedResolver resolver = createResolver("http");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("http", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("abc.com", proxy.getHost());
        assertEquals(8080, proxy.getPort());
        assertEquals("http", proxy.getType());
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    /**
     * Https proxy work for https URL directly when set both
     */
    @Test
    public void javaHttpAndHttpsProxyForHttpsURL() throws Exception {
        System.setProperty("https.proxyHost", "localhost");
        System.setProperty("https.proxyPort", "8778");
        System.setProperty("http.proxyHost", "abc.com");

        AetherBasedResolver resolver = createResolver("https");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("https", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        assertEquals("localhost", proxy.getHost());
        assertEquals(8778, proxy.getPort());
        assertEquals("https", proxy.getType());
        assertThat(proxy.getAuthentication(), is(nullValue()));
    }

    /**
     * Https proxy can't work for http URL
     */
    @Test
    public void javaHttpsProxyForHttpURL() throws Exception {
        System.setProperty("https.proxyHost", "localhost");
        System.setProperty("https.proxyPort", "8778");

        AetherBasedResolver resolver = createResolver("http");
        final List<RemoteRepository> repositories = resolver.getRepositories();
        assertEquals(1, repositories.size());

        final RemoteRepository remoteRepository = repositories.get(0);
        assertEquals("qfdqfqfqf.fra", remoteRepository.getHost());
        assertEquals("fake", remoteRepository.getId());
        assertEquals("http", remoteRepository.getProtocol());

        final Proxy proxy = remoteRepository.getProxy();

        // because https proxy only work for https url
        assertThat(proxy, is(nullValue()));
    }
}
