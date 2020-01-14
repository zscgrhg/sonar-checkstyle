package org.checkstyle.plugins.sonar;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.Build;
import com.sonar.orchestrator.build.BuildResult;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.locator.FileLocation;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sonarqube.ws.WsMeasures;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.measure.ComponentWsRequest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.fail;

/**
 * This is integration test for execution of plugin jar in sonar server.
 */
class RunPluginTest {

    private final static String PROJECT_KEY = "jacoco-test-project";
    private static final String FILE_KEY =
            "jacoco-test-project:src/main/java/org/sonarsource/test/Calc.java";
    private static final String FILE_WITHOUT_COVERAGE_KEY =
            "jacoco-test-project:src/main/java/org/sonarsource/test/CalcNoCoverage.java";


    private static Orchestrator orchestrator;

    @BeforeClass
    public static void beforeAll() {
        Orchestrator orchestrator = Orchestrator.builderEnv()
            .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE[7.9]"))
            .addPlugin(FileLocation.byWildcardMavenFilename(
                    new File("../../sonar-java-plugin/target"), "sonar-java-plugin-*.jar"))
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
    public void run() {
        SonarScanner build = SonarScanner.create()
                .setProjectKey(PROJECT_KEY)
                .setDebugLogs(true)
                .setSourceDirs("src/main")
                .setTestDirs("src/test")
                .setProperty("sonar.java.binaries", ".")
                .setProjectDir(prepareProject("simple-project-jacoco"));
        orchestrator.executeBuild(build);

        MavenBuild build = test_project("com.puppycrows.tools:checkstyle", "src/");
        executeBuildWithCommonProperties(build, projectName, false);

        List<String> metricKeys = Arrays.asList("line_coverage", "lines_to_cover",
                                                "uncovered_lines", "branch_coverage",
                                                "conditions_to_cover",
                                                "uncovered_conditions", "coverage");

        return getWsClient().measures().component(new ComponentWsRequest()
                                                          .setComponent(fileKey)
                                                          .setMetricKeys(metricKeys))
                .getComponent().getMeasuresList()
                .stream()
                .collect(Collectors.toMap(WsMeasures.Measure::getMetric,
                        m -> Double.parseDouble(m.getValue())));
    }

    private static void executeBuildWithCommonProperties(Build<?> build, String projectName,
                                                         boolean buildQuietly) throws IOException {
        build.setProperty("sonar.cpd.exclusions", "**/*")
                .setProperty("sonar.import_unknown_files", "true")
                .setProperty("sonar.skipPackageDesign", "true")
                .setProperty("dump.old", effectiveDumpOldFolder.resolve(projectName).toString())
                .setProperty("dump.new",
                        FileLocation.of("target/actual/" + projectName).getFile().getAbsolutePath())
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
        String differences = new String(Files.readAllBytes(
                Paths.get(litsDifferencesPath(projectName))), StandardCharsets.UTF_8);
        Assertions.assertThat(differences).isEmpty();
    }

    private static String litsDifferencesPath(String projectName) {
        return FileLocation.of("target/" + projectName + "_differences").getFile().getAbsolutePath();
    }

    private static void dumpServerLogs() throws IOException {
        Server server = orchestrator.getServer();
        LOG.error("::::::::::: DUMPING SERVER LOGS :::::::::::");
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
        LOG.error("============== START " + logFile.getName() + " ==============");
        LOG.error(System.lineSeparator()
                + logs.stream().collect(Collectors.joining(System.lineSeparator())));
        LOG.error("============== END " + logFile.getName() + " ==============");
    }


    private WsClient getWsClient() {
        return WsClientFactories.getDefault()
                .newClient(HttpConnector.newBuilder()
                                        .url(orchestrator.getServer().getUrl())
                                        .build());
    }

    private static MavenBuild test_project(String projectKey, String projectName)
            throws IOException {
        return test_project(projectKey, null, projectName);
    }

    private static MavenBuild test_project(String projectKey, @Nullable String path,
                                           String projectName) throws IOException {
        String pomLocation = "../sources/" + (path != null ? path + "/" : "")
                + projectName + "/pom.xml";
        File pomFile = FileLocation.of(pomLocation).getFile().getCanonicalFile();
        prepareProject(projectKey, projectName);
        MavenBuild mavenBuild = MavenBuild.create()
                .setPom(pomFile)
                .setCleanPackageSonarGoals()
                .addArgument("-DskipTests");
        mavenBuild.setProperty("sonar.projectKey", projectKey);
        return mavenBuild;
    }
}
