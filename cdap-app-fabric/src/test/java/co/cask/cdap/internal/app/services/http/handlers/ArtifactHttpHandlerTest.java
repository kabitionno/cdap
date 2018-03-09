/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.services.http.handlers;

import co.cask.cdap.ConfigTestApp;
import co.cask.cdap.WordCountApp;
import co.cask.cdap.api.artifact.ApplicationClass;
import co.cask.cdap.api.artifact.ArtifactInfo;
import co.cask.cdap.api.artifact.ArtifactRange;
import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.artifact.ArtifactSummary;
import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.plugin.PluginPropertyField;
import co.cask.cdap.app.program.ManifestFields;
import co.cask.cdap.client.MetadataClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.config.ConnectionConfig;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.EndpointStrategy;
import co.cask.cdap.common.discovery.RandomEndpointStrategy;
import co.cask.cdap.common.metadata.MetadataRecord;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.gateway.handlers.ArtifactHttpHandler;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.artifact.plugin.Plugin1;
import co.cask.cdap.internal.app.runtime.artifact.plugin.Plugin2;
import co.cask.cdap.internal.app.runtime.artifact.plugin.endpointtest.PluginEndpointContextTestPlugin;
import co.cask.cdap.internal.app.runtime.artifact.plugin.invalid.InvalidPlugin;
import co.cask.cdap.internal.app.runtime.artifact.plugin.invalid2.InvalidPluginMethodParams;
import co.cask.cdap.internal.app.runtime.artifact.plugin.invalid3.InvalidPluginMethodParamType;
import co.cask.cdap.internal.app.runtime.artifact.plugin.p3.CallablePlugin;
import co.cask.cdap.internal.app.runtime.artifact.plugin.p4.CallingPlugin;
import co.cask.cdap.internal.app.runtime.artifact.plugin.p5.PluginWithPojo;
import co.cask.cdap.internal.app.runtime.artifact.plugin.p5.TestData;
import co.cask.cdap.internal.app.services.http.AppFabricTestBase;
import co.cask.cdap.internal.io.ReflectionSchemaGenerator;
import co.cask.cdap.internal.io.SchemaTypeAdapter;
import co.cask.cdap.proto.artifact.ArtifactPropertiesRequest;
import co.cask.cdap.proto.artifact.ArtifactSummaryProperties;
import co.cask.cdap.proto.artifact.PluginInfo;
import co.cask.cdap.proto.artifact.PluginSummary;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.metadata.MetadataScope;
import co.cask.common.http.HttpRequest;
import co.cask.common.http.HttpRequests;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.http.HttpResponse;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.discovery.ServiceDiscovered;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;
import javax.annotation.Nullable;
import javax.ws.rs.HttpMethod;

/**
 * Tests for {@link ArtifactHttpHandler}
 */
public class ArtifactHttpHandlerTest extends AppFabricTestBase {
  private static final ReflectionSchemaGenerator schemaGenerator = new ReflectionSchemaGenerator(false);
  private static final Type ARTIFACTS_TYPE = new TypeToken<Set<ArtifactSummary>>() { }.getType();
  private static final Type PLUGIN_SUMMARIES_TYPE = new TypeToken<Set<PluginSummary>>() { }.getType();
  private static final Type PLUGIN_INFOS_TYPE = new TypeToken<Set<PluginInfo>>() { }.getType();
  private static final Type PLUGIN_TYPES_TYPE = new TypeToken<Set<String>>() { }.getType();
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
    .create();
  private static ArtifactRepository artifactRepository;
  private static MetadataClient metadataClient;
  private static String systemArtifactsDir;
  protected static ClientConfig clientConfig;

  @BeforeClass
  public static void setup() throws IOException {
    artifactRepository = getInjector().getInstance(ArtifactRepository.class);
    systemArtifactsDir = getInjector().getInstance(CConfiguration.class).get(Constants.AppFabric.SYSTEM_ARTIFACTS_DIR);
    DiscoveryServiceClient discoveryClient = getInjector().getInstance(DiscoveryServiceClient.class);
    ServiceDiscovered metadataHttpDiscovered = discoveryClient.discover(Constants.Service.METADATA_SERVICE);
    EndpointStrategy endpointStrategy = new RandomEndpointStrategy(metadataHttpDiscovered);
    Discoverable discoverable = endpointStrategy.pick(1, TimeUnit.SECONDS);
    Assert.assertNotNull(discoverable);
    String host = "127.0.0.1";
    int port = discoverable.getSocketAddress().getPort();
    ConnectionConfig connectionConfig = ConnectionConfig.builder().setHostname(host).setPort(port).build();
    clientConfig = ClientConfig.builder().setConnectionConfig(connectionConfig).build();
    metadataClient = new MetadataClient(clientConfig);
  }
  @After
  public void wipeData() throws Exception {
    artifactRepository.clear(NamespaceId.DEFAULT);
    artifactRepository.clear(NamespaceId.SYSTEM);

  }

  // test deploying an application artifact that has a non-application as its main class
  @Test
  public void testAddBadApp() throws Exception {
    ArtifactId artifactId = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(),
                        addAppArtifact(artifactId.toId(), ArtifactSummary.class).getStatusLine().getStatusCode());
  }

  @Test
  public void testNotFound() throws IOException, URISyntaxException {
    Assert.assertTrue(getArtifacts(NamespaceId.DEFAULT).isEmpty());
    Assert.assertNull(getArtifacts(NamespaceId.DEFAULT, "wordcount"));
    Assert.assertNull(getArtifact(NamespaceId.DEFAULT.artifact("wordcount", "1.0.0")));
  }

  @Test
  public void testAddAndGet() throws Exception {
    File wordCountArtifact = buildAppArtifact(WordCountApp.class, "wordcount.jar");
    File configTestArtifact = buildAppArtifact(ConfigTestApp.class, "cfgtest.jar");

    // add 2 versions of the same app that doesn't use config
    ArtifactId wordcountId1 = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    ArtifactId wordcountId2 = NamespaceId.DEFAULT.artifact("wordcount", "2.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addArtifact(wordcountId1.toId(), Files.newInputStreamSupplier(wordCountArtifact), null)
                          .getStatusLine().getStatusCode());
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addArtifact(wordcountId2.toId(), Files.newInputStreamSupplier(wordCountArtifact), null)
                          .getStatusLine().getStatusCode());
    // and 1 version of another app that uses a config
    ArtifactId configTestAppId = NamespaceId.DEFAULT.artifact("cfgtest", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addArtifact(configTestAppId.toId(), Files.newInputStreamSupplier(configTestArtifact), null)
                          .getStatusLine().getStatusCode());

    // test get /artifacts endpoint
    Set<ArtifactSummary> expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0"),
      new ArtifactSummary("wordcount", "2.0.0"),
      new ArtifactSummary("cfgtest", "1.0.0")
    );
    Set<ArtifactSummary> actualArtifacts = getArtifacts(NamespaceId.DEFAULT);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount endpoint
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0"),
      new ArtifactSummary("wordcount", "2.0.0")
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, "wordcount");
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/cfgtest/versions/1.0.0 endpoint
    Schema appConfigSchema = schemaGenerator.generate(ConfigTestApp.ConfigClass.class);
    ArtifactInfo actualInfo = getArtifact(configTestAppId);

    Assert.assertEquals("cfgtest", actualInfo.getName());
    Assert.assertEquals("1.0.0", actualInfo.getVersion());

    ApplicationClass appClass = new ApplicationClass(ConfigTestApp.class.getName(), "", appConfigSchema);
    Assert.assertTrue(actualInfo.getClasses().getApps().contains(appClass));
  }

  @Test
  public void testBatchProperties() throws Exception {
    ArtifactId wordcountId1 = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordcountId1.toId(), WordCountApp.class).getStatusLine().getStatusCode());
    addArtifactProperties(wordcountId1.toId(), ImmutableMap.of("k1", "v1", "k2", "v2"));

    ArtifactId wordcountId2 = NamespaceId.DEFAULT.artifact("wc", "2.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordcountId2.toId(), WordCountApp.class).getStatusLine().getStatusCode());
    addArtifactProperties(wordcountId2.toId(), ImmutableMap.of("k2", "v20", "k3", "v30"));

    List<String> props = ImmutableList.of("k1", "k2", "k3");
    List<ArtifactPropertiesRequest> requestList = ImmutableList.of(
      new ArtifactPropertiesRequest(wordcountId1.getArtifact(), wordcountId1.getVersion(), ArtifactScope.USER, props),
      new ArtifactPropertiesRequest(wordcountId2.getArtifact(), wordcountId2.getVersion(), ArtifactScope.USER, props));
    URL url = getEndPoint(String.format("%s/namespaces/%s/artifactproperties",
                                        Constants.Gateway.API_VERSION_3, NamespaceId.DEFAULT.getNamespace())).toURL();
    HttpRequest request = HttpRequest.post(url).withBody(GSON.toJson(requestList)).build();
    co.cask.common.http.HttpResponse response = HttpRequests.execute(request);

    Assert.assertEquals(HttpResponseStatus.OK.code(), response.getResponseCode());

    List<ArtifactSummaryProperties> actual =
      GSON.fromJson(response.getResponseBodyAsString(), new TypeToken<List<ArtifactSummaryProperties>>() { }.getType());
    List<ArtifactSummaryProperties> expected = ImmutableList.of(
      new ArtifactSummaryProperties(wordcountId1.getArtifact(), wordcountId1.getVersion(), ArtifactScope.USER,
                                    ImmutableMap.of("k1", "v1", "k2", "v2")),
      new ArtifactSummaryProperties(wordcountId2.getArtifact(), wordcountId2.getVersion(), ArtifactScope.USER,
                                    ImmutableMap.of("k2", "v20", "k3", "v30")));
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testDeletePropertiesAndArtifacts() throws Exception {
    // add 2 versions of the same app that doesn't use config
    ArtifactId wordcountId1 = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordcountId1.toId(), WordCountApp.class).getStatusLine().getStatusCode());

    // test get /artifacts endpoint
    Set<ArtifactSummary> expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0")
    );
    Set<ArtifactSummary> actualArtifacts = getArtifacts(NamespaceId.DEFAULT);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);
    addArtifactProperties(wordcountId1.toId(), ImmutableMap.of("key1", "value1", "key2", "value2", "key3", "value3"));
    Assert.assertEquals(ImmutableMap.of("key1", "value1", "key2", "value2", "key3", "value3"),
                        getArtifactProperties(wordcountId1));

    // delete a single property
    deleteArtifact(wordcountId1, false, "key1", 200);
    Assert.assertEquals(ImmutableMap.of("key2", "value2", "key3", "value3"), getArtifactProperties(wordcountId1));

    // delete all properties
    deleteArtifact(wordcountId1, false, null, 200);
    Assert.assertEquals(ImmutableMap.of(), getArtifactProperties(wordcountId1));

    Set<MetadataRecord> metadataRecords = metadataClient.getMetadata(wordcountId1.toId(), MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    Assert.assertEquals(new MetadataRecord(wordcountId1, MetadataScope.USER),
                        metadataRecords.iterator().next());
    // delete artifact
    deleteArtifact(wordcountId1, true, null, 200);
    try {
      metadataClient.getMetadata(wordcountId1.toId(), MetadataScope.USER);
      Assert.fail("Should not reach here");
    } catch (NotFoundException e) {
      // no-op
    }
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT);
    Assert.assertTrue(actualArtifacts.isEmpty());
  }

  protected void deleteArtifact(ArtifactId artifact, boolean deleteArtifact,
                                @Nullable String property, int expectedResponseCode) throws Exception {
    String path = String.format("artifacts/%s/versions/%s/", artifact.getArtifact(), artifact.getVersion());
    if (!deleteArtifact) {
      path += property == null ? "properties" : String.format("properties/%s", property);
    }
    HttpResponse response = doDelete(getVersionedAPIPath(path, artifact.getNamespace()));
    Assert.assertEquals(expectedResponseCode, response.getStatusLine().getStatusCode());
  }

  @Test
  public void testSystemArtifacts() throws Exception {
    // add the app in the default namespace
    File systemArtifact = buildAppArtifact(WordCountApp.class, "wordcount-1.0.0.jar");

    ArtifactId defaultId = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addArtifact(defaultId.toId(), Files.newInputStreamSupplier(systemArtifact), null)
                          .getStatusLine().getStatusCode());
    // add system artifact
    addSystemArtifacts();

    // test get /artifacts
    Set<ArtifactSummary> expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.USER),
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    Set<ArtifactSummary> actualArtifacts = getArtifacts(NamespaceId.DEFAULT);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts?scope=system
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, ArtifactScope.SYSTEM);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts?scope=user
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.USER)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, ArtifactScope.USER);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount?scope=user
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.USER)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, "wordcount", ArtifactScope.USER);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount?scope=system
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, "wordcount", ArtifactScope.SYSTEM);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount/versions/1.0.0?scope=user
    ArtifactInfo actualInfo = getArtifact(defaultId, ArtifactScope.USER);
    Assert.assertEquals("wordcount", actualInfo.getName());
    Assert.assertEquals("1.0.0", actualInfo.getVersion());

    // test get /artifacts/wordcount/versions/1.0.0?scope=system
    actualInfo = getArtifact(defaultId, ArtifactScope.SYSTEM);
    Assert.assertEquals("wordcount", actualInfo.getName());
    Assert.assertEquals("1.0.0", actualInfo.getVersion());
    try {
      ArtifactId systemId = NamespaceId.SYSTEM.artifact("wordcount", "1.0.0");
      artifactRepository.addArtifact(systemId.toId(), systemArtifact, new HashSet<ArtifactRange>(), null);
      Assert.fail();
    } catch (Exception e) {
      // no-op - expected exception while adding artifact in system namespace
    }
    cleanupSystemArtifactsDirectory();
  }

  private void addSystemArtifacts() throws Exception {
    File destination = new File(systemArtifactsDir + "/wordcount-1.0.0.jar");
    buildAppArtifact(WordCountApp.class, new Manifest(), destination);
    artifactRepository.addSystemArtifacts();
  }

  private void cleanupSystemArtifactsDirectory() throws Exception {
    File dir = new File(systemArtifactsDir);
    for (File jarFile : DirUtils.listFiles(dir, "jar")) {
      jarFile.delete();
    }
  }

  /**
   * Tests that system artifacts can be accessed in a user namespace only if appropriately scoped.
   */
  @Test
  public void testSystemArtifactIsolation() throws Exception {
    // add the app in the default namespace
    ArtifactId defaultId = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");

    // add system artifact wordcount
    addSystemArtifacts();

    // test get /artifacts
    Set<ArtifactSummary> expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    Set<ArtifactSummary> actualArtifacts = getArtifacts(NamespaceId.DEFAULT);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts?scope=system
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, ArtifactScope.SYSTEM);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts?scope=user
    expectedArtifacts = Sets.newHashSet();
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, ArtifactScope.USER);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount?scope=user
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, "wordcount", ArtifactScope.USER);
    Assert.assertNull(actualArtifacts);

    // test get /artifacts/wordcount?scope=system
    expectedArtifacts = Sets.newHashSet(
      new ArtifactSummary("wordcount", "1.0.0", ArtifactScope.SYSTEM)
    );
    actualArtifacts = getArtifacts(NamespaceId.DEFAULT, "wordcount", ArtifactScope.SYSTEM);
    Assert.assertEquals(expectedArtifacts, actualArtifacts);

    // test get /artifacts/wordcount/versions/1.0.0?scope=user
    ArtifactInfo actualInfo = getArtifact(defaultId, ArtifactScope.USER);
    Assert.assertEquals(null , actualInfo);

    // test get /artifacts/wordcount/versions/1.0.0?scope=system
    actualInfo = getArtifact(defaultId, ArtifactScope.SYSTEM);
    Assert.assertEquals("wordcount", actualInfo.getName());
    Assert.assertEquals("1.0.0", actualInfo.getVersion());

    // test delete /default/artifacts/wordcount/versions/1.0.0
    deleteArtifact(defaultId.toId(), 404);
    cleanupSystemArtifactsDirectory();
  }

  @Test
  public void testPluginNamespaceIsolation() throws Exception {
    // add a system artifact
    ArtifactId systemId = NamespaceId.SYSTEM.artifact("wordcount", "1.0.0");
    addSystemArtifacts();

    Set<ArtifactRange> parents = Sets.newHashSet(new ArtifactRange(
      systemId.getNamespace(), systemId.getArtifact(),
      new ArtifactVersion(systemId.getVersion()), true, new ArtifactVersion(systemId.getVersion()), true));

    NamespaceId namespace1 = new NamespaceId("ns1");
    NamespaceId namespace2 = new NamespaceId("ns2");
    createNamespace(namespace1.getNamespace());
    createNamespace(namespace2.getNamespace());

    try {
      // add some plugins in namespace1. Will contain Plugin1 and Plugin2
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, Plugin1.class.getPackage().getName());
      ArtifactId pluginsId1 = namespace1.artifact("plugins1", "1.0.0");
      Assert.assertEquals(HttpResponseStatus.OK.code(),
                          addPluginArtifact(pluginsId1.toId(), Plugin1.class, manifest, parents)
                            .getStatusLine().getStatusCode());

      // add some plugins in namespace2. Will contain Plugin1 and Plugin2
      manifest = new Manifest();
      manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, Plugin1.class.getPackage().getName());
      ArtifactId pluginsId2 = namespace2.artifact("plugins2", "1.0.0");
      Assert.assertEquals(HttpResponseStatus.OK.code(),
                          addPluginArtifact(pluginsId2.toId(), Plugin1.class, manifest, parents)
                            .getStatusLine().getStatusCode());

      ArtifactSummary artifact1 =
        new ArtifactSummary(pluginsId1.getArtifact(), pluginsId1.getVersion(), ArtifactScope.USER);
      ArtifactSummary artifact2 =
        new ArtifactSummary(pluginsId2.getArtifact(), pluginsId2.getVersion(), ArtifactScope.USER);

      PluginSummary summary1Namespace1 =
        new PluginSummary("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), artifact1);
      PluginSummary summary2Namespace1 =
        new PluginSummary("Plugin2", "callable", "Just returns the configured integer",
                          Plugin2.class.getName(), artifact1);
      PluginSummary summary1Namespace2 =
        new PluginSummary("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), artifact2);
      PluginSummary summary2Namespace2 =
        new PluginSummary("Plugin2", "callable", "Just returns the configured integer",
                          Plugin2.class.getName(), artifact2);

      PluginInfo info1Namespace1 =
        new PluginInfo("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), "config", artifact1,
                       ImmutableMap.of(
                         "x", new PluginPropertyField("x", "", "int", true, false),
                         "stuff", new PluginPropertyField("stuff", "", "string", true, true)
                       ), new HashSet<>());
      PluginInfo info2Namespace1 =
        new PluginInfo("Plugin2", "callable", "Just returns the configured integer",
                       Plugin2.class.getName(), "config", artifact1,
                       ImmutableMap.of(
                         "v", new PluginPropertyField("v", "value to return when called", "int", true, false)
                       ), new HashSet<>());
      PluginInfo info1Namespace2 =
        new PluginInfo("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), "config", artifact2,
                       ImmutableMap.of(
                         "x", new PluginPropertyField("x", "", "int", true, false),
                         "stuff", new PluginPropertyField("stuff", "", "string", true, true)
                       ), new HashSet<>());
      PluginInfo info2Namespace2 =
        new PluginInfo("Plugin2", "callable", "Just returns the configured integer",
                       Plugin2.class.getName(), "config", artifact2,
                       ImmutableMap.of(
                         "v", new PluginPropertyField("v", "value to return when called", "int", true, false)
                       ), new HashSet<>());

      ArtifactId namespace1Artifact = namespace1.artifact(systemId.getArtifact(), systemId.getVersion());
      ArtifactId namespace2Artifact = namespace2.artifact(systemId.getArtifact(), systemId.getVersion());

      // should see same types in both namespaces
      Assert.assertEquals(ImmutableSet.of("dummy", "callable"),
                          getPluginTypes(namespace1Artifact, ArtifactScope.SYSTEM));
      Assert.assertEquals(ImmutableSet.of("dummy", "callable"),
                          getPluginTypes(namespace2Artifact, ArtifactScope.SYSTEM));

      // should see that plugins in namespace1 come only from the namespace1 artifact
      Assert.assertEquals(ImmutableSet.of(summary1Namespace1),
                          getPluginSummaries(namespace1Artifact, "dummy", ArtifactScope.SYSTEM));
      Assert.assertEquals(ImmutableSet.of(summary2Namespace1),
                          getPluginSummaries(namespace1Artifact, "callable", ArtifactScope.SYSTEM));

      Assert.assertEquals(ImmutableSet.of(info1Namespace1),
                          getPluginInfos(namespace1Artifact, "dummy", "Plugin1", ArtifactScope.SYSTEM));
      Assert.assertEquals(ImmutableSet.of(info2Namespace1),
                          getPluginInfos(namespace1Artifact, "callable", "Plugin2", ArtifactScope.SYSTEM));

      // should see that plugins in namespace2 come only from the namespace2 artifact
      Assert.assertEquals(ImmutableSet.of(summary1Namespace2),
                          getPluginSummaries(namespace2Artifact, "dummy", ArtifactScope.SYSTEM));
      Assert.assertEquals(ImmutableSet.of(summary2Namespace2),
                          getPluginSummaries(namespace2Artifact, "callable", ArtifactScope.SYSTEM));

      Assert.assertEquals(ImmutableSet.of(info1Namespace2),
                          getPluginInfos(namespace2Artifact, "dummy", "Plugin1", ArtifactScope.SYSTEM));
      Assert.assertEquals(ImmutableSet.of(info2Namespace2),
                          getPluginInfos(namespace2Artifact, "callable", "Plugin2", ArtifactScope.SYSTEM));
    } finally {
      deleteNamespace("iso1");
      deleteNamespace("iso2");
      cleanupSystemArtifactsDirectory();
    }
  }

  @Test
  public void testPluginWithEndpoints() throws Exception {
    // add an app for plugins to extend
    ArtifactId wordCount1Id = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordCount1Id.toId(), WordCountApp.class).getStatusLine().getStatusCode());

    ArtifactId wordCount2Id = NamespaceId.DEFAULT.artifact("testartifact", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordCount2Id.toId(), WordCountApp.class).getStatusLine().getStatusCode());
    // add some plugins.
    // plugins-3.0.0 extends wordcount[1.0.0,2.0.0)
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, CallablePlugin.class.getPackage().getName());
    ArtifactId plugins3Id = NamespaceId.DEFAULT.artifact("plugins3", "1.0.0");
    Set<ArtifactRange> plugins3Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(plugins3Id.toId(), CallablePlugin.class, manifest,
                                          plugins3Parents).getStatusLine().getStatusCode());

    Set<PluginInfo> expectedInfos = Sets.newHashSet(
      new PluginInfo("CallablePlugin", "interactive", "This is plugin with endpoint",
                     CallablePlugin.class.getName(), null, new ArtifactSummary("plugins3", "1.0.0"),
                     ImmutableMap.of(), ImmutableSet.of("ping")));

    Assert.assertEquals(expectedInfos, getPluginInfos(wordCount1Id, "interactive", "CallablePlugin"));
    // test plugin with endpoint
    Assert.assertEquals("hello",
                         GSON.fromJson(callPluginMethod(plugins3Id, "interactive",
                                          "CallablePlugin", "ping", "user",
                                          ArtifactScope.USER, 200).getResponseBodyAsString(), String.class));

    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, CallingPlugin.class.getPackage().getName());
    ArtifactId plugins4Id = NamespaceId.DEFAULT.artifact("plugins4", "1.0.0");
    // we also add test artifact as parent for calling plugin,
    // when callable plugin is loaded by calling plugin's method,
    // it will try with "testArtifact" parent - wouldn't be able to load
    // then it will load using "wordcount" parent  and succeed
    Set<ArtifactRange> plugins4Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")),
                                                         new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "testartifact", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(plugins4Id.toId(), CallingPlugin.class, manifest,
                                          plugins4Parents).getStatusLine().getStatusCode());

    // test plugin with endpoint having endpoint-context parameter
    Assert.assertEquals("hi user",
                        GSON.fromJson(callPluginMethod(plugins4Id, "interactive", "CallingPlugin", "ping", "user",
                                         ArtifactScope.USER, 200).getResponseBodyAsString(), String.class));

    // test plugin that accepts list of data and aggregates and returns result map
    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, PluginWithPojo.class.getPackage().getName());
    ArtifactId plugins5Id = NamespaceId.DEFAULT.artifact("aggregator", "1.0.0");
    Set<ArtifactRange> plugins5Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(plugins5Id.toId(), PluginWithPojo.class, manifest,
                                          plugins5Parents).getStatusLine().getStatusCode());

    // test plugin with endpoint having endpoint-context parameter
    List<TestData> data = ImmutableList.of(new TestData(1, 10), new TestData(1, 20),
                                           new TestData(3, 15), new TestData(4, 5), new TestData(3, 15));
    Map<Long, Long> expectedResult = new HashMap<>();
    expectedResult.put(1L, 30L);
    expectedResult.put(3L, 30L);
    expectedResult.put(4L, 5L);
    String response = callPluginMethod(plugins5Id, "interactive", "aggregator", "aggregate",
                                       GSON.toJson(data),
                                       ArtifactScope.USER, 200).getResponseBodyAsString();
    Assert.assertEquals(expectedResult,
                        GSON.fromJson(response, new TypeToken<Map<Long, Long>>() { }.getType()));


    // test calling a non-existent plugin method "bing"
    callPluginMethod(plugins4Id, "interactive", "CallingPlugin", "bing", "user", ArtifactScope.USER, 404);


    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, InvalidPlugin.class.getPackage().getName());
    ArtifactId invalidPluginId = NamespaceId.DEFAULT.artifact("invalid", "1.0.0");
    Set<ArtifactRange> invalidPluginParents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(),
                        addPluginArtifact(invalidPluginId.toId(), InvalidPlugin.class, manifest,
                                          invalidPluginParents).getStatusLine().getStatusCode());

    // test adding plugin artifact which has endpoint method containing 3 params (invalid)
    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE,
                                     InvalidPluginMethodParams.class.getPackage().getName());
    invalidPluginId = NamespaceId.DEFAULT.artifact("invalidParams", "1.0.0");
    invalidPluginParents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(),
                        addPluginArtifact(invalidPluginId.toId(), InvalidPluginMethodParams.class, manifest,
                                          invalidPluginParents).getStatusLine().getStatusCode());

    // test adding plugin artifact which has endpoint method containing 2 params
    // but 2nd param is not EndpointPluginContext (invalid)
    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE,
                                     InvalidPluginMethodParamType.class.getPackage().getName());
    invalidPluginId = NamespaceId.DEFAULT.artifact("invalidParamType", "1.0.0");
    invalidPluginParents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.BAD_REQUEST.code(),
                        addPluginArtifact(invalidPluginId.toId(), InvalidPluginMethodParamType.class, manifest,
                                          invalidPluginParents).getStatusLine().getStatusCode());


    // test adding plugin artifact which has endpoint methods containing 2 params
    // but 2nd param is implementation and extensions of EndpointPluginContext, should succeed
    manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE,
                                     PluginEndpointContextTestPlugin.class.getPackage().getName());
    ArtifactId validPluginId = NamespaceId.DEFAULT.artifact("extender", "1.0.0");
    Set<ArtifactRange> validPluginParents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(validPluginId.toId(), PluginEndpointContextTestPlugin.class, manifest,
                                          validPluginParents).getStatusLine().getStatusCode());
  }

  @Test
  public void invalidInputsToPluginEndpoint() throws Exception {
    // add an app for plugins to extend
    ArtifactId wordCount1Id = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordCount1Id.toId(), WordCountApp.class).getStatusLine().getStatusCode());

    // test plugin with endpoint that throws IllegalArgumentException
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, PluginWithPojo.class.getPackage().getName());
    ArtifactId pluginsId = NamespaceId.DEFAULT.artifact("aggregator", "1.0.0");

    Set<ArtifactRange> plugins5Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(pluginsId.toId(), PluginWithPojo.class, manifest,
                                          plugins5Parents).getStatusLine().getStatusCode());

    // this call should throw IllegalArgumentException
    String response = callPluginMethod(pluginsId, "interactive", "aggregator", "throwException", "testString",
                                       ArtifactScope.USER, 400).getResponseBodyAsString();
    Assert.assertEquals("Invalid user inputs: testString", response);
  }

  @Test
  public void testGetPlugins() throws Exception {
    // add an app for plugins to extend
    ArtifactId wordCount1Id = NamespaceId.DEFAULT.artifact("wordcount", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordCount1Id.toId(), WordCountApp.class).getStatusLine().getStatusCode());
    ArtifactId wordCount2Id = NamespaceId.DEFAULT.artifact("wordcount", "2.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addAppArtifact(wordCount2Id.toId(), WordCountApp.class).getStatusLine().getStatusCode());

    // add some plugins.
    // plugins-1.0.0 extends wordcount[1.0.0,2.0.0)
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE, Plugin1.class.getPackage().getName());
    ArtifactId pluginsId1 = NamespaceId.DEFAULT.artifact("plugins", "1.0.0");
    Set<ArtifactRange> plugins1Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("2.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(pluginsId1.toId(), Plugin1.class, manifest,
                                          plugins1Parents).getStatusLine().getStatusCode());

    // plugin-2.0.0 extends wordcount[1.0.0,3.0.0)
    ArtifactId pluginsId2 = NamespaceId.DEFAULT.artifact("plugins", "2.0.0");
    Set<ArtifactRange> plugins2Parents = Sets.newHashSet(new ArtifactRange(
      NamespaceId.DEFAULT.getNamespace(), "wordcount", new ArtifactVersion("1.0.0"), new ArtifactVersion("3.0.0")));
    Assert.assertEquals(HttpResponseStatus.OK.code(),
                        addPluginArtifact(pluginsId2.toId(), Plugin1.class, manifest,
                                          plugins2Parents).getStatusLine().getStatusCode());

    ArtifactSummary plugins1Artifact = new ArtifactSummary("plugins", "1.0.0");
    ArtifactSummary plugins2Artifact = new ArtifactSummary("plugins", "2.0.0");

    // get plugin types, should be the same for both
    Set<String> expectedTypes = Sets.newHashSet("dummy", "callable");
    Set<String> actualTypes = getPluginTypes(wordCount1Id);
    Assert.assertEquals(expectedTypes, actualTypes);
    actualTypes = getPluginTypes(wordCount2Id);
    Assert.assertEquals(expectedTypes, actualTypes);

    // get plugin summaries. wordcount1 should see plugins from both plugin artifacts
    Set<PluginSummary> expectedSummaries = Sets.newHashSet(
      new PluginSummary("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), plugins1Artifact),
      new PluginSummary("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), plugins2Artifact)
    );
    Set<PluginSummary> actualSummaries = getPluginSummaries(wordCount1Id, "dummy");
    Assert.assertEquals(expectedSummaries, actualSummaries);

    expectedSummaries = Sets.newHashSet(
      new PluginSummary(
        "Plugin2", "callable", "Just returns the configured integer", Plugin2.class.getName(), plugins1Artifact),
      new PluginSummary(
        "Plugin2", "callable", "Just returns the configured integer", Plugin2.class.getName(), plugins2Artifact)
    );
    actualSummaries = getPluginSummaries(wordCount1Id, "callable");
    Assert.assertEquals(expectedSummaries, actualSummaries);

    // wordcount2 should only see plugins from plugins2 artifact
    expectedSummaries = Sets.newHashSet(
      new PluginSummary("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), plugins2Artifact)
    );
    actualSummaries = getPluginSummaries(wordCount2Id, "dummy");
    Assert.assertEquals(expectedSummaries, actualSummaries);

    expectedSummaries = Sets.newHashSet(
      new PluginSummary(
        "Plugin2", "callable", "Just returns the configured integer", Plugin2.class.getName(), plugins2Artifact)
    );
    actualSummaries = getPluginSummaries(wordCount2Id, "callable");
    Assert.assertEquals(expectedSummaries, actualSummaries);

    // get plugin info. Again, wordcount1 should see plugins from both artifacts
    Map<String, PluginPropertyField> p1Properties = ImmutableMap.of(
      "x", new PluginPropertyField("x", "", "int", true, false),
      "stuff", new PluginPropertyField("stuff", "", "string", true, true)
    );
    Map<String, PluginPropertyField> p2Properties = ImmutableMap.of(
      "v", new PluginPropertyField("v", "value to return when called", "int", true, false)
    );

    Set<PluginInfo> expectedInfos = Sets.newHashSet(
      new PluginInfo("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), "config",
                     plugins1Artifact, p1Properties, new HashSet<>()),
      new PluginInfo("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), "config",
                     plugins2Artifact, p1Properties, new HashSet<>())
    );
    Assert.assertEquals(expectedInfos, getPluginInfos(wordCount1Id, "dummy", "Plugin1"));

    expectedInfos = Sets.newHashSet(
      new PluginInfo("Plugin2", "callable", "Just returns the configured integer",
                     Plugin2.class.getName(), "config", plugins1Artifact, p2Properties, new HashSet<>()),
      new PluginInfo("Plugin2", "callable", "Just returns the configured integer",
                     Plugin2.class.getName(), "config", plugins2Artifact, p2Properties, new HashSet<>())
    );
    Assert.assertEquals(expectedInfos, getPluginInfos(wordCount1Id, "callable", "Plugin2"));

    // while wordcount2 should only see plugins from plugins2 artifact
    expectedInfos = Sets.newHashSet(
      new PluginInfo("Plugin1", "dummy", "This is plugin1", Plugin1.class.getName(), "config",
                     plugins2Artifact, p1Properties, new HashSet<>())
    );
    Assert.assertEquals(expectedInfos, getPluginInfos(wordCount2Id, "dummy", "Plugin1"));

    expectedInfos = Sets.newHashSet(
      new PluginInfo("Plugin2", "callable", "Just returns the configured integer",
                     Plugin2.class.getName(), "config", plugins2Artifact, p2Properties, new HashSet<>())
    );
    Assert.assertEquals(expectedInfos, getPluginInfos(wordCount2Id, "callable", "Plugin2"));
  }

  private Set<ArtifactSummary> getArtifacts(NamespaceId namespace) throws URISyntaxException, IOException {
    return getArtifacts(namespace, (ArtifactScope) null);
  }

  private Set<ArtifactSummary> getArtifacts(NamespaceId namespace, ArtifactScope scope)
    throws URISyntaxException, IOException {

    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts%s",
                                             Constants.Gateway.API_VERSION_3,
                                             namespace.getNamespace(),
                                             getScopeQuery(scope)))
      .toURL();
    return getResults(endpoint, ARTIFACTS_TYPE);
  }

  private Map<String, String> getArtifactProperties(ArtifactId artifact)
    throws URISyntaxException, IOException {

    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts/%s/versions/%s/properties",
                                             Constants.Gateway.API_VERSION_3, artifact.getNamespace(),
                                             artifact.getArtifact(), artifact.getVersion())).toURL();
    return getResults(endpoint, MAP_STRING_STRING_TYPE);
  }

  private Set<ArtifactSummary> getArtifacts(NamespaceId namespace, String name)
    throws URISyntaxException, IOException {
    return getArtifacts(namespace, name, null);
  }

  private Set<ArtifactSummary> getArtifacts(NamespaceId namespace, String name, ArtifactScope scope)
    throws URISyntaxException, IOException {

    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts/%s%s",
                                             Constants.Gateway.API_VERSION_3,
                                             namespace.getNamespace(), name,
                                             getScopeQuery(scope)))
      .toURL();
    return getResults(endpoint, ARTIFACTS_TYPE);
  }

  // get /artifacts/{name}/versions/{version}
  private ArtifactInfo getArtifact(ArtifactId artifactId) throws URISyntaxException, IOException {
    return getArtifact(artifactId, null);
  }

  // get /artifacts/{name}/versions/{version}?scope={scope}
  private ArtifactInfo getArtifact(ArtifactId artifactId, ArtifactScope scope) throws URISyntaxException, IOException {
    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts/%s/versions/%s%s",
                                             Constants.Gateway.API_VERSION_3, artifactId.getNamespace(),
                                             artifactId.getArtifact(), artifactId.getVersion(),
                                             getScopeQuery(scope)))
      .toURL();

    return getResults(endpoint, ArtifactInfo.class);
  }

  // get /artifacts/{name}/versions/{version}/extensions
  private Set<String> getPluginTypes(ArtifactId artifactId) throws URISyntaxException, IOException {
    return getPluginTypes(artifactId, null);
  }

  // get /artifacts/{name}/versions/{version}/extensions?scope={scope}
  private Set<String> getPluginTypes(ArtifactId artifactId,
                                     ArtifactScope scope) throws URISyntaxException, IOException {
    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts/%s/versions/%s/extensions%s",
                                             Constants.Gateway.API_VERSION_3,
                                             artifactId.getNamespace(),
                                             artifactId.getArtifact(),
                                             artifactId.getVersion(),
                                             getScopeQuery(scope))
    ).toURL();

    return getResults(endpoint, PLUGIN_TYPES_TYPE);
  }

  // get /artifacts/{name}/versions/{version}/extensions/{plugin-type}
  private Set<PluginSummary> getPluginSummaries(ArtifactId artifactId,
                                                String pluginType) throws URISyntaxException, IOException {
    return getPluginSummaries(artifactId, pluginType, null);
  }

  // get /artifacts/{name}/versions/{version}/extensions/{plugin-type}?scope={scope}
  private Set<PluginSummary> getPluginSummaries(ArtifactId artifactId,
                                                String pluginType,
                                                ArtifactScope scope) throws URISyntaxException, IOException {
    URL endpoint = getEndPoint(String.format("%s/namespaces/%s/artifacts/%s/versions/%s/extensions/%s%s",
                                             Constants.Gateway.API_VERSION_3,
                                             artifactId.getNamespace(),
                                             artifactId.getArtifact(),
                                             artifactId.getVersion(),
                                             pluginType,
                                             getScopeQuery(scope)))
      .toURL();

    return getResults(endpoint, PLUGIN_SUMMARIES_TYPE);
  }

  // get /artifacts/{name}/versions/{version}/extensions/{plugin-type}/plugins/{plugin-name}
  private Set<PluginInfo> getPluginInfos(ArtifactId artifactId,
                                         String pluginType, String pluginName) throws URISyntaxException, IOException {
    return getPluginInfos(artifactId, pluginType, pluginName, null);
  }

  // get /artifacts/{name}/versions/{version}/extensions/{plugin-type}/plugins/{plugin-name}?scope={scope}
  private Set<PluginInfo> getPluginInfos(ArtifactId artifactId,
                                         String pluginType,
                                         String pluginName,
                                         ArtifactScope scope) throws URISyntaxException, IOException {
    URL endpoint = getEndPoint(
      String.format("%s/namespaces/%s/artifacts/%s/versions/%s/extensions/%s/plugins/%s%s",
                    Constants.Gateway.API_VERSION_3,
                    artifactId.getNamespace(),
                    artifactId.getArtifact(),
                    artifactId.getVersion(),
                    pluginType,
                    pluginName,
                    getScopeQuery(scope)))
      .toURL();

    return getResults(endpoint, PLUGIN_INFOS_TYPE);
  }

  private String getScopeQuery(ArtifactScope scope) {
    return (scope == null) ? ("") : ("?scope=" + scope.name());
  }

  private co.cask.common.http.HttpResponse callPluginMethod(
    ArtifactId plugins3Id, String pluginType, String pluginName, String pluginMethod,
    String body, ArtifactScope scope, int expectedResponseCode) throws URISyntaxException, IOException {
    URL endpoint = getEndPoint(
      String.format("%s/namespaces/%s/artifacts/%s/versions/%s/plugintypes/%s/plugins/%s/methods/%s?scope=%s",
                    Constants.Gateway.API_VERSION_3,
                    plugins3Id.getNamespace(),
                    plugins3Id.getArtifact(),
                    plugins3Id.getVersion(),
                    pluginType,
                    pluginName,
                    pluginMethod,
                    scope.name()))
      .toURL();
    HttpRequest request = HttpRequest.post(endpoint).withBody(body).build();
    co.cask.common.http.HttpResponse response = HttpRequests.execute(request);
    Assert.assertEquals(expectedResponseCode, response.getResponseCode());
    return response;
  }

  // gets the contents from doing a get on the given url as the given type, or null if a 404 is returned
  private <T> T getResults(URL endpoint, Type type) throws IOException {
    HttpURLConnection urlConn = (HttpURLConnection) endpoint.openConnection();
    urlConn.setRequestMethod(HttpMethod.GET);

    int responseCode = urlConn.getResponseCode();
    if (responseCode == HttpResponseStatus.NOT_FOUND.code()) {
      return null;
    }
    Assert.assertEquals(HttpResponseStatus.OK.code(), responseCode);

    String responseStr = new String(ByteStreams.toByteArray(urlConn.getInputStream()), Charsets.UTF_8);
    urlConn.disconnect();
    return GSON.fromJson(responseStr, type);
  }
}
