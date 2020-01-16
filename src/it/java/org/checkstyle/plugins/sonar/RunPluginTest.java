package org.checkstyle.plugins.sonar;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.Build;
import com.sonar.orchestrator.build.BuildResult;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.locator.FileLocation;
import org.apache.commons.io.FileUtils;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.fail;

/**
 * a lot of code from https://github.com/SonarSource/sonar-java/blob/5aaaeba90cd85a40391706ad2f806d6a156f80cb/its/ruling/src/test/java/org/sonar/java/it/JavaRulingTest.java
 * and from
 * https://github.com/SonarSource/sonar-jacoco/blob/master/its/src/test/java/org/sonar/plugins/jacoco/its/JacocoTest.java
 */
public class RunPluginTest {

    private final static String PROJECT_KEY = "checkstyle-test-project";
    private final static String PROJECT_NAME = "src/";
    private static final String FILE_KEY = "checkstyle-test-project:src/main/java/org/sonarsource/test/Calc.java";
    private static final int LOGS_NUMBER_LINES = 200;
    private static final Logger LOG = LoggerFactory.getLogger(RunPluginTest.class);

    private static Path effectiveDumpOldFolder;
    private static Orchestrator orchestrator;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void beforeAll() {
        Orchestrator orchestrator = Orchestrator.builderEnv()
                .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE[7.9]"))
                .addPlugin(FileLocation.byWildcardMavenFilename(new File("../../sonar-java-plugin/target"), "sonar-java-plugin-*.jar"))
                //.addPlugin(MavenLocation.of("org.sonarsource.java", "sonar-java-plugin", "5.2.0.13398")
                .setServerProperty("sonar.web.javaOpts", "-Xmx1G")
                .build();

        // start server
        orchestrator.start();
    }

    @AfterClass
    public static void afterAll() {
        orchestrator.stop();
    }

    @Test
    public void run() throws IOException {
        SonarScanner scanner = SonarScanner.create()
                .setProjectKey(PROJECT_KEY)
                .setProjectName(PROJECT_NAME)
                .setDebugLogs(true)
                .setSourceDirs("src/main")
                .setTestDirs("src/test")
                .setProperty("sonar.java.binaries", ".")
                .setProjectDir(prepareProject(PROJECT_NAME));
        orchestrator.executeBuild(scanner);

        MavenBuild build = test_project("com.puppycrows.tools:checkstyle", "src/");
        executeBuildWithCommonProperties(build, PROJECT_NAME, false);

        List<String> metricKeys = Arrays.asList("line_coverage", "lines_to_cover",
                                                "uncovered_lines", "branch_coverage",
                                                "conditions_to_cover", "uncovered_conditions", "coverage");

        /* // uncompilable due to API change; also this method is supposed to return nothing but does?
        return getWsClient().measures().component(new ComponentRequest()
                                                          .setComponent(FILE_KEY)
                                                          .setMetricKeys(metricKeys))
                .getComponent().getMeasuresList()
                .stream()
                .collect(Collectors.toMap(WsUtils.Measure::getMetric, m -> Double.parseDouble(m.getValue())));
        */
    }

    private File prepareProject(String name) throws IOException {
        Path projectRoot = Paths.get("src/test/resources").resolve(name);
        File targetDir = temp.newFolder(name);
        FileUtils.copyDirectory(projectRoot.toFile(), targetDir);
        return targetDir;
    }

    private static void prepareProject(String projectKey, String projectName) {
        orchestrator.getServer().provisionProject(projectKey, projectName);
        orchestrator.getServer().associateProjectToQualityProfile(projectKey, "java", "rules");
    }

    private static void executeBuildWithCommonProperties(Build<?> build, String projectName, boolean buildQuietly) throws IOException {
        build.setProperty("sonar.cpd.exclusions", "**/*")
                .setProperty("sonar.import_unknown_files", "true")
                .setProperty("sonar.skipPackageDesign", "true")
                .setProperty("dump.old", effectiveDumpOldFolder.resolve(projectName).toString())
                .setProperty("dump.new", FileLocation.of("target/actual/" + projectName).getFile().getAbsolutePath())
                .setProperty("lits.differences", litsDifferencesPath(projectName))
                .setProperty("sonar.java.xfile", "true")
                .setProperty("sonar.java.failOnException", "true");
        BuildResult buildResult;
        if (buildQuietly) {
            // if build fail, ruling job is not violently interrupted, allowing time to dump SQ logs
            buildResult = orchestrator.executeBuildQuietly(build);
        } else {
            buildResult = orchestrator.executeBuild(build);
        }
        if (buildResult.isSuccess()) {
            assertNoDifferences(projectName);
        } else {
            dumpServerLogs();
            fail("Build failure for project: " + projectName);
        }
    }

    private static void assertNoDifferences(String projectName) throws IOException {
        String differences = new String(Files.readAllBytes(Paths.get(litsDifferencesPath(projectName))), StandardCharsets.UTF_8);
        Assertions.assertThat(differences).isEmpty();
    }

    private static String litsDifferencesPath(String projectName) {
        return FileLocation.of("target/" + projectName + "_differences").getFile().getAbsolutePath();
    }

    private static void dumpServerLogs() throws IOException {
        Server server = orchestrator.getServer();
        LOG.error("::::::::::::::::::::::::::::::::::: DUMPING SERVER LOGS :::::::::::::::::::::::::::::::::::");
        dumpServerLogLastLines(server.getAppLogs());
        dumpServerLogLastLines(server.getCeLogs());
        dumpServerLogLastLines(server.getEsLogs());
        dumpServerLogLastLines(server.getWebLogs());
    }

    private static void dumpServerLogLastLines(File logFile) throws IOException {
        if (!logFile.exists()) {
            return;
        }
        List<String> logs = Files.readAllLines(logFile.toPath());
        int nbLines = logs.size();
        if (nbLines > LOGS_NUMBER_LINES) {
            logs = logs.subList(nbLines - LOGS_NUMBER_LINES, nbLines);
        }
        LOG.error("=================================== START " + logFile.getName() + " ===================================");
        LOG.error(System.lineSeparator() + logs.stream().collect(Collectors.joining(System.lineSeparator())));
        LOG.error("===================================== END " + logFile.getName() + " ===================================");
    }


    private WsClient getWsClient() {
        return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
                                                                .url(orchestrator.getServer().getUrl())
                                                                .build());
    }

    private static MavenBuild test_project(String projectKey, String projectName) throws IOException {
        return test_project(projectKey, null, projectName);
    }

    private static MavenBuild test_project(String projectKey, @Nullable String path, String projectName) throws IOException {
        String pomLocation = "../sources/" + (path != null ? path + "/" : "") + projectName + "/pom.xml";
        File pomFile = FileLocation.of(pomLocation).getFile().getCanonicalFile();
        prepareProject(projectKey, projectName);
        MavenBuild mavenBuild = MavenBuild.create().setPom(pomFile).setCleanPackageSonarGoals().addArgument("-DskipTests");
        mavenBuild.setProperty("sonar.projectKey", projectKey);
        return mavenBuild;
    }
}
